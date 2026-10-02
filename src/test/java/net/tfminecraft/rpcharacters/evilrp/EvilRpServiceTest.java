package net.tfminecraft.rpcharacters.evilrp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.loaders.PvpLoader;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.*;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.permadeath.*;
import net.tfminecraft.rpcharacters.pvp.PvpStrikeService;
import net.tfminecraft.rpcharacters.tutorial.TutorialService;
import net.tfminecraft.rpcharacters.utils.TraitChangeService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class EvilRpServiceTest extends EvilRpFixture {
    @Test void loaderUsesDefaultsClampsMinutesAndKeepsLongDurationArithmetic() throws Exception {
        load("{}"); assertEquals(30, EvilRpLoader.getSessionMinutes()); assertEquals(1_800_000L, EvilRpLoader.getSessionMs());
        load("session-minutes: 0"); assertEquals(1, EvilRpLoader.getSessionMinutes());
        load("session-minutes: 2147483647"); assertEquals(2147483647L * 60_000L, EvilRpLoader.getSessionMs());
        load("bad: [unterminated"); assertEquals(30, EvilRpLoader.getSessionMinutes());
        new EvilRpLoader().load(folder.resolve("missing.yml").toFile()); assertEquals(30, EvilRpLoader.getSessionMinutes());
    }

    @Test void onlyLivingActiveCharactersCanStartSessions() {
        assertFalse(EvilRpService.recordPlay(null));
        players.when(() -> PlayerManager.get(player)).thenReturn(null); assertFalse(EvilRpService.recordPlay(player));
        players.when(() -> PlayerManager.get(player)).thenReturn(data);
        doReturn(false).when(character).isActive(); assertFalse(EvilRpService.recordPlay(player));
        doReturn(true).when(character).isActive(); character.setStatus(Status.DEAD);
        assertFalse(EvilRpService.recordPlay(player)); verify(manager, never()).savePlayer(any());
    }

    @Test void newSessionSavesBeforeTutorialAndFallbackReportsCurrentStrikes() {
        character.setEvilRpStrikes(2); long before = System.currentTimeMillis();
        assertTrue(EvilRpService.recordPlay(player));
        assertTrue(character.getEvilRpSessionEndsAtMs() >= before + 120_000);
        assertTrue(character.getEvilRpSessionEndsAtMs() <= System.currentTimeMillis() + 120_000);
        verify(manager).savePlayer(player);
        tutorial.verify(() -> TutorialService.show(player, TutorialService.EVIL_RP, Map.of("minutes", "2", "strikes", "2")));
        assertTrue(message(player).contains("2/3 strikes")); assertTrue(EvilRpService.isInSession(player));
    }

    @Test void shownTutorialAvoidsDuplicateChatAndFurtherPlayResetsSession() {
        tutorial.when(() -> TutorialService.show(eq(player), eq(TutorialService.EVIL_RP), anyMap())).thenReturn(true);
        assertTrue(EvilRpService.recordPlay(player)); assertNull(player.nextMessage());
        character.setEvilRpSessionEndsAtMs(System.currentTimeMillis() + 1000);
        assertTrue(EvilRpService.recordPlay(player)); assertTrue(message(player).contains("reset to"));
        assertTrue(EvilRpService.remainingMs(character) > 100_000);
        tutorial.verify(() -> TutorialService.show(eq(player), eq(TutorialService.EVIL_RP), anyMap()), times(1));
        verify(manager, times(2)).savePlayer(player);
    }

    @Test void sessionQueriesAndSwitchMessagesCoverPendingDecisionsAndInactiveSessions() {
        assertFalse(EvilRpService.isInSession((RPCharacter) null)); assertEquals(0, EvilRpService.remainingMs(null));
        assertEquals(0, EvilRpService.remainingMs(character)); assertFalse(EvilRpService.blocksCharacterSwitch(player));
        pvp.when(() -> PvpStrikeService.hasPendingDecision(player)).thenReturn(true);
        assertTrue(EvilRpService.blocksCharacterSwitch(player)); EvilRpService.sendSwitchBlocked(player);
        assertTrue(message(player).contains("killer decides"));
        pvp.when(() -> PvpStrikeService.hasPendingDecision(player)).thenReturn(false);
        EvilRpService.sendSwitchBlocked(player); assertTrue(message(player).endsWith("session."));
        EvilRpService.sendJoinReminder(player); assertNull(player.nextMessage());
        character.setEvilRpSessionEndsAtMs(System.currentTimeMillis() + 60_000);
        assertTrue(EvilRpService.blocksCharacterSwitch(player)); EvilRpService.sendSwitchBlocked(player);
        assertTrue(message(player).contains("left")); EvilRpService.sendJoinReminder(player);
        assertTrue(message(player).contains("still running"));
        assertEquals("0:00", EvilRpService.formatRemaining(0)); assertEquals("0:01", EvilRpService.formatRemaining(1));
        assertEquals("1:01", EvilRpService.formatRemaining(60_001));
    }

    @Test void firstAndSecondStrikesGiveInjuriesAndThirdDelegatesDeathWithKillerContext() {
        injuries.when(() -> PermadeathService.giveRandomInjury(player, character)).thenReturn(injury);
        injuries.when(() -> PermadeathService.givePermanentInjury(player, character)).thenReturn(injury);
        injuries.when(() -> PermadeathService.killCharacter(player, character, PermakillCause.STRIKES, admin, false)).thenReturn(true);
        long before = System.currentTimeMillis();
        assertEquals(StrikeOutcome.HEALING_INJURY, EvilRpService.applyStrike(player, character));
        assertEquals(1, character.getEvilRpStrikes()); assertTrue(character.getLastStrikeAtMs() >= before);
        assertEquals(List.of("§cStrike 1", "Injury received"), titles.getFirst());
        assertTrue(message(player).contains("healing injury"));
        assertEquals(StrikeOutcome.PERMANENT_INJURY, EvilRpService.applyStrike(player, character));
        assertTrue(message(player).contains("One more strike"));
        assertEquals(StrikeOutcome.DEATH, EvilRpService.applyStrike(player, character, admin, false));
        injuries.verify(() -> PermadeathService.killCharacter(player, character, PermakillCause.STRIKES, admin, false));
        verify(manager, times(3)).savePlayer(player); verify(logger, times(3)).info(contains("Strike"));
    }

    @Test void exhaustedInjuryPoolsStillRecordStrikesAndGiveFallbackFeedback() {
        assertEquals(StrikeOutcome.HEALING_INJURY, EvilRpService.applyStrike(player, character));
        assertTrue(message(player).contains("no injuries left")); assertEquals(" ", titles.getFirst().get(1));
        assertEquals(StrikeOutcome.PERMANENT_INJURY, EvilRpService.applyStrike(player, character));
        assertTrue(message(player).contains("no injuries left")); assertEquals(2, character.getEvilRpStrikes());
    }

    @Test void cancelledPermakillIsReportedWithoutClaimingSuccess() {
        assertFalse(EvilRpService.killByStrike(player, character, null, true));
        verify(logger).warning(contains("permakill was cancelled"));
        injuries.when(() -> PermadeathService.killCharacter(player, character, PermakillCause.STRIKES, null, true)).thenReturn(true);
        assertTrue(EvilRpService.killByStrike(player, character, null, true));
    }

    @Test void decayHonoursPolicyPeriodsAndStartsUntrackedStrikeAges() {
        assertFalse(EvilRpService.applyDecay(null, 5000)); character.setEvilRpStrikes(2);
        assertFalse(EvilRpService.applyDecay(character, 5000)); enableDecay(1000);
        assertTrue(EvilRpService.applyDecay(character, 5000)); assertEquals(5000, character.getLastStrikeAtMs());
        assertFalse(EvilRpService.applyDecay(character, 5999));
        assertTrue(EvilRpService.applyDecay(character, 6500)); assertEquals(1, character.getEvilRpStrikes());
        assertEquals(6000, character.getLastStrikeAtMs());
        assertTrue(EvilRpService.applyDecay(character, 8000)); assertEquals(0, character.getEvilRpStrikes());
        assertFalse(EvilRpService.applyDecay(character, 9000));
        assertEquals(0, StrikeDecay.apply(-1, 3, 4, 1).strikes());
        assertEquals(2, StrikeDecay.apply(2, 3, 4, 0).strikes());
    }

    @Test void strikeFirstAppliesElapsedDecay() {
        enableDecay(1000); character.setEvilRpStrikes(2); character.setLastStrikeAtMs(System.currentTimeMillis() - 10_000);
        assertEquals(StrikeOutcome.HEALING_INJURY, EvilRpService.applyStrike(player, character));
        assertEquals(1, character.getEvilRpStrikes());
    }

    @Test void timerClearsOnlyExpiredSessionsAndNotifiesOnlyRecentExpiries() {
        EvilRpService.start(); assertNotNull(timer);
        character.setEvilRpSessionEndsAtMs(System.currentTimeMillis() - 1000); timer.run();
        assertEquals(0, character.getEvilRpSessionEndsAtMs()); assertTrue(message(player).contains("has ended"));
        character.setEvilRpSessionEndsAtMs(System.currentTimeMillis() - 10_000); timer.run();
        assertEquals(0, character.getEvilRpSessionEndsAtMs()); assertNull(player.nextMessage());
        character.setEvilRpSessionEndsAtMs(System.currentTimeMillis() + 60_000); timer.run();
        assertTrue(EvilRpService.isInSession(character)); character.setEvilRpSessionEndsAtMs(0); timer.run();
        assertNull(player.nextMessage());
        var previous = task; EvilRpService.start(); verify(previous).cancel();
        EvilRpService.shutdown(); verify(task).cancel(); EvilRpService.shutdown(); verify(task, times(1)).cancel();
    }

    @Test void periodicDecayCoversAllLivingCharactersAndSavesOnlyChanges() {
        enableDecay(1000); character.setEvilRpStrikes(2); character.setLastStrikeAtMs(System.currentTimeMillis() - 10_000);
        var inactive = new RPCharacter(player); inactive.setName("Inactive"); inactive.setEvilRpStrikes(1);
        inactive.setLastStrikeAtMs(System.currentTimeMillis() - 10_000); data.addCharacter(inactive);
        var dead = new RPCharacter(player); dead.setName("Dead"); dead.setStatus(Status.DEAD); dead.setEvilRpStrikes(2); data.addCharacter(dead);
        EvilRpService.start(); for (int i = 0; i < 60; i++) timer.run();
        assertEquals(0, character.getEvilRpStrikes()); assertEquals(0, inactive.getEvilRpStrikes()); assertEquals(2, dead.getEvilRpStrikes());
        verify(manager).savePlayer(player);
        for (int i = 0; i < 60; i++) timer.run(); verify(manager, times(1)).savePlayer(player);
    }

    @Test void joinProcessesVerdictBeforeDelayedReminderAndIgnoresLoggedOutPlayers() {
        character.setEvilRpSessionEndsAtMs(System.currentTimeMillis() + 60_000);
        new EvilRpListener().onJoin(new PlayerJoinEvent(player, "Joined"));
        pvp.verify(() -> PvpStrikeService.handleJoin(player)); assertNull(player.nextMessage());
        assertEquals(60L, delays.getFirst()); delayed.removeFirst().run(); assertTrue(message(player).contains("still running"));
        new EvilRpListener().onJoin(new PlayerJoinEvent(player, "Joined")); player.disconnect();
        delayed.removeFirst().run(); assertNull(player.nextMessage());
    }

    @Test void excessivePersistedStrikesStillEscalateToDeathWithoutWrapping() {
        character.setEvilRpStrikes(Integer.MAX_VALUE);
        assertEquals(StrikeOutcome.DEATH, EvilRpService.applyStrike(player, character));
        assertEquals(Integer.MAX_VALUE, character.getEvilRpStrikes());
        injuries.verify(() -> PermadeathService.killCharacter(player, character, PermakillCause.STRIKES, null, true));
    }

    @Test void excessiveStrikeCountCannotDisableTheNextKillingStrike() {
        assertTrue(StrikeOutcome.nextStrikeKills(Integer.MAX_VALUE, false));
    }
}

