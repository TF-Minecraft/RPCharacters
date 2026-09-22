package net.tfminecraft.rpcharacters.kit;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.subapi.ArmorMerger;
import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.objects.RPCharacter;

/**
 * Build customised kit items before collection: base → optional skin → custom lore.
 */
public final class KitCustomiseApplyService {

	public static final String PDC_KEY = "kit_customise";

	private KitCustomiseApplyService() {}

	public static ItemStack buildStack(KitCustomiseData data) {
		if (data == null) {
			return null;
		}
		String path = data.getPath();
		if (path == null || path.isBlank()) {
			path = "m.tools.IRON_HUNTING_KNIFE";
		}
		ItemStack stack;
		try {
			stack = TLibs.getItemAPI().getCreator().getItemFromPath(path);
		} catch (Exception e) {
			RPCharacters.plugin.getLogger().warning(
					"[kit-customise] path '" + path + "' threw: " + e.getMessage()
			);
			return null;
		}
		if (stack == null || stack.getType().isAir()) {
			return null;
		}
		stack = stack.clone();

		// Capture MMOItems / base lore before skin merge (setType can wipe meta lore).
		List<String> baseLore = copyLore(stack);

		String slug = data.getSkinSlug();
		String colouredName = formatDisplayName(data);
		if (slug != null && !slug.isBlank()) {
			String ns = data.getIaNamespace();
			if (ns == null || ns.isBlank()) {
				ns = "tfmc_submissions";
			}
			String iaPath = "ia." + ns + ":" + slug;
			try {
				ArmorMerger merger = TLibs.getItemAPI().getArmorMerger();
				// Name/lore applied after merge so vanilla + IA books keep customise text.
				stack = merger.merge(stack, Optional.empty(), iaPath);
			} catch (Exception e) {
				RPCharacters.plugin.getLogger().warning(
						"[kit-customise] skin merge failed for " + slug + ": " + e.getMessage()
				);
			}
		}

		ItemMeta meta = stack.getItemMeta();
		if (meta != null) {
			if (colouredName != null && !colouredName.isBlank()) {
				meta.setDisplayName(colouredName);
			}
			List<String> custom = data.getLore();
			List<String> customFormatted = new ArrayList<>();
			if (custom != null) {
				for (String line : custom) {
					if (line == null || line.isBlank()) {
						continue;
					}
					customFormatted.add(formatLoreLine(line));
				}
			}
			List<String> merged = mergeLore(baseLore, customFormatted);
			if (!merged.isEmpty()) {
				meta.setLore(merged);
				RPCharacters.plugin.getLogger().info(
						"[kit-customise] applied lore kit_key=" + data.getKitKey()
								+ " base_lines=" + baseLore.size()
								+ " custom_lines=" + customFormatted.size()
								+ " total=" + merged.size()
								+ " material=" + stack.getType()
				);
			} else {
				RPCharacters.plugin.getLogger().info(
						"[kit-customise] no lore kit_key=" + data.getKitKey()
				);
			}
			if (RPCharacters.plugin != null) {
				NamespacedKey key = new NamespacedKey(RPCharacters.plugin, PDC_KEY);
				meta.getPersistentDataContainer().set(
						key, PersistentDataType.STRING, data.getKitKey()
				);
			}
			if (!stack.setItemMeta(meta)) {
				RPCharacters.plugin.getLogger().warning(
						"[kit-customise] setItemMeta failed kit_key=" + data.getKitKey()
								+ " material=" + stack.getType()
				);
			}
		} else {
			RPCharacters.plugin.getLogger().warning(
					"[kit-customise] null ItemMeta after merge kit_key=" + data.getKitKey()
							+ " material=" + stack.getType()
			);
		}
		return stack;
	}

	/** Copy existing lore lines from a stack (empty if none). */
	static List<String> copyLore(ItemStack stack) {
		if (stack == null) {
			return List.of();
		}
		ItemMeta meta = stack.getItemMeta();
		if (meta == null || !meta.hasLore() || meta.getLore() == null) {
			return List.of();
		}
		return new ArrayList<>(meta.getLore());
	}

