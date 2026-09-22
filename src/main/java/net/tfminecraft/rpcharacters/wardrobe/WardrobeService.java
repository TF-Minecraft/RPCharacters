package net.tfminecraft.rpcharacters.wardrobe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.api.ProvinceSystemClient;
import net.tfminecraft.rpcharacters.identity.MaskService;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;

/**
 * Pull wardrobe from PS, cache, apply on join/switch/mask.
 */
public final class WardrobeService {

	/** Soft refresh interval for online players with an active character (~45s). */
	private static final long SOFT_REFRESH_TICKS = 20L * 45L;

	private static BukkitTask softRefreshTask;

	private WardrobeService() {}

	public static void startSoftRefresh(org.bukkit.plugin.Plugin plugin) {
		stopSoftRefresh();
		softRefreshTask = Bukkit.getScheduler().runTaskTimer(
			plugin,
			WardrobeService::softRefreshOnlinePlayers,
			SOFT_REFRESH_TICKS,
			SOFT_REFRESH_TICKS
		);
	}

	public static void stopSoftRefresh() {
		if (softRefreshTask != null) {
			softRefreshTask.cancel();
			softRefreshTask = null;
		}
	}

	private static void softRefreshOnlinePlayers() {
		for (Player player : Bukkit.getOnlinePlayers()) {
			PlayerData pd = PlayerManager.get(player);
			if (pd == null || !pd.hasActiveCharacter()) {
				continue;
			}
			refreshActiveAsync(player);
		}
	}

	/** Refresh + apply for the player's current active character (if any). */
	public static void refreshActiveAsync(Player player) {
		refreshActiveAsync(player, null);
	}

	/**
	 * Refresh + apply. {@code onMainAfterSuccess} runs on the main thread after
	 * a successful pull and cache put (may be null).
	 */
	public static void refreshActiveAsync(Player player, Runnable onMainAfterSuccess) {
		if (player == null || !player.isOnline()) {
			return;
		}
		PlayerData pd = PlayerManager.get(player);
		if (pd == null || !pd.hasActiveCharacter()) {
			return;
		}
		RPCharacter active = pd.getActiveCharacter();
		if (active == null) {
			return;
		}
		refreshAsync(player, active.getId(), onMainAfterSuccess);
	}

	public static void refreshAsync(Player player, String characterId) {
		refreshAsync(player, characterId, null);
	}

	public static void refreshAsync(
		Player player,
		String characterId,
		Runnable onMainAfterSuccess
	) {
		if (player == null || !player.isOnline() || characterId == null || characterId.isBlank()) {
			return;
		}
		UUID playerId = player.getUniqueId();
		String uuid = playerId.toString();
		String cid = characterId.trim();
		Bukkit.getScheduler().runTaskAsynchronously(RPCharacters.plugin, () -> {
			ProvinceSystemClient.SimpleResult result =
				ProvinceSystemClient.fetchWardrobe(uuid, cid);
			if (!result.ok) {
				RPCharacters.plugin.getLogger().log(
					Level.WARNING,
					"Wardrobe pull failed for " + uuid + "/" + cid + ": " + result.error
				);
				return;
			}
			WardrobeSnapshot snapshot = WardrobeSnapshot.parse(result.body);
			if (snapshot == null) {
				RPCharacters.plugin.getLogger().warning(
					"Wardrobe pull returned unreadable JSON for " + uuid + "/" + cid
				);
				return;
			}
			List<String> pendingSlots = pendingSlotIds(snapshot);
			Bukkit.getScheduler().runTask(RPCharacters.plugin, () -> {
				Player online = Bukkit.getPlayer(playerId);
				if (online == null || !online.isOnline()) {
					return;
				}
				PlayerData pd = PlayerManager.get(online);
				if (pd == null || !pd.hasActiveCharacter()) {
					return;
				}
				if (!cid.equalsIgnoreCase(pd.getActiveCharacter().getId())) {
					return;
				}
				WardrobeCache.captureAccountSkinIfNeeded(online);
				WardrobeCache.put(playerId, snapshot);
				net.tfminecraft.rpcharacters.mail.MailRecipientDirectory.cacheWardrobeSnapshot(
						playerId,
						snapshot);
				applyFor(online);
				if (!pendingSlots.isEmpty()) {
					ackPendingAsync(uuid, cid, pendingSlots);
				}
				if (onMainAfterSuccess != null) {
					onMainAfterSuccess.run();
				}
			});
		});
	}

	private static List<String> pendingSlotIds(WardrobeSnapshot snapshot) {
		List<String> out = new ArrayList<>();
		for (WardrobeSlotData slot : snapshot.getSlots().values()) {
			if (slot != null && slot.isApplyPending() && slot.canApply()) {
				out.add(slot.getSlot());
			}
		}
		return out;
	}

	private static void ackPendingAsync(
		String playerUuid,
		String characterId,
		List<String> slots
	) {
		Bukkit.getScheduler().runTaskAsynchronously(RPCharacters.plugin, () -> {
			ProvinceSystemClient.SimpleResult ack =
				ProvinceSystemClient.ackWardrobe(playerUuid, characterId, slots);
			if (!ack.ok) {
				RPCharacters.plugin.getLogger().log(
					Level.WARNING,
					"Wardrobe ack failed for " + playerUuid + "/" + characterId
						+ ": " + ack.error
				);
			}
		});
	}

