package net.tfminecraft.rpcharacters.grave;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;

import net.tfminecraft.rpcharacters.RPCharacters;

public final class GraveKeys {

	public static final PersistentDataType<String, String> GRAVE_ID_TYPE = PersistentDataType.STRING;

	private GraveKeys() {}

	public static NamespacedKey graveId() {
		return new NamespacedKey(RPCharacters.plugin, "grave-id");
	}
}