	/**
	 * Base (e.g. MMOItems) lore + optional {@code " "} spacer + custom lines.
	 * Spacer only when both sides are non-empty.
	 */
	static List<String> mergeLore(List<String> baseLore, List<String> customLore) {
		List<String> base = baseLore != null ? baseLore : List.of();
		List<String> custom = customLore != null ? customLore : List.of();
		if (base.isEmpty()) {
			return new ArrayList<>(custom);
		}
		if (custom.isEmpty()) {
			return new ArrayList<>(base);
		}
		List<String> out = new ArrayList<>(base.size() + 1 + custom.size());
		out.addAll(base);
		out.add(" ");
		out.addAll(custom);
		return out;
	}

	/**
	 * True when this customise needs no skin, or the IA custom item for its slug exists.
	 */
	public static boolean isSkinPresent(KitCustomiseData data) {
		if (data == null) {
			return true;
		}
		String slug = data.getSkinSlug();
		if (slug == null || slug.isBlank()) {
			return true;
		}
		String ns = data.getIaNamespace();
		if (ns == null || ns.isBlank()) {
			ns = "tfmc_submissions";
		}
		try {
			dev.lone.itemsadder.api.CustomStack custom =
					dev.lone.itemsadder.api.CustomStack.getInstance(ns + ":" + slug);
			if (custom == null) {
				return false;
			}
			ItemStack item = custom.getItemStack();
			return item != null && item.getType() != Material.AIR;
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * True when every customise in {@code keys} that belongs to this character has its
	 * required skin available on ItemsAdder (name/lore-only rows pass).
	 */
	public static boolean requiredSkinsReady(
			RPCharacter character,
			List<String> editableKeys
	) {
		if (character == null || editableKeys == null || editableKeys.isEmpty()) {
			return true;
		}
		for (KitCustomiseData data : character.getKitCustomisations().values()) {
			if (data == null || data.getKitKey().isBlank()) {
				continue;
			}
			if (!editableKeys.contains(data.getKitKey())) {
				continue;
			}
			if (!isSkinPresent(data)) {
				return false;
			}
		}
		return true;
	}

	static String formatDisplayName(KitCustomiseData data) {
		if (data == null || data.getDisplayName() == null || data.getDisplayName().isBlank()) {
			return null;
		}
		String plain = data.getDisplayName().trim();
		List<String> colours = data.getNameColours();
		List<String> styles = data.getNameStyles();
		boolean hasColours = colours != null && !colours.isEmpty();
		boolean hasStyles = styles != null && !styles.isEmpty();
		try {
			if (!hasColours && !hasStyles) {
				return StringFormatter.formatHex(plain);
			}
			return StringFormatter.formatDisplayName(
					plain,
					hasColours ? new ArrayList<>(colours) : new ArrayList<>(),
					hasStyles ? new ArrayList<>(styles) : new ArrayList<>()
			);
		} catch (Exception e) {
			try {
				return StringFormatter.formatHex(plain);
			} catch (Exception e2) {
				return plain;
			}
		}
	}

	/** Lore lines: always lead with §7 unless a real colour is already first, then formatHex. */
	static String formatLoreLine(String line) {
		if (line == null) {
			return "";
		}
		String withGray = ensureLoreGray(line);
		try {
			return StringFormatter.formatHex(withGray);
		} catch (Exception e) {
			return withGray;
		}
	}

	/**
	 * Prepend §7 when the line has no leading colour token (0-9a-f / #RRGGBB).
	 * Style codes alone (§l / &l) still get §7 so bold is gray, not purple italic.
	 */
	static String ensureLoreGray(String line) {
		if (line == null) {
			return "";
		}
		String s = line.strip();
		if (s.isEmpty()) {
			return s;
		}
		if (hasLeadingLoreColour(s)) {
			return s;
		}
		return "\u00A77" + s;
	}

	static boolean hasLeadingLoreColour(String line) {
		if (line == null || line.isEmpty()) {
			return false;
		}
		char first = line.charAt(0);
		if ((first == '\u00A7' || first == '&') && line.length() >= 2) {
			char code = Character.toLowerCase(line.charAt(1));
			return "0123456789abcdef".indexOf(code) >= 0;
		}
		if (first == '#' && line.length() >= 7) {
			for (int i = 1; i <= 6; i++) {
				char c = line.charAt(i);
				if (Character.digit(c, 16) < 0) {
					return false;
				}
			}
			return true;
		}
		return false;
	}

}
