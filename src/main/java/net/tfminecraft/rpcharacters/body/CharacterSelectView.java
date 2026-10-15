package net.tfminecraft.rpcharacters.body;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

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
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.tfminecraft.rpcharacters.RPCharacters;
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
import net.tfminecraft.rpcharacters.playerlist.SkinPortrait;
import net.tfminecraft.rpcharacters.playerlist.SkinPortrait.SkinTexture;
import net.tfminecraft.rpcharacters.utils.AgeFormatter;
import net.tfminecraft.rpcharacters.utils.ClueFormatter;
import net.tfminecraft.tlibs.character.CharacterRef;

/**
 * Join-time character picker, shown while the player has no active body. It
 * focuses one living character at a time with a skin bust, a roster strip and
 * a separate memorials page. Every button rechecks that the click came from the
 * same connection, the same selector session and the current view, and that the
 * owner still considers this selector current. Previews only read skins.
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

		default boolean needsDiscordLink() { return false; }

		default void linkDiscord() {}
	}

	/** Loads one character's 16x32 front view, completing with null when there is none. */
	@FunctionalInterface
	public interface PortraitLoader {
		CompletableFuture<BufferedImage> load(UUID owner, String characterId, SkinTexture accountFallback);
	}

	/** Memorials per page. */
	static final int PAGE_SIZE = 6;
	/** Character tabs per row. The whole roster stays visible. */
	static final int TABS = 3;
	/** Skin rows in the bust: the head and upper chest. */
	static final int BUST_ROWS = 14;
	static final int TAB_NAME = 11;
	static final String NO_COOLDOWN_PERMISSION = "rpcharacters.no_cooldown";
	public static final String LOADING = "Loading portrait…";
	public static final String NO_PORTRAIT = "No portrait";
	public static final TextColor TEAL = TextColor.color(0x66F0E2);
	public static final TextColor PALE = TextColor.color(0xFFFFFF);
	public static final TextColor MUTED = TextColor.color(0xD0DCDD);
	public static final TextColor DIM = TextColor.color(0xA4B0B1);
	public static final TextColor WARN = TextColor.color(0xFF8F7D);
	private static final int WIDTH = 300;
	private static final ClickCallback.Options CALLBACKS = ClickCallback.Options.builder()
			.uses(1).lifetime(Duration.ofMinutes(10)).build();

	static DenarWallet wallet = new DenarEconomyWallet();
	/** Guild name, "" for none, or null when guilds can't be read. */
	static Function<UUID, String> guilds = CharacterSelectView::simpleFactionsGuild;
	static PortraitLoader portraits = SelectPortraits::load;
	static Function<Player, SkinTexture> accountTextures = CharacterSelectView::accountSkin;
	static BiFunction<ClickCallback<Audience>, ClickCallback.Options, ClickEvent> clickEvents = ClickEvent::callback;
	static Executor mainThread = task -> Bukkit.getScheduler().runTask(RPCharacters.plugin, task);

	private static final Map<UUID, Session> SESSIONS = new HashMap<>();
	private static final AtomicLong SESSION_IDS = new AtomicLong();

	/** One opened selector. Main thread only; replaced on every open. */
	static final class Session {
		final long id;
		final Player player;
		final PlayerData data;
		final Actions actions;
		final SkinTexture accountSkin;
		final Map<String, Component> portraits = new HashMap<>();
		final Set<String> loading = new HashSet<>();
		int revision;
		boolean closed;
		boolean painting;
		boolean memorials;
		boolean creating;
		int memorialPage;
		String selected;
		String notice;

		Session(Player player, PlayerData data, Actions actions, SkinTexture accountSkin) {
			this.id = SESSION_IDS.incrementAndGet();
			this.player = player;
			this.data = data;
			this.actions = actions;
			this.accountSkin = accountSkin;
		}

		boolean live() {
			Session current = SESSIONS.get(player.getUniqueId());
			return !closed && current != null && current.id == id && actions.isCurrent(player, data);
		}

		boolean accepts(Audience audience, int seen) {
			return audience == player && seen == revision && live();
		}
	}

	private CharacterSelectView() {}

	public static void open(Player player, PlayerData data, Actions actions) {
		if (player == null || data == null || actions == null || !actions.isCurrent(player, data)) return;
		Session session = new Session(player, data, actions, accountTexture(player));
		Session previous = SESSIONS.put(player.getUniqueId(), session);
		if (previous != null) previous.closed = true;
		render(session);
	}

	/** Ends this connection's selector. A newer connection with the same UUID keeps its own. */
	public static void close(Player player) {
		if (player == null) return;
		Session session = SESSIONS.get(player.getUniqueId());
		if (session != null && session.player == player) end(session);
	}

	public static void notice(Player player, String message) {
		Session session = SESSIONS.get(player.getUniqueId());
		if (session != null && session.player == player && session.live()) notice(session, message);
	}

	/** Ends every selector, for plugin shutdown. */
	public static void cleanupAll() {
		for (Session session : SESSIONS.values()) {
			session.closed = true;
			session.player.closeDialog();
		}
		SESSIONS.clear();
	}

	/** Renews idle selectors from the five-minute checkpoint before callbacks expire. */
	static void refreshAll() {
		for (Session session : List.copyOf(SESSIONS.values())) {
			try {
				if (session.live()) {
					session.revision++;
					render(session);
				} else {
					end(session);
					session.player.closeDialog();
				}
			} catch (RuntimeException error) {
				Logger.getLogger(CharacterSelectView.class.getName()).log(Level.WARNING,
						"Could not refresh character selector for " + session.player.getUniqueId(), error);
				end(session);
				session.player.closeDialog();
			}
		}
	}

	static Session session(UUID owner) {
		return SESSIONS.get(owner);
	}

	private static void end(Session session) {
		session.closed = true;
		SESSIONS.remove(session.player.getUniqueId(), session);
	}

	/** The room dresses everyone as Steve; portraits fall back to the skin worn before it. */
	public static SkinTexture accountSkin(Player player) {
		var saved = net.tfminecraft.rpcharacters.wardrobe.WardrobeCache.getAccountSkin(player);
		return saved == null ? SkinPortrait.texture(player) : SkinPortrait.parseTextures(saved.getValue());
	}

	private static SkinTexture accountTexture(Player player) {
		try {
			return accountTextures.apply(player);
		} catch (RuntimeException unreadable) {
			return null;
		}
	}

	private static void render(Session session) {
		session.painting = true;
		try {
			if (session.memorials) renderMemorials(session);
			else renderRoster(session);
		} finally {
			session.painting = false;
		}
	}

	private static void renderRoster(Session session) {
		Player player = session.player;
		PlayerData data = session.data;
		List<RPCharacter> alive = alive(data);
		RPCharacter selected = focus(session, alive);
		if (selected == null) session.creating = true;

		List<DialogBody> body = new ArrayList<>();
		if (session.notice != null) body.add(text(Component.text(session.notice, WARN)));
		if (session.actions.needsDiscordLink()) {
			body.add(text(link(Component.text("Link Discord to play", TEAL), "Get your Discord link code", session, s -> {
				end(s);
				s.player.closeDialog();
				s.actions.linkDiscord();
			})));
		}
		body.add(DialogBody.plainMessage(strip(session, alive, selected), 360));
		String reason = null;
		if (session.creating) {
			body.add(DialogBody.plainMessage(placeholder("New character"), SkinPortrait.ROW_WIDTH));
			body.add(text(Component.text("Create a new character", TEAL, TextDecoration.BOLD)));
			body.add(text(Component.text("Choose a name, class and race.\nReview your choices before you begin.", MUTED)));
			String blocked = createBlockReason(player, data);
			if (blocked != null) body.add(text(Component.text(blocked, WARN)));
		} else {
			reason = session.actions.blockReason(selected);
			body.add(DialogBody.plainMessage(portrait(session, selected), SkinPortrait.ROW_WIDTH));
			body.add(text(characterCard(selected, reason)));
		}

		List<ActionButton> buttons = new ArrayList<>();
		if (!session.creating) {
			String id = selected.getId();
			Component label = reason == null ? Component.text("▶ Play", TEAL, TextDecoration.BOLD)
					: Component.text("▶ Play", DIM);
			String action = reason == null ? "Play as " + name(selected) : reason;
			buttons.add(button(label, action + "\n" + details(player.getUniqueId(), selected), 110, session,
					s -> play(s, id)));
		}
		else {
			String createBlock = createBlockReason(player, data);
			buttons.add(button(Component.text("Create character", createBlock == null ? TEAL : DIM, TextDecoration.BOLD),
					(createBlock == null ? "Start a new character" : createBlock) + "\n" + slotsUsed(player, alive.size()),
					160, session, CharacterSelectView::create));
		}
		int fallen = memorials(data).size();
		if (fallen > 0) {
			body.add(text(link(Component.text("Memorials (" + fallen + ")", MUTED),
					"Characters who have died or gone missing", session, s -> navigate(s, v -> {
						v.memorials = true;
						v.memorialPage = 0;
					}))));
		}
		// One row of buttons: a shared grid would widen columns to the widest button.
		show(session, Component.text("Choose your character", TEAL, TextDecoration.BOLD), body, buttons, null, 1);
	}

	private static void renderMemorials(Session session) {
		List<RPCharacter> fallen = memorials(session.data);
		int pages = Math.max(1, (fallen.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		int page = Math.max(0, Math.min(session.memorialPage, pages - 1));
		session.memorialPage = page;

		List<Component> cards = new ArrayList<>();
		for (RPCharacter character : page(fallen, page)) cards.add(memorialCard(character));
		List<DialogBody> body = new ArrayList<>();
		body.add(text(cards.isEmpty() ? Component.text("No memorials.", MUTED)
				: Component.join(JoinConfiguration.separator(Component.text("\n\n")), cards)));
		if (pages > 1) body.add(text(Component.text("Page " + (page + 1) + " of " + pages, DIM)));

		List<ActionButton> buttons = new ArrayList<>();
		if (page > 0) buttons.add(button(Component.text("‹ Newer", PALE), null, 80, session,
				s -> navigate(s, v -> v.memorialPage = page - 1)));
		if (page + 1 < pages) buttons.add(button(Component.text("Older ›", PALE), null, 80, session,
				s -> navigate(s, v -> v.memorialPage = page + 1)));
		buttons.add(button(Component.text("Back", TEAL, TextDecoration.BOLD), "Back to your characters", 100, session,
				s -> navigate(s, v -> v.memorials = false)));
		show(session, Component.text("Memorials", PALE, TextDecoration.BOLD), body, buttons, leave(session),
				buttons.size());
	}

	private static ActionButton leave(Session session) {
		return button(Component.text("Leave", MUTED), "Disconnect from the server", 80, session, s -> {
			end(s);
			s.actions.leave();
		});
	}

	private static void show(Session session, Component title, List<DialogBody> body, List<ActionButton> buttons,
			ActionButton exit, int columns) {
		session.player.showDialog(Dialog.create(builder -> builder.empty()
				.base(DialogBase.builder(title).canCloseWithEscape(false).pause(false)
						.afterAction(DialogBase.DialogAfterAction.NONE).body(body).build())
				.type(DialogType.multiAction(buttons, exit, columns))));
	}

	private static DialogBody text(Component contents) {
		return DialogBody.plainMessage(contents, WIDTH);
	}

	/** Keeps the focus on the same character by id; falls back to the most recent one. */
	private static RPCharacter focus(Session session, List<RPCharacter> alive) {
		for (RPCharacter character : alive) {
			if (character.getId().equals(session.selected)) return character;
		}
		RPCharacter first = alive.isEmpty() ? null : alive.getFirst();
		session.selected = first == null ? null : first.getId();
		return first;
	}

	/** Compact roster rows; arrows wrap around every character and the creation tab. */
	private static Component strip(Session session, List<RPCharacter> alive, RPCharacter selected) {
		TextComponent.Builder line = Component.text();
		Component next = alive.isEmpty() ? Component.empty() : link(Component.text("›", PALE, TextDecoration.BOLD), "Next character", session, s -> step(s, 1));
		if (!alive.isEmpty()) line.append(link(Component.text("‹", PALE, TextDecoration.BOLD), "Previous character", session, s -> step(s, -1)));
		int index = 0;
		for (RPCharacter character : alive) {
			if (index > 0 && index % TABS == 0) {
				if (index == TABS) line.append(Component.text("   ")).append(next);
				line.append(Component.newline());
			} else line.append(Component.text("   "));
			line.append(tab(session, character, !session.creating && character == selected));
			index++;
		}
		line.append(Component.text(alive.isEmpty() ? "" : "   ")).append(link(session.creating
				? Component.text("New character", TEAL, TextDecoration.BOLD, TextDecoration.UNDERLINED)
				: Component.text("New character", MUTED), "Create a new character", session, s -> navigate(s, v -> v.creating = true)));
		if (!alive.isEmpty() && alive.size() <= TABS) line.append(Component.text("   ")).append(next);
		return line.build();
	}

	private static Component tab(Session session, RPCharacter character, boolean focused) {
		String id = character.getId();
		String name = name(character);
		String label = name.length() > TAB_NAME ? name.substring(0, TAB_NAME - 1) + "…" : name;
		Component text = focused ? Component.text(label, TEAL, TextDecoration.BOLD, TextDecoration.UNDERLINED)
				: Component.text(label, MUTED);
		return link(text, name, session, s -> navigate(s, v -> { v.selected = id; v.creating = false; }));
	}

	private static void step(Session session, int delta) {
		navigate(session, s -> {
			List<RPCharacter> alive = alive(s.data);
			if (alive.isEmpty()) { s.creating = true; return; }
			int index = s.creating ? alive.size() : alive.indexOf(focus(s, alive));
			int next = Math.floorMod(index + delta, alive.size() + 1);
			s.creating = next == alive.size();
			if (!s.creating) s.selected = alive.get(next).getId();
		});
	}

	private static void navigate(Session session, Consumer<Session> change) {
		session.revision++;
		session.notice = null;
		change.accept(session);
		render(session);
	}

	private static void notice(Session session, String notice) {
		session.revision++;
		session.notice = notice;
		render(session);
	}

	/** Cached bust, or a placeholder while the selected character's skin loads. */
	private static Component portrait(Session session, RPCharacter character) {
		String id = character.getId();
		Component cached = session.portraits.get(id);
		if (cached != null) return cached;
		request(session, id);
		return session.portraits.getOrDefault(id, placeholder(LOADING));
	}

	private static void request(Session session, String id) {
		if (!session.loading.add(id)) return;
		CompletableFuture<BufferedImage> future;
		try {
			future = portraits.load(session.player.getUniqueId(), id, session.accountSkin);
		} catch (RuntimeException failure) {
			future = null;
		}
		if (future == null) future = CompletableFuture.completedFuture(null);
		future.whenComplete((image, error) -> dispatch(() -> arrived(session, id, error == null ? image : null)));
	}

	private static void dispatch(Runnable task) {
		try {
			mainThread.execute(task);
		} catch (RuntimeException disabled) {
			// The plugin is shutting down; the selector is going away with it.
		}
	}

	private static void arrived(Session session, String id, BufferedImage front) {
		session.loading.remove(id);
		if (!session.live()) return;
		session.portraits.put(id, front == null ? placeholder(NO_PORTRAIT) : bust(front));
		if (!session.painting && !session.memorials && !session.creating && Objects.equals(id, session.selected)) render(session);
	}

	public static Component bust(BufferedImage front) {
		return Component.join(JoinConfiguration.newlines(), SkinPortrait.rows(front).subList(0, BUST_ROWS));
	}

	/** A neutral figure with a caption on the last row, the same height as a bust. */
	public static Component placeholder(String caption) {
		List<Component> rows = new ArrayList<>(SkinPortrait.rows(silhouette()).subList(0, BUST_ROWS - 1));
		rows.add(Component.text(caption, DIM));
		return Component.join(JoinConfiguration.newlines(), rows);
	}

	static BufferedImage silhouette() {
		BufferedImage image = new BufferedImage(SkinPortrait.WIDTH, SkinPortrait.HEIGHT, BufferedImage.TYPE_INT_ARGB);
		fill(image, 4, 0, 8, 8, 0xFF46525A);
		fill(image, 4, 8, 8, 12, 0xFF3A4547);
		fill(image, 0, 8, 4, 12, 0xFF333D3F);
		fill(image, 12, 8, 4, 12, 0xFF333D3F);
		return image;
	}

	private static void fill(BufferedImage image, int x, int y, int width, int height, int argb) {
		for (int dy = 0; dy < height; dy++) {
			for (int dx = 0; dx < width; dx++) image.setRGB(x + dx, y + dy, argb);
		}
	}

	private static String slotsUsed(Player player, int alive) {
		return alive + " of " + CharacterSlotService.getMaxAliveCharacters(player) + " character slots used";
	}

	/** Living characters, most recently played first, then memorials. */
	static List<RPCharacter> cards(PlayerData data) {
		List<RPCharacter> cards = new ArrayList<>(alive(data));
		cards.addAll(memorials(data));
		return cards;
	}

	static List<RPCharacter> alive(PlayerData data) {
		return data.getCharacters(Status.ALIVE).stream()
				.sorted(Comparator.comparing(RPCharacter::getLastPlayedAt,
						Comparator.nullsLast(Comparator.<Instant>reverseOrder())).thenComparing(byName()))
				.toList();
	}

	static List<RPCharacter> memorials(PlayerData data) {
		return data.getCharacters().stream().filter(c -> c.getStatus() != Status.ALIVE)
				.sorted(Comparator.comparing(RPCharacter::getDiedAt,
						Comparator.nullsLast(Comparator.<Instant>reverseOrder())).thenComparing(byName()))
				.toList();
	}

	private static Comparator<RPCharacter> byName() {
		return Comparator.comparing(c -> c.getName() == null ? "" : c.getName(), String.CASE_INSENSITIVE_ORDER);
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

	/** The character's guild name, or null when it has none or guilds can't be read. */
	static String guild(RPCharacter character) {
		UUID id;
		try { id = UUID.fromString(character.getId()); }
		catch (IllegalArgumentException | NullPointerException invalid) { return null; }
		String guild = guilds.apply(id);
		return guild == null || guild.isBlank() ? null : guild;
	}

	/** This character's own pouch and bank; empty for a record without a character ID. */
	static String balances(UUID owner, RPCharacter character) {
		CharacterRef ref;
		try {
			ref = new CharacterRef(owner, UUID.fromString(character.getId()));
		} catch (IllegalArgumentException | NullPointerException notACharacterId) {
			return "";
		}
		if (!wallet.available()) return "Pouch and bank: unavailable";
		return "Pouch: " + PaidChangeService.formatCents(wallet.balance(ref, Account.POUCH))
				+ " • Bank: " + PaidChangeService.formatCents(wallet.balance(ref, Account.BANK));
	}

	/** The card keeps to who the character is; the Play tooltip carries the rest. */
	static Component characterCard(RPCharacter character, String blockReason) {
		TextComponent.Builder card = Component.text()
				.append(Component.text(name(character), PALE, TextDecoration.BOLD));
		line(card, joined(race(character), className(character), age(character)), TEAL);
		line(card, joined(guild(character), lastPlayed(character)), MUTED);
		if (blockReason != null) line(card, blockReason, WARN);
		return card.build();
	}

	/** Birthday, playtime and money, one per line. */
	static String details(UUID owner, RPCharacter character) {
		List<String> lines = new ArrayList<>();
		String born = birthday(character);
		if (born != null) lines.add("Born " + born);
		lines.add("Played " + formatPlaytime(character.getOnlinePlaytimeSeconds()));
		String money = balances(owner, character);
		if (!money.isEmpty()) lines.add(money);
		return String.join("\n", lines);
	}

	private static String lastPlayed(RPCharacter character) {
		if (character.getLastPlayedAt() == null) return null;
		long seconds = Duration.between(character.getLastPlayedAt(), Instant.now()).getSeconds();
		return seconds < 3600 ? "Last played <1h ago" : "Last played " + AgeFormatter.formatAge(seconds) + " ago";
	}

	static Component memorialCard(RPCharacter character) {
		String fate = character.getStatus() == Status.MISSING ? "Missing" : "Deceased";
		if (character.getStatus() == Status.DEAD && character.getDiedAt() != null) {
			fate += " " + FantasyCalendar.formatDate(character.getDiedAt().toEpochMilli());
		}
		return Component.text()
				.append(Component.text("† " + name(character), PALE, TextDecoration.ITALIC))
				.append(Component.newline())
				.append(Component.text(joined(race(character), className(character), fate), MUTED))
				.build();
	}

	private static void line(TextComponent.Builder card, String text, TextColor color) {
		if (text.isEmpty()) return;
		card.append(Component.newline()).append(Component.text(text, color));
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
		return birthday(character) == null ? null : "Age " + AgeCalculator.formatAge(character.getBirthday());
	}

	private static String birthday(RPCharacter character) {
		String birthday = character.getBirthday();
		if (birthday == null || birthday.isBlank() || FantasyCalendar.fromIso(birthday) == null) return null;
		return FantasyCalendar.formatBirthday(birthday);
	}

	static String formatPlaytime(int seconds) {
		long minutes = Math.max(0L, seconds) / 60;
		return (minutes / 1440) + "d " + (minutes / 60 % 24) + "h " + (minutes % 60) + "m";
	}

	/** Re-resolves by id so a stale button can't play a character that has since changed. */
	private static void play(Session session, String characterId) {
		RPCharacter character = session.data.getCharacterById(characterId);
		if (character == null || character.getStatus() != Status.ALIVE) {
			notice(session, "That character can no longer be played.");
			return;
		}
		String reason = session.actions.blockReason(character);
		if (reason != null) {
			notice(session, reason);
			return;
		}
		end(session);
		session.player.closeDialog();
		session.actions.play(character);
	}

	private static void create(Session session) {
		String reason = createBlockReason(session.player, session.data);
		if (reason != null) {
			notice(session, reason + ".");
			return;
		}
		end(session);
		session.player.closeDialog();
		session.actions.create();
	}

	/** Body text that runs a guarded callback, like a button. */
	private static Component link(Component label, String hover, Session session, Consumer<Session> onClick) {
		int seen = session.revision;
		return label.hoverEvent(HoverEvent.showText(Component.text(hover)))
				.clickEvent(clickEvents.apply(audience -> {
					if (session.accepts(audience, seen)) onClick.accept(session);
				}, CALLBACKS));
	}

	private static ActionButton button(Component label, String tooltip, int width, Session session,
			Consumer<Session> onClick) {
		int seen = session.revision;
		ActionButton.Builder builder = ActionButton.builder(label).width(width)
				.action(DialogAction.customClick((response, audience) -> {
					if (session.accepts(audience, seen)) onClick.accept(session);
				}, CALLBACKS));
		if (tooltip != null) builder.tooltip(Component.text(tooltip));
		return builder.build();
	}

	/** The guild belonging to the character shown on this card. */
	static String simpleFactionsGuild(UUID characterId) {
		if (!Bukkit.getPluginManager().isPluginEnabled("SimpleFactions")) return null;
		try {
			return SimpleFactionsGuild.name(characterId);
		} catch (NoClassDefFoundError | RuntimeException failure) {
			return null;
		}
	}

	/** Keeps SimpleFactions types out of the view so it loads without SimpleFactions. */
	static final class SimpleFactionsGuild {
		private SimpleFactionsGuild() {}

		static String name(UUID characterId) {
			if (!net.tfminecraft.simplefactions.api.SimpleFactionsApi.ready()) return null;
			net.tfminecraft.simplefactions.guild.Guild guild =
					net.tfminecraft.simplefactions.managers.FactionManager.getGuildByMember(characterId);
			return guild == null || guild.getName() == null ? "" : ClueFormatter.stripColor(guild.getName());
		}
	}
}
