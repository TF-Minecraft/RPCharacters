package net.tfminecraft.rpcharacters.professions;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.bukkit.ChatColor;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.utils.RPTexts;

public final class ProfessionLoreBuilder {
	private static final Pattern LEGACY_COST_LINE = Pattern.compile("(?i).*\\bcost\\b:.*");
	private static final Pattern LEGACY_REQUIRES_LINE = Pattern.compile("(?i).*\\brequires\\b.*");

	private ProfessionLoreBuilder() {}

	private static String t(String raw) {
		return RPTexts.formatGui(raw);
	}

	public static ItemStack buildUpgradeItem(ProfessionUpgradeDefinition upgrade, RPCharacter character) {
		ItemStack item = new ItemStack(upgrade.getMenuItem());
		ItemMeta meta = item.getItemMeta();
		boolean unlocked = character != null && character.hasProfessionUpgrade(upgrade.getId());
		meta.setLore(buildUpgradeLore(upgrade, character, unlocked));
		item.setItemMeta(meta);
		return item;
	}

	public static List<String> buildUpgradeLore(ProfessionUpgradeDefinition upgrade, RPCharacter character,
			boolean unlocked) {
		List<String> lore = new ArrayList<>(descriptionLore(upgrade.getMenuItem()));
		appendRequirements(lore, upgrade, character);
		appendCost(lore, upgrade);
		if (unlocked) {
			lore.add(" ");
			lore.add(t(RPTexts.GUI_INFO + "UNLOCKED"));
		}
		return lore;
	}

	private static List<String> descriptionLore(ItemStack template) {
		List<String> lore = template.getItemMeta().getLore();
		if (lore == null || lore.isEmpty()) {
			return new ArrayList<>();
		}
		List<String> description = new ArrayList<>();
		for (String line : lore) {
			if (isLegacyDynamicLine(line)) {
				continue;
			}
			description.add(line);
		}
		trimTrailingBlankLines(description);
		return description;
	}

	private static boolean isLegacyDynamicLine(String line) {
		if (line == null) {
			return true;
		}
		String plain = ChatColor.stripColor(line).trim();
		if (plain.isEmpty()) {
			return true;
		}
		return LEGACY_COST_LINE.matcher(plain).matches()
				|| LEGACY_REQUIRES_LINE.matcher(plain).matches();
	}

	private static void trimTrailingBlankLines(List<String> lore) {
		while (!lore.isEmpty() && ChatColor.stripColor(lore.get(lore.size() - 1)).trim().isEmpty()) {
			lore.remove(lore.size() - 1);
		}
	}

	private static void appendRequirements(List<String> lore, ProfessionUpgradeDefinition upgrade,
			RPCharacter character) {
		if (upgrade.getRequirements().isEmpty()) {
			return;
		}
		lore.add(" ");
		for (String requirementId : upgrade.getRequirements()) {
			ProfessionUpgradeDefinition required = ProfessionRegistry.getUpgrade(requirementId);
			String displayName = required != null
					? required.getMenuItem().getItemMeta().getDisplayName()
					: requirementId;
			boolean met = character != null && character.hasProfessionUpgrade(requirementId);
			if (met) {
				lore.add(t(RPTexts.GUI_SUCCESS + "✔ " + displayName));
			} else {
				lore.add(t(RPTexts.ERROR + "Requires " + displayName));
			}
		}
	}

	private static void appendCost(List<String> lore, ProfessionUpgradeDefinition upgrade) {
		lore.add(" ");
		String pointLabel = upgrade.getCost() == 1 ? "Point" : "Points";
		lore.add(t(RPTexts.GUI_WARN + "Cost: " + RPTexts.GUI_SUCCESS + upgrade.getCost() + " " + pointLabel));
	}
}

