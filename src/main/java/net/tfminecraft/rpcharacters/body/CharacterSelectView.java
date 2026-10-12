package net.tfminecraft.rpcharacters.body;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.Indyuce.mmocore.MMOCore;
import net.Indyuce.mmocore.api.player.profess.PlayerClass;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.tfminecraft.rpcharacters.calendar.AgeCalculator;
import net.tfminecraft.rpcharacters.calendar.FantasyCalendar;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.paidchange.DenarEconomyWallet;
import net.tfminecraft.rpcharacters.paidchange.DenarWallet;
import net.tfminecraft.rpcharacters.paidchange.DenarWallet.Account;
import net.tfminecraft.rpcharacters.paidchange.PaidChangeService;
import net.tfminecraft.rpcharacters.persona.CharacterSlotService;
import net.tfminecraft.rpcharacters.persona.PermissionGroupService;
import net.tfminecraft.rpcharacters.utils.AgeFormatter;
import net.tfminecraft.rpcharacters.utils.ClueFormatter;

/**
 * Join-time character picker, shown while the player has no active body. Every
 * button rechecks that the click came from the same connection and that the
 * owner still considers this selector current.
 */
public final class CharacterSelectView {

	/** Owner hooks. One instance belongs to one player's connection. */
	public interface Actions {
		boolean isCurrent(Player player, PlayerData data);

		/** Why the character cannot be played now, or null when it can. */
		String blockReason(RPCharacter character);

		void play(RPCharacter character);

		void create();

		void leave();
	}

	static final int PAGE_SIZE = 10;
	static final String NO_COOLDOWN_PERMISSION = "rpcharacters.no_cooldown";
	private static final int WIDTH = 320;
	private static final ClickCallback.Options CALLBACKS = ClickCallback.Options.builder()
			.uses(1).lifetime(Duration.ofMinutes(10)).build();

	static DenarWallet wallet = new DenarEconomyWallet();
	/** Guild name, "" for none, or null when guilds can't be read. */
	static Function<Player, String> guilds = CharacterSelectView::simpleFactionsGuild;

	private record Session(Player player, PlayerData data, Actions actions) {
		boolean accepts(Audience audience) {
			return audience == player && actions.isCurrent(player, data);
		}
	}

	private CharacterSelectView() {}

	public static void open(Player player, PlayerData data, Actions actions) {
		if (player == null || data == null || actions == null || !actions.isCurrent(player, data)) return;
		openPage(new Session(player, data, actions), 0, null);
	}

