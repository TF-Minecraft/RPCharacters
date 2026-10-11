package net.tfminecraft.rpcharacters.plots;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.UUID;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.lifecycle.CharacterActivatedEvent;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.permadeath.CharacterPermakillEvent;
import net.tfminecraft.rpcharacters.permadeath.PermakillCause;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PlotListenerTest {
    @TempDir Path directory;
    PlotWorld world;
    PlayerMock master, assassin, target;
    final PlotListener listener = new PlotListener();
    CommandSender staff = mock(CommandSender.class);

    @BeforeEach
    void setup() throws Exception {
        world = new PlotWorld(directory);
        master = world.player("Master", "c-master", 50);
        assassin = world.player("Assassin", "c-assassin", 60);
        target = world.player("Target", "c-target", 40);
        when(staff.getName()).thenReturn("Staffer");
    }

    @AfterEach
    void close() { world.close(); }

    Plot executing() {
        Plot plot = PlotService.request(master);
        PlotService.approve(staff, plot.code, "Target");
        PlotService.invite(master, assassin);
        PlotService.accept(assassin, plot.code);
        PlotService.assign(master, "c-assassin", PlotRole.ASSASSIN, null);
        plot.phase = PlotPhase.EXECUTION;
        plot.member("c-assassin").huntingSince = world.clock.get() - PlotConfig.huntWarmup();
        return plot;
    }

    /** A click on the top inventory, or below it when the slot is past its end. */
    InventoryClickEvent click(PlayerMock player, int slot) {
        var open = player.getOpenInventory();
        var top = open.getTopInventory() != null ? open.getTopInventory() : world.server.createInventory(null, 9);
        var view = mock(org.bukkit.inventory.InventoryView.class);
        when(view.getTopInventory()).thenReturn(top);
        var bottom = player.getInventory();
        when(view.getBottomInventory()).thenReturn(bottom);
        boolean inTop = slot < top.getSize();
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        boolean[] cancelled = {false};
        when(event.getView()).thenReturn(view);
        when(event.getClickedInventory()).thenReturn(inTop ? top : bottom);
        when(event.getSlot()).thenReturn(inTop ? slot : slot - top.getSize());
        when(event.getClick()).thenReturn(ClickType.LEFT);
        org.mockito.Mockito.doAnswer(call -> cancelled[0] = call.getArgument(0)).when(event).setCancelled(org.mockito.ArgumentMatchers.anyBoolean());
        when(event.isCancelled()).thenAnswer(call -> cancelled[0]);
        return event;
    }

    static EntityDamageByEntityEvent blow(Entity victim, Entity damager) {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getEntity()).thenReturn(victim);
        when(event.getDamager()).thenReturn(damager);
        return event;
    }

    @Test
    void plotMenuClicksRunTheirSlotActionOnly() {
        PlotMenu.open(master);
        InventoryClickEvent outside = click(master, 60);
        listener.onClick(outside);
        assertTrue(outside.isCancelled());
        InventoryClickEvent empty = click(master, 0);
        listener.onClick(empty);
        assertTrue(empty.isCancelled());
        listener.onClick(click(master, 2));
        assertEquals(PlotApproach.DEFENSIVE, world.store().peek("c-master").approach());
        InventoryDragEvent drag = mock(InventoryDragEvent.class);
        var view = click(master, 0).getView();
        when(drag.getView()).thenReturn(view);
        listener.onDrag(drag);
        verify(drag).setCancelled(true);
        master.closeInventory();
        InventoryClickEvent other = click(master, 0);
        assertFalse(other.getView().getTopInventory().getHolder() instanceof PlotMenu.Holder);
        listener.onClick(other);
        assertFalse(other.isCancelled());
        InventoryDragEvent otherDrag = mock(InventoryDragEvent.class);
        var closed = other.getView();
        when(otherDrag.getView()).thenReturn(closed);
        listener.onDrag(otherDrag);
        verify(otherDrag, never()).setCancelled(true);
    }

    @Test
    void blowsAndDeathsReachThePlots() {
        Plot plot = executing();
        Zombie zombie = mock(Zombie.class);
        listener.onDamage(blow(assassin, zombie));
        listener.onDamage(blow(zombie, assassin));
        listener.onDamage(blow(target, target));
        Arrow stray = mock(Arrow.class);
        listener.onDamage(blow(target, stray));
        Arrow arrow = mock(Arrow.class);
        when(arrow.getShooter()).thenReturn(assassin);
        listener.onDamage(blow(target, arrow));
        PlayerDeathEvent death = mock(PlayerDeathEvent.class);
        when(death.getEntity()).thenReturn(target);
        listener.onDeath(death);
        assertEquals(PlotPhase.SUCCEEDED, plot.phase);
        listener.onDamage(blow(target, assassin));
    }

    @Test
    void returningPlayersGetTheirMessages() {
        world.logOff(master);
        PlotService.tell(new PlotCharacter("c-master", master.getUniqueId(), "Master", "M"), "later");
        world.play(master, "c-master");
        listener.onJoin(new PlayerJoinEvent(master, "joined"));
        master.disconnect();
        world.server.getScheduler().performTicks(101);
        assertFalse(world.heard(master, "later"));
        master.reconnect();
        listener.onJoin(new PlayerJoinEvent(master, "joined"));
        world.server.getScheduler().performTicks(101);
        assertTrue(world.heard(master, "later"));
    }

    @Test
    void charactersDyingOrSwitchingReachThePlots() {
        Plot plot = executing();
        RPCharacter dying = mock(RPCharacter.class);
        when(dying.getId()).thenReturn("c-master");
        when(dying.getStatus()).thenReturn(Status.ALIVE);
        listener.onPermakill(new CharacterPermakillEvent(master, dying, PermakillCause.OTHER));
        world.server.getScheduler().performOneTick();
        assertEquals(PlotPhase.EXECUTION, plot.phase);
        when(dying.getStatus()).thenReturn(Status.DEAD);
        listener.onPermakill(new CharacterPermakillEvent(master, dying, PermakillCause.OTHER));
        world.server.getScheduler().performOneTick();
        assertEquals(PlotPhase.DISSOLVED, plot.phase);

        world.logOff(target);
        PlotService.tell(new PlotCharacter("c-target", target.getUniqueId(), "Target", "T"), "held");
        world.play(target, "c-target");
        listener.onActivated(new CharacterActivatedEvent(target, target.getUniqueId(), mock(RPCharacter.class), null));
        listener.onActivated(new CharacterActivatedEvent(null, UUID.randomUUID(), mock(RPCharacter.class), null));
        world.server.getScheduler().performOneTick();
        assertTrue(world.heard(target, "held"));
    }
}
