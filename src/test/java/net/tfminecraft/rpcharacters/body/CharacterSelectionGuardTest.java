package net.tfminecraft.rpcharacters.body;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.creation.CharacterCreation;
import net.tfminecraft.rpcharacters.managers.CreationManager;
import org.bukkit.Location;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.Cancellable;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class CharacterSelectionGuardTest {
    ServerMock server;
    PlayerMock player;
    CharacterBodies bodies;
    CharacterSelectionGuard guard;
    RuntimeTestState state;
    Location room;

    @BeforeEach void setup() {
        server = MockBukkit.mock(); player = server.addPlayer("Alex"); bodies = mock(CharacterBodies.class);
        state = new RuntimeTestState(CreationManager.class); CreationManager.activeCreators.clear();
        room = new Location(player.getWorld(), .5, 64, .5); when(bodies.room()).thenReturn(room);
        guard = new CharacterSelectionGuard(bodies, MockBukkit.createMockPlugin());
    }

    @AfterEach void cleanup() { state.close(); MockBukkit.unmock(); }

    @Test void spawnCapturesOriginalLocationAndRoomRespawnCannotEscapeWhileBodyRecoveryRetainsItsVanillaSpawn() {
        AsyncPlayerSpawnLocationEvent spawn = mock(AsyncPlayerSpawnLocationEvent.class, RETURNS_DEEP_STUBS);
        Location original = new Location(player.getWorld(), 50, 75, 50);
        when(spawn.getConnection().getProfile().getId()).thenReturn(player.getUniqueId()); when(spawn.getSpawnLocation()).thenReturn(original);
        guard.spawn(spawn); verify(bodies).rememberSpawn(player.getUniqueId(), original); verify(spawn, never()).setSpawnLocation(any());
        PlayerJoinEvent join = mock(PlayerJoinEvent.class); when(join.getPlayer()).thenReturn(player);
        guard.join(join); verify(bodies).join(player);
        var post = mock(com.destroystokyo.paper.event.player.PlayerPostRespawnEvent.class); when(post.getPlayer()).thenReturn(player);
        guard.respawned(post); verify(bodies).respawned(player);
        PlayerRespawnEvent respawn = mock(PlayerRespawnEvent.class); when(respawn.getPlayer()).thenReturn(player);
        guard.respawn(respawn); verify(respawn, never()).setRespawnLocation(any()); verify(bodies, never()).finishDeath(any());
        when(bodies.awaitingDeadBody(player)).thenReturn(true);
        guard.respawn(respawn); verify(respawn).setRespawnLocation(room); verify(bodies).finishDeath(player);
        when(bodies.awaitingDeadBody(player)).thenReturn(false); when(bodies.isSelecting(player)).thenReturn(true);
        clearInvocations(respawn); guard.respawn(respawn); verify(respawn).setRespawnLocation(room);
        when(bodies.isRespawning(player)).thenReturn(true); clearInvocations(respawn);
        guard.respawn(respawn); verify(respawn,never()).setRespawnLocation(any());
    }

    @Test void anyDamageToAPlayerStopsTheirReturnToSelection() {
        EntityDamageEvent hurt = mock(EntityDamageEvent.class); when(hurt.getEntity()).thenReturn(player);
        guard.hurt(hurt); verify(bodies).interruptReturn(eq(player), contains("hurt"));
        when(hurt.getEntity()).thenReturn(mock(Zombie.class)); clearInvocations(bodies);
        guard.hurt(hurt); verify(bodies, never()).interruptReturn(any(), any());
    }

    @Test void selectionLocksPositionAndTeleportButAllowsLookingAroundAndTheAuthoritativeRestoreTeleport() {
        Location destination = room.clone().add(.2, 0, 0);
        PlayerMoveEvent move = new PlayerMoveEvent(player, room, destination);
        guard.move(move); assertFalse(move.isCancelled());
        when(bodies.isSelecting(player)).thenReturn(true);
        Location looking = room.clone(); looking.setYaw(90);
        PlayerMoveEvent look = new PlayerMoveEvent(player, room, looking);
        guard.move(look); assertFalse(look.isCancelled()); assertEquals(looking, look.getTo());
        guard.move(move); assertTrue(move.isCancelled());
        // Cancellation corrects directly; changing the destination triggers our blocked teleport handler.
        assertEquals(destination, move.getTo());
        when(bodies.isApplying(player)).thenReturn(true);
        PlayerMoveEvent restore = new PlayerMoveEvent(player, room, destination);
        guard.move(restore); assertFalse(restore.isCancelled());
        PlayerTeleportEvent teleport = mock(PlayerTeleportEvent.class); when(teleport.getPlayer()).thenReturn(player);
        guard.teleport(teleport); verify(teleport, never()).setCancelled(anyBoolean());
        when(bodies.isApplying(player)).thenReturn(false); guard.teleport(teleport); verify(teleport).setCancelled(true);
        clearInvocations(teleport); when(bodies.isSelecting(player)).thenReturn(false);
        guard.teleport(teleport); verify(teleport, never()).setCancelled(anyBoolean());
    }

    @Test void selectionPreventsTakingOrInflictingDamageWithoutAffectingUnrelatedEntities() {
        EntityDamageEvent damage = mock(EntityDamageEvent.class); when(damage.getEntity()).thenReturn(player);
        guard.damage(damage); verify(damage, never()).setCancelled(anyBoolean());
        when(bodies.isSelecting(player)).thenReturn(true); guard.damage(damage); verify(damage).setCancelled(true);
        EntityDamageByEntityEvent attack = mock(EntityDamageByEntityEvent.class);
        Zombie victim = mock(Zombie.class); when(attack.getEntity()).thenReturn(victim); when(attack.getDamager()).thenReturn(player);
        guard.damage(attack); verify(attack).setCancelled(true);
        when(attack.getDamager()).thenReturn(victim); clearInvocations(attack);
        guard.damage(attack); verify(attack, never()).setCancelled(anyBoolean());
    }

    @Test void selectingPlayersCannotChangeItemsFoodOrTheWorldWhilePlayablePlayersKeepNormalEvents() {
        playerBoundary(PlayerDropItemEvent.class, PlayerDropItemEvent::getPlayer, guard::drop);
        playerBoundary(PlayerSwapHandItemsEvent.class, PlayerSwapHandItemsEvent::getPlayer, guard::swap);
        playerBoundary(PlayerInteractEvent.class, PlayerInteractEvent::getPlayer, guard::interact);
        playerBoundary(PlayerInteractEntityEvent.class, PlayerInteractEntityEvent::getPlayer, guard::entityInteract);
        playerBoundary(BlockBreakEvent.class, BlockBreakEvent::getPlayer, guard::breakBlock);
        playerBoundary(BlockPlaceEvent.class, BlockPlaceEvent::getPlayer, guard::placeBlock);
        FoodLevelChangeEvent food = mock(FoodLevelChangeEvent.class); when(food.getEntity()).thenReturn(player);
        EntityPickupItemEvent pickup = mock(EntityPickupItemEvent.class); when(pickup.getEntity()).thenReturn(player);
        for (boolean selecting : new boolean[]{false, true}) {
            when(bodies.isSelecting(player)).thenReturn(selecting);
            guard.food(food); guard.pickup(pickup);
            verify(food, times(selecting ? 1 : 0)).setCancelled(true);
            verify(pickup, times(selecting ? 1 : 0)).setCancelled(true);
        }
        when(food.getEntity()).thenReturn(mock(HumanEntity.class)); when(pickup.getEntity()).thenReturn(mock(Zombie.class));
        clearInvocations(food, pickup); guard.food(food); guard.pickup(pickup);
        verify(food, never()).setCancelled(anyBoolean()); verify(pickup, never()).setCancelled(anyBoolean());
    }

    private <E extends Cancellable> void playerBoundary(Class<E> type, Function<E, Player> actor, Consumer<E> handler) {
        E event = mock(type); when(actor.apply(event)).thenReturn(player);
        when(bodies.isSelecting(player)).thenReturn(false); handler.accept(event); verify(event, never()).setCancelled(anyBoolean());
        when(bodies.isSelecting(player)).thenReturn(true); handler.accept(event); verify(event).setCancelled(true);
    }

    @Test void inventoryMutationIsBlockedAndOnlyTheActiveCreatorMayOpenAnInventory() {
        InventoryClickEvent click = mock(InventoryClickEvent.class); when(click.getWhoClicked()).thenReturn(player);
        InventoryDragEvent drag = mock(InventoryDragEvent.class); when(drag.getWhoClicked()).thenReturn(player);
        InventoryOpenEvent open = mock(InventoryOpenEvent.class); when(open.getPlayer()).thenReturn(player);
        guard.click(click); guard.drag(drag); guard.open(open);
        verify(click, never()).setCancelled(anyBoolean()); verify(drag, never()).setCancelled(anyBoolean()); verify(open, never()).setCancelled(anyBoolean());
        when(bodies.isSelecting(player)).thenReturn(true); guard.click(click); guard.drag(drag); guard.open(open);
        verify(click).setCancelled(true); verify(drag).setCancelled(true); verify(open).setCancelled(true);
        CreationManager.activeCreators.put(player, mock(CharacterCreation.class)); clearInvocations(open);
        guard.open(open); verify(open, never()).setCancelled(anyBoolean());
        HumanEntity npc = mock(HumanEntity.class); when(click.getWhoClicked()).thenReturn(npc); when(drag.getWhoClicked()).thenReturn(npc); when(open.getPlayer()).thenReturn(npc);
        clearInvocations(click, drag, open); guard.click(click); guard.drag(drag); guard.open(open);
        verify(click, never()).setCancelled(anyBoolean()); verify(drag, never()).setCancelled(anyBoolean()); verify(open, never()).setCancelled(anyBoolean());
    }

    @Test void chatStaysAvailableForCreationButCannotBroadcastFromSelection() {
        AsyncPlayerChatEvent chat = mock(AsyncPlayerChatEvent.class); when(chat.getPlayer()).thenReturn(player);
        guard.chat(chat); verify(chat, never()).setCancelled(anyBoolean());
        when(bodies.isSelecting(player)).thenReturn(true); CreationManager.activeCreators.put(player, mock(CharacterCreation.class));
        guard.chat(chat); verify(chat, never()).setCancelled(anyBoolean());
        CreationManager.activeCreators.clear(); guard.chat(chat); verify(chat).setCancelled(true);
        assertNull(player.nextMessage()); server.getScheduler().performOneTick();
        assertEquals("Choose a character before chatting.", player.nextMessage());
    }

    @Test void selectionAllowsOnlyLinkingAndCreationNavigationCommands() {
        PlayerCommandPreprocessEvent gameplay = command("/minecraft:give @s diamond");
        guard.command(gameplay); verify(gameplay, never()).setCancelled(anyBoolean());
        when(bodies.isSelecting(player)).thenReturn(true);
        for (String text : List.of("/discord", "/link", "/link token", "/linkdiscord", "/tfmcweb:linkdiscord",
                " /TFMCWEB:LINKDISCORD ", "/rpcharacter select", "/rpcharacters:rpcharacter select",
                " /RPCHARACTER   NEXT ", "/rpcharacter cancel", "/rpcharacter back", "/rpcharacter help")) {
            PlayerCommandPreprocessEvent allowed = command(text); guard.command(allowed); verify(allowed, never()).setCancelled(anyBoolean());
        }
        for (String text : List.of("/give diamond", "/rpcharacter select extra", "/rpcharacter switch Bob", "/discord token", "/linkevil",
                "/linkdiscord token", "/linkdiscordevil", "/tfmcweb:linkdiscord token", "/tfmcweb:unlinkdiscord", "/tfmcwebadmin")) {
            PlayerCommandPreprocessEvent denied = command(text); guard.command(denied); verify(denied).setCancelled(true);
            assertTrue(player.nextMessage().contains("/rpcharacter select"));
        }
    }

    private PlayerCommandPreprocessEvent command(String text) {
        PlayerCommandPreprocessEvent event = mock(PlayerCommandPreprocessEvent.class);
        when(event.getPlayer()).thenReturn(player); when(event.getMessage()).thenReturn(text); return event;
    }
}
