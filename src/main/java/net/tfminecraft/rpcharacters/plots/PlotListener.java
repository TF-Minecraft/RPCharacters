package net.tfminecraft.rpcharacters.plots;

import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.lifecycle.CharacterActivatedEvent;
import net.tfminecraft.rpcharacters.permadeath.CharacterPermakillEvent;

/** Plot menus, blows, deaths, character changes and messages waiting for a returning character. */
public final class PlotListener implements Listener {
    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof PlotMenu.Holder holder)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        var action = holder.actions.get(event.getSlot());
        if (action != null) action.accept(event.getClick());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof PlotMenu.Holder) event.setCancelled(true);
    }

    /** Before the knockout manager (HIGHEST) cancels a downing blow; blows another plugin blocked never count. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = event.getDamager() instanceof Player player ? player
                : event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter ? shooter : null;
        if (attacker != null && !attacker.equals(victim)) PlotHunt.damaged(victim, attacker);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        PlotHunt.died(event.getEntity(), event.getEntity().getKiller());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Characters load after joining.
        org.bukkit.Bukkit.getScheduler().runTaskLater(RPCharacters.plugin, () -> {
            if (player.isOnline()) PlotService.deliver(player);
        }, 100L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPermakill(CharacterPermakillEvent event) {
        var character = event.getCharacter();
        // The death is committed after the event; confirm it next tick.
        org.bukkit.Bukkit.getScheduler().runTask(RPCharacters.plugin, () -> {
            if (character.getStatus() == Status.DEAD) PlotHunt.characterDied(character.getId());
        });
    }

    @EventHandler
    public void onActivated(CharacterActivatedEvent event) {
        Player player = event.getOwner();
        if (player != null) org.bukkit.Bukkit.getScheduler().runTask(RPCharacters.plugin, () -> PlotService.deliver(player));
    }
}
