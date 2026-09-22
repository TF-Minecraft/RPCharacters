package net.tfminecraft.rpcharacters.kit;

public final class KitItemDefinition {

	private final String path;
	private final int amount;
	private final KitEditableSpec editable;
	private final String displayName;

	public KitItemDefinition(String path, int amount, KitEditableSpec editable) {
		this(path, amount, editable, null);
	}

	public KitItemDefinition(String path, int amount, KitEditableSpec editable, String displayName) {
		this.path = path;
		this.amount = Math.max(1, amount);
		this.editable = editable;
		this.displayName = displayName != null && !displayName.isBlank() ? displayName.trim() : null;
	}

	public String getPath() {
		return path;
	}

	public int getAmount() {
		return amount;
	}

	public KitEditableSpec getEditable() {
		return editable;
	}

	public boolean isEditable() {
		return editable != null;
	}

	public String getDisplayName() {
		return displayName;
	}
}
