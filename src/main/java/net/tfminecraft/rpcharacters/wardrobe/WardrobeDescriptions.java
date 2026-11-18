package net.tfminecraft.rpcharacters.wardrobe;

import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.persona.DescriptionValidator;
import net.tfminecraft.rpcharacters.persona.PersonaCooldownManager;
import net.tfminecraft.rpcharacters.utils.ClueFormatter;
import net.tfminecraft.rpcharacters.utils.RPTexts;

/**
 * Wardrobe skin descriptions: what others read while a skin is worn, in place of the
 * character description. Shared by the wardrobe menu prompt and the command. Base never
 * has one; the text is saved on the website with the skin.
 */
public final class WardrobeDescriptions {

	static final String COOLDOWN_FIELD = "wardrobe_description";
	static final String DESCRIPTION_COMMAND = "/rpcharacter description";
	static final String WARDROBE_COMMAND = "/rpcharacter wardrobe";

	private WardrobeDescriptions() {}

	/** Whether the player may set a description on this skin; tells them why not. */
	static boolean canDescribe(Player player, String slotId) {
		if (!player.hasPermission(Cache.personaSetPermission)) {
			RPTexts.send(player, RPTexts.ERROR + "You do not have permission to change your description.");
			return false;
		}
		PlayerData pd = PlayerManager.get(player);
		if (pd == null || !pd.hasActiveCharacter()) {
			RPTexts.send(player, RPTexts.ERROR + "You need an active character.");
			return false;
		}
		WardrobeSnapshot snapshot = WardrobeCache.get(player);
		if (snapshot == null || !pd.getActiveCharacter().getId().equalsIgnoreCase(snapshot.getCharacterId())) {
			RPTexts.send(player, RPTexts.WARN + "Wardrobe is still loading. Try again in a moment.");
			return false;
		}
		if (!snapshot.supportsDescriptions()) {
			RPTexts.send(player, RPTexts.ERROR + "Skin descriptions are not available yet.");
			return false;
		}
		if (WardrobeSnapshot.SLOT_BASE.equals(slotId)) {
			RPTexts.send(player, RPTexts.WARN + "Base uses your character description. Change it with "
				+ DESCRIPTION_COMMAND + ".");
			return false;
		}
		WardrobeSlotData data = snapshot.getSlot(slotId);
		if (data == null || !data.isUnlocked() || !data.isFilled()) {
			RPTexts.send(player, RPTexts.ERROR + "That skin slot is empty or locked.");
			return false;
		}
		return true;
	}

	/**
	 * Check and save {@code raw} ("clear" removes it) for the skin.
	 * @return false when the text itself was refused, so a chat prompt can ask again
	 */
	static boolean submit(Player player, String slotId, String raw) {
		if (!canDescribe(player, slotId)) {
			return true;
		}
		String trimmed = raw == null ? "" : raw.trim();
		String text = null;
		if (!trimmed.equalsIgnoreCase("clear")) {
			if (isOnCooldown(player)) {
				return true;
			}
			text = player.hasPermission(Cache.personaDescriptionColorsPermission)
				? trimmed
				: ClueFormatter.stripColor(RPTexts.formatGui(trimmed)).trim();
			String error = DescriptionValidator.validate(text);
			if (error != null) {
				RPTexts.send(player, error);
				return false;
			}
		}
		String label = WardrobeService.labelForSlot(WardrobeCache.get(player), slotId);
		boolean clearing = text == null;
		WardrobeService.setDescription(player, slotId, text, error -> {
			if (error != null) {
				RPTexts.send(player, RPTexts.ERROR + error);
				return;
			}
			if (clearing) {
				RPTexts.send(player, RPTexts.SUCCESS + "Others now read your character description while you wear "
					+ RPTexts.WARN + label + RPTexts.SUCCESS + ".");
				return;
			}
			applyCooldown(player);
			RPTexts.send(player, RPTexts.SUCCESS + "Description saved. Others read it while you wear "
				+ RPTexts.WARN + label + RPTexts.SUCCESS + ".");
		});
		return true;
	}

	/** Line after equipping a skin, so players learn the feature where they change skins. */
	static void sendEquipHint(Player player, String slotId) {
		WardrobeSnapshot snapshot = WardrobeCache.get(player);
		if (snapshot == null || !snapshot.supportsDescriptions()
				|| WardrobeSnapshot.SLOT_BASE.equals(slotId)) {
			return;
		}
		WardrobeSlotData data = snapshot.getSlot(slotId);
		if (data != null && data.getDescription() != null) {
			RPTexts.send(player, RPTexts.MUTED + "Others now read this skin's own description.");
		} else {
			RPTexts.send(player, RPTexts.MUTED + "Tip: shift-click a skin in " + WARDROBE_COMMAND
				+ " to give it its own description.");
		}
	}

	/** Line after /rpcharacter description while a non-Base skin is worn. */
	public static void sendCharacterDescriptionHint(Player player) {
		WardrobeSlotData worn = WardrobeService.wornDescribableSlot(player);
		if (worn == null) {
			return;
		}
		String label = WardrobeService.labelForSlot(WardrobeCache.get(player), worn.getSlot());
		if (worn.getDescription() != null) {
			RPTexts.send(player, RPTexts.WARN + "While you wear " + label
				+ ", others read that skin's own description instead. Shift-click it in "
				+ WARDROBE_COMMAND + " to change it.");
		} else {
			RPTexts.send(player, RPTexts.MUTED + "This is your main description. To set one only for "
				+ label + ", shift-click it in " + WARDROBE_COMMAND + ".");
		}
	}

	private static boolean isOnCooldown(Player player) {
		if (player.hasPermission(Cache.personaBypassCooldownPermission)) {
			return false;
		}
		PersonaCooldownManager cooldowns = PersonaCooldownManager.get();
		if (!cooldowns.isOnCooldown(player, COOLDOWN_FIELD, Cache.personaDescriptionCooldownSeconds)) {
			return false;
		}
		int remaining = cooldowns.getRemainingSeconds(player, COOLDOWN_FIELD);
		RPTexts.send(player, RPTexts.ERROR + "Wait " + RPTexts.WARN + remaining + RPTexts.ERROR + "s.");
		return true;
	}

	private static void applyCooldown(Player player) {
		if (player.hasPermission(Cache.personaBypassCooldownPermission)) {
			return;
		}
		PersonaCooldownManager.get().applyCooldown(player, COOLDOWN_FIELD, Cache.personaDescriptionCooldownSeconds);
	}
}
