package net.tfminecraft.rpcharacters.wardrobe;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.tlibs.armour.ArmorEquipEvent;
import net.tfminecraft.tlibs.armour.ArmorType;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.utils.RPTexts;

/**
 * Mask helmet on/off → re-apply wardrobe skin; wardrobe GUI clicks; clear cache on quit.
 */
public final class WardrobeListener implements Listener {

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onArmorEquip(ArmorEquipEvent event) {
		if (event.getType() != ArmorType.HELMET) {
			return;
		}
		Player player = event.getPlayer();
		if (player == null || !player.isOnline()) {
			return;
		}
		// Helmet inventory updates after the event; apply next tick.
		Bukkit.getScheduler().runTask(RPCharacters.plugin, () -> {
			if (player.isOnline()) {
				WardrobeService.applyFor(player);
			}
		});
	}

	@EventHandler
	public void onWardrobeGuiClick(InventoryClickEvent event) {
		if (!(event.getView().getTopInventory().getHolder()
			instanceof WardrobeGuiHolder holder)) {
			return;
		}
		event.setCancelled(true);
		if (!(event.getWhoClicked() instanceof Player player)) {
			return;
		}
		if (!player.equals(holder.getOwner())) {
			return;
		}
		if (event.getClickedInventory() == null
			|| !event.getClickedInventory().equals(event.getView().getTopInventory())) {
			return;
		}
		ItemStack item = event.getCurrentItem();
		String slotId = WardrobeGui.readSlotId(item);
		if (slotId == null) {
			return;
		}

		WardrobeSnapshot snapshot = WardrobeCache.get(player);
		if (snapshot == null) {
			RPTexts.send(player, RPTexts.WARN + "Wardrobe is still loading.");
			return;
		}
		WardrobeSlotData data = snapshot.getSlot(slotId);
		boolean unlocked = WardrobeGui.isUnlocked(snapshot, slotId, data);
		boolean filled = data != null && data.isFilled() && data.canApply();

		if (!unlocked) {
			RPTexts.send(
				player,
				WardrobeGui.unlockChatMessage(WardrobeGui.minSlotsFor(slotId))
			);
			player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
			return;
		}
		if (!filled) {
			RPTexts.send(
				player,
				RPTexts.WARN
					+ "That slot is empty. Upload a skin on the website "
					+ "(character → Wardrobe)."
			);
			player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
			return;
		}

		String active = snapshot.getActiveSlot();
		if (active != null && active.equalsIgnoreCase(slotId)) {
			RPTexts.send(
				player,
				RPTexts.SUCCESS + "Already using "
					+ RPTexts.WARN + WardrobeService.labelForSlot(snapshot, slotId)
					+ RPTexts.SUCCESS + "."
			);
			return;
		}

		boolean reopenWardrobe = WardrobeGui.closeIfOpen(player);

		WardrobeService.setActiveAndApply(player, slotId, error -> {
			if (error != null) {
				RPTexts.send(player, RPTexts.ERROR + error);
				player.playSound(
					player.getLocation(),
					Sound.ENTITY_VILLAGER_NO,
					1f,
					1f
				);
				return;
			}
			RPTexts.send(
				player,
				RPTexts.SUCCESS + "Equipped "
					+ RPTexts.WARN + WardrobeService.labelForSlot(
						WardrobeCache.get(player),
						slotId
					)
					+ RPTexts.SUCCESS + "."
			);
			player.playSound(
				player.getLocation(),
				Sound.BLOCK_NOTE_BLOCK_BIT,
				1f,
				1f
			);
			if (reopenWardrobe) {
				Bukkit.getScheduler().runTask(RPCharacters.plugin, () -> {
					if (player.isOnline()) {
						WardrobeGui.openNow(player);
					}
				});
			}
		});
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		WardrobeCache.clear(event.getPlayer());
	}
}