	private static void openPage(Session session, int requestedPage, String notice) {
		Player player = session.player();
		PlayerData data = session.data();
		List<RPCharacter> cards = cards(data);
		int pages = Math.max(1, (cards.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		int page = Math.max(0, Math.min(requestedPage, pages - 1));
		int alive = data.getCharacters(Status.ALIVE).size();
		int slots = CharacterSlotService.getMaxAliveCharacters(player);

		List<DialogBody> body = new ArrayList<>();
		String summary = "Characters: " + alive + " of " + slots + " slots used";
		if (pages > 1) summary += " • Page " + (page + 1) + "/" + pages;
		body.add(DialogBody.plainMessage(Component.text(summary, NamedTextColor.GRAY), WIDTH));
		if (notice != null) body.add(DialogBody.plainMessage(Component.text(notice, NamedTextColor.RED), WIDTH));
		body.add(DialogBody.plainMessage(accountSummary(player), WIDTH));

		List<ActionButton> buttons = new ArrayList<>();
		for (RPCharacter character : page(cards, page)) {
			if (character.getStatus() != Status.ALIVE) {
				body.add(DialogBody.plainMessage(memorialCard(character), WIDTH));
				continue;
			}
			String reason = session.actions().blockReason(character);
			body.add(DialogBody.plainMessage(characterCard(character, reason), WIDTH));
			if (reason == null) buttons.add(playButton(session, character, page));
		}
		if (cards.isEmpty()) {
			body.add(DialogBody.plainMessage(Component.text("You have no characters yet.", NamedTextColor.GRAY), WIDTH));
		}
		String createBlock = createBlockReason(player, data);
		if (createBlock == null) {
			body.add(DialogBody.plainMessage(Component.text("Empty slot", NamedTextColor.GREEN), WIDTH));
			buttons.add(button("Create character", "Start a new character", session, s -> create(s, page)));
		} else if (CharacterSlotService.hasFreeSlot(player, data)) {
			body.add(DialogBody.plainMessage(Component.text("Empty slot • " + createBlock, NamedTextColor.GRAY), WIDTH));
		}
		if (page > 0) buttons.add(button("Previous", null, session, s -> openPage(s, page - 1, null)));
		if (page + 1 < pages) buttons.add(button("Next", null, session, s -> openPage(s, page + 1, null)));

		ActionButton leave = button("Leave", "Disconnect from the server", session, s -> s.actions().leave());
		player.showDialog(Dialog.create(builder -> builder.empty()
				.base(DialogBase.builder(Component.text("Choose your character", NamedTextColor.GOLD)
						.decorate(TextDecoration.BOLD))
						.canCloseWithEscape(false).body(body).build())
				.type(DialogType.multiAction(buttons, leave, 2))));
	}

	/** Living characters, most recently played first, then memorials. */
	static List<RPCharacter> cards(PlayerData data) {
		Comparator<RPCharacter> byName = Comparator.comparing(c -> c.getName() == null ? "" : c.getName(),
				String.CASE_INSENSITIVE_ORDER);
		List<RPCharacter> cards = new ArrayList<>(data.getCharacters(Status.ALIVE).stream()
				.sorted(Comparator.comparing(RPCharacter::getLastPlayedAt,
						Comparator.nullsLast(Comparator.<Instant>reverseOrder())).thenComparing(byName))
				.toList());
		cards.addAll(data.getCharacters().stream().filter(c -> c.getStatus() != Status.ALIVE)
				.sorted(Comparator.comparing(RPCharacter::getDiedAt,
						Comparator.nullsLast(Comparator.<Instant>reverseOrder())).thenComparing(byName))
				.toList());
		return cards;
	}

	static List<RPCharacter> page(List<RPCharacter> cards, int page) {
		int start = Math.min(Math.max(0, page) * PAGE_SIZE, cards.size());
		return cards.subList(start, Math.min(start + PAGE_SIZE, cards.size()));
	}

	/** Mirrors CreationManager's slot and switch-cooldown checks; null when a character can be created. */
	static String createBlockReason(Player player, PlayerData data) {
		if (!CharacterSlotService.hasFreeSlot(player, data)) return "No free character slot";
		if (!data.getCharacters(Status.ALIVE).isEmpty() && !player.hasPermission(NO_COOLDOWN_PERMISSION)
				&& PermissionGroupService.hasCharacterSwitchCooldown(player, data)) {
			long minutes = PermissionGroupService.getRemainingCooldownMinutes(player, data);
			return "New character available in " + AgeFormatter.formatCountdown(minutes * 60_000L);
		}
		return null;
	}

	static Component accountSummary(Player player) {
		List<String> parts = new ArrayList<>();
		String guild = guilds.apply(player);
		if (guild != null) parts.add("Guild: " + (guild.isBlank() ? "none" : guild));
		if (wallet.available()) {
			parts.add("Pouch: " + PaidChangeService.formatDenars(wallet.balance(player.getUniqueId(), Account.POUCH)));
			parts.add("Bank: " + PaidChangeService.formatDenars(wallet.balance(player.getUniqueId(), Account.BANK)));
		} else {
			parts.add("Pouch and bank: unavailable");
		}
		return Component.text("Shared by all your characters", NamedTextColor.DARK_GRAY)
				.append(Component.newline())
				.append(Component.text(String.join(" • ", parts), NamedTextColor.GRAY));
	}

	static Component characterCard(RPCharacter character, String blockReason) {
		TextComponent.Builder card = Component.text()
				.append(Component.text(name(character), NamedTextColor.WHITE, TextDecoration.BOLD));
		line(card, joined(race(character), className(character), age(character)));
		List<String> history = new ArrayList<>();
		history.add("Played " + formatPlaytime(character.getOnlinePlaytimeSeconds()));
		if (character.getLastPlayedAt() != null) {
			long seconds = Duration.between(character.getLastPlayedAt(), Instant.now()).getSeconds();
			history.add("Last played " + AgeFormatter.formatAge(seconds) + " ago");
		}
		line(card, String.join(" • ", history));
		if (character.hasLastLocation()) {
			line(card, String.format(Locale.ROOT, "Last location: %s %d, %d, %d", character.getLastLocationWorld(),
					(long) Math.floor(character.getLastLocationX()), (long) Math.floor(character.getLastLocationY()),
					(long) Math.floor(character.getLastLocationZ())));
		}
		if (blockReason != null) {
			card.append(Component.newline()).append(Component.text(blockReason, NamedTextColor.RED));
		}
		return card.build();
	}

	static Component memorialCard(RPCharacter character) {
		String fate = character.getStatus() == Status.MISSING ? "Missing" : "Deceased";
		if (character.getStatus() == Status.DEAD && character.getDiedAt() != null) {
			fate += " " + FantasyCalendar.formatDate(character.getDiedAt().toEpochMilli());
		}
		return Component.text()
				.append(Component.text("† " + name(character), NamedTextColor.DARK_GRAY, TextDecoration.ITALIC))
				.append(Component.newline())
				.append(Component.text(joined(race(character), className(character), fate), NamedTextColor.DARK_GRAY))
				.build();
	}

	private static void line(TextComponent.Builder card, String text) {
		if (text.isEmpty()) return;
		card.append(Component.newline()).append(Component.text(text, NamedTextColor.GRAY));
	}

	private static String joined(String... parts) {
		List<String> present = new ArrayList<>();
		for (String part : parts) {
			if (part != null && !part.isBlank()) present.add(part);
		}
		return String.join(" • ", present);
	}

	private static String name(RPCharacter character) {
		return character.getName() == null || character.getName().isBlank() ? "Unnamed" : character.getName();
	}

	private static String race(RPCharacter character) {
		return character.getRace() == null ? null : ClueFormatter.stripColor(character.getRace().getName());
	}

	static String className(RPCharacter character) {
		String id = character.getMMOClass();
		if (id == null || id.isBlank()) return null;
		try {
			PlayerClass playerClass = MMOCore.plugin.classManager.get(id);
			if (playerClass != null && playerClass.getName() != null) return ClueFormatter.stripColor(playerClass.getName());
		} catch (RuntimeException ignored) {
			// MMOCore not ready or the class was removed; fall back to the stored id.
		}
		return id.charAt(0) + id.substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
	}

	private static String age(RPCharacter character) {
		String birthday = character.getBirthday();
		if (birthday == null || birthday.isBlank() || FantasyCalendar.fromIso(birthday) == null) return null;
		return "Age " + AgeCalculator.formatAge(birthday) + ", born " + FantasyCalendar.formatBirthday(birthday);
	}

	static String formatPlaytime(int seconds) {
		long minutes = Math.max(0L, seconds) / 60;
		return (minutes / 1440) + "d " + (minutes / 60 % 24) + "h " + (minutes % 60) + "m";
	}

	private static ActionButton playButton(Session session, RPCharacter character, int page) {
		String id = character.getId();
		return button(name(character), "Play as " + name(character), session, s -> play(s, id, page));
	}

	/** Re-resolves by id so a stale button can't play a character that has since changed. */
	private static void play(Session session, String characterId, int page) {
		RPCharacter character = session.data().getCharacterById(characterId);
		if (character == null || character.getStatus() != Status.ALIVE) {
			openPage(session, page, "That character can no longer be played.");
			return;
		}
		String reason = session.actions().blockReason(character);
		if (reason != null) {
			openPage(session, page, reason);
			return;
		}
		session.actions().play(character);
	}

	private static void create(Session session, int page) {
		String reason = createBlockReason(session.player(), session.data());
		if (reason != null) {
			openPage(session, page, reason + ".");
			return;
		}
		session.actions().create();
	}

	private static ActionButton button(String label, String tooltip, Session session,
			Consumer<Session> onClick) {
		ActionButton.Builder builder = ActionButton.builder(Component.text(label)).width(150)
				.action(DialogAction.customClick((response, audience) -> {
					if (session.accepts(audience)) onClick.accept(session);
				}, CALLBACKS));
		if (tooltip != null) builder.tooltip(Component.text(tooltip));
		return builder.build();
	}

	/** Account-wide until characters own their guild membership. */
	static String simpleFactionsGuild(Player player) {
		if (!Bukkit.getPluginManager().isPluginEnabled("SimpleFactions")) return null;
		try {
			return SimpleFactionsGuild.name(player.getName());
		} catch (NoClassDefFoundError | RuntimeException failure) {
			return null;
		}
	}

	/** Keeps SimpleFactions types out of the view so it loads without SimpleFactions. */
	static final class SimpleFactionsGuild {
		private SimpleFactionsGuild() {}

		static String name(String playerName) {
			net.tfminecraft.simplefactions.guild.Guild guild =
					net.tfminecraft.simplefactions.managers.FactionManager.getGuildByMember(playerName);
			return guild == null || guild.getName() == null ? "" : ClueFormatter.stripColor(guild.getName());
		}
	}
}
