package net.tfminecraft.rpcharacters.prosthetics;

import net.tfminecraft.rpcharacters.objects.ProstheticReplacement;

public final class ProstheticInstallMatch {

	private final ProstheticReplacement replacement;
	private final String traitId;
	private final String itemPath;

	public ProstheticInstallMatch(ProstheticReplacement replacement, String traitId, String itemPath) {
		this.replacement = replacement;
		this.traitId = traitId;
		this.itemPath = itemPath;
	}

	public ProstheticReplacement getReplacement() {
		return replacement;
	}

	public String getTraitId() {
		return traitId;
	}

	public String getItemPath() {
		return itemPath;
	}
}
