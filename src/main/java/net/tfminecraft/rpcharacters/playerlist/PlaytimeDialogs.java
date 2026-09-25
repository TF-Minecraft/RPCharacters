package net.tfminecraft.rpcharacters.playerlist;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.playtime.CharacterPlaytimeDirectory;
import net.tfminecraft.rpcharacters.playtime.CharacterPlaytimeDirectory.Entry;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.utils.RPTexts;

/** Per-character playtime, including inactive and deceased characters. */
public final class PlaytimeDialogs {

	public static final Key OPEN_ACTION = Key.key("rpcharacters", "playtime_list");
	static final int PAGE_SIZE = 12;
	private static final ClickCallback.Options CALLBACKS = ClickCallback.Options.builder()
			.uses(ClickCallback.UNLIMITED_USES).lifetime(Duration.ofMinutes(10)).build();

	private PlaytimeDialogs() {}

	public static void open(Player viewer) {
		if (!allowed(viewer)) return;
		openPage(viewer, sortedPlayers(CharacterPlaytimeDirectory.getAll()), 0);
	}

	static List<Entry> sortedPlayers(List<Entry> players) {
		return players.stream()
				.sorted(Comparator.comparingInt(Entry::seconds).reversed()
						.thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER)
						.thenComparing(Entry::ownerId)
						.thenComparing(Entry::characterId))
				.toList();
	}

	private static boolean allowed(Player viewer) {
		if (viewer.hasPermission(Cache.playerList.permission())) return true;
		RPTexts.send(viewer, RPTexts.ERROR + "You do not have permission to view the player list.");
		return false;
	}

	private static void openPage(Player viewer, List<Entry> players, int requestedPage) {
		if (!allowed(viewer)) return;
		int pages = Math.max(1, (players.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		int page = Math.max(0, Math.min(requestedPage, pages - 1));
		List<DialogBody> body = new ArrayList<>();
		body.add(DialogBody.plainMessage(Component.text("Character playtime", NamedTextColor.GRAY)
				.append(Component.newline()).append(Component.text(players.size()
						+ " characters • Page " + (page + 1) + "/" + pages, NamedTextColor.DARK_GRAY))
				.append(Component.newline()).append(Component.text("Hover a character for player details", NamedTextColor.GRAY)), 420));
		if (players.isEmpty()) {
			body.add(DialogBody.plainMessage(Component.text("No characters recorded yet."), 420));
		}
		Component rows = GuiText.padded("§7#", 32)
				.append(GuiText.padded("", 14))
				.append(GuiText.padded("§7Character", 150))
				.append(GuiText.padded("§7Playtime", 112));
		int position = page * PAGE_SIZE;
		// Entries include saved offline characters; no character files are read here.
		for (Entry player : pagePlayers(players, page)) {
			int rank = ++position;
			TextColor colour = switch (rank) {
				case 1 -> NamedTextColor.GOLD;
				case 2 -> TextColor.color(0xC0C0C0);
				case 3 -> TextColor.color(0xCD7F32);
				default -> NamedTextColor.GRAY;
			};
			Component row = Component.text()
					.append(GuiText.padded(Integer.toString(rank), 32).color(colour))
					.append(GuiText.padded(onlineMarker(viewer, player), 14))
					.append(GuiText.padded(shortName(player.name()), 150).color(NamedTextColor.WHITE))
					.append(GuiText.padded(formatSeconds(player.seconds()), 112).color(colour))
					.hoverEvent(tooltip(player)).build();
			rows = rows.append(Component.newline()).append(row);
		}
		if (!players.isEmpty()) body.add(DialogBody.plainMessage(rows, 420));
		List<ActionButton> buttons = new ArrayList<>();
		if (page > 0) buttons.add(pageButton("Previous", players, page - 1));
		if (page + 1 < pages) buttons.add(pageButton("Next", players, page + 1));
		buttons.add(ActionButton.builder(Component.text("Who's online")).width(130)
				.action(DialogAction.customClick((response, audience) -> {
					if (audience instanceof Player clicker && allowed(clicker)) {
						PlayerListDialogs.openList(clicker);
					}
				}, CALLBACKS)).build());
		viewer.showDialog(Dialog.create(builder -> builder.empty()
				.base(DialogBase.builder(Component.text("Playtime leaderboard", NamedTextColor.GOLD)
						.decorate(TextDecoration.BOLD))
						.canCloseWithEscape(true).body(body).build())
				.type(DialogType.multiAction(buttons,
						ActionButton.builder(Component.text("Close")).width(130).build(), 3))));
	}

	static List<Entry> pagePlayers(List<Entry> players, int page) {
		int start = Math.min(Math.max(0, page) * PAGE_SIZE, players.size());
		return players.subList(start, Math.min(start + PAGE_SIZE, players.size()));
	}

	private static ActionButton pageButton(String label, List<Entry> players, int page) {
		return ActionButton.builder(Component.text(label)).width(130)
				.action(DialogAction.customClick((response, audience) -> {
					if (audience instanceof Player clicker) openPage(clicker, players, page);
				}, CALLBACKS)).build();
	}

	static String formatSeconds(int seconds) {
		long minutes = Math.max(0L, seconds) / 60;
		return (minutes / 1440) + "d " + (minutes / 60 % 24) + "h " + (minutes % 60) + "m";
	}

	static String shortName(String name) {
		if (GuiText.width(name) <= 146) return name;
		int end = name.length();
		while (end > 0 && GuiText.width(name.substring(0, end) + "...") > 146) {
			end = name.offsetByCodePoints(end, -1);
		}
		return name.substring(0, end) + "...";
	}

	static String onlineMarker(Player viewer, Entry entry) {
		return visibleActivePlayer(viewer, entry) != null ? "§a●" : "";
	}

	private static Player visibleActivePlayer(Player viewer, Entry entry) {
		if (entry.status() != Status.ALIVE || entry.hidden()) return null;
		Player online = Bukkit.getPlayer(entry.ownerId());
		if (online == null || (!online.equals(viewer) && !viewer.canSee(online))) return null;
		PlayerData owner = PlayerManager.get(online);
		RPCharacter active = owner == null ? null : owner.getActiveCharacter();
		return active != null && !active.isHidden() && entry.characterId().equals(active.getId()) ? online : null;
	}

	static Component tooltip(Entry entry) {
		String playerName = Bukkit.getOfflinePlayer(entry.ownerId()).getName();
		if (playerName == null || playerName.isBlank()) playerName = entry.ownerId().toString();
		String status = switch (entry.status()) {
			case ALIVE -> "Alive";
			case DEAD -> "Deceased";
			case MISSING -> "Missing";
		};
		return Component.text(entry.name(), NamedTextColor.WHITE)
				.append(Component.newline()).append(Component.text("Player: " + playerName, NamedTextColor.GRAY))
				.append(Component.newline()).append(Component.text("Character playtime: "
						+ formatSeconds(entry.seconds()) + " " + (entry.seconds() % 60) + "s", NamedTextColor.GRAY))
				.append(Component.newline()).append(Component.text("Character status: " + status, NamedTextColor.GRAY));
	}
}
