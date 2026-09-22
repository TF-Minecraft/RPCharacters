package net.tfminecraft.rpcharacters.kit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class KitDefinition {

	private final String id;
	private final String displayName;
	private final int cooldownHours;
	private final boolean oncePerCharacter;
	private final List<KitItemDefinition> items;

	public KitDefinition(
			String id,
			String displayName,
			int cooldownHours,
			boolean oncePerCharacter,
			List<KitItemDefinition> items
	) {
		this.id = id != null ? id.trim().toLowerCase(Locale.ROOT) : "";
		this.displayName = displayName != null && !displayName.isBlank()
				? displayName.trim()
				: this.id;
		this.cooldownHours = Math.max(0, cooldownHours);
		this.oncePerCharacter = oncePerCharacter;
		this.items = items != null
				? Collections.unmodifiableList(new ArrayList<>(items))
				: List.of();
	}

	public String getId() {
		return id;
	}

	public String getDisplayName() {
		return displayName;
	}

	public int getCooldownHours() {
		return cooldownHours;
	}

	public long getCooldownMs() {
		return cooldownHours * 3600_000L;
	}

	public boolean isOncePerCharacter() {
		return oncePerCharacter;
	}

	public List<KitItemDefinition> getItems() {
		return items;
	}

	public List<String> editableKitKeys() {
		List<String> keys = new ArrayList<>();
		for (KitItemDefinition item : items) {
			if (item == null || !item.isEditable() || item.getPath() == null) {
				continue;
			}
			keys.add(EditableKitPreviewBuilder.kitKeyFromPath(item.getPath()));
		}
		return keys;
	}
}
