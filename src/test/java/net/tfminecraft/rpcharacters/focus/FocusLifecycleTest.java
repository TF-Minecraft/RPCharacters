package net.tfminecraft.rpcharacters.focus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.mockito.MockedStatic;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.lifecycle.CharacterActivatedEvent;
import net.tfminecraft.rpcharacters.objects.RPCharacter;

class FocusLifecycleTest {
    @TempDir Path root;
    JavaPlugin plugin;
    Player player;
    FocusStore store;
    MockedStatic<RPCharacters> characters;
    final AtomicReference<RPCharacter> active = new AtomicReference<>();

    @BeforeEach void setup() {
        FocusConfig.max = 150;
        FocusConfig.basePerHour = 10;
        FocusConfig.regenIntervalTicks = 72_000;
        FocusConfig.offlineRegen = false;
        FocusConfig.regenBonuses.clear();
        plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        when(plugin.getDataFolder()).thenReturn(root.resolve("RPCharacters").toFile());
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        store = new FocusStore(root.resolve("RPCharacters/data/focus").toFile());
        characters = mockStatic(RPCharacters.class);
        characters.when(() -> RPCharacters.getActiveCharacter(player)).thenAnswer(invocation -> active.get());
    }

    @AfterEach void cleanup() { characters.close(); }

    private RPCharacter character(String id) {
        RPCharacter character = mock(RPCharacter.class);
        when(character.getId()).thenReturn(id);
        return character;
    }

    @Test
    void switchAndQuitPersistIndependentCharacterBalances() {
        var service = new FocusService(plugin, store);
        var listener = new FocusListener(service);
        var alice = character("alice");
        var bob = character("bob");
        active.set(alice);
        listener.onCharacterActivated(new CharacterActivatedEvent(player, player.getUniqueId(), alice, null));
        assertTrue(service.trySpend(player, 40));
        active.set(bob);
        listener.onCharacterActivated(new CharacterActivatedEvent(player, player.getUniqueId(), bob, alice));
        assertEquals(110, store.load("alice").getPoints());
        assertEquals(150, service.getPoints(player));
        service.trySpend(player, 10);
        listener.onQuit(new PlayerQuitEvent(player, (String) null));
        assertEquals(140, store.load("bob").getPoints());
        assertEquals(0, service.getPoints(player));
        active.set(alice);
        service.activate(player, alice);
        assertEquals(110, service.getPoints(player));
    }

    @Test
    void failedActivationCannotReusePreviousCharacterOrOverwriteCorruptData() throws Exception {
        var service = new FocusService(plugin, store);
        active.set(character("alice"));
        service.activate(player, active.get());
        Path bad = root.resolve("RPCharacters/data/focus/bob.json");
        Files.writeString(bad, "broken");
        active.set(character("bob"));
        service.activate(player, active.get());
        assertEquals(0, service.getPoints(player));
        assertFalse(service.trySpend(player, 1));
        service.deactivate(player);
        assertEquals("broken", Files.readString(bad));
        verify(plugin.getLogger()).severe(contains("Focus unavailable for character bob"));
    }

    @Test
    void offlineRegenSettingControlsActivationWithoutChangingStoredTimestampOnRead() {
        var data = FocusData.createNew("alice", player.getUniqueId().toString());
        data.setPoints(20);
        data.setLastRegenMs(System.currentTimeMillis() - 7_200_500);
        store.save(data);
        var service = new FocusService(plugin, store);
        FocusConfig.offlineRegen = true;
        active.set(character("alice"));
        service.activate(player, active.get());
        assertEquals(40, service.getPoints(player));
        FocusConfig.offlineRegen = false;
        active.set(character("alice"));
        service.activate(player, active.get());
        assertEquals(20, service.getPoints(player));
        service.deactivate(player);
        assertTrue(System.currentTimeMillis() - store.load("alice").getLastRegenMs() < 5000);
    }

