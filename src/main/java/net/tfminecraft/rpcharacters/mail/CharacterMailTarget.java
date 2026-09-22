package net.tfminecraft.rpcharacters.mail;

import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.rpcharacters.api.CharacterSkull;

/**
 * Mail picker / flight-distance snapshot. Location is the live player position when
 * that character is active and the owner is online; otherwise the stored last location
 * (null if the world is not loaded).
 */
public final class CharacterMailTarget {

	private final UUID ownerUuid;
	private final String characterId;
	private final String displayTab;
	private final String displayPlain;
	private final String worldName;
	private final Location location;
	private final String baseTextureValue;
	private final String baseTextureSignature;

	public CharacterMailTarget(
			UUID ownerUuid,
			String characterId,
			String displayTab,
			String displayPlain,
			String worldName,
			Location location,
			String baseTextureValue,
			String baseTextureSignature) {
		this.ownerUuid = ownerUuid;
		this.characterId = characterId;
		this.displayTab = displayTab != null ? displayTab : "";
		this.displayPlain = displayPlain != null ? displayPlain : "";
		this.worldName = worldName;
		this.location = location;
		this.baseTextureValue = baseTextureValue;
		this.baseTextureSignature = baseTextureSignature;
	}

	public UUID getOwnerUuid() {
		return ownerUuid;
	}

	public String getCharacterId() {
		return characterId;
	}

	public String getDisplayTab() {
		return displayTab;
	}

	public String getDisplayPlain() {
		return displayPlain;
	}

	public String getWorldName() {
		return worldName;
	}

	public Location getLocation() {
		return location;
	}

	public String getBaseTextureValue() {
		return baseTextureValue;
	}

	public String getBaseTextureSignature() {
		return baseTextureSignature;
	}

	/** Player head using this target's base wardrobe textures. */
	public ItemStack getSkull() {
		return CharacterSkull.fromTextures(baseTextureValue, baseTextureSignature);
	}
}
