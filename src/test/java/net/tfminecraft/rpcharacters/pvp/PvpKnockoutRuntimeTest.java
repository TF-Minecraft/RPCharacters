package net.tfminecraft.rpcharacters.pvp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.creation.CharacterCreation;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.loaders.PvpLoader;
import net.tfminecraft.rpcharacters.managers.*;
import net.tfminecraft.rpcharacters.objects.*;
import net.tfminecraft.rpcharacters.objects.races.Race;
import net.tfminecraft.rpcharacters.permadeath.PermadeathService;

class PvpKnockoutRuntimeTest {
    @TempDir Path folder;
    ServerMock server; PlayerMock victim, attacker; RuntimeTestState state; PvpKnockoutManager manager;
    MockedStatic<PlayerManager> players; MockedStatic<PermadeathService> death; MockedStatic<PvpStrikeService> strikes;
    java.util.List<org.bukkit.scheduler.BukkitTask> scheduled = new ArrayList<>();
    long queued() { return scheduled.stream().filter(task -> server.getScheduler().isQueued(task.getTaskId())).count(); }
    MockedStatic<Bukkit> bukkit; MockedStatic<dev.geco.gsit.api.GSitAPI> poses; PluginManager pluginManager;
    @BeforeEach void setup() {
        server = MockBukkit.mock(); state = new RuntimeTestState(RPCharacters.class, CreationManager.class, PvpLoader.class);
        RPCharacters.plugin = mock(RPCharacters.class); when(RPCharacters.plugin.isEnabled()).thenReturn(true);
        when(RPCharacters.plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("knockout-test"));
        Cache.attributes = new ArrayList<>(); Cache.professions = new ArrayList<>();
        victim = server.addPlayer("Victim"); attacker = server.addPlayer("Attacker");
        var cfg = new YamlConfiguration(); cfg.set("name", "Human");
        var character = new RPCharacter(attacker, UUID.randomUUID().toString(), "Attacker", true, Status.ALIVE, new Race("human", cfg), new ArrayList<>(), null);
        character.setPvpLethal(false); var data = new PlayerData(attacker); data.getCharacters().add(character);
        players = mockStatic(PlayerManager.class); players.when(() -> PlayerManager.get(attacker)).thenReturn(data);
        death = mockStatic(PermadeathService.class); strikes = mockStatic(PvpStrikeService.class);
        pluginManager = mock(PluginManager.class); bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS);
        bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
        var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class, org.mockito.AdditionalAnswers.delegatesTo(server.getScheduler()));
        doAnswer(call -> { var task = server.getScheduler().runTaskTimer(call.getArgument(0), (Runnable) call.getArgument(1), call.getArgument(2), call.getArgument(3)); scheduled.add(task); return task; })
                .when(scheduler).runTaskTimer(eq(RPCharacters.plugin), any(Runnable.class), anyLong(), anyLong());
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        poses = mockStatic(dev.geco.gsit.api.GSitAPI.class); manager = new PvpKnockoutManager();
    }
    @AfterEach void cleanup() { manager.shutdown(); server.getScheduler().cancelTasks(RPCharacters.plugin); poses.close(); bukkit.close(); strikes.close(); death.close(); players.close(); state.close(); MockBukkit.unmock(); }
    EntityDamageByEntityEvent hit(Entity target, double damage) {
        var event = mock(EntityDamageByEntityEvent.class); when(event.getEntity()).thenReturn(target); when(event.getDamager()).thenReturn(attacker); when(event.getFinalDamage()).thenReturn(damage); manager.onLethalDamage(event); return event;
    }

    @Test void startReplacesItsTimerAndOptionalCrawlIntegrationShutsDownCleanly() {
        when(pluginManager.isPluginEnabled("GSit")).thenReturn(true);
        manager.start(); manager.start();
        verify(pluginManager, times(1)).registerEvents(any(KnockoutCrawl.class), eq(RPCharacters.plugin));
        assertEquals(1, queued());
        var crawl = mock(dev.geco.gsit.model.Crawl.class);
        poses.when(() -> dev.geco.gsit.api.GSitAPI.startCrawl(victim)).thenReturn(crawl);
        poses.when(() -> dev.geco.gsit.api.GSitAPI.getCrawlByPlayer(victim)).thenReturn(crawl);
        hit(victim, 30); server.getScheduler().performTicks(1); manager.shutdown();
        poses.verify(() -> dev.geco.gsit.api.GSitAPI.stopCrawl(crawl, dev.geco.gsit.model.StopReason.PLUGIN));
        assertTrue(queued() == 0); manager.shutdown();
    }

    @Test void killingBlowIsCancelledAndSubsequentTicksHoldThePosition() {
        manager.start(); Location original = victim.getLocation();
        var event = hit(victim, 30); verify(event).setCancelled(true);
        assertEquals(1, victim.getHealth()); assertTrue(victim.hasPotionEffect(PotionEffectType.BLINDNESS));
        strikes.verify(() -> PvpStrikeService.handleKnockout(victim, attacker));
        server.getScheduler().performTicks(1); assertEquals(original, victim.getLocation());
        Location moved = original.clone().add(1, 0, 0); moved.setYaw(80); victim.teleport(moved);
        server.getScheduler().performTicks(1); original.setYaw(80); assertEquals(original, victim.getLocation());
        victim.disconnect(); server.getScheduler().performTicks(1); manager.shutdown();
    }

    @Test void ordinaryDamageMobsCreationAndPendingDeathNeverTriggerKnockout() {
        verify(hit(mock(Zombie.class), 30), never()).setCancelled(true);
        verify(hit(victim, 1), never()).setCancelled(true);
        death.when(() -> PermadeathService.isAwaitingPermakillRespawn(victim)).thenReturn(true);
        verify(hit(victim, 30), never()).setCancelled(true);
        death.when(() -> PermadeathService.isAwaitingPermakillRespawn(victim)).thenReturn(false);
        CreationManager.activeCreators.put(victim, CharacterCreation.forEdit(victim, new RPCharacter(victim)));
        verify(hit(victim, 30), never()).setCancelled(true); CreationManager.activeCreators.remove(victim);
        players.when(() -> PlayerManager.get(attacker)).thenReturn(null);
        verify(hit(victim, 30), never()).setCancelled(true); strikes.verifyNoInteractions();
    }

    @Test void creationAndPendingRespawnReleaseExistingFreezes() {
        manager.start(); hit(victim, 30);
        CreationManager.activeCreators.put(victim, CharacterCreation.forEdit(victim, new RPCharacter(victim)));
        server.getScheduler().performTicks(1); CreationManager.activeCreators.remove(victim);
        Location moved = victim.getLocation().add(1, 0, 0); victim.teleport(moved); server.getScheduler().performTicks(1); assertEquals(moved, victim.getLocation());
        victim.setHealth(20); hit(victim, 30);
        death.when(() -> PermadeathService.isAwaitingPermakillRespawn(victim)).thenReturn(true);
        server.getScheduler().performTicks(1); moved.add(1, 0, 0); victim.teleport(moved); server.getScheduler().performTicks(1); assertEquals(moved, victim.getLocation());
    }

    @Test void disconnectBeforeShutdownDoesNotLeaveARepeatingTask() {
        manager.start(); hit(victim, 30); victim.disconnect(); manager.shutdown();
        assertTrue(queued() == 0);
    }

    @Test void largeConfiguredDurationCannotWrapBlindnessToANegativeDuration() throws Exception {
        Path config = folder.resolve("pvp.yml"); Files.writeString(config, "knockout-seconds: 2147483647\n"); new PvpLoader().load(config.toFile());
        hit(victim, 30);
        assertEquals(Integer.MAX_VALUE, victim.getPotionEffect(PotionEffectType.BLINDNESS).getDuration());
    }
}
