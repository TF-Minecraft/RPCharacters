package net.tfminecraft.rpcharacters.focus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import net.Indyuce.mmocore.api.player.attribute.PlayerAttributes;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.*;
import net.tfminecraft.rpcharacters.objects.races.Race;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.scheduler.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class FocusRuntimeTest extends FocusRuntimeFixture {
    @Test void configurationLoadsBonusesBoundsAndExplicitlyDisabledRestoreItems() throws Exception {
        Files.writeString(config, """
            max: 0
            base_per_hour: 7
            regen_interval_ticks: 0
            offline_regen: false
            regen_bonuses:
              - {mmocore_id: strength, extra_per_hour_per_point: 2.5}
              - {mmocore_id: dexterity, extra_per_hour_per_point: absent}
              - {extra_per_hour_per_point: 9}
              - {mmocore_id: ' ', extra_per_hour_per_point: 9}
            restore_items: disabled
            """);
        assertTrue(FocusConfigLoader.load(config.toFile()));
        assertEquals(1, FocusConfig.max); assertEquals(1, FocusConfig.regenIntervalTicks);
        assertEquals(7, FocusConfig.basePerHour); assertFalse(FocusConfig.offlineRegen);
        assertEquals(2, FocusConfig.regenBonuses.size());
        assertEquals("strength", FocusConfig.regenBonuses.getFirst().mmocoreId);
        assertEquals(2.5, FocusConfig.regenBonuses.getFirst().extraPerHourPerPoint);
        assertEquals(0, FocusConfig.regenBonuses.getLast().extraPerHourPerPoint);
        assertTrue(FocusConfig.restoreItems.isEmpty());
    }

    @Test void invalidOrUnreadableConfigurationPreservesPreviouslyLoadedValues() throws Exception {
        FocusConfig.max = 180; FocusConfig.regenBonuses.add(new FocusConfig.RegenBonus("strength", 2));
        Files.writeString(config, "max: [invalid");
        assertFalse(FocusConfigLoader.load(config.toFile())); assertEquals(180, FocusConfig.max);
        assertEquals(1, FocusConfig.regenBonuses.size());
        assertFalse(FocusConfigLoader.load(folder.resolve("missing.yml").toFile()));
        verify(logger, times(2)).severe(contains("Failed to load focus.yml"));
    }

    @Test void moduleCreatesResourceStartsExistingPlayersAndReloadFailurePreservesTimer() throws Exception {
        doAnswer(call -> { Files.writeString(config, "max: 150\noffline_regen: false\nregen_interval_ticks: 20\n"); return null; })
            .when(plugin).saveResource("focus.yml", false);
        module = new FocusModule(plugin);
        assertTrue(module.reloadConfig()); assertTrue(module.start());
        assertEquals(150, module.getService().getPoints(player)); assertEquals(1, timers.size());
        verify(plugin).saveResource("focus.yml", false);
        assertFalse(HandlerList.getRegisteredListeners(plugin).isEmpty());
        assertTrue(module.getService().trySpend(player, 25));
        Files.writeString(config, "max: [invalid");
        assertFalse(module.reloadConfig()); assertEquals(125, module.getService().getPoints(player));
        verify(tasks.getFirst(), never()).cancel();
        module.shutdown(); assertNull(module.getService()); verify(tasks.getFirst()).cancel();
        assertTrue(HandlerList.getRegisteredListeners(plugin).isEmpty());
        assertEquals(125, store.load(character.getId()).getPoints());
    }

    @Test void moduleDoesNotStartOnMalformedConfiguration() throws Exception {
        Files.writeString(config, "max: [invalid"); module = new FocusModule(plugin);
        assertFalse(module.start()); assertNull(module.getService()); assertTrue(timers.isEmpty());
        assertTrue(HandlerList.getRegisteredListeners(plugin).isEmpty());
    }

    @Test void moduleStartupFailureUnregistersItsListenerAndCanBeRetried() throws Exception {
        Files.writeString(config, "max: 150\noffline_regen: false");
        doThrow(new IllegalStateException("scheduler unavailable"))
            .when(scheduler).runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong());
        module = new FocusModule(plugin); assertFalse(module.start()); assertNull(module.getService());
        assertTrue(HandlerList.getRegisteredListeners(plugin).isEmpty());
        verify(logger).severe(contains("scheduler unavailable"));
        scheduleNormally(); assertTrue(module.start()); assertEquals(150, module.getService().getPoints(player));
    }

    @Test void serviceRejectsUnboundInputsAndOnlySavesTheMatchingPreviousCharacter() {
        var tracked = mock(FocusStore.class); var service = new FocusService(tracked);
        service.activate(null, character); service.activate(player, null);
        service.activate(player, character(null)); service.activate(player, character(" "));
        service.savePrevious(null, character); service.savePrevious(player, null); service.savePrevious(player, character);
        service.deactivate(null); service.deactivate(player);
        assertEquals(0, service.getPoints(null)); assertFalse(service.trySpend(null, 1)); service.grant(null, 1);
        verifyNoInteractions(tracked);
        var data = FocusData.createNew(character.getId(), "old owner"); data.setPoints(20);
        when(tracked.load(character.getId())).thenReturn(data); service.activate(player, character);
        assertEquals(player.getUniqueId().toString(), data.getOwnerUuid());
        service.savePrevious(player, character("different")); verify(tracked, never()).save(any());
        service.grant(player, 7); assertEquals(27, service.getPoints(player));
        service.savePrevious(player, character); verify(tracked).save(data);
        doThrow(new IllegalStateException("disk full")).when(tracked).save(data);
        assertFalse(service.restore(player)); verify(logger).severe("disk full");
        assertDoesNotThrow(() -> service.deactivate(player)); assertEquals(0, service.getPoints(player));
    }

    @Test void scheduledRegenerationUsesRealAttributesAndSkipsPlayersWithoutCharacters() {
        MockBukkit.createMockPlugin("MMOCore"); server.addPlayer("Unbound");
        var mmo = mock(net.Indyuce.mmocore.api.player.PlayerData.class, RETURNS_DEEP_STUBS);
        var attributes = new PlayerAttributes(mmo); attributes.getInstance("strength").setBase(4);
        when(mmo.getAttributes()).thenReturn(attributes);
        boundary(net.Indyuce.mmocore.api.player.PlayerData.class)
            .when(() -> net.Indyuce.mmocore.api.player.PlayerData.get(player)).thenReturn(mmo);
        FocusConfig.regenBonuses.add(new FocusConfig.RegenBonus("strength", 2.5));
        var tracked = mock(FocusStore.class); var data = FocusData.createNew(character.getId(), "owner");
        when(tracked.load(character.getId())).thenReturn(data);
        var service = new FocusService(plugin, tracked); service.start();
        data.setPoints(20); data.setLastRegenMs(System.currentTimeMillis() - 7_200_500);
        timers.getFirst().run(); assertEquals(60, service.getPoints(player));
        assertTrue(System.currentTimeMillis() - data.getLastRegenMs() < 2000);
        service.shutdown(); verify(tracked).save(data); verify(tasks.getFirst()).cancel();
    }

    @Test void missingInvalidOrUnavailableMmoAttributesKeepTheBaseRegenRate() {
        FocusConfig.offlineRegen = true;
        FocusConfig.regenBonuses.add(new FocusConfig.RegenBonus("strength", 2.5));
        FocusConfig.regenBonuses.add(new FocusConfig.RegenBonus(null, 3));
        FocusConfig.regenBonuses.add(new FocusConfig.RegenBonus(" ", 3));
        var tracked = mock(FocusStore.class); var data = FocusData.createNew(character.getId(), "owner");
        when(tracked.load(character.getId())).thenReturn(data); var service = new FocusService(plugin, tracked);
        data.setPoints(20); data.setLastRegenMs(System.currentTimeMillis() - 3_600_500);
        service.activate(player, character); assertEquals(30, service.getPoints(player));
        MockBukkit.createMockPlugin("MMOCore");
        boundary(net.Indyuce.mmocore.api.player.PlayerData.class)
            .when(() -> net.Indyuce.mmocore.api.player.PlayerData.get(player)).thenThrow(new IllegalStateException("not ready"));
        data.setPoints(20); data.setLastRegenMs(System.currentTimeMillis() - 3_600_500);
        service.activate(player, character); assertEquals(30, service.getPoints(player));
    }

    @Test void legacyStoreRecordsReceiveTheirFilenameIdentityAndInvalidInputsDoNotWrite() throws Exception {
        Files.createDirectories(storeFolder);
        Files.writeString(storeFolder.resolve("legacy.json"), "{\"ownerUuid\":\"owner\",\"points\":12,\"lastRegenMs\":10}");
        var data = store.load("legacy"); assertEquals("legacy", data.getCharacterId());
        assertEquals(12, data.getPoints()); store.save(data); assertEquals("legacy", store.load("legacy").getCharacterId());
        assertNull(store.load(null)); assertNull(store.load(" ")); store.save(null); store.save(new FocusData());
        data.setCharacterId(" "); store.save(data);
        try (var files = Files.list(storeFolder)) { assertEquals(1, files.count()); }
    }

    @Test void failedStoreSavePreservesExistingFilesAndReportsPrimaryFailure() throws Exception {
        Path blocked = folder.resolve("not-a-folder"); Files.writeString(blocked, "preserve");
        var data = FocusData.createNew("alice", "owner");
        assertThrows(IllegalStateException.class, () -> new FocusStore(blocked.toFile()).save(data));
        assertEquals("preserve", Files.readString(blocked));
        Files.createDirectories(storeFolder.resolve("alice.json"));
        Files.writeString(storeFolder.resolve("alice.json/existing"), "preserve");
        try (var files = mockStatic(Files.class, call -> {
            if (call.getMethod().getName().equals("deleteIfExists")) throw new IOException("secondary cleanup failure");
            return call.callRealMethod();
        })) {
            var failure = assertThrows(IllegalStateException.class, () -> store.save(data));
            assertTrue(failure.getMessage().contains("Cannot save focus file"));
            assertFalse(failure.getMessage().contains("secondary cleanup"));
        }
        assertEquals("preserve", Files.readString(storeFolder.resolve("alice.json/existing")));
    }

    @Test void unsupportedAtomicReplacementCannotDestroyThePreviousFocusRecord() throws Exception {
        var data = FocusData.createNew("alice", "owner"); data.setPoints(80); store.save(data);
        Path saved = storeFolder.resolve("alice.json"); String previous = Files.readString(saved); data.setPoints(20);
        try (var files = mockStatic(Files.class, call -> {
            if (call.getMethod().getName().equals("move")) {
                CopyOption[] options = (CopyOption[]) call.getRawArguments()[2];
                if (Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE)) {
                    throw new AtomicMoveNotSupportedException("source", "destination", "atomic replacement unavailable");
                }
                Files.writeString(saved, "partial"); throw new IOException("replacement failed");
            }
            return call.callRealMethod();
        })) {
            assertThrows(IllegalStateException.class, () -> store.save(data));
        }
        assertEquals(previous, Files.readString(saved), "A failed save must preserve the last durable focus balance");
    }

    @Test void commandExplainsSyntaxWithoutChangingAnyFocus() {
        player.addAttachment(plugin, FocusCommand.PERMISSION, true); var handler = new FocusCommand(plugin);
        for (String[] args : List.of(new String[0], new String[]{"unknown"}, new String[]{"restore"})) {
            assertTrue(handler.onCommand(player, mock(Command.class), "focus", args));
            assertEquals("Usage: /focus restore <player> | /focus reload", player.nextMessage());
        }
    }

    @Test void nonpositiveRegenAndSpendRequestsPreserveTheBalance() {
        var data = FocusData.createNew("alice", "owner"); data.setPoints(20); data.setLastRegenMs(1000);
        assertEquals(0, data.applyRegenForElapsed(10, 0, 10_000));
        assertEquals(0, data.applyRegenForElapsed(0, 1000, 10_000));
        assertEquals(0, data.applyRegenForElapsed(-2, 1000, 10_000));
        assertTrue(data.trySpend(0)); assertTrue(data.trySpend(-5)); data.grant(0); data.grant(-4);
        assertEquals(20, data.getPoints()); assertEquals(1000, data.getLastRegenMs());
    }

    @Test void manyElapsedIntervalsCannotWrapTheRegeneratedBalanceNegative() {
        var data = FocusData.createNew("alice", "owner"); data.setPoints(20); data.setLastRegenMs(1);
        long now = 1 + ((long) Integer.MAX_VALUE + 1) * 1000;
        assertEquals(130, data.applyRegenForElapsed(3600, 1000, now));
        assertEquals(150, data.getPoints()); assertEquals(now, data.getLastRegenMs());
    }

    @Test void largePositiveRegenAmountCannotWrapTheAdditionNegative() {
        var data = FocusData.createNew("alice", "owner"); data.setPoints(20); data.setLastRegenMs(1);
        assertEquals(130, data.applyRegenForElapsed(Integer.MAX_VALUE, 3_600_000, 3_600_001));
        assertEquals(150, data.getPoints());
    }

    @Test void rateAboveAnIntegerCannotBecomeOnePointPerInterval() {
        var data = FocusData.createNew("alice", "owner"); data.setPoints(20); data.setLastRegenMs(1);
        assertEquals(130, data.applyRegenForElapsed(2.0 * Integer.MAX_VALUE, 3_600_000, 3_600_001));
        assertEquals(150, data.getPoints());
    }

    @Test void enormousConfiguredIntervalsDoNotWrapToMilliseconds() {
        FocusConfig.offlineRegen = true; FocusConfig.regenIntervalTicks = Long.MAX_VALUE / 25 + 1;
        var data = FocusData.createNew(character.getId(), "owner"); data.setPoints(20);
        data.setLastRegenMs(System.currentTimeMillis() - 3_600_000); store.save(data);
        var service = new FocusService(plugin, store); service.activate(player, character);
        assertEquals(20, service.getPoints(player), "An interval beyond a lifetime cannot grant an hour of regeneration immediately");
    }
}

