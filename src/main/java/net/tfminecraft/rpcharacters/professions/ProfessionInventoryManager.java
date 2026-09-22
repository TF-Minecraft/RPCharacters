package net.tfminecraft.rpcharacters.professions;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.utils.RPTexts;

public final class ProfessionInventoryManager {

	private static String t(String raw) {
		return RPTexts.formatGui(raw);
	}

	public void openMainMenu(Player player) {
		PlayerData pd = PlayerManager.get(player);
		RPCharacter character = pd != null ? pd.getActiveCharacter() : null;
		if (character == null) {
			RPTexts.send(player, RPTexts.ERROR + "You need an active character to use professions.");
			return;
		}
		Inventory inventory = RPCharacters.plugin.getServer().createInventory(null, 27,
				ChatColor.DARK_GRAY + "Profession Menu");
		int slot = 0;
		for (ProfessionDefinition profession : ProfessionRegistry.getProfessions()) {
			ItemStack menuItem = new ItemStack(profession.getMenuItem());
			ItemMeta meta = menuItem.getItemMeta();
			int freePoints = ProfessionPointService.getFreePoints(player, profession.getId());
			List<String> lore = meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
			lore.add(RPTexts.spacer());
			lore.add(t(RPTexts.GUI_WARN + "Free Points" + RPTexts.GUI_ACCENT + ": " + RPTexts.GUI_SUCCESS + freePoints));
			meta.setLore(lore);
			menuItem.setItemMeta(meta);
			inventory.setItem(slot++, menuItem);
		}
		ItemStack spentItem = new ItemStack(Material.EMERALD, 1);
		ItemMeta spentMeta = spentItem.getItemMeta();
		spentMeta.setDisplayName(t(RPTexts.GUI_SUCCESS + "Spent Points: " + character.getTotalSpentPoints() + "/"
				+ Cache.professionMaxSpendingPoints));
		spentItem.setItemMeta(spentMeta);
		inventory.setItem(26, spentItem);
		player.openInventory(inventory);
	}

	public void openProfessionMenu(Player player, ProfessionDefinition profession) {
		PlayerData pd = PlayerManager.get(player);
		RPCharacter character = pd != null ? pd.getActiveCharacter() : null;
		if (character == null) {
			return;
		}
		Inventory inventory = RPCharacters.plugin.getServer().createInventory(null, 54,
				ChatColor.DARK_GRAY + profession.getName() + " Menu");
		int slot = 0;
		for (ProfessionUpgradeDefinition upgrade : profession.getUpgrades()) {
			inventory.setItem(slot++, ProfessionLoreBuilder.buildUpgradeItem(upgrade, character));
		}
		ItemStack back = new ItemStack(Material.BARRIER, 1);
		ItemMeta backMeta = back.getItemMeta();
		backMeta.setDisplayName(t(RPTexts.ERROR + "Back"));
		back.setItemMeta(backMeta);
		inventory.setItem(53, back);
		player.openInventory(inventory);
	}

	public static ItemStack unlockedItem(ProfessionUpgradeDefinition upgrade, RPCharacter character) {
		return ProfessionLoreBuilder.buildUpgradeItem(upgrade, character);
	}
}
