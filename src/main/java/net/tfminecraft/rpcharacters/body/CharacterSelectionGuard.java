package net.tfminecraft.rpcharacters.body;

import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import net.tfminecraft.rpcharacters.managers.CreationManager;

/** Selection has no playable body, even when a player dismisses a dialog or sends packets directly. */
final class CharacterSelectionGuard implements Listener {
    static final String MOVED = "You moved, so you stay with your character.";
    private final CharacterBodies bodies;
    private final Plugin plugin;

    CharacterSelectionGuard(CharacterBodies bodies, Plugin plugin) {
        this.bodies = bodies;
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void spawn(AsyncPlayerSpawnLocationEvent event) {
        bodies.rememberSpawn(event.getConnection().getProfile().getId(), event.getSpawnLocation());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void join(PlayerJoinEvent event) { bodies.join(event.getPlayer()); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void respawn(PlayerRespawnEvent event) {
        if (bodies.awaitingDeadBody(event.getPlayer())) {
            event.setRespawnLocation(bodies.room());
            bodies.finishDeath(event.getPlayer());
        } else if (bodies.isSelecting(event.getPlayer()) && !bodies.isRespawning(event.getPlayer())) {
            event.setRespawnLocation(bodies.room());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void respawned(PlayerPostRespawnEvent event) { bodies.respawned(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void worldChanged(PlayerChangedWorldEvent event) { refresh(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void gameMode(PlayerGameModeChangeEvent event) { refresh(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void flight(PlayerToggleFlightEvent event) { refresh(event.getPlayer()); }

    private void refresh(Player player) {
        if (bodies.isSelecting(player)) Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) bodies.refreshPresentation(player);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void move(PlayerMoveEvent event) {
        if (!bodies.isSelecting(event.getPlayer()) || bodies.isApplying(event.getPlayer())) return;
        // Cancellation corrects directly; setTo would trigger our blocked teleport handler.
        if (event.hasChangedPosition()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) {
        if (bodies.isSelecting(event.getPlayer()) && !bodies.isApplying(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void damage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && bodies.isSelecting(player)) event.setCancelled(true);
        if (event instanceof EntityDamageByEntityEvent attack && attack.getDamager() instanceof Player player
                && bodies.isSelecting(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void hurt(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) bodies.interruptReturn(player, "You were hurt, so you stay with your character.");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void moved(PlayerMoveEvent event) {
        if (event.hasChangedPosition()) bodies.interruptReturn(event.getPlayer(), MOVED);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleported(PlayerTeleportEvent event) {
        bodies.interruptReturn(event.getPlayer(), MOVED);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void mounted(EntityMountEvent event) {
        if (event.getEntity() instanceof Player player) bodies.interruptReturn(player, MOVED);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void food(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && bodies.isSelecting(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void pickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && bodies.isSelecting(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void drop(PlayerDropItemEvent event) {
        if (bodies.isSelecting(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void swap(PlayerSwapHandItemsEvent event) {
        if (bodies.isSelecting(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void interact(PlayerInteractEvent event) {
        if (bodies.isSelecting(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void entityInteract(PlayerInteractEntityEvent event) {
        if (bodies.isSelecting(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) {
        if (bodies.isSelecting(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void placeBlock(BlockPlaceEvent event) {
        if (bodies.isSelecting(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void click(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && bodies.isSelecting(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void drag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && bodies.isSelecting(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void open(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && bodies.isSelecting(player)
                && !CreationManager.activeCreators.containsKey(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void chat(AsyncPlayerChatEvent event) {
        if (bodies.isSelecting(event.getPlayer()) && !CreationManager.activeCreators.containsKey(event.getPlayer())) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, () -> bodies.feedback(event.getPlayer(), "Choose a character before chatting."));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void command(PlayerCommandPreprocessEvent event) {
        if (!bodies.isSelecting(event.getPlayer())) return;
        String command = event.getMessage().trim().toLowerCase(Locale.ROOT);
        if (command.equals("/discord") || command.startsWith("/link ") || command.equals("/link")
                || command.equals("/linkdiscord") || command.equals("/tfmcweb:linkdiscord")) {
            bodies.interact(event.getPlayer());
            return;
        }
        String[] words = command.split("\\s+");
        if (words.length == 2 && (words[0].equals("/rpcharacter") || words[0].equals("/rpcharacters:rpcharacter"))
                && java.util.Set.of("menu", "cancel", "next", "back", "help").contains(words[1])) return;
        event.setCancelled(true);
        bodies.feedback(event.getPlayer(), "Choose a character before using commands. Use /rpcharacter menu to reopen selection.");
    }
}
