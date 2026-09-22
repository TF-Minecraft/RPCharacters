package net.tfminecraft.rpcharacters.wardrobe;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;

/**
 * Per-session wardrobe + original account textures + last applied skin.
 */
public final class WardrobeCache {

	private static final Map<UUID, WardrobeSnapshot> SNAPSHOTS = new ConcurrentHashMap<>();
	private static final Map<UUID, SkinTextures> ACCOUNT_SKINS = new ConcurrentHashMap<>();
	private static final Map<UUID, SkinTextures> LAST_APPLIED = new ConcurrentHashMap<>();

	private WardrobeCache() {}

	public static void put(UUID playerId, WardrobeSnapshot snapshot) {
		if (playerId == null || snapshot == null) {
			return;
		}
		SNAPSHOTS.put(playerId, snapshot);
	}

	public static WardrobeSnapshot get(UUID playerId) {
		if (playerId == null) {
			return null;
		}
		return SNAPSHOTS.get(playerId);
	}

	public static WardrobeSnapshot get(Player player) {
		return player == null ? null : get(player.getUniqueId());
	}

	public static void clear(UUID playerId) {
		if (playerId == null) {
			return;
		}
		SNAPSHOTS.remove(playerId);
		ACCOUNT_SKINS.remove(playerId);
		LAST_APPLIED.remove(playerId);
	}

	public static void clear(Player player) {
		if (player != null) {
			clear(player.getUniqueId());
		}
	}

	/**
	 * Snapshot account skin once before any wardrobe apply in this session.
	 */
	public static void captureAccountSkinIfNeeded(Player player) {
		if (player == null) {
			return;
		}
		UUID id = player.getUniqueId();
		if (ACCOUNT_SKINS.containsKey(id)) {
			return;
		}
		SkinTextures textures = SkinApplyHelper.readTextures(player);
		if (textures != null && textures.isValid()) {
			ACCOUNT_SKINS.put(id, textures);
		}
	}

	public static SkinTextures getAccountSkin(UUID playerId) {
		if (playerId == null) {
			return null;
		}
		return ACCOUNT_SKINS.get(playerId);
	}

	public static SkinTextures getAccountSkin(Player player) {
		return player == null ? null : getAccountSkin(player.getUniqueId());
	}

	public static SkinTextures getLastApplied(UUID playerId) {
		if (playerId == null) {
			return null;
		}
		return LAST_APPLIED.get(playerId);
	}

	public static SkinTextures getLastApplied(Player player) {
		return player == null ? null : getLastApplied(player.getUniqueId());
	}

	public static void setLastApplied(Player player, SkinTextures textures) {
		if (player == null || textures == null || !textures.isValid()) {
			return;
		}
		LAST_APPLIED.put(player.getUniqueId(), textures);
	}
}
