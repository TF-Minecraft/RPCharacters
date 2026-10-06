package net.tfminecraft.rpcharacters.pvp;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.potion.PotionEffectType;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import dev.geco.gsit.api.GSitAPI;
import dev.geco.gsit.api.event.PrePlayerStopCrawlEvent;
import dev.geco.gsit.model.Crawl;
import dev.geco.gsit.model.StopReason;
import org.bukkit.event.HandlerList;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.permadeath.PermadeathBattleExemption;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PvpKnockoutManagerTest {
    @BeforeAll static void initializeServerRegistries() {
        // Initialize real MockBukkit registry values before any test resolves Paper constants.
        org.mockbukkit.mockbukkit.MockBukkit.mock();
        try {
            assertNotNull(Attribute.MAX_HEALTH);
            assertNotNull(PotionEffectType.BLINDNESS);
        } finally {
            org.mockbukkit.mockbukkit.MockBukkit.unmock();
        }
    }

    @Test void forcesCrawlWithoutCommandAndMaintainsFreeze() throws Exception {
        Player victim = knockoutPlayer();
        // The command would reject an airborne player with no crawl permission.
        when(victim.isOnGround()).thenReturn(false);
        when(victim.hasPermission("gsit.crawl")).thenReturn(false);
        var manager = new PvpKnockoutManager();
        attachCrawl(manager);
        try (var bukkit = mockStatic(Bukkit.class); var gsit = mockStatic(GSitAPI.class)) {
            bukkit.when(() -> Bukkit.getPlayer(victim.getUniqueId())).thenReturn(victim);
            gsit.when(() -> GSitAPI.isPlayerCrawling(victim)).thenReturn(false, true);
            invoke(manager, "applyKnockout", victim);
            when(victim.getLocation()).thenReturn(new Location(null, 11, 64, 10, 90, 20));
            for (int i = 0; i < 600; i++) invoke(manager, "tick");
            gsit.verify(() -> GSitAPI.startCrawl(victim), times(1));
        }
        verify(victim, never()).performCommand(anyString());
        verify(victim).setHealth(1.0);
        verify(victim).addPotionEffect(argThat(effect ->
                effect.getType().equals(PotionEffectType.BLINDNESS) && effect.getDuration() == 600));
        verify(victim, times(600)).teleport(new Location(null, 10, 64, 10, 90, 20));
    }

    @Test void freezeDoesNotBuildUpFallDistance() throws Exception {
        Player victim = knockoutPlayer();
        var manager = new PvpKnockoutManager();
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(victim.getUniqueId())).thenReturn(victim);
            invoke(manager, "applyKnockout", victim);
            // Airborne when downed: every tick the client falls a little and is pulled back up.
            when(victim.getLocation()).thenReturn(new Location(null, 10, 63.9, 10));
            for (int i = 0; i < 600; i++) invoke(manager, "tick");
            verify(victim, times(600)).setFallDistance(0f);
            expire(manager, victim);
            invoke(manager, "tick");
            verify(victim, times(601)).setFallDistance(0f);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"FALL", "DROWNING", "FIRE_TICK", "SUFFOCATION", "STARVATION", "CUSTOM"})
    void downedPlayerSurvivesDamageWithoutAttacker(String cause) throws Exception {
        Player victim = knockoutPlayer();
        when(victim.getHealth()).thenReturn(1.0);
        var manager = new PvpKnockoutManager();
        var event = mock(EntityDamageEvent.class);
        when(event.getEntity()).thenReturn(victim);
        when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.valueOf(cause));
        when(event.getFinalDamage()).thenReturn(4.0);
        manager.onLethalDamage(event);
        verify(event, never()).setCancelled(true);
        invoke(manager, "applyKnockout", victim);
        manager.onLethalDamage(event);
        verify(event).setCancelled(true);
    }

    @Test void nonlethalBlowStillKillsInStartedBattle() {
        Player attacker = player(), victim = knockoutPlayer();
        when(victim.getHealth()).thenReturn(2.0);
        var event = mock(EntityDamageByEntityEvent.class);
        when(event.getEntity()).thenReturn(victim);
        when(event.getDamager()).thenReturn(attacker);
        when(event.getFinalDamage()).thenReturn(4.0);
        var manager = new PvpKnockoutManager();
        PlayerData nonlethal = data(false);
        try (var players = mockStatic(PlayerManager.class); var strikes = mockStatic(PvpStrikeService.class)) {
            players.when(() -> PlayerManager.get(attacker)).thenReturn(nonlethal);
            // SimpleFactions only counts a battle life when the victim really dies.
            PermadeathBattleExemption.set(p -> p == victim);
            manager.onLethalDamage(event);
            verify(event, never()).setCancelled(true);
            verify(victim, never()).setHealth(anyDouble());
            strikes.verifyNoInteractions();
            PermadeathBattleExemption.set(null);
            manager.onLethalDamage(event);
            verify(event).setCancelled(true);
            strikes.verify(() -> PvpStrikeService.handleKnockout(victim, attacker));
        } finally {
            PermadeathBattleExemption.set(null);
        }
    }

    @Test void playerDownedBeforeBattleStartsCanStillDie() throws Exception {
        Player victim = knockoutPlayer();
        when(victim.getHealth()).thenReturn(1.0);
        var manager = new PvpKnockoutManager();
        var event = mock(EntityDamageEvent.class);
        when(event.getEntity()).thenReturn(victim);
        when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.FALL);
        when(event.getFinalDamage()).thenReturn(4.0);
        invoke(manager, "applyKnockout", victim);
        try {
            PermadeathBattleExemption.set(p -> p == victim);
            manager.onLethalDamage(event);
            verify(event, never()).setCancelled(true);
        } finally {
            PermadeathBattleExemption.set(null);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"KILL", "VOID", "WORLD_BORDER"})
    void killCommandVoidAndBorderStillKillDownedPlayer(String cause) {
        var event = mock(EntityDamageEvent.class);
        when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.valueOf(cause));
        assertFalse(PvpKnockoutManager.downedPlayerSurvives(event));
    }

    @Test void mobsAndPlayersCanStillFinishDownedPlayer() {
        var mobHit = mock(EntityDamageByEntityEvent.class);
        when(mobHit.getCause()).thenReturn(EntityDamageEvent.DamageCause.ENTITY_ATTACK);
        when(mobHit.getDamager()).thenReturn(mock(Entity.class));
        assertFalse(PvpKnockoutManager.downedPlayerSurvives(mobHit));
        var caused = mock(EntityDamageEvent.class);
        DamageSource source = mock(DamageSource.class);
        when(caused.getCause()).thenReturn(EntityDamageEvent.DamageCause.MAGIC);
        when(caused.getDamageSource()).thenReturn(source);
        Player attacker = player();
        when(source.getCausingEntity()).thenReturn(attacker);
        assertFalse(PvpKnockoutManager.downedPlayerSurvives(caused));
    }

    @Test void existingCrawlIsKeptAndInterruptedCrawlIsRestored() throws Exception {
        Player victim = knockoutPlayer();
        var manager = new PvpKnockoutManager();
        attachCrawl(manager);
        try (var bukkit = mockStatic(Bukkit.class); var gsit = mockStatic(GSitAPI.class)) {
            bukkit.when(() -> Bukkit.getPlayer(victim.getUniqueId())).thenReturn(victim);
            gsit.when(() -> GSitAPI.isPlayerCrawling(victim)).thenReturn(true);
            invoke(manager, "applyKnockout", victim);
            invoke(manager, "tick");
            gsit.verify(() -> GSitAPI.startCrawl(victim), never());
            // GSit ends crawling on teleports; the knockout restores it afterwards.
            gsit.when(() -> GSitAPI.isPlayerCrawling(victim)).thenReturn(false);
            invoke(manager, "tick");
            gsit.verify(() -> GSitAPI.startCrawl(victim));
        }
        verify(victim, never()).performCommand(anyString());
    }

    @Test void playerCannotGetUpUntilKnockoutEnds() throws Exception {
        Player victim = knockoutPlayer();
        var manager = new PvpKnockoutManager();
        var integration = attachCrawl(manager);
        Crawl crawl = mock(Crawl.class);
        when(crawl.getPlayer()).thenReturn(victim);
        try (var gsit = mockStatic(GSitAPI.class)) {
            invoke(manager, "applyKnockout", victim);
            var getUp = new PrePlayerStopCrawlEvent(crawl, StopReason.GET_UP);
            integration.onStopCrawl(getUp);
            assertTrue(getUp.isCancelled());
            var teleport = new PrePlayerStopCrawlEvent(crawl, StopReason.TELEPORT);
            integration.onStopCrawl(teleport);
            assertFalse(teleport.isCancelled());
            expire(manager, victim);
            var recovered = new PrePlayerStopCrawlEvent(crawl, StopReason.GET_UP);
            integration.onStopCrawl(recovered);
            assertFalse(recovered.isCancelled());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"expiry", "quit", "death", "gamemode", "offline", "shutdown"})
    void releasesCrawlWhenKnockoutEnds(String reason) throws Exception {
        Player victim = knockoutPlayer();
        var manager = new PvpKnockoutManager();
        attachCrawl(manager);
        Crawl crawl = mock(Crawl.class);
        try (var bukkit = mockStatic(Bukkit.class); var gsit = mockStatic(GSitAPI.class);
                var handlers = mockStatic(HandlerList.class)) {
            bukkit.when(() -> Bukkit.getPlayer(victim.getUniqueId())).thenReturn(victim);
            gsit.when(() -> GSitAPI.getCrawlByPlayer(victim)).thenReturn(crawl);
            gsit.when(() -> GSitAPI.startCrawl(victim)).thenReturn(crawl);
            invoke(manager, "applyKnockout", victim);
            switch (reason) {
                case "expiry" -> expire(manager, victim);
                case "quit" -> {
                    var event = mock(PlayerQuitEvent.class);
                    when(event.getPlayer()).thenReturn(victim);
                    manager.onQuit(event);
                }
                case "death" -> {
                    var event = mock(PlayerDeathEvent.class);
                    when(event.getEntity()).thenReturn(victim);
                    manager.onDeath(event);
                }
                case "gamemode" -> when(victim.getGameMode()).thenReturn(GameMode.CREATIVE);
                case "offline" -> when(victim.isOnline()).thenReturn(false);
                case "shutdown" -> manager.shutdown();
            }
            invoke(manager, "tick");
            invoke(manager, "tick");
            gsit.verify(() -> GSitAPI.stopCrawl(crawl, StopReason.PLUGIN), times(1));
            gsit.verify(() -> GSitAPI.startCrawl(victim), times(1));
        }
    }

    @Test void ordinaryPlayerCrawlIsNotStoppedOnQuit() throws Exception {
        Player victim = knockoutPlayer();
        var manager = new PvpKnockoutManager();
        attachCrawl(manager);
        var event = mock(PlayerQuitEvent.class);
        when(event.getPlayer()).thenReturn(victim);
        try (var gsit = mockStatic(GSitAPI.class)) {
            manager.onQuit(event);
            gsit.verifyNoInteractions();
        }
    }

    @Test void recoveryPreservesCrawlThatPredatesKnockout() throws Exception {
        Player victim = knockoutPlayer();
        var manager = new PvpKnockoutManager();
        attachCrawl(manager);
        Crawl existing = mock(Crawl.class);
        try (var bukkit = mockStatic(Bukkit.class); var gsit = mockStatic(GSitAPI.class)) {
            bukkit.when(() -> Bukkit.getPlayer(victim.getUniqueId())).thenReturn(victim);
            gsit.when(() -> GSitAPI.isPlayerCrawling(victim)).thenReturn(true);
            gsit.when(() -> GSitAPI.getCrawlByPlayer(victim)).thenReturn(existing);
            invoke(manager, "applyKnockout", victim);
            expire(manager, victim);
            invoke(manager, "tick");
            gsit.verify(() -> GSitAPI.startCrawl(victim), never());
            gsit.verify(() -> GSitAPI.stopCrawl(existing, StopReason.PLUGIN), never());
        }
    }

    @Test void recoveryDoesNotStopReplacementOwnedByAnotherPlugin() throws Exception {
        Player victim = knockoutPlayer();
        var manager = new PvpKnockoutManager();
        attachCrawl(manager);
        Crawl owned = mock(Crawl.class), replacement = mock(Crawl.class);
        try (var bukkit = mockStatic(Bukkit.class); var gsit = mockStatic(GSitAPI.class)) {
            bukkit.when(() -> Bukkit.getPlayer(victim.getUniqueId())).thenReturn(victim);
            gsit.when(() -> GSitAPI.startCrawl(victim)).thenReturn(owned);
            invoke(manager, "applyKnockout", victim);
            gsit.when(() -> GSitAPI.getCrawlByPlayer(victim)).thenReturn(replacement);
            expire(manager, victim);
            invoke(manager, "tick");
            gsit.verify(() -> GSitAPI.stopCrawl(owned, StopReason.PLUGIN), never());
            gsit.verify(() -> GSitAPI.stopCrawl(replacement, StopReason.PLUGIN), never());
        }
    }

    private Player knockoutPlayer() {
        Player victim = player();
        when(victim.getLocation()).thenReturn(new Location(null, 10, 64, 10));
        when(victim.isOnline()).thenReturn(true);
        when(victim.getGameMode()).thenReturn(GameMode.SURVIVAL);
        return victim;
    }

    private KnockoutCrawl attachCrawl(PvpKnockoutManager manager) throws Exception {
        var active = PvpKnockoutManager.class.getDeclaredMethod("isKnockedOut", Player.class);
        active.setAccessible(true);
        var integration = new KnockoutCrawl(player -> {
            try {
                return (boolean) active.invoke(manager, player);
            } catch (ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
        });
        var field = PvpKnockoutManager.class.getDeclaredField("crawl");
        field.setAccessible(true);
        field.set(manager, integration);
        return integration;
    }

    private void expire(PvpKnockoutManager manager, Player victim) throws Exception {
        var field = PvpKnockoutManager.class.getDeclaredField("knockouts");
        field.setAccessible(true);
        Object knockout = ((java.util.Map<?, ?>) field.get(manager)).get(victim.getUniqueId());
        var until = knockout.getClass().getDeclaredField("untilMs");
        until.setAccessible(true);
        until.setLong(knockout, 0L);
    }

    private void invoke(PvpKnockoutManager manager, String name, Player... players) throws Exception {
        var method = players.length == 0
                ? PvpKnockoutManager.class.getDeclaredMethod(name)
                : PvpKnockoutManager.class.getDeclaredMethod(name, Player.class);
        method.setAccessible(true);
        method.invoke(manager, (Object[]) players);
    }

    private Player player() {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        return p;
    }

    private PlayerData data(boolean lethal) {
        PlayerData pd = mock(PlayerData.class);
        RPCharacter character = mock(RPCharacter.class);
        when(pd.hasActiveCharacter()).thenReturn(true);
        when(pd.getActiveCharacter()).thenReturn(character);
        when(character.isPvpLethal()).thenReturn(lethal);
        return pd;
    }

    @Test void attackerModeControlsMeleeRegardlessOfVictimMode() {
        Player attacker = player(), victim = player();
        var event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(attacker);
        PlayerData lethal = data(true), nonlethal = data(false);
        try (var players = mockStatic(PlayerManager.class)) {
            players.when(() -> PlayerManager.get(attacker)).thenReturn(nonlethal);
            players.when(() -> PlayerManager.get(victim)).thenReturn(lethal);
            assertTrue(PvpKnockoutManager.usesNonlethalMode(event, victim));
            players.when(() -> PlayerManager.get(attacker)).thenReturn(lethal);
            players.when(() -> PlayerManager.get(victim)).thenReturn(nonlethal);
            assertFalse(PvpKnockoutManager.usesNonlethalMode(event, victim));
        }
    }

    @Test void projectileUsesShooterMode() {
        Player attacker = player(), victim = player();
        Projectile arrow = mock(Projectile.class);
        when(arrow.getShooter()).thenReturn(attacker);
        var event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(arrow);
        PlayerData nonlethal = data(false);
        try (var players = mockStatic(PlayerManager.class)) {
            players.when(() -> PlayerManager.get(attacker)).thenReturn(nonlethal);
            assertTrue(PvpKnockoutManager.usesNonlethalMode(event, victim));
        }
    }

    @Test void damageSourceResolvesIndirectPlayerDamage() {
        Player attacker = player();
        var event = mock(EntityDamageEvent.class);
        DamageSource source = mock(DamageSource.class);
        when(event.getDamageSource()).thenReturn(source);
        when(source.getCausingEntity()).thenReturn(attacker);
        assertSame(attacker, PvpKnockoutManager.attackingPlayer(event));
    }

    @Test void environmentalDamageIgnoresVictimMode() {
        Player victim = player();
        var event = mock(EntityDamageEvent.class);
        PlayerData nonlethal = data(false), lethal = data(true);
        try (var players = mockStatic(PlayerManager.class)) {
            players.when(() -> PlayerManager.get(victim)).thenReturn(nonlethal);
            assertFalse(PvpKnockoutManager.usesNonlethalMode(event, victim));
            players.when(() -> PlayerManager.get(victim)).thenReturn(lethal);
            assertFalse(PvpKnockoutManager.usesNonlethalMode(event, victim));
        }
    }

    @Test void mobDamageIgnoresVictimMode() {
        Player victim = player();
        Entity mob = mock(Entity.class);
        var event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(mob);
        PlayerData nonlethal = data(false);
        try (var players = mockStatic(PlayerManager.class)) {
            players.when(() -> PlayerManager.get(victim)).thenReturn(nonlethal);
            assertFalse(PvpKnockoutManager.usesNonlethalMode(event, victim));
        }
    }

    @Test void selfDamageIgnoresOwnMode() {
        Player victim = player();
        var event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(victim);
        PlayerData nonlethal = data(false);
        try (var players = mockStatic(PlayerManager.class)) {
            players.when(() -> PlayerManager.get(victim)).thenReturn(nonlethal);
            assertFalse(PvpKnockoutManager.usesNonlethalMode(event, victim));
        }
    }

    @Test void attackerWithoutCharacterDoesNotUseVictimPreference() {
        Player attacker = player(), victim = player();
        var event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(attacker);
        PlayerData nonlethal = data(false);
        try (var players = mockStatic(PlayerManager.class)) {
            players.when(() -> PlayerManager.get(victim)).thenReturn(nonlethal);
            assertFalse(PvpKnockoutManager.usesNonlethalMode(event, victim));
        }
    }
}
