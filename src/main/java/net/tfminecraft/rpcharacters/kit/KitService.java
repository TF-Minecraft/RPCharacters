package net.tfminecraft.rpcharacters.kit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.json.simple.JSONObject;

import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.loaders.KitLoader;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.rpcharacters.api.ProvinceSystemClient;
import net.tfminecraft.rpcharacters.ingest.KitCustomiseIngestService;

public final class KitService {

	private static final Set<UUID> claimsInFlight = ConcurrentHashMap.newKeySet();

	private KitService() {
	}

	public static long cooldownRemainingMs(PlayerData pd, String kitId) {
		KitDefinition kit = KitLoader.getKit(kitId);
		if (pd == null || kit == null) {
			return 0L;
		}
		Long last = pd.getLastKitClaimAtMs(kit.getId());
		if (last == null || last <= 0L) {
			return 0L;
		}
		long elapsed = System.currentTimeMillis() - last;
		long remaining = kit.getCooldownMs() - elapsed;
		return Math.max(0L, remaining);
	}

	public static boolean isCooldownActive(PlayerData pd, String kitId) {
		return cooldownRemainingMs(pd, kitId) > 0L;
	}

	public static int cooldownRemainingHoursCeil(PlayerData pd, String kitId) {
		long remaining = cooldownRemainingMs(pd, kitId);
		if (remaining <= 0L) {
			return 0;
		}
		return (int) Math.max(1L, (remaining + 3_599_999L) / 3_600_000L);
	}

	/** Legacy helpers for starter kit. */
	public static long cooldownRemainingMs(PlayerData pd) {
		return cooldownRemainingMs(pd, KitLoader.DEFAULT_KIT_ID);
	}

	public static boolean isCooldownActive(PlayerData pd) {
		return isCooldownActive(pd, KitLoader.DEFAULT_KIT_ID);
	}

	public static int cooldownRemainingHoursCeil(PlayerData pd) {
		return cooldownRemainingHoursCeil(pd, KitLoader.DEFAULT_KIT_ID);
	}

	/**
	 * Stamp eligibility for every configured kit. Claim via
	 * {@code /rpcharacter kit <id>} only (no auto-grant).
	 */
	public static void onCharacterCreated(Player player, PlayerData pd, RPCharacter character) {
		if (pd == null || character == null) {
			return;
		}
		for (String kitId : KitLoader.kitIds()) {
			character.setKitStatus(kitId, KitStatus.ELIGIBLE);
		}
	}

