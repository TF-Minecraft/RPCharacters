package net.tfminecraft.rpcharacters.pvp;

import java.util.UUID;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.junit.jupiter.api.Test;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PvpKnockoutManagerTest {
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