    @Test
    void everyPublicOperationEvictsAndSavesAnInactiveCharactersBalance() {
        for (String operation : List.of("get", "spend", "grant", "restore")) {
            var service = new FocusService(plugin, store);
            var character = character(operation);
            active.set(character);
            service.activate(player, character);
            assertTrue(service.trySpend(player, 40));
            active.set(null);
            switch (operation) {
                case "get" -> assertEquals(0, service.getPoints(player));
                case "spend" -> assertFalse(service.trySpend(player, 1));
                case "grant" -> service.grant(player, 1);
                case "restore" -> assertFalse(service.restore(player));
            }
            assertEquals(110, store.load(operation).getPoints());
            active.set(character);
            assertEquals(0, service.getPoints(player), "Inactive state must be evicted, not merely hidden");
            service.activate(player, character);
            assertEquals(110, service.getPoints(player));
        }
    }

    @Test
    void aDifferentActiveCharacterCannotSpendTheCachedCharactersFocus() {
        var service = new FocusService(plugin, store);
        var alice = character("alice");
        active.set(alice);
        service.activate(player, alice);
        service.trySpend(player, 40);
        active.set(character("bob"));
        assertFalse(service.trySpend(player, 1));
        assertEquals(110, store.load("alice").getPoints());
    }

    @Test
    void timerAndSaveAllEvictInactiveCharactersWithoutRegenerating() {
        var scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong()))
                .thenReturn(mock(BukkitTask.class));
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            for (boolean timer : List.of(true, false)) {
                var trackedStore = mock(FocusStore.class);
                var data = FocusData.createNew("alice", player.getUniqueId().toString());
                data.setPoints(20);
                when(trackedStore.load("alice")).thenReturn(data);
                var service = new FocusService(plugin, trackedStore);
                var alice = character("alice");
                active.set(alice);
                service.activate(player, alice);
                data.setLastRegenMs(System.currentTimeMillis() - 7_200_500);
                long lastRegen = data.getLastRegenMs();
                active.set(null);
                if (timer) {
                    service.restartRegen();
                    var callback = ArgumentCaptor.forClass(Runnable.class);
                    verify(scheduler).runTaskTimer(eq(plugin), callback.capture(), anyLong(), anyLong());
                    callback.getValue().run();
                } else {
                    service.saveAllOnline();
                }
                assertEquals(20, data.getPoints());
                assertEquals(lastRegen, data.getLastRegenMs());
                verify(trackedStore).save(data);
                active.set(alice);
                assertEquals(0, service.getPoints(player));
                service.shutdown();
            }
        }
    }

    @Test
    void startupReloadAndShutdownHaveOneTimerAndSaveBalance() throws Exception {
        var server = mock(Server.class);
        var manager = mock(PluginManager.class);
        var scheduler = mock(BukkitScheduler.class);
        var first = mock(BukkitTask.class);
        var second = mock(BukkitTask.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(manager);
        Path config = root.resolve("RPCharacters/focus.yml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "max: 150\nregen_interval_ticks: 72000\noffline_regen: false\n");
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong())).thenReturn(first, second);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            var module = new FocusModule(plugin);
            assertTrue(module.start());
            assertTrue(module.start());
            FocusService service = module.getService();
            active.set(character("alice"));
            service.activate(player, active.get());
            service.trySpend(player, 30);
            Files.writeString(config, "max: 180\nregen_interval_ticks: 100\noffline_regen: false\n");
            assertTrue(module.reloadConfig());
            assertSame(service, module.getService());
            assertEquals(180, service.getMax());
            verify(first).cancel();
            verify(scheduler).runTaskTimer(eq(plugin), any(Runnable.class), eq(100L), eq(100L));
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            module.shutdown();
            verify(second).cancel();
            verify(manager, times(1)).registerEvents(any(FocusListener.class), eq(plugin));
            assertEquals(120, store.load("alice").getPoints());
            assertNull(module.getService());
        }
    }

}
