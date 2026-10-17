package net.tfminecraft.rpcharacters.pvp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.lumine.mythic.lib.api.event.skill.PlayerCastSkillEvent;

class KnockoutRestrictionsTest {

    Player downed, standing;
    KnockoutRestrictions restrictions;

    @BeforeEach void setup() {
        downed = player();
        standing = player();
        restrictions = new KnockoutRestrictions(player -> player == downed);
    }

    @Test void aDownedPlayerCannotHitShootOrCastButOthersCan() {
        var hit = mock(EntityDamageByEntityEvent.class);
        when(hit.getDamager()).thenReturn(downed);
        when(hit.getEntity()).thenReturn(standing);
        restrictions.onAttack(hit);
        verify(hit).setCancelled(true);
        var hitBack = mock(EntityDamageByEntityEvent.class);
        when(hitBack.getDamager()).thenReturn(standing);
        when(hitBack.getEntity()).thenReturn(downed);
        restrictions.onAttack(hitBack);
        verify(hitBack, never()).setCancelled(true);
        // Their own fall or fire damage is not an attack.
        var self = mock(EntityDamageByEntityEvent.class);
        when(self.getDamager()).thenReturn(downed);
        when(self.getEntity()).thenReturn(downed);
        restrictions.onAttack(self);
        verify(self, never()).setCancelled(true);
        var environment = mock(EntityDamageByEntityEvent.class);
        when(environment.getDamager()).thenReturn(mock(Zombie.class));
        restrictions.onAttack(environment);
        verify(environment, never()).setCancelled(true);

        var bow = mock(EntityShootBowEvent.class);
        when(bow.getEntity()).thenReturn(downed);
        restrictions.onShootBow(bow);
        verify(bow).setCancelled(true);
        var mobBow = mock(EntityShootBowEvent.class);
        when(mobBow.getEntity()).thenReturn(mock(Zombie.class));
        restrictions.onShootBow(mobBow);
        verify(mobBow, never()).setCancelled(true);

        Arrow arrow = mock(Arrow.class);
        when(arrow.getShooter()).thenReturn(downed);
        var launch = mock(ProjectileLaunchEvent.class);
        when(launch.getEntity()).thenReturn(arrow);
        restrictions.onLaunch(launch);
        verify(launch).setCancelled(true);
        Arrow mobArrow = mock(Arrow.class);
        when(mobArrow.getShooter()).thenReturn(mock(Zombie.class));
        var mobLaunch = mock(ProjectileLaunchEvent.class);
        when(mobLaunch.getEntity()).thenReturn(mobArrow);
        restrictions.onLaunch(mobLaunch);
        verify(mobLaunch, never()).setCancelled(true);

        var cast = mock(PlayerCastSkillEvent.class);
        when(cast.getPlayer()).thenReturn(downed);
        restrictions.onCastSkill(cast);
        verify(cast).setCancelled(true);
        var otherCast = mock(PlayerCastSkillEvent.class);
        when(otherCast.getPlayer()).thenReturn(standing);
        restrictions.onCastSkill(otherCast);
        verify(otherCast, never()).setCancelled(true);
    }

    @Test void aDownedPlayerCannotUseEatDropOrBuild() {
        List<Consumer<Player>> actions = List.of(
                player -> check(mock(PlayerInteractEntityEvent.class), player, restrictions::onInteractEntity),
                player -> check(mock(PlayerItemConsumeEvent.class), player, restrictions::onConsume),
                player -> check(mock(PlayerSwapHandItemsEvent.class), player, restrictions::onSwapHands),
                player -> check(mock(PlayerDropItemEvent.class), player, restrictions::onDrop),
                player -> check(mock(BlockPlaceEvent.class), player, restrictions::onPlace),
                player -> check(mock(BlockBreakEvent.class), player, restrictions::onBreak));
        for (var action : actions) {
            action.accept(downed);
            action.accept(standing);
        }
    }

    @Test void clicksAreDeniedButPressurePlatesStillWork() {
        var click = interact(downed, Action.RIGHT_CLICK_AIR);
        restrictions.onInteract(click);
        verify(click).setUseItemInHand(Event.Result.DENY);
        verify(click).setUseInteractedBlock(Event.Result.DENY);
        var plate = interact(downed, Action.PHYSICAL);
        restrictions.onInteract(plate);
        verify(plate, never()).setUseItemInHand(any());
        var standingClick = interact(standing, Action.RIGHT_CLICK_BLOCK);
        restrictions.onInteract(standingClick);
        verify(standingClick, never()).setUseItemInHand(any());
    }

    @Test void theNoticeIsSentAtMostOnceASecondAndResetsOnQuit() {
        var first = interact(downed, Action.LEFT_CLICK_AIR);
        restrictions.onInteract(first);
        restrictions.onInteract(interact(downed, Action.RIGHT_CLICK_AIR));
        verify(downed, times(1)).sendMessage(contains("knocked out"));
        var quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(downed);
        restrictions.onQuit(quit);
        restrictions.onInteract(interact(downed, Action.RIGHT_CLICK_AIR));
        verify(downed, times(2)).sendMessage(contains("knocked out"));
        verify(standing, never()).sendMessage(anyString());
    }

    @Test void theNoticeRepeatsOnceTheIntervalHasPassed() throws Exception {
        restrictions.onInteract(interact(downed, Action.RIGHT_CLICK_AIR));
        var field = KnockoutRestrictions.class.getDeclaredField("lastNotice");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var notices = (java.util.Map<UUID, Long>) field.get(restrictions);
        notices.put(downed.getUniqueId(), System.currentTimeMillis() - 1_000L);
        restrictions.onInteract(interact(downed, Action.RIGHT_CLICK_AIR));
        verify(downed, times(2)).sendMessage(contains("knocked out"));
    }

    private <E extends Event & Cancellable> void check(E event, Player player, Consumer<E> handler) {
        stubPlayer(event, player);
        handler.accept(event);
        if (player == downed) {
            verify(event).setCancelled(true);
        } else {
            verify(event, never()).setCancelled(true);
        }
    }

    private static void stubPlayer(Event event, Player player) {
        if (event instanceof org.bukkit.event.player.PlayerEvent playerEvent) {
            when(playerEvent.getPlayer()).thenReturn(player);
        } else if (event instanceof BlockPlaceEvent place) {
            when(place.getPlayer()).thenReturn(player);
        } else if (event instanceof BlockBreakEvent breaking) {
            when(breaking.getPlayer()).thenReturn(player);
        }
    }

    private static PlayerInteractEvent interact(Player player, Action action) {
        var event = mock(PlayerInteractEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getAction()).thenReturn(action);
        return event;
    }

    private static Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        return player;
    }
}
