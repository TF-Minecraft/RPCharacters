package net.tfminecraft.rpcharacters.professions;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.rpcharacters.utils.RPTexts;

public final class ProfessionItemFactory {
	private ProfessionItemFactory() {}

	public static ProfessionItemSpec snapshot(Object node) {
		if (node == null) {
			return ProfessionItemSpec.empty();
		}
		if (node instanceof String path) {
			return new ProfessionItemSpec(path, null, null, null, List.of(), false, List.of());
		}
		if (node instanceof ConfigurationSection section) {
			String path = section.getString("item");
			if (path == null || path.isBlank()) {
				path = section.getString("path");
			}
			String material = section.contains("material") ? section.getString("material") : null;
			String name = section.contains("name") ? section.getString("name") : null;
			Integer modelData = section.contains("model_data") ? section.getInt("model_data") : null;
			List<String> enchants = section.contains("enchants")
					? new ArrayList<>(section.getStringList("enchants"))
					: List.of();
			List<String> lore = section.contains("lore")
					? new ArrayList<>(section.getStringList("lore"))
					: List.of();
			return new ProfessionItemSpec(path, material, name, modelData, enchants,
					section.getBoolean("hide_enchants", false), lore);
		}
		return ProfessionItemSpec.empty();
	}

	public static ItemStack fromNode(Object node) {
		return build(snapshot(node));
	}

	public static ItemStack build(ProfessionItemSpec spec) {
		if (spec == null) {
			return new ItemStack(Material.BARRIER);
		}
		ItemStack item = resolveBase(spec);
		ItemMeta meta = item.getItemMeta();
		if (meta == null) {
			return item;
		}
		if (spec.hasName()) {
			meta.setDisplayName(formatItemText(spec.getName()));
		}
		if (spec.hasModelData()) {
			meta.setCustomModelData(spec.getModelData());
		}
		if (spec.hasEnchants()) {
			for (String enchantSpec : spec.getEnchants()) {
				String[] parts = enchantSpec.split("\\.");
				if (parts.length < 2) {
					continue;
				}
				Enchantment enchant = Enchantment.getByKey(NamespacedKey.minecraft(parts[0]));
				if (enchant == null) {
					continue;
				}
				meta.addEnchant(enchant, Integer.parseInt(parts[1]), true);
			}
		}
		if (spec.isHideEnchants()) {
			meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
		}
		List<String> lore = new ArrayList<>();
		for (String line : spec.getLore()) {
			lore.add(formatItemText(line));
		}
		if (!lore.isEmpty()) {
			meta.setLore(lore);
		}
		item.setItemMeta(meta);
		return item;
	}

	private static ItemStack resolveBase(ProfessionItemSpec spec) {
		Material fallbackMaterial = parseMaterial(spec.getMaterial());
		String path = spec.getPath();
		if (path != null && !path.isBlank()) {
			ItemStack fromPath = fromPath(path);
			if (fromPath != null) {
				return fromPath;
			}
			if (fallbackMaterial != null) {
				return new ItemStack(fallbackMaterial, 1);
			}
			return new ItemStack(Material.BARRIER);
		}
		if (fallbackMaterial != null) {
			return new ItemStack(fallbackMaterial, 1);
		}
		return new ItemStack(Material.BARRIER);
	}

	private static Material parseMaterial(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return Material.valueOf(raw.toUpperCase());
		} catch (IllegalArgumentException ignored) {
			return Material.BARRIER;
		}
	}

	private static ItemStack fromPath(String ref) {
		String normalized = normalizePath(ref);
		if (normalized.isBlank()) {
			return null;
		}
		try {
			ItemStack built = TLibs.getItemAPI().getCreator().getItemFromPath(normalized);
			if (built == null || built.getType() == Material.AIR) {
				return null;
			}
			return built.clone();
		} catch (Exception ex) {
			return null;
		}
	}

	private static String normalizePath(String ref) {
		if (ref == null || ref.isBlank()) {
			return "";
		}
		String trimmed = ref.trim();
		if (trimmed.toLowerCase().startsWith("vanilla.")) {
			return "v." + trimmed.substring("vanilla.".length()).toLowerCase();
		}
		return trimmed;
	}

	private static String formatItemText(String raw) {
		if (raw == null || raw.isEmpty()) {
			return raw;
		}
		return RPTexts.formatGui(raw);
	}
}
