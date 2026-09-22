package net.tfminecraft.rpcharacters.wardrobe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;

/**
 * /rpcharacter wardrobe — GUI picker, or chat equip with a slot arg.
 */
public final class WardrobeCommand {

	public static final String SUBCOMMAND = "wardrobe";

	private WardrobeCommand() {}

	public static boolean handle(CommandSender sender, String label, String[] args) {
		if (!(sender instanceof Player)) {
			sender.sendMessage("Players only.");
			return true;
		}
		Player player = (Player) sender;
		PlayerData pd = PlayerManager.get(player);
		if (pd == null || !pd.hasActiveCharacter()) {
			RPTexts.send(player, RPTexts.ERROR + "You need an active character.");
			return true;
		}

		// args[0] is "wardrobe"; slot is args[1] when present
		if (args.length <= 1) {
			WardrobeGui.open(player);
			return true;
		}

		WardrobeSnapshot snapshot = WardrobeCache.get(player);
		if (snapshot == null) {
			RPTexts.send(
				player,
				RPTexts.WARN + "Loading wardrobe… Try again in a moment."
			);
			WardrobeService.refreshActiveAsync(player);
			return true;
		}

		String slot = resolveSlotArg(snapshot, args[1]);
		if (slot == null) {
			RPTexts.send(
				player,
				RPTexts.ERROR + "Usage: /" + label + " wardrobe [base|extra_1|extra_2|name]"
			);
			return true;
		}

		String active = snapshot.getActiveSlot();
		if (active != null && active.equalsIgnoreCase(slot)) {
			RPTexts.send(
				player,
				RPTexts.SUCCESS + "Already using "
					+ RPTexts.WARN + WardrobeService.labelForSlot(snapshot, slot)
					+ RPTexts.SUCCESS + "."
			);
			return true;
		}

		WardrobeService.setActiveAndApply(player, slot, error -> {
			if (error != null) {
				RPTexts.send(player, RPTexts.ERROR + error);
				return;
			}
			RPTexts.send(
				player,
				RPTexts.SUCCESS + "Equipped "
					+ RPTexts.WARN + WardrobeService.labelForSlot(snapshot, slot)
					+ RPTexts.SUCCESS + "."
			);
		});
		return true;
	}

	private static String resolveSlotArg(WardrobeSnapshot snapshot, String raw) {
		String byId = WardrobeService.normalizeSwappable(raw);
		if (byId != null) {
			return byId;
		}
		String needle = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)
			.replace('_', ' ').replaceAll("\\s+", " ").trim();
		if (needle.isEmpty()) {
			return null;
		}
		String match = null;
		for (String id : List.of(
			WardrobeSnapshot.SLOT_BASE,
			WardrobeSnapshot.SLOT_EXTRA_1,
			WardrobeSnapshot.SLOT_EXTRA_2
		)) {
			WardrobeSlotData slot = snapshot.getSlot(id);
			if (slot == null
				|| !slot.isUnlocked()
				|| !slot.isFilled()
				|| !slot.canApply()) {
				continue;
			}
			String label = WardrobeService.labelForSlot(snapshot, id)
				.toLowerCase(Locale.ROOT)
				.replace('_', ' ')
				.replaceAll("\\s+", " ")
				.trim();
			if (!label.equals(needle)) {
				continue;
			}
			if (match != null) {
				return null; // ambiguous name
			}
			match = id;
		}
		return match;
	}

	public static List<String> tabComplete(Player player, String[] args) {
		if (args.length != 2) {
			return Collections.emptyList();
		}
		WardrobeSnapshot snapshot = WardrobeCache.get(player);
		List<String> options = new ArrayList<>();
		if (snapshot == null) {
			options.add(WardrobeSnapshot.SLOT_BASE);
			options.add(WardrobeSnapshot.SLOT_EXTRA_1);
			options.add(WardrobeSnapshot.SLOT_EXTRA_2);
		} else {
			for (String id : List.of(
				WardrobeSnapshot.SLOT_BASE,
				WardrobeSnapshot.SLOT_EXTRA_1,
				WardrobeSnapshot.SLOT_EXTRA_2
			)) {
				WardrobeSlotData slot = snapshot.getSlot(id);
				if (slot != null
					&& slot.isUnlocked()
					&& slot.isFilled()
					&& slot.canApply()) {
					options.add(id);
					String name = slot.getDisplayName();
					if (name != null && !name.isBlank()) {
						String cleaned = name.trim().replace(' ', '_');
						if (!cleaned.equalsIgnoreCase(id)
							&& !options.contains(cleaned)) {
							options.add(cleaned);
						}
					}
				}
			}
		}
		String prefix = args[1].toLowerCase(Locale.ROOT);
		return options.stream()
			.filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix))
			.collect(Collectors.toList());
	}
}