	public static void tryClaim(Player player, String kitIdRaw) {
		if (player == null || !player.isOnline()) {
			return;
		}
		String kitId = kitIdRaw != null ? kitIdRaw.trim().toLowerCase(Locale.ROOT) : "";
		KitDefinition kit = KitLoader.getKit(kitId);
		if (kit == null || kitId.isEmpty()) {
			RPTexts.send(player, RPTexts.ERROR + "Unknown kit. Usage: /rpcharacter kit <id>");
			return;
		}
		if (!PlayerManager.exists(player)) {
			RPTexts.send(player, RPTexts.ERROR + "No character data loaded.");
			return;
		}
		PlayerData pd = PlayerManager.get(player);
		if (pd == null || !pd.hasActiveCharacter()) {
			RPTexts.send(player, RPTexts.ERROR + "You need an active character to claim a kit.");
			return;
		}
		RPCharacter character = pd.getActiveCharacter();
		if (character == null) {
			RPTexts.send(player, RPTexts.ERROR + "You need an active character to claim a kit.");
			return;
		}

		if (!prepareLocalClaim(player, pd, character, kit, kitId)) {
			return;
		}

		UUID playerId = player.getUniqueId();
		if (!claimsInFlight.add(playerId)) {
			RPTexts.send(player, RPTexts.WARN + "Your kit claim is already in progress.");
			return;
		}

		String characterId = character.getId();
		RPTexts.send(player, RPTexts.MUTED + "Checking your kit...");
		Bukkit.getScheduler().runTaskAsynchronously(RPCharacters.plugin, () -> {
			boolean scheduledMain = false;
			try {
				ProvinceSystemClient.SimpleResult claimStatus =
						ProvinceSystemClient.fetchLoreItemClaimStatus(
								playerId.toString(), characterId, kitId
						);
				boolean pendingSkin = claimStatus.ok
						&& ProvinceSystemClient.claimStatusPendingSkin(claimStatus.body);
				boolean pendingPack = claimStatus.ok
						&& ProvinceSystemClient.claimStatusPendingPack(claimStatus.body);
				if (claimStatus.ok) {
					String body = claimStatus.body != null ? claimStatus.body : "";
					String snippet = body.length() > 400 ? body.substring(0, 400) + "..." : body;
					RPCharacters.plugin.getLogger().info(
							"[kit-claim] claim-status ok pending_skin=" + pendingSkin
									+ " pending_pack=" + pendingPack
									+ " body=" + snippet
					);
				} else {
					RPCharacters.plugin.getLogger().warning(
							"[kit-claim] claim-status failed for " + playerId
									+ " kit=" + kitId + ": " + claimStatus.error
					);
				}

				List<JSONObject> pendingItems = List.of();
				if (!Cache.devCharacters) {
					ProvinceSystemClient.SimpleResult pending =
							ProvinceSystemClient.fetchPendingLoreItems();
					if (!pending.ok) {
						RPCharacters.plugin.getLogger().warning(
								"[kit-customise] claim-pull failed: " + pending.error
						);
					} else {
						String body = pending.body != null ? pending.body : "";
						if (body.isBlank()) {
							RPCharacters.plugin.getLogger().info(
									"[kit-customise] claim-pull empty body char=" + characterId
											+ " uuid=" + playerId
							);
						} else {
							RPCharacters.plugin.getLogger().info(
									"[kit-customise] claim-pull body=" + body
							);
						}
						pendingItems = ProvinceSystemClient.parsePendingLoreItems(pending.body);
					}
				}

				List<JSONObject> itemsForMain = pendingItems;
				boolean skin = pendingSkin;
				boolean pack = pendingPack;
				Bukkit.getScheduler().runTask(RPCharacters.plugin, () -> {
					try {
						finishClaimAfterFetch(
								playerId, kitId, characterId, skin, pack, itemsForMain
						);
					} finally {
						claimsInFlight.remove(playerId);
					}
				});
				scheduledMain = true;
			} catch (Exception e) {
				RPCharacters.plugin.getLogger().warning(
						"[kit-claim] async failed for " + playerId + " kit=" + kitId
								+ ": " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
				);
				Bukkit.getScheduler().runTask(RPCharacters.plugin, () -> {
					Player online = Bukkit.getPlayer(playerId);
					if (online != null && online.isOnline()) {
						RPTexts.send(online, RPTexts.ERROR + "Could not check your kit. Try again.");
					}
				});
			} finally {
				if (!scheduledMain) {
					claimsInFlight.remove(playerId);
				}
			}
		});
	}

	/**
	 * Local eligibility before HTTP. Stamps missing kit status as eligible.
	 *
	 * @return false if the player was already told they cannot claim
	 */
	private static boolean prepareLocalClaim(
			Player player,
			PlayerData pd,
			RPCharacter character,
			KitDefinition kit,
			String kitId
	) {
		KitStatus status = character.getKitStatus(kitId);
		RPCharacters.plugin.getLogger().info(
				"[kit-claim] start player=" + player.getName()
						+ " char=" + character.getId()
						+ " kit=" + kitId
						+ " status=" + (status != null ? status.toStorage() : "null")
		);
		// Missing status = pre-kit / never stamped. Website treats that as eligible
		// for customise; claim must match (CE /tfmc starter is retired).
		if (status == null) {
			status = KitStatus.ELIGIBLE;
			character.setKitStatus(kitId, status);
			RPCharacters.plugin.getLogger().info(
					"[kit-claim] stamped missing status as eligible for "
							+ player.getName() + " char=" + character.getId()
							+ " kit=" + kitId
			);
		}
		if (kit.isOncePerCharacter()) {
			if (status == KitStatus.GRANTED) {
				RPTexts.send(player, RPTexts.ERROR + "This character has already claimed that kit.");
				return false;
			}
			if (status != KitStatus.ELIGIBLE && status != KitStatus.INELIGIBLE) {
				RPTexts.send(player, RPTexts.ERROR + "This character cannot claim that kit.");
				return false;
			}
		}

		if (isCooldownActive(pd, kitId)) {
			int hours = cooldownRemainingHoursCeil(pd, kitId);
			RPTexts.send(player, RPTexts.WARN
					+ "You must wait " + hours + " hours before claiming that kit again.");
			return false;
		}
		return true;
	}

