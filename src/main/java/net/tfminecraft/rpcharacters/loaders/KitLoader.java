package net.tfminecraft.rpcharacters.loaders;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.kit.KitDefinition;
import net.tfminecraft.rpcharacters.kit.KitEditableSpec;
import net.tfminecraft.rpcharacters.kit.KitItemDefinition;

public final class KitLoader implements LoaderInterface {

	public static final String DEFAULT_KIT_ID = "starter";

	private static Map<String, KitDefinition> kits = Collections.emptyMap();

	@Override
	public void load(File configFile) {
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(configFile);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
			RPCharacters.plugin.getLogger().warning(
					"Failed to load " + configFile.getName() + " — kits disabled until fixed."
			);
			kits = Collections.emptyMap();
			return;
		}

		Map<String, KitDefinition> loaded = new LinkedHashMap<>();
		ConfigurationSection kitsSection = config.getConfigurationSection("kits");
		if (kitsSection != null) {
			for (String rawId : kitsSection.getKeys(false)) {
				ConfigurationSection kitSec = kitsSection.getConfigurationSection(rawId);
				if (kitSec == null) {
					continue;
				}
				KitDefinition def = parseKit(rawId, kitSec);
				if (def != null && !def.getId().isEmpty()) {
					loaded.put(def.getId(), def);
				}
			}
		} else if (config.contains("items")) {
			// Legacy flat kit.yml → starter
			KitDefinition starter = parseLegacyFlat(config);
			if (starter != null) {
				loaded.put(starter.getId(), starter);
				RPCharacters.plugin.getLogger().info(
						"Loaded legacy kit.yml as kit id '" + DEFAULT_KIT_ID + "'."
				);
			}
		}

		kits = Collections.unmodifiableMap(loaded);
		RPCharacters.plugin.getLogger().info(
				"Loaded " + kits.size() + " kit(s): " + String.join(", ", kits.keySet())
		);
	}

	/**
	 * Prefer kits.yml; if missing, fall back to legacy kit.yml in the same folder.
	 */
	public void loadPreferred(File dataFolder) {
		File kitsFile = new File(dataFolder, "kits.yml");
		File legacy = new File(dataFolder, "kit.yml");
		if (kitsFile.exists()) {
			load(kitsFile);
			return;
		}
		if (legacy.exists()) {
			RPCharacters.plugin.getLogger().warning(
					"kits.yml missing — loading legacy kit.yml as starter."
			);
			load(legacy);
			return;
		}
		RPCharacters.plugin.getLogger().warning(
				"No kits.yml or kit.yml found — kits disabled until configured."
		);
		kits = Collections.emptyMap();
	}

	private static KitDefinition parseKit(String rawId, ConfigurationSection kitSec) {
		String id = rawId != null ? rawId.trim().toLowerCase(Locale.ROOT) : "";
		if (id.isEmpty()) {
			return null;
		}
		String display = kitSec.getString("display-name", id);
		int cooldown = Math.max(0, kitSec.getInt("cooldown-hours", 48));
		boolean once = kitSec.getBoolean("once-per-character", true);
		List<KitItemDefinition> items = parseItems(kitSec.getMapList("items"), id);
		if (items == null) {
			return null;
		}
		return new KitDefinition(id, display, cooldown, once, items);
	}

	private static KitDefinition parseLegacyFlat(FileConfiguration config) {
		int cooldown = Math.max(0, config.getInt("cooldown-hours", 48));
		List<KitItemDefinition> items = parseItems(config.getMapList("items"), DEFAULT_KIT_ID);
		if (items == null) {
			return null;
		}
		return new KitDefinition(DEFAULT_KIT_ID, "Starter", cooldown, true, items);
	}

	/**
	 * @return item list, or {@code null} if the kit must be rejected (fail-loud)
	 */
	private static List<KitItemDefinition> parseItems(List<Map<?, ?>> rawItems, String kitId) {
		List<KitItemDefinition> loaded = new ArrayList<>();
		if (rawItems == null) {
			return loaded;
		}
		for (Map<?, ?> map : rawItems) {
			Object pathObj = map.get("path");
			String path = pathObj != null ? pathObj.toString().trim() : "";
			if (path.isEmpty()) {
				RPCharacters.plugin.getLogger().warning(
						"Kit '" + kitId + "' item missing path — skipped."
				);
				continue;
			}
			int amount = 1;
			Object amountObj = map.get("amount");
			if (amountObj instanceof Number) {
				amount = ((Number) amountObj).intValue();
			} else if (amountObj != null) {
				try {
					amount = Integer.parseInt(amountObj.toString());
				} catch (NumberFormatException ignored) {
					amount = 1;
				}
			}
			KitEditableSpec editable = null;
			Object editableObj = map.get("editable");
			if (editableObj instanceof Map<?, ?> editableMap) {
				editable = parseEditable(editableMap, kitId, path);
				if (editable == null) {
					return null;
				}
			}
			loaded.add(new KitItemDefinition(path, amount, editable, stringOf(map.get("display-name"))));
		}
		return loaded;
	}

	/**
	 * @return spec, or {@code null} if editable is invalid (missing 2d-template)
	 */
	private static KitEditableSpec parseEditable(
			Map<?, ?> editableMap,
			String kitId,
			String path
	) {
		String twoD = stringOf(editableMap.get("2d-template"));
		if (twoD.isEmpty()) {
			RPCharacters.plugin.getLogger().severe(
					"Kit '" + kitId + "' editable item '" + path
							+ "' missing required 2d-template — kit not loaded."
			);
			return null;
		}
		String threeD = stringOf(editableMap.get("3d-template"));
		return new KitEditableSpec(
				stringOf(editableMap.get("skin-png")),
				stringOf(editableMap.get("skin-png-signed")),
				stringOf(editableMap.get("base-set")),
				twoD,
				threeD.isEmpty() ? null : threeD
		);
	}

	private static String stringOf(Object value) {
		return value == null ? "" : value.toString().trim();
	}

	public static Map<String, KitDefinition> getKits() {
		return kits;
	}

	public static Set<String> kitIds() {
		return kits.keySet();
	}

	public static KitDefinition getKit(String kitId) {
		if (kitId == null || kitId.isBlank()) {
			return null;
		}
		return kits.get(kitId.trim().toLowerCase(Locale.ROOT));
	}

	/** Legacy: items for default starter kit. */
	public static List<KitItemDefinition> getItems() {
		KitDefinition starter = getKit(DEFAULT_KIT_ID);
		return starter != null ? starter.getItems() : List.of();
	}

	/** Legacy: starter cooldown hours. */
	public static int getCooldownHours() {
		KitDefinition starter = getKit(DEFAULT_KIT_ID);
		return starter != null ? starter.getCooldownHours() : 48;
	}

	public static long getCooldownMs() {
		return getCooldownHours() * 3600_000L;
	}

	public static long getCooldownMs(String kitId) {
		KitDefinition kit = getKit(kitId);
		return kit != null ? kit.getCooldownMs() : 0L;
	}
}