	/**
	 * Apply current cache: masked if wearing mask, else active, else account skin.
	 */
	public static void applyFor(Player player) {
		if (player == null || !player.isOnline()) {
			return;
		}
		WardrobeCache.captureAccountSkinIfNeeded(player);
		WardrobeSnapshot snapshot = WardrobeCache.get(player);
		if (snapshot == null) {
			return;
		}

		if (MaskService.isMasked(player)) {
			WardrobeSlotData masked = snapshot.getSlot(WardrobeSnapshot.SLOT_MASKED);
			if (masked != null && masked.canApply()) {
				SkinApplyHelper.apply(
					player,
					masked.getTextureValue(),
					masked.getTextureSignature()
				);
				return;
			}
		}

		String active = snapshot.getActiveSlot();
		if (active != null && !active.isBlank()) {
			WardrobeSlotData slot = snapshot.getSlot(active);
			if (slot != null && slot.canApply()) {
				SkinApplyHelper.apply(
					player,
					slot.getTextureValue(),
					slot.getTextureSignature()
				);
				return;
			}
		}

		SkinTextures account = WardrobeCache.getAccountSkin(player);
		if (account != null && account.isValid()) {
			SkinApplyHelper.apply(player, account);
		}
	}

	/**
	 * Set active swappable slot (local + PS), then apply.
	 * @return null on success, else error message for the player
	 */
	public static void setActiveAndApply(
		Player player,
		String slot,
		ActiveCallback callback
	) {
		if (player == null || !player.isOnline()) {
			if (callback != null) {
				callback.done("Player is offline.");
			}
			return;
		}
		String slotKey = normalizeSwappable(slot);
		if (slotKey == null) {
			if (callback != null) {
				callback.done("Unknown skin slot. Use base, extra_1, or extra_2.");
			}
			return;
		}
		if (WardrobeSnapshot.SLOT_MASKED.equals(slotKey)) {
			if (callback != null) {
				callback.done("Masked skins cannot be equipped manually.");
			}
			return;
		}

		WardrobeSnapshot snapshot = WardrobeCache.get(player);
		if (snapshot == null) {
			if (callback != null) {
				callback.done("Wardrobe is still loading. Try again in a moment.");
			}
			return;
		}
		WardrobeSlotData data = snapshot.getSlot(slotKey);
		if (data == null || !data.isUnlocked() || !data.isFilled() || !data.canApply()) {
			if (callback != null) {
				callback.done("That skin slot is empty or locked. Upload skins on the website.");
			}
			return;
		}

		PlayerData pd = PlayerManager.get(player);
		if (pd == null || !pd.hasActiveCharacter()) {
			if (callback != null) {
				callback.done("You need an active character.");
			}
			return;
		}
		String characterId = pd.getActiveCharacter().getId();
		UUID playerId = player.getUniqueId();
		String uuid = playerId.toString();

		Bukkit.getScheduler().runTaskAsynchronously(RPCharacters.plugin, () -> {
			ProvinceSystemClient.SimpleResult result =
				ProvinceSystemClient.setWardrobeActive(uuid, characterId, slotKey);
			Bukkit.getScheduler().runTask(RPCharacters.plugin, () -> {
				Player online = Bukkit.getPlayer(playerId);
				if (online == null || !online.isOnline()) {
					if (callback != null) {
						callback.done("Player went offline.");
					}
					return;
				}
				if (!result.ok) {
					if (callback != null) {
						callback.done(
							result.error != null ? result.error : "Could not set active skin."
						);
					}
					return;
				}
				WardrobeSnapshot cached = WardrobeCache.get(playerId);
				if (cached != null
					&& characterId.equalsIgnoreCase(cached.getCharacterId())) {
					cached.setActiveSlot(slotKey);
				}
				WardrobeGui.closeIfOpen(online);
				applyFor(online);
				if (callback != null) {
					callback.done(null);
				}
			});
		});
	}

	public static String labelForSlot(WardrobeSnapshot snapshot, String slot) {
		if (snapshot != null && slot != null) {
			WardrobeSlotData data = snapshot.getSlot(slot);
			if (data != null) {
				String custom = data.getDisplayName();
				if (custom != null && !custom.isBlank()) {
					return custom.trim();
				}
			}
		}
		return labelForSlot(slot);
	}

	public static String labelForSlot(String slot) {
		if (slot == null) {
			return "Skin";
		}
		switch (slot.trim().toLowerCase(Locale.ROOT)) {
			case WardrobeSnapshot.SLOT_BASE:
				return "Base";
			case WardrobeSnapshot.SLOT_EXTRA_1:
				return "Skin 2";
			case WardrobeSnapshot.SLOT_EXTRA_2:
				return "Skin 3";
			case WardrobeSnapshot.SLOT_MASKED:
				return "Masked";
			default:
				return slot;
		}
	}

	public static String normalizeSwappable(String raw) {
		if (raw == null) {
			return null;
		}
		String s = raw.trim().toLowerCase(Locale.ROOT);
		switch (s) {
			case "base":
			case "1":
			case "skin1":
			case "skin_1":
				return WardrobeSnapshot.SLOT_BASE;
			case "extra_1":
			case "extra1":
			case "2":
			case "skin2":
			case "skin_2":
				return WardrobeSnapshot.SLOT_EXTRA_1;
			case "extra_2":
			case "extra2":
			case "3":
			case "skin3":
			case "skin_3":
				return WardrobeSnapshot.SLOT_EXTRA_2;
			default:
				return null;
		}
	}

	@FunctionalInterface
	public interface ActiveCallback {
		/** {@code error} null means success. */
		void done(String error);
	}
}
