package net.tfminecraft.rpcharacters.wardrobe;

/**
 * One wardrobe slot from PS plugin wardrobe pull.
 */
public final class WardrobeSlotData {

	private final String slot;
	private final boolean unlocked;
	private final boolean filled;
	private final boolean signed;
	private final boolean applyPending;
	private final String model;
	private final String displayName;
	private final String textureValue;
	private final String textureSignature;
	private final String description;

	public WardrobeSlotData(
		String slot,
		boolean unlocked,
		boolean filled,
		boolean signed,
		boolean applyPending,
		String model,
		String displayName,
		String textureValue,
		String textureSignature
	) {
		this(slot, unlocked, filled, signed, applyPending, model, displayName,
			textureValue, textureSignature, null);
	}

	public WardrobeSlotData(
		String slot,
		boolean unlocked,
		boolean filled,
		boolean signed,
		boolean applyPending,
		String model,
		String displayName,
		String textureValue,
		String textureSignature,
		String description
	) {
		this.slot = slot;
		this.unlocked = unlocked;
		this.filled = filled;
		this.signed = signed;
		this.applyPending = applyPending;
		this.model = model;
		this.displayName = displayName;
		this.textureValue = textureValue;
		this.textureSignature = textureSignature;
		this.description = description == null || description.isBlank() ? null : description.trim();
	}

	public String getSlot() {
		return slot;
	}

	public boolean isUnlocked() {
		return unlocked;
	}

	public boolean isFilled() {
		return filled;
	}

	public boolean isSigned() {
		return signed;
	}

	public boolean isApplyPending() {
		return applyPending;
	}

	public String getModel() {
		return model;
	}

	public String getDisplayName() {
		return displayName;
	}

	public String getTextureValue() {
		return textureValue;
	}

	public String getTextureSignature() {
		return textureSignature;
	}

	public WardrobeSlotData withDescription(String newDescription) {
		return new WardrobeSlotData(slot, unlocked, filled, signed, applyPending, model, displayName,
			textureValue, textureSignature, newDescription);
	}

	/** What others read while this skin is worn, or null to use the character description. */
	public String getDescription() {
		return description;
	}

	public boolean canApply() {
		return signed
			&& textureValue != null
			&& !textureValue.isEmpty()
			&& textureSignature != null
			&& !textureSignature.isEmpty();
	}
}
