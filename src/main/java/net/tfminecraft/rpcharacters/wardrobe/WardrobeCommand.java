package net.tfminecraft.rpcharacters.wardrobe;

import java.util.ArrayList;
import java.util.Arrays;
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
 * /rpcharacter wardrobe — GUI picker, or chat equip with a slot arg;
 * /rpcharacter wardrobe describe &lt;skin&gt; [text|clear] sets a skin's description.
 */
public final class WardrobeCommand {

	public static final String SUBCOMMAND = "wardrobe";
	public static final String DESCRIBE = "describe";

	private WardrobeCommand() {}

	public static boolean handle(CommandSender sender, String label, String[] args) {
		if (!(sender instanceof Player)) {
			sender.sendMessage("Players only.");
			return true;
		}
		Player player = (Player) sender;
		// Any wardrobe command ends a description prompt left open from the menu.
		WardrobeDescriptionInput.cancel(player);
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

		if (DESCRIBE.equalsIgnoreCase(args[1]) && snapshot.supportsDescriptions()) {
			return handleDescribe(player, label, snapshot, args);
		}

		String slot = resolveSlotArg(snapshot, args[1]);
		if (slot == null) {
			RPTexts.send(
				player,
				RPTexts.ERROR + "Usage: /" + label + " wardrobe [base|extra_1-extra_"
					+ (WardrobeSlots.MAX_SWAPPABLE - 1) + "|1-" + WardrobeSlots.MAX_SWAPPABLE + "|name]"
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
			WardrobeDescriptions.sendEquipHint(player, slot);
		});
		return true;
	}

	private static boolean handleDescribe(Player player, String label, WardrobeSnapshot snapshot, String[] args) {
		if (args.length < 3) {
			RPTexts.send(player, RPTexts.WARN + "Usage: /" + label + " wardrobe describe <skin> [text...|clear]");
			return true;
		}
		String slot = WardrobeSnapshot.SLOT_MASKED.equalsIgnoreCase(args[2])
			? WardrobeSnapshot.SLOT_MASKED
			: resolveSlotArg(snapshot, args[2]);
		if (slot == null) {
			RPTexts.send(player, RPTexts.ERROR + "Unknown skin. Use a skin number from 2 to "
				+ WardrobeSlots.MAX_SWAPPABLE + ", its name, or masked.");
			return true;
		}
		if (args.length == 3) {
			if (WardrobeDescriptions.canDescribe(player, slot)) {
				WardrobeDescriptionInput.begin(player, slot);
			}
			return true;
		}
		WardrobeDescriptions.submit(player, slot, String.join(" ", Arrays.copyOfRange(args, 3, args.length)));
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
		for (String id : WardrobeSlots.swappable()) {
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
		WardrobeSnapshot snapshot = WardrobeCache.get(player);
		boolean describing = args.length >= 3 && DESCRIBE.equalsIgnoreCase(args[1])
			&& snapshot != null && snapshot.supportsDescriptions();
		if (describing && args.length == 4) {
			return "clear".startsWith(args[3].toLowerCase(Locale.ROOT)) ? List.of("clear") : Collections.emptyList();
		}
		if (args.length != 2 && !(describing && args.length == 3)) {
			return Collections.emptyList();
		}
		List<String> options = new ArrayList<>();
		if (args.length == 2 && snapshot != null && snapshot.supportsDescriptions()) {
			options.add(DESCRIBE);
		}
		if (snapshot == null) {
			options.addAll(WardrobeSlots.swappable());
		} else {
			for (String id : WardrobeSlots.swappable()) {
				WardrobeSlotData slot = snapshot.getSlot(id);
				if (slot != null
					&& slot.isUnlocked()
					&& slot.isFilled()
					&& slot.canApply()) {
					options.add(id);
					String name = slot.getDisplayName();
					if (name != null && !name.isBlank()) {
						String cleaned = name.trim().replace(' ', '_');
						if (id.equals(resolveSlotArg(snapshot, cleaned))
							&& !cleaned.equalsIgnoreCase(id) && !options.contains(cleaned)) {
							options.add(cleaned);
						}
					}
				}
			}
		}
		if (describing) {
			options.remove(WardrobeSnapshot.SLOT_BASE);
			WardrobeSlotData masked = snapshot.getSlot(WardrobeSnapshot.SLOT_MASKED);
			if (masked != null && masked.isFilled()) {
				options.add(WardrobeSnapshot.SLOT_MASKED);
			}
		}
		String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
		return options.stream()
			.filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix))
			.collect(Collectors.toList());
	}
}