abstract class FocusRuntimeFixture {
    @TempDir Path folder;
    Path config, storeFolder; ServerMock server; RuntimeTestState state; RPCharacters plugin;
    Logger logger; PlayerMock player; Race race; RPCharacter character; PlayerData account; FocusStore store;
    BukkitScheduler scheduler; FocusModule module;
    final List<Runnable> timers = new ArrayList<>(); final List<BukkitTask> tasks = new ArrayList<>();
    final Map<Player, PlayerData> accounts = new HashMap<>(); final List<AutoCloseable> boundaries = new ArrayList<>();

    @BeforeEach void setupFocus() {
        server = MockBukkit.mock(); state = new RuntimeTestState(RPCharacters.class, FocusConfig.class);
        Cache.attributes = new ArrayList<>(); Cache.professions = new ArrayList<>(); Cache.backgroundTraitTypes = new ArrayList<>();
        FocusConfig.max = 150; FocusConfig.basePerHour = 10; FocusConfig.regenIntervalTicks = 72_000;
        FocusConfig.offlineRegen = false; FocusConfig.regenBonuses.clear(); FocusConfig.restoreItems.clear();
        plugin = mock(RPCharacters.class); logger = mock(Logger.class);
        var host = MockBukkit.createMockPlugin("FocusHost");
        when(plugin.getLogger()).thenReturn(logger); when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getServer()).thenReturn(server); when(plugin.isEnabled()).thenReturn(true); when(plugin.getName()).thenReturn("FocusTest");
        when(plugin.getPluginLoader()).thenReturn(host.getPluginLoader()); when(plugin.getDescription()).thenReturn(host.getDescription());
        RPCharacters.plugin = plugin; config = folder.resolve("focus.yml"); storeFolder = folder.resolve("data/focus");
        store = new FocusStore(storeFolder.toFile()); player = spy(new PlayerMock(server, "Focused")); server.addPlayer(player);
        doNothing().when(player).playSound(any(Location.class), any(Sound.class), anyFloat(), anyFloat());
        var yaml = new YamlConfiguration(); yaml.set("name", "Human"); race = new Race("human", yaml);
        character = character("alice"); account = new PlayerData(player); account.addCharacter(character); accounts.put(player, account);
        boundary(PlayerManager.class).when(() -> PlayerManager.get(any(Player.class))).thenAnswer(call -> accounts.get(call.getArgument(0)));
        scheduler = mock(BukkitScheduler.class); scheduleNormally();
        var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS); boundaries.add(bukkit); bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
    }

    void scheduleNormally() {
        doAnswer(call -> {
            timers.add(call.getArgument(1)); var task = mock(BukkitTask.class); tasks.add(task); return task;
        }).when(scheduler).runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong());
    }
    RPCharacter character(String id) { return new RPCharacter(player, id, "Focused", true, Status.ALIVE, race, new ArrayList<>(), "HUMAN"); }
    <T> MockedStatic<T> boundary(Class<T> owner) { var mock = mockStatic(owner); boundaries.add(mock); return mock; }
    @AfterEach void cleanupFocus() throws Exception {
        if (module != null) module.shutdown(); HandlerList.unregisterAll(plugin);
        for (int i = boundaries.size() - 1; i >= 0; i--) boundaries.get(i).close(); state.close(); MockBukkit.unmock();
    }
}