	private static void finishClaimAfterFetch(
			UUID playerId,
			String kitId,
			String expectedCharacterId,
			boolean pendingSkin,
			boolean pendingPack,
			List<JSONObject> pendingItems
	) {
		Player player = Bukkit.getPlayer(playerId);
		if (player == null || !player.isOnline()) {
			return;
		}
		if (!PlayerManager.exists(player)) {
			RPTexts.send(player, RPTexts.ERROR + "No character data loaded.");
			return;
		}
		PlayerData pd = PlayerManager.get(player);
		if (pd == null || !pd.hasActiveCharacter()) {
			RPTexts.send(player, RPTexts.ERROR + "You need an active character to claim a kit.");
			return;
		}
		RPCharacter character = pd.getActiveCharacter();
		if (character == null
				|| expectedCharacterId == null
				|| !expectedCharacterId.equalsIgnoreCase(character.getId())) {
			RPTexts.send(player, RPTexts.ERROR + "Your active character changed. Claim the kit again.");
			return;
		}
		KitDefinition kit = KitLoader.getKit(kitId);
		if (kit == null) {
			RPTexts.send(player, RPTexts.ERROR + "Unknown kit. Usage: /rpcharacter kit <id>");
			return;
		}
		if (!prepareLocalClaim(player, pd, character, kit, kitId)) {
			return;
		}
		if (pendingSkin) {
			RPTexts.send(player, RPTexts.WARN
					+ "A custom kit item is still waiting for approval.");
			return;
		}
		if (pendingPack) {
			RPTexts.send(player, RPTexts.WARN
					+ "A custom kit skin is still pending pack. "
					+ "It will be added within 24 hours - try claiming again after that.");
			return;
		}

		List<JSONObject> ackRows = KitCustomiseIngestService.applyReadyForCharacterOnMain(
				player, character, pendingItems
		);
		KitCustomiseIngestService.ackAsync(ackRows);
		grantKitItems(player, pd, character, kit, kitId);
	}

	private static void grantKitItems(
			Player player,
			PlayerData pd,
			RPCharacter character,
			KitDefinition kit,
			String kitId
	) {
		List<String> editableKeys = kit.editableKitKeys();
		int customiseCount = 0;
		for (KitCustomiseData data : character.getKitCustomisations().values()) {
			if (data == null || data.getKitKey().isBlank()) {
				continue;
			}
			if (!editableKeys.contains(data.getKitKey())) {
				continue;
			}
			customiseCount++;
			int loreSize = data.getLore() != null ? data.getLore().size() : 0;
			RPCharacters.plugin.getLogger().info(
					"[kit-claim] stamped customise kit_key=" + data.getKitKey()
							+ " display_name=" + data.getDisplayName()
							+ " lore_lines=" + loreSize
							+ " skin_slug=" + data.getSkinSlug()
							+ " path=" + data.getPath()
			);
		}
		RPCharacters.plugin.getLogger().info(
				"[kit-claim] after ingest editable customise count=" + customiseCount
						+ " editable_keys=" + editableKeys
		);

		for (KitCustomiseData data : character.getKitCustomisations().values()) {
			if (data == null || data.getKitKey().isBlank()) {
				continue;
			}
			if (!editableKeys.contains(data.getKitKey())) {
				continue;
			}
			boolean present = KitCustomiseApplyService.isSkinPresent(data);
			RPCharacters.plugin.getLogger().info(
					"[kit-claim] skin-ready kit_key=" + data.getKitKey()
							+ " skin_slug=" + data.getSkinSlug()
							+ " present=" + present
			);
		}
		if (!KitCustomiseApplyService.requiredSkinsReady(character, editableKeys)) {
			RPTexts.send(player, RPTexts.WARN
					+ "Kit is not ready yet, awaiting skins.");
			return;
		}

		List<KitItemDefinition> definitions = kit.getItems();
		if (definitions.isEmpty()) {
			RPCharacters.plugin.getLogger().warning(
					"[kit-claim] skipped for " + player.getName() + ": kit '" + kitId + "' has no items."
			);
			RPTexts.send(player, RPTexts.ERROR + "That kit is not configured. Contact staff.");
			return;
		}

		List<ItemStack> stacks = new ArrayList<>();
		for (KitItemDefinition def : definitions) {
			List<ItemStack> built = buildStacks(def, character.getKitCustomisations());
			if (built.isEmpty()) {
				if (def.isEditable() && character.getKitCustomisations().get(
						EditableKitPreviewBuilder.kitKeyFromPath(def.getPath())) != null) {
					RPCharacters.plugin.getLogger().warning(
							"[kit-claim] aborted kit='" + kitId + "' custom path='" + def.getPath() + "'"
					);
					RPTexts.send(player, RPTexts.ERROR
							+ "That customised kit item could not be built. Your kit has not been claimed. Contact staff.");
					return;
				}
				RPCharacters.plugin.getLogger().warning(
						"[kit-claim] kit '" + kitId + "' could not build path '" + def.getPath()
								+ "' for " + player.getName() + " - skipped line."
				);
				continue;
			}
			stacks.addAll(built);
		}
		if (stacks.isEmpty()) {
			RPCharacters.plugin.getLogger().warning(
					"[kit-claim] aborted for " + player.getName() + " kit=" + kitId + ": no stacks produced."
			);
			RPTexts.send(player, RPTexts.ERROR + "That kit could not be built. Contact staff.");
			return;
		}

		boolean dropped = false;
		Location dropAt = player.getLocation();
		for (ItemStack stack : stacks) {
			Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
			for (ItemStack left : leftover.values()) {
				if (left != null && !left.getType().isAir() && left.getAmount() > 0) {
					player.getWorld().dropItemNaturally(dropAt, left);
					dropped = true;
				}
			}
		}

		if (kit.isOncePerCharacter()) {
			character.setKitStatus(kitId, KitStatus.GRANTED);
		} else {
			character.setKitStatus(kitId, KitStatus.ELIGIBLE);
		}
		pd.setLastKitClaimAtMs(kitId, System.currentTimeMillis());
		RPCharacters.getPlayerManager().savePlayer(player);
		net.tfminecraft.rpcharacters.ingest.RosterSyncService.pushRosterForPlayer(player);

		RPTexts.send(player, RPTexts.SUCCESS + "You claimed the " + kit.getDisplayName() + " kit!");
		if (dropped) {
			RPTexts.send(player, RPTexts.WARN + "Some kit items did not fit and were dropped at your feet.");
		}
	}

