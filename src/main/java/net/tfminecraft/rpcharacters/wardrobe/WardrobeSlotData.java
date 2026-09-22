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
		this.slot = slot;
		this.unlocked = unlocked;
		this.filled = filled;
		this.signed = signed;
		this.applyPending = applyPending;
		this.model = model;
		this.displayName = displayName;
		this.textureValue = textureValue;
		this.textureSignature = textureSignature;
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

	public boolean canApply() {
		return signed
			&& textureValue != null
			&& !textureValue.isEmpty()
			&& textureSignature != null
			&& !textureSignature.isEmpty();
	}
}