abstract class EvilRpFixture {
    @TempDir Path folder;
    ServerMock server; RuntimeTestState state; RPCharacters plugin; Logger logger;
    PlayerMock player, admin; RPCharacter character; PlayerData data; PlayerManager manager; Trait injury;
    MockedStatic<PlayerManager> players; MockedStatic<PvpLoader> settings;
    MockedStatic<PvpStrikeService> pvp; MockedStatic<PermadeathService> injuries; MockedStatic<TutorialService> tutorial;
    final List<AutoCloseable> boundaries = new ArrayList<>();
    final List<List<String>> titles = new ArrayList<>();
    final Deque<Runnable> delayed = new ArrayDeque<>(); final List<Long> delays = new ArrayList<>();
    Runnable timer; BukkitTask task;

    @BeforeEach void setupEvilRp() throws Exception {
        server = MockBukkit.mock(); state = new RuntimeTestState(RPCharacters.class, EvilRpService.class, EvilRpLoader.class, PvpLoader.class);
        Cache.attributes = new ArrayList<>(); Cache.professions = new ArrayList<>();
        player = spy(new PlayerMock(server, "Subject")); server.addPlayer(player); admin = server.addPlayer("Staff");
        plugin = mock(RPCharacters.class); logger = mock(Logger.class); when(plugin.getLogger()).thenReturn(logger);
        RPCharacters.plugin = plugin; manager = mock(PlayerManager.class);
        boundary(RPCharacters.class).when(RPCharacters::getPlayerManager).thenReturn(manager);
        character = spy(new RPCharacter(player)); character.setName("Aria"); doReturn(true).when(character).isActive();
        data = new PlayerData(player); data.addCharacter(character);
        players = boundary(PlayerManager.class); players.when(() -> PlayerManager.get(any(Player.class)))
            .thenAnswer(call -> call.<Player>getArgument(0).getUniqueId().equals(player.getUniqueId()) ? data : null);
        settings = boundary(PvpLoader.class); pvp = boundary(PvpStrikeService.class);
        injuries = boundary(PermadeathService.class); tutorial = boundary(TutorialService.class);
        injury = mock(Trait.class);
        boundary(TraitChangeService.class).when(() -> TraitChangeService.resolveGainedMessage(injury)).thenReturn("Injury received");
        doAnswer(call -> { titles.add(List.of(call.getArgument(0), call.getArgument(1))); return null; })
            .when(player).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
        var scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(20L), eq(20L))).thenAnswer(call -> {
            timer = call.getArgument(1); task = mock(BukkitTask.class); return task;
        });
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), eq(60L))).thenAnswer(call -> {
            delayed.add(call.getArgument(1)); delays.add(call.getArgument(2)); return mock(BukkitTask.class);
        });
        var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS); boundaries.add(bukkit);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(call -> List.of(player, admin).stream().filter(Player::isOnline).toList());
        bukkit.when(() -> Bukkit.getPlayerExact("Subject")).thenReturn(player);
        load("session-minutes: 2");
    }

    <T> MockedStatic<T> boundary(Class<T> type) { var value = mockStatic(type); boundaries.add(value); return value; }
    void load(String text) throws Exception { new EvilRpLoader().load(Files.writeString(folder.resolve("evil.yml"), text).toFile()); }
    void enableDecay(long period) { settings.when(PvpLoader::isStrikeDecayEnabled).thenReturn(true); settings.when(PvpLoader::getStrikeDecayMs).thenReturn(period); }
    String message(PlayerMock recipient) { return Objects.requireNonNull(recipient.nextMessage()); }
    @AfterEach void cleanupEvilRp() throws Exception {
        EvilRpService.shutdown(); for (int i = boundaries.size() - 1; i >= 0; i--) boundaries.get(i).close();
        state.close(); MockBukkit.unmock();
    }
}