	/**
	 * Staff reset: restore claimability for one character + kit; clear cooldown and
	 * customisations. Caller must ensure target is online with loaded PlayerData.
	 */
	public static final class ResetResult {
		public final boolean ok;
		public final String message;
		public final boolean psWipeOk;
		public final String psWipeError;

		private ResetResult(boolean ok, String message, boolean psWipeOk, String psWipeError) {
			this.ok = ok;
			this.message = message;
			this.psWipeOk = psWipeOk;
			this.psWipeError = psWipeError;
		}

		public static ResetResult fail(String message) {
			return new ResetResult(false, message, true, null);
		}

		public static ResetResult ok(String message, boolean psWipeOk, String psWipeError) {
			return new ResetResult(true, message, psWipeOk, psWipeError);
		}
	}

	private static final class ResolvedKitTarget {
		final PlayerData pd;
		final RPCharacter character;
		final KitDefinition kit;
		final String kitId;
		final String label;

		ResolvedKitTarget(
				PlayerData pd,
				RPCharacter character,
				KitDefinition kit,
				String kitId,
				String label
		) {
			this.pd = pd;
			this.character = character;
			this.kit = kit;
			this.kitId = kitId;
			this.label = label;
		}
	}

	/**
	 * Resolve player + public character id (slug) + kit. UUID character id accepted as fallback.
	 */
	private static Object resolveKitTarget(Player target, String characterIdRaw, String kitIdRaw, String usage) {
		if (target == null || !target.isOnline()) {
			return ResetResult.fail("Player must be online.");
		}
		String characterRef = characterIdRaw != null ? characterIdRaw.trim() : "";
		String kitId = kitIdRaw != null ? kitIdRaw.trim().toLowerCase(Locale.ROOT) : "";
		if (characterRef.isEmpty() || kitId.isEmpty()) {
			return ResetResult.fail(usage);
		}
		KitDefinition kit = KitLoader.getKit(kitId);
		if (kit == null) {
			return ResetResult.fail("Unknown kit id '" + kitId + "'.");
		}
		if (!PlayerManager.exists(target)) {
			return ResetResult.fail("No character data loaded for " + target.getName() + ".");
		}
		PlayerData pd = PlayerManager.get(target);
		if (pd == null) {
			return ResetResult.fail("No character data loaded for " + target.getName() + ".");
		}
		RPCharacter character = pd.getCharacterBySlug(characterRef);
		if (character == null) {
			character = pd.getCharacterById(characterRef);
		}
		if (character == null) {
			return ResetResult.fail(
					"Character '" + characterRef + "' not found for " + target.getName() + "."
			);
		}
		String label = character.getSlug() != null && !character.getSlug().isBlank()
				? character.getSlug()
				: character.getId();
		return new ResolvedKitTarget(pd, character, kit, kitId, label);
	}

