package net.tfminecraft.rpcharacters.objects;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.tfminecraft.rpcharacters.objects.trait.Trait;

public final class ProstheticReplacement {

	private final String permanentInjuryId;
	private final Map<String, String> itemByTraitId;

	public ProstheticReplacement(String permanentInjuryId, Map<String, String> itemByTraitId) {
		this.permanentInjuryId = permanentInjuryId;
		this.itemByTraitId = Collections.unmodifiableMap(itemByTraitId);
	}

	public String getPermanentInjuryId() {
		return permanentInjuryId;
	}

	public Map<String, String> getItemByTraitId() {
		return itemByTraitId;
	}

	public boolean containsTrait(String prostheticTraitId) {
		return getItemPath(prostheticTraitId) != null;
	}

	public String getItemPath(String prostheticTraitId) {
		if (prostheticTraitId == null || prostheticTraitId.isBlank()) {
			return null;
		}
		return itemByTraitId.get(prostheticTraitId.toLowerCase(Locale.ROOT));
	}

	public String ownedProstheticId(List<Trait> traits) {
		if (traits == null) {
			return null;
		}
		for (Trait trait : traits) {
			if (trait == null || trait.getId() == null) {
				continue;
			}
			if (containsTrait(trait.getId())) {
				return trait.getId().toLowerCase(Locale.ROOT);
			}
		}
		return null;
	}
}
