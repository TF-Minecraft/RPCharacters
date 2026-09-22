package net.tfminecraft.rpcharacters.wardrobe;

/**
 * Mojang texture property pair.
 */
public final class SkinTextures {

	private final String value;
	private final String signature;

	public SkinTextures(String value, String signature) {
		this.value = value;
		this.signature = signature;
	}

	public String getValue() {
		return value;
	}

	public String getSignature() {
		return signature;
	}

	public boolean isValid() {
		return value != null
			&& !value.isEmpty()
			&& signature != null
			&& !signature.isEmpty();
	}

	/** Same Mojang texture pair (skips redundant Paper profile applies). */
	public boolean sameTextures(SkinTextures other) {
		if (other == null) {
			return false;
		}
		return sameTextures(other.value, other.signature);
	}

	public boolean sameTextures(String otherValue, String otherSignature) {
		if (!isValid()
			|| otherValue == null
			|| otherValue.isEmpty()
			|| otherSignature == null
			|| otherSignature.isEmpty()) {
			return false;
		}
		return value.equals(otherValue) && signature.equals(otherSignature);
	}
}
