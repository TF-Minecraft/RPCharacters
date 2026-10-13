package net.tfminecraft.rpcharacters.estate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.json.simple.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.body.BodyStore;
import net.tfminecraft.rpcharacters.body.CharacterBodies;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.identity.CharacterDirectory;
import net.tfminecraft.rpcharacters.identity.CharacterIdentityService;
import net.tfminecraft.rpcharacters.ingest.RosterSyncService;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.will.CharacterWill;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterRevivedEvent;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.CharacterSessionStartEvent;
import net.tfminecraft.tlibs.character.CharacterStatus;
import net.tfminecraft.tlibs.character.Characters;
import net.tfminecraft.tlibs.character.estate.EstateParticipant;
import net.tfminecraft.tlibs.character.estate.EstateService;
import net.tfminecraft.tlibs.character.estate.Estates;
import net.tfminecraft.tlibs.character.estate.Holdings;
import net.tfminecraft.tlibs.character.estate.Outcome;

class EstateModuleTest {
    /** The plugin without its own startup, so only estates run. */
    public static class Host extends RPCharacters {
        @Override public void onEnable() {}
        @Override public void onDisable() {}
    }

    @TempDir Path root;
    private ServerMock server;
    private RuntimeTestState state;
    private RPCharacters plugin;
    private PluginCommand command;
    private CharacterIdentityService identity;
    private MockedStatic<RPCharacters> statics;
    private MockedStatic<RosterSyncService> roster;
    private final List<LogRecord> logs = new ArrayList<>();
    private final List<CharacterRevivedEvent> revived = new ArrayList<>();
    private final CharacterRef aria = new CharacterRef(UUID.randomUUID(), UUID.randomUUID());
    private final UUID death = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        state = new RuntimeTestState(EstateModule.class);
        plugin = spy(MockBukkit.loadSimple(Host.class));
        doReturn(root.toFile()).when(plugin).getDataFolder();
        command = mock(PluginCommand.class);
        doReturn(command).when(plugin).getCommand("estate");
        plugin.getLogger().addHandler(new Handler() {
            @Override public void publish(LogRecord record) { logs.add(record); }
            @Override public void flush() {}
            @Override public void close() {}
        });
        identity = mock(CharacterIdentityService.class);
        CharacterInfo dead = new CharacterInfo(aria, "Aria", CharacterStatus.DEAD, "elf", Set.of(), 0, null);
        when(identity.deaths()).thenReturn(List.of(new CharacterDirectory.Death(dead, death)));
        when(identity.info(aria.characterId())).thenReturn(Optional.of(dead));
        when(identity.deathId(aria.characterId())).thenReturn(Optional.of(death));
        statics = mockStatic(RPCharacters.class);
        statics.when(RPCharacters::getCharacterIdentity).thenReturn(identity);
        roster = mockStatic(RosterSyncService.class);
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void on(CharacterRevivedEvent event) {
                revived.add(event);
            }
        }, MockBukkit.createMockPlugin("Listener"));
        Files.writeString(root.resolve("config.yml"), "estates:\n  wait-hours: 0.5\n");
    }

    @AfterEach
    void tearDown() {
        statics.close();
        roster.close();
        state.close();
        MockBukkit.unmock();
    }

    @Test
    void startRegistersTheServiceReconcilesAndStopsCleanly() throws Exception {
        EstateModule module = EstateModule.start(plugin);
        assertNotNull(server.getServicesManager().load(EstateService.class));
        assertTrue(server.getServicesManager().getRegistrations(EstateParticipant.class).isEmpty());
        var executor = org.mockito.ArgumentCaptor.forClass(EstateCommand.class);
        verify(command).setExecutor(executor.capture());
        PlayerMock kira = server.addPlayer("Kira");
        try (var characters = mockStatic(Characters.class)) {
            characters.when(() -> Characters.currentSession(kira)).thenReturn(Optional.of(
                    new CharacterSession(new CharacterRef(kira.getUniqueId(), UUID.randomUUID()), 1)));
            executor.getValue().onCommand(kira, null, "estate", new String[] {"collect"});
            assertEquals("You have no inheritance to collect.", kira.nextMessage());
        }
        Path record = root.resolve("data/estates/" + death + ".json");
        assertTrue(Files.exists(record));
        EstateRecord saved = EstateJson.read(Files.readString(record));
        assertEquals(saved.createdAt.plusSeconds(1800), saved.dueAt);
        UUID later = UUID.randomUUID();
        CharacterRef bran = new CharacterRef(UUID.randomUUID(), UUID.randomUUID());
        when(identity.deaths()).thenReturn(List.of(new CharacterDirectory.Death(
                new CharacterInfo(bran, "Bran", CharacterStatus.DEAD, "elf", Set.of(), 0, null), later)));
        server.getScheduler().performTicks(EstateModule.HOUR_TICKS);
        assertTrue(Files.exists(root.resolve("data/estates/" + later + ".json")));
        module.stop();
        assertNull(server.getServicesManager().load(EstateService.class));
        UUID afterStop = UUID.randomUUID();
        when(identity.deaths()).thenReturn(List.of(new CharacterDirectory.Death(
                new CharacterInfo(bran, "Bran", CharacterStatus.DEAD, "elf", Set.of(), 0, null), afterStop)));
        server.getScheduler().performTicks(EstateModule.HOUR_TICKS);
        assertFalse(Files.exists(root.resolve("data/estates/" + afterStop + ".json")));
    }

    @Test
    void bodiesRegisterTheirParticipantAndSimpleFactionsWakesTheDecisions() throws Exception {
        CharacterBodies bodies = mock(CharacterBodies.class);
        BodyStore store = new BodyStore(root.resolve("data/bodies"));
        when(bodies.store()).thenReturn(store);
        when(bodies.hasLease(aria.ownerId(), aria.characterId())).thenReturn(true);
        statics.when(RPCharacters::getCharacterBodies).thenReturn(bodies);
        Files.writeString(root.resolve("config.yml"), "estates:\n  wait-hours: 0\n");
        CharacterRef kira = new CharacterRef(UUID.randomUUID(), UUID.randomUUID());
        when(identity.info(kira.characterId())).thenReturn(Optional.of(
                new CharacterInfo(kira, "Kira", CharacterStatus.ALIVE, "elf", Set.of(), 0, null)));
        Path folder = Files.createDirectories(root.resolve("data/characterdata/" + aria.ownerId()));
        Files.writeString(folder.resolve(aria.characterId() + ".json"), "{\"will-heir\":\"" + kira.characterId()
                + "\",\"will-revision\":1}");
        try (var estates = mockStatic(Estates.class, CALLS_REAL_METHODS)) {
            estates.when(() -> Estates.guardHolds(any())).thenReturn(true);
            EstateModule module = EstateModule.start(plugin);
            EstateParticipant body = server.getServicesManager().load(EstateParticipant.class);
            assertEquals(BodyEstate.ID, body.id());
            Path file = root.resolve("data/estates/" + death + ".json");
            assertEquals("OPEN", EstateJson.read(Files.readString(file)).postponeReason == null ? "?" : "OPEN");
            var fallback = mock(net.tfminecraft.tlibs.character.estate.EstateFallback.class);
            when(fallback.deathGuild(any(), any())).thenReturn(net.tfminecraft.tlibs.character.estate.DeathGuild.NONE);
            server.getServicesManager().register(net.tfminecraft.tlibs.character.estate.EstateFallback.class, fallback, plugin,
                    org.bukkit.plugin.ServicePriority.Normal);
            server.getPluginManager().callEvent(new PluginEnableEvent(MockBukkit.createMockPlugin("Other")));
            assertEquals("OPEN", EstateJson.read(Files.readString(file)).state.name());
            server.getPluginManager().callEvent(new PluginEnableEvent(MockBukkit.createMockPlugin("SimpleFactions")));
            EstateRecord record = EstateJson.read(Files.readString(file));
            assertEquals("DECIDED", record.state.name());
            assertEquals("RETRY", record.participants.get(BodyEstate.ID).outcome());
            assertEquals(Outcome.retry("the body is still in play"), body.settle(record.toDecision()));
            when(bodies.hasLease(any(), any())).thenReturn(false);
            assertEquals(Outcome.retry("no stored body yet"), body.settle(record.toDecision()));
            server.getPluginManager().callEvent(new net.tfminecraft.simplefactions.events.SimpleFactionsReadyEvent());
            module.stop();
            assertTrue(server.getServicesManager().getRegistrations(EstateParticipant.class).isEmpty());
        }
    }

    @Test
    void aFailedEnableUnregistersWhatItRegisteredBeforeFailing() {
        doReturn(null).when(plugin).getCommand("estate");
        assertThrows(NullPointerException.class, () -> EstateModule.start(plugin));
        assertNull(server.getServicesManager().load(EstateService.class));
    }

    @Test
    void withoutSimpleFactionsItReliesOnThePluginEnableEvent() throws Exception {
        EstateModule.readyEvent = "missing.ReadyEvent";
        EstateModule module = EstateModule.start(plugin);
        assertTrue(logs.stream().anyMatch(r -> r.getMessage().contains("re-check when it enables")));
        module.stop();
    }

    @Test
    void hooksOpenRecordsReportBlockersFireRevivesAndFillTheRoster() throws Exception {
        when(identity.deaths()).thenReturn(List.of());
        EstateModule module = EstateModule.start(plugin);
        RPCharacter character = mock(RPCharacter.class);
        when(character.getId()).thenReturn(aria.characterId().toString());
        when(character.getName()).thenReturn("§aAria ");
        when(character.getDeathId()).thenReturn(death);
        when(character.getDiedAt()).thenReturn(java.time.Instant.now());
        when(character.getWill()).thenReturn(CharacterWill.NONE);
        module.died(aria.ownerId(), character, true, "COMMAND", null, null);
        EstateRecord record = EstateJson.read(Files.readString(root.resolve("data/estates/" + death + ".json")));
        assertEquals("Aria", record.name);
        RPCharacter broken = mock(RPCharacter.class);
        when(broken.getId()).thenReturn("not-a-uuid");
        module.died(aria.ownerId(), broken, true, "COMMAND", null, null);
        assertTrue(logs.stream().anyMatch(r -> r.getLevel() == Level.SEVERE && r.getMessage().contains("was not opened")));
        assertTrue(module.reviveBlockers(death).isEmpty());
        JSONObject row = new JSONObject();
        module.appendRoster(row, death);
        assertEquals("OPEN", ((JSONObject) row.get("estate")).get("status"));
        JSONObject none = new JSONObject();
        module.appendRoster(none, UUID.randomUUID());
        assertFalse(none.containsKey("estate"));
        module.revived(aria, death, false, "Mod");
        assertEquals(1, revived.size());
        assertEquals(death, revived.getFirst().getPreviousDeathId());
        assertEquals("CANCELLED", EstateJson.read(Files.readString(root.resolve("data/estates/" + death + ".json"))).state.name());
        module.stop();
    }

    @Test
    void holdingsListEachParticipantsLabelsOrCounts() throws Exception {
        EstateModule module = EstateModule.start(plugin);
        EstateTestKit.Participant labelled = new EstateTestKit.Participant("denareconomy");
        labelled.holdings = new Holdings(Map.of("cents", 34025), List.of("340.25 in pouch and bank"));
        EstateTestKit.Participant counted = new EstateTestKit.Participant("thievery");
        counted.holdings = new Holdings(Map.of("locks", 2, "doors", 0), List.of());
        EstateTestKit.Participant broken = new EstateTestKit.Participant("broken") {
            @Override
            public Holdings describe(UUID characterId) {
                throw new IllegalStateException("cannot");
            }
        };
        for (var participant : List.of(labelled, counted, broken)) {
            server.getServicesManager().register(EstateParticipant.class, participant, plugin,
                    org.bukkit.plugin.ServicePriority.Normal);
        }
        assertEquals(Set.of("340.25 in pouch and bank", "2 locks"), Set.copyOf(module.holdings(aria.characterId())));
        module.stop();
    }

    @Test
    void theRecipientIsToldAtSessionStartThatAnInheritanceWaits() throws Exception {
        EstateModule module = EstateModule.start(plugin);
        PlayerMock kira = server.addPlayer("Kira");
        CharacterRef kiraRef = new CharacterRef(kira.getUniqueId(), UUID.randomUUID());
        module.onSessionStart(new CharacterSessionStartEvent(kira, new CharacterSession(kiraRef, 1)));
        assertNull(kira.nextComponentMessage());
        EstateBook book = new EstateBook(root.resolve("data/estates"), DurableFiles.DISK, java.time.Clock.systemUTC(),
                plugin.getLogger());
        book.load();
        EstateRecord record = EstateBook.edit(book.get(death).orElseThrow());
        record.body = new EstateRecord.Body();
        record.body.state = "COLLECTABLE";
        record.body.stacks.add("AQ==");
        record.body.addressedTo = new EstateRecord.Named(kiraRef, "Kira");
        book.save(record);
        module.stop();
        EstateModule restarted = EstateModule.start(plugin);
        restarted.onSessionStart(new CharacterSessionStartEvent(kira, new CharacterSession(kiraRef, 2)));
        assertTrue(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(kira.nextComponentMessage()).contains("You have an inheritance to collect"));
        restarted.stop();
        assertEquals(Status.DEAD, Status.valueOf(CharacterStatus.DEAD.name()));
        verify(identity, atLeastOnce()).deaths();
        roster.verify(() -> RosterSyncService.pushRosterAsync(aria.ownerId()), atLeastOnce());
    }
}
