package net.tfminecraft.rpcharacters.professions;

import java.util.HashMap;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import net.Indyuce.mmocore.api.event.PlayerLevelChangeEvent;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.utils.RPTexts;

public class ProfessionListener implements Listener {
	public static final Map<Player, String> currentProfessionMenu = new HashMap<>();
	public static final Map<Player, ProfessionUpgradeDefinition> pendingRemoval = new HashMap<>();

	private final ProfessionInventoryManager inventoryManager = new ProfessionInventoryManager();

	// Keep the existing legacy text representation, formatting, and exact-string comparisons.
	@SuppressWarnings("deprecation")
	@EventHandler
	public void onMainMenuClick(InventoryClickEvent event) {
		if (event.getClickedInventory() == null || event.getCurrentItem() == null) {
			return;
		}
		if (!event.getView().getTitle().equalsIgnoreCase(ChatColor.DARK_GRAY + "Profession Menu")) {
			return;
		}
		event.setCancelled(true);
		if (!(event.getWhoClicked() instanceof Player player)) {
			return;
		}
		String itemName = event.getCurrentItem().getItemMeta().getDisplayName();
		for (ProfessionDefinition profession : ProfessionRegistry.getProfessions()) {
			if (profession.getMenuItem().getItemMeta().getDisplayName().equalsIgnoreCase(itemName)) {
				inventoryManager.openProfessionMenu(player, profession);
				currentProfessionMenu.put(player, profession.getName());
				return;
			}
		}
	}

	// Keep the existing legacy text representation, formatting, and exact-string comparisons.
	@SuppressWarnings("deprecation")
	@EventHandler
	public void onProfessionMenuClick(InventoryClickEvent event) {
		if (event.getClickedInventory() == null || event.getCurrentItem() == null) {
			return;
		}
		if (!(event.getWhoClicked() instanceof Player player)) {
			return;
		}
		String menuName = currentProfessionMenu.get(player);
		if (menuName == null) {
			return;
		}
		if (!event.getView().getTitle().equalsIgnoreCase(ChatColor.DARK_GRAY + menuName + " Menu")) {
			return;
		}
		event.setCancelled(true);
		if (event.getCurrentItem().getType().equals(Material.BARRIER)) {
			inventoryManager.openMainMenu(player);
			currentProfessionMenu.remove(player);
			return;
		}
		PlayerData pd = PlayerManager.get(player);
		RPCharacter character = pd != null ? pd.getActiveCharacter() : null;
		if (character == null) {
			return;
		}
		String itemName = event.getCurrentItem().getItemMeta().getDisplayName();
		for (ProfessionDefinition profession : ProfessionRegistry.getProfessions()) {
			if (!profession.getName().equalsIgnoreCase(menuName)) {
				continue;
			}
			for (ProfessionUpgradeDefinition upgrade : profession.getUpgrades()) {
				if (!upgrade.getMenuItem().getItemMeta().getDisplayName().equalsIgnoreCase(itemName)) {
					continue;
				}
				handleUpgradeClick(player, character, profession, upgrade, event);
				return;
			}
		}
	}

	// Keep the existing legacy text representation, formatting, and exact-string comparisons.
	@SuppressWarnings("deprecation")
	private void handleUpgradeClick(Player player, RPCharacter character, ProfessionDefinition profession,
			ProfessionUpgradeDefinition upgrade, InventoryClickEvent event) {
		if (character.getTotalSpentPoints() + upgrade.getCost() > Cache.professionMaxSpendingPoints
				&& !character.hasProfessionUpgrade(upgrade.getId())) {
			RPTexts.send(player, RPTexts.ERROR + "You already have the maximum amount of upgrades!");
			player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
			return;
		}
		if (character.hasProfessionUpgrade(upgrade.getId())) {
			for (ProfessionUpgradeDefinition other : profession.getUpgrades()) {
				if (character.hasProfessionUpgrade(other.getId()) && other.getRequirements().contains(upgrade.getId())) {
					RPTexts.send(player, RPTexts.ERROR
							+ "Cannot remove this upgrade as it is a requirement for another upgrade!");
					return;
				}
			}
			player.closeInventory();
			RPTexts.send(player, RPTexts.ERROR + "Are you sure you want to remove this upgrade? Points are not refunded!");
			RPTexts.send(player, RPTexts.WARN + "To confirm type " + RPTexts.COMMAND + "/profession confirm"
					+ RPTexts.WARN + " within 60 seconds");
			pendingRemoval.put(player, upgrade);
			new BukkitRunnable() {
				@Override
				public void run() {
					pendingRemoval.remove(player);
				}
			}.runTaskLater(RPCharacters.plugin, 1200L);
			return;
		}
		if (ProfessionPointService.getFreePoints(player, profession.getId()) < upgrade.getCost()) {
			RPTexts.send(player, RPTexts.ERROR + "You cannot afford this upgrade!");
			player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
			return;
		}
		for (String requirement : upgrade.getRequirements()) {
			if (!character.hasProfessionUpgrade(requirement)) {
				RPTexts.send(player, RPTexts.ERROR + "You dont have the required upgrades!");
				player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
				return;
			}
		}
		character.addProfessionUpgrade(upgrade.getId());
		ProfessionIntegrator.applyUpgrade(player, upgrade);
		RPCharacters.getPlayerManager().savePlayer(player);
		RPTexts.send(player, RPTexts.WARN + "Purchased " + upgrade.getMenuItem().getItemMeta().getDisplayName()
				+ RPTexts.WARN + " for " + RPTexts.SUCCESS + upgrade.getCost() + RPTexts.WARN + " points.");
		player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1f, 1f);
		event.getClickedInventory().setItem(event.getSlot(),
				ProfessionInventoryManager.unlockedItem(upgrade, character));
		Bukkit.getPluginManager().callEvent(
				new ProfessionUpgradePurchasedEvent(player, character, upgrade.getId(), upgrade.getCost()));
	}

	@EventHandler
	public void onProfessionLevelUp(PlayerLevelChangeEvent event) {
		ProfessionPointService.onProfessionLevelUp(event);
	}
}