	/**
	 * Staff: make kit claimable again. Keeps in-game and ProvinceSystem customisations.
	 */
	public static ResetResult reclaimKit(Player target, String characterIdRaw, String kitIdRaw) {
		Object resolved = resolveKitTarget(
				target,
				characterIdRaw,
				kitIdRaw,
				"Usage: /rpcharacter reclaimkit <player> <character_id> <kit_id>"
		);
		if (resolved instanceof ResetResult fail) {
			return fail;
		}
		ResolvedKitTarget t = (ResolvedKitTarget) resolved;
		t.character.setKitStatus(t.kitId, KitStatus.ELIGIBLE);
		t.pd.setLastKitClaimAtMs(t.kitId, null);
		RPCharacters.getPlayerManager().savePlayer(target);
		net.tfminecraft.rpcharacters.ingest.RosterSyncService.pushRosterForPlayer(target);
		return ResetResult.ok(
				"Reclaimed kit '" + t.kitId + "' for " + target.getName()
						+ " character " + t.label + " (customisations kept).",
				true,
				null
		);
	}

	/**
	 * Staff: full wipe — reclaimable + clear in-game and ProvinceSystem customisations.
	 */
	public static ResetResult resetKit(Player target, String characterIdRaw, String kitIdRaw) {
		Object resolved = resolveKitTarget(
				target,
				characterIdRaw,
				kitIdRaw,
				"Usage: /rpcharacter resetkit <player> <character_id> <kit_id>"
		);
		if (resolved instanceof ResetResult fail) {
			return fail;
		}
		ResolvedKitTarget t = (ResolvedKitTarget) resolved;

		t.character.setKitStatus(t.kitId, KitStatus.ELIGIBLE);
		t.pd.setLastKitClaimAtMs(t.kitId, null);
		for (String key : t.kit.editableKitKeys()) {
			t.character.removeKitCustomise(key);
		}
		RPCharacters.getPlayerManager().savePlayer(target);
		net.tfminecraft.rpcharacters.ingest.RosterSyncService.pushRosterForPlayer(target);

		String playerUuid = target.getUniqueId().toString();
		net.tfminecraft.rpcharacters.api.ProvinceSystemClient.SimpleResult wipe =
				net.tfminecraft.rpcharacters.api.ProvinceSystemClient.clearLoreItemCustomisations(
						playerUuid, t.character.getId(), t.kitId
				);
		String msg = "Reset kit '" + t.kitId + "' for " + target.getName()
				+ " character " + t.label + " (customisations wiped).";
		if (!wipe.ok) {
			RPCharacters.plugin.getLogger().warning(
					"[resetkit] ProvinceSystem customise wipe failed: " + wipe.error
			);
			return ResetResult.ok(msg, false, wipe.error);
		}
		return ResetResult.ok(msg, true, null);
	}

	// Customise only the new grant, before inventory insertion or overflow drops.
	static List<ItemStack> buildStacks(
			KitItemDefinition def, Map<String, KitCustomiseData> customisations
	) {
		List<ItemStack> out = new ArrayList<>();
		if (def == null || def.getPath() == null || def.getPath().isBlank()) {
			return out;
		}
		ItemStack template;
		try {
			KitCustomiseData customise = def.isEditable()
					? customisations.get(EditableKitPreviewBuilder.kitKeyFromPath(def.getPath()))
					: null;
			template = customise != null
					? KitCustomiseApplyService.buildStack(customise)
					: TLibs.getItemAPI().getCreator().getItemFromPath(def.getPath());
		} catch (Exception e) {
			RPCharacters.plugin.getLogger().warning(
					"Kit path '" + def.getPath() + "' threw: " + e.getMessage()
			);
			return out;
		}
		if (template == null || template.getType().isAir()) {
			return out;
		}
		int remaining = def.getAmount();
		int max = Math.max(1, template.getMaxStackSize());
		while (remaining > 0) {
			int take = Math.min(max, remaining);
			ItemStack stack = template.clone();
			stack.setAmount(take);
			out.add(stack);
			remaining -= take;
		}
		return out;
	}
}
