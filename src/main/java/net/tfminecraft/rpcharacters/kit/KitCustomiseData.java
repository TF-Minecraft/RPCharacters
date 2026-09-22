package net.tfminecraft.rpcharacters.kit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Applied kit customise snapshot persisted on a character.
 */
public final class KitCustomiseData {

	private final String kitKey;
	private final String displayName;
	private final List<String> lore;
	private final String skinSlug;
	private final String path;
	/** ItemsAdder namespace (tfmc_submissions or tfmc_armorshop). */
	private final String iaNamespace;
	private final List<String> nameColours;
	private final List<String> nameStyles;

	public KitCustomiseData(
			String kitKey,
			String displayName,
			List<String> lore,
			String skinSlug,
			String path
	) {
		this(kitKey, displayName, lore, skinSlug, path, null, null, null);
	}

	public KitCustomiseData(
			String kitKey,
			String displayName,
			List<String> lore,
			String skinSlug,
			String path,
			String iaNamespace
	) {
		this(kitKey, displayName, lore, skinSlug, path, iaNamespace, null, null);
	}

	public KitCustomiseData(
			String kitKey,
			String displayName,
			List<String> lore,
			String skinSlug,
			String path,
			String iaNamespace,
			List<String> nameColours
	) {
		this(kitKey, displayName, lore, skinSlug, path, iaNamespace, nameColours, null);
	}

	public KitCustomiseData(
			String kitKey,
			String displayName,
			List<String> lore,
			String skinSlug,
			String path,
			String iaNamespace,
			List<String> nameColours,
			List<String> nameStyles
	) {
		this.kitKey = kitKey != null ? kitKey.trim() : "";
		this.displayName = displayName != null ? displayName : "";
		this.lore = lore != null
				? Collections.unmodifiableList(new ArrayList<>(lore))
				: List.of();
		this.skinSlug = skinSlug != null && !skinSlug.isBlank() ? skinSlug.trim() : null;
		this.path = path != null ? path.trim() : "";
		String ns = iaNamespace != null ? iaNamespace.trim() : "";
		this.iaNamespace = ns.isEmpty() ? "tfmc_submissions" : ns;
		this.nameColours = nameColours != null
				? Collections.unmodifiableList(new ArrayList<>(nameColours))
				: List.of();
		this.nameStyles = nameStyles != null
				? Collections.unmodifiableList(new ArrayList<>(nameStyles))
				: List.of();
	}

	public String getKitKey() {
		return kitKey;
	}

	public String getDisplayName() {
		return displayName;
	}

	public List<String> getLore() {
		return lore;
	}

	public String getSkinSlug() {
		return skinSlug;
	}

	public String getPath() {
		return path;
	}

	public String getIaNamespace() {
		return iaNamespace;
	}

	public List<String> getNameColours() {
		return nameColours;
	}

	public List<String> getNameStyles() {
		return nameStyles;
	}
}
