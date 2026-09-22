package net.tfminecraft.rpcharacters.kit;

/**
 * Editable fields on a kit line. Custom skins are player submissions
 * (Discord review → ps_items), not staff categories.
 */
public final class KitEditableSpec {

	private final String skinPng;
	/** Optional signed-cover default for book items. Empty when unset. */
	private final String skinPngSigned;
	private final String baseSet;
	private final String twoDTemplate;
	/** Null when 3D is disallowed. */
	private final String threeDTemplate;

	public KitEditableSpec(
			String skinPng,
			String baseSet,
			String twoDTemplate,
			String threeDTemplate
	) {
		this(skinPng, "", baseSet, twoDTemplate, threeDTemplate);
	}

	public KitEditableSpec(
			String skinPng,
			String skinPngSigned,
			String baseSet,
			String twoDTemplate,
			String threeDTemplate
	) {
		this.skinPng = skinPng != null ? skinPng.trim() : "";
		this.skinPngSigned = skinPngSigned != null ? skinPngSigned.trim() : "";
		this.baseSet = baseSet != null ? baseSet.trim() : "";
		this.twoDTemplate = twoDTemplate != null ? twoDTemplate.trim() : "";
		String t3 = threeDTemplate != null ? threeDTemplate.trim() : "";
		this.threeDTemplate = t3.isEmpty() ? null : t3;
	}

	public String getSkinPng() {
		return skinPng;
	}

	public String getSkinPngSigned() {
		return skinPngSigned;
	}

	public String getBaseSet() {
		return baseSet;
	}

	public String get2dTemplate() {
		return twoDTemplate;
	}

	/** Null when the kit line has no 3d-template. */
	public String get3dTemplate() {
		return threeDTemplate;
	}
}
