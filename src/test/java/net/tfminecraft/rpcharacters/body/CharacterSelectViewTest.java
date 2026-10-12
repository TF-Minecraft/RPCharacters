package net.tfminecraft.rpcharacters.body;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.RegistryBuilderFactory;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.body.PlainMessageDialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.papermc.paper.registry.data.dialog.type.MultiActionType;
import net.Indyuce.mmocore.MMOCore;
import net.Indyuce.mmocore.api.player.profess.PlayerClass;
import net.Indyuce.mmocore.manager.ClassManager;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.calendar.FantasyCalendar;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.races.Race;
import net.tfminecraft.rpcharacters.paidchange.DenarWallet;
import net.tfminecraft.rpcharacters.paidchange.DenarWallet.Account;
import net.tfminecraft.rpcharacters.persona.CharacterSlotService;
import net.tfminecraft.rpcharacters.persona.PermissionGroupService;
import net.tfminecraft.rpcharacters.playerlist.SkinPortrait;
import net.tfminecraft.rpcharacters.playerlist.SkinPortrait.SkinTexture;
import net.tfminecraft.simplefactions.guild.Guild;
import net.tfminecraft.simplefactions.managers.FactionManager;

class CharacterSelectViewTest {
	private static final UUID OWNER = UUID.randomUUID();
	private static final SkinTexture ACCOUNT_SKIN = new SkinTexture("https://textures.minecraft.net/texture/a", false);

	private RuntimeTestState state;
	private MMOCore oldMmo;
	private Player player;
	private PlayerData data;
	private CharacterSelectView.Actions actions;
	private DenarWallet wallet;
	private MockedStatic<CharacterSlotService> slots;
	private MockedStatic<PermissionGroupService> groups;
	private DialogCapture ui;
	/** Pending portrait requests by character id, in request order. */
	private final Map<String, CompletableFuture<BufferedImage>> loads = new HashMap<>();
	private final List<String> requested = new ArrayList<>();
	private final List<Runnable> mainQueue = new ArrayList<>();
	private final Map<ClickEvent, ClickCallback<Audience>> links = new IdentityHashMap<>();
	private final Map<ClickEvent, ClickCallback.Options> linkOptions = new IdentityHashMap<>();

	@BeforeEach
	void setup() {
		state = new RuntimeTestState(CharacterSelectView.class);
		CharacterSelectView.cleanupAll();
		oldMmo = MMOCore.plugin;
		MMOCore.plugin = null;
		player = mock(Player.class);
		when(player.getUniqueId()).thenReturn(OWNER);
		when(player.getName()).thenReturn("Owner");
		data = new PlayerData(OWNER);
		actions = mock(CharacterSelectView.Actions.class);
		when(actions.isCurrent(player, data)).thenReturn(true);
		wallet = mock(DenarWallet.class);
		when(wallet.available()).thenReturn(true);
		when(wallet.balance(OWNER, Account.POUCH)).thenReturn(new BigDecimal("12.50"));
		when(wallet.balance(OWNER, Account.BANK)).thenReturn(new BigDecimal("300.00"));
		CharacterSelectView.wallet = wallet;
		CharacterSelectView.guilds = p -> "Ironhand";
		CharacterSelectView.accountTextures = p -> p == player ? ACCOUNT_SKIN : null;
		CharacterSelectView.portraits = (owner, id, fallback) -> {
			assertEquals(OWNER, owner);
			requested.add(id + "|" + (fallback == null ? "none" : fallback.url()));
			return loads.computeIfAbsent(id, k -> new CompletableFuture<>());
		};
		CharacterSelectView.mainThread = mainQueue::add;
		CharacterSelectView.clickEvents = (callback, options) -> {
			ClickEvent event = ClickEvent.suggestCommand("link" + links.size());
			links.put(event, callback);
			linkOptions.put(event, options);
			return event;
		};
		slots = mockStatic(CharacterSlotService.class);
		slots.when(() -> CharacterSlotService.getMaxAliveCharacters(any())).thenReturn(4);
		slots.when(() -> CharacterSlotService.hasFreeSlot(any(), eq(data)))
				.thenAnswer(call -> data.getCharacters(Status.ALIVE).size() < 4);
		groups = mockStatic(PermissionGroupService.class);
		ui = new DialogCapture();
	}

	@AfterEach
	void teardown() {
		CharacterSelectView.cleanupAll();
		ui.close();
		groups.close();
		slots.close();
		MMOCore.plugin = oldMmo;
		state.close();
	}

	private RPCharacter character(String id, String name, Status status) {
		RPCharacter character = mock(RPCharacter.class);
		when(character.getId()).thenReturn(id);
		when(character.getName()).thenReturn(name);
		when(character.getSlug()).thenReturn(id);
		when(character.getStatus()).thenReturn(status);
		data.addCharacter(character);
		return character;
	}

	/** Living characters named after their ids, most recently played in the given order. */
	private void roster(String... ids) {
		for (int i = 0; i < ids.length; i++) {
			RPCharacter c = character(ids[i], ids[i], Status.ALIVE);
			when(c.getLastPlayedAt()).thenReturn(Instant.now().minusSeconds(60L * (i + 1)));
		}
	}

	private static String text(Component component) {
		return PlainTextComponentSerializer.plainText().serialize(component);
	}

	private List<String> labels() {
		return ui.last().buttons().stream().map(b -> text(b.label())).toList();
	}

	private ActionButton button(String label) {
		return ui.last().buttons().stream().filter(b -> text(b.label()).equals(label)).findFirst().orElseThrow();
	}

	private void click(String label) {
		ui.click(button(label), player);
	}

	/** The roster strip's clickable parts, by plain label. */
	private Map<String, Component> strip() {
		Map<String, Component> parts = new java.util.LinkedHashMap<>();
		for (Component child : ui.last().strip().children()) {
			if (child.clickEvent() != null) parts.put(text(child), child);
		}
		return parts;
	}

	private List<String> tabs() {
		return List.copyOf(strip().keySet());
	}

	private void link(String label, Audience viewer) {
		links.get(strip().get(label).clickEvent()).accept(viewer);
	}

	private void link(String label) {
		link(label, player);
	}

	private ActionButton exit() {
		return ((MultiActionType) ui.last().type()).exitAction();
	}

	private int columns() {
		return ((MultiActionType) ui.last().type()).columns();
	}

	private void drain() {
		List<Runnable> tasks = new ArrayList<>(mainQueue);
		mainQueue.clear();
		tasks.forEach(Runnable::run);
	}

	private static BufferedImage skin(int argb) {
		BufferedImage image = new BufferedImage(16, 32, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(5, 2, argb);
		return image;
	}

	@Test
	void doesNothingForMissingArgumentsOrAStaleOwner() {
		CharacterSelectView.open(null, data, actions);
		CharacterSelectView.open(player, null, actions);
		CharacterSelectView.open(player, data, null);
		when(actions.isCurrent(player, data)).thenReturn(false);
		CharacterSelectView.open(player, data, actions);
		assertTrue(ui.opened.isEmpty());
		assertNull(CharacterSelectView.session(OWNER));
		verify(player, never()).showDialog(any(Dialog.class));
	}

	@Test
	void focusesTheMostRecentCharacterWithStripBustCardActionsAndSharedFooter() {
		Race race = mock(Race.class);
		when(race.getName()).thenReturn("&6Human");
		RPCharacter wren = character("wren", "Lady Wren", Status.ALIVE);
		when(wren.getRace()).thenReturn(race);
		when(wren.getMMOClass()).thenReturn("SWORD_MASTER");
		String birthday = FantasyCalendar.toIso(FantasyCalendar.getCurrentDate().minusYears(27).minusDays(3));
		when(wren.getBirthday()).thenReturn(birthday);
		when(wren.getOnlinePlaytimeSeconds()).thenReturn(2 * 86400 + 3 * 3600 + 4 * 60);
		when(wren.getLastPlayedAt()).thenReturn(Instant.now().minus(Duration.ofHours(50)));
		when(wren.hasLastLocation()).thenReturn(true);
		when(wren.getLastLocationWorld()).thenReturn("world");
		when(wren.getLastLocationX()).thenReturn(120.7);
		when(wren.getLastLocationY()).thenReturn(64.0);
		when(wren.getLastLocationZ()).thenReturn(-30.2);
		character("rowan", "Rowan", Status.ALIVE);
		RPCharacter old = character("old", "Old Tom", Status.DEAD);
		when(old.getDiedAt()).thenReturn(Instant.parse("2026-09-01T12:00:00Z"));
		character("lost", "Lost Kit", Status.MISSING);

		CharacterSelectView.open(player, data, actions);

		verify(player).showDialog(ui.last().dialog());
		assertEquals("Choose your character", text(ui.last().base().title()));
		assertFalse(ui.last().base().canCloseWithEscape());
		assertEquals(List.of("‹", "Lady Wren", "Rowan", "›"), tabs());
		assertEquals("‹   Lady Wren   Rowan   ›", text(ui.last().strip()));
		assertEquals("Lady Wren", text((Component) strip().get("Lady Wren").hoverEvent().value()));
		assertEquals(HoverEvent.Action.SHOW_TEXT, strip().get("‹").hoverEvent().action());
		assertEquals(SkinPortrait.ROW_WIDTH, ui.last().portraitPart().width());
		assertEquals(CharacterSelectView.placeholder(CharacterSelectView.LOADING), ui.last().portrait());
		assertEquals(List.of("wren|" + ACCOUNT_SKIN.url()), requested);
		assertEquals("‹   Lady Wren   Rowan   ›\nLady Wren\nHuman • Sword master • Age 27, born "
				+ FantasyCalendar.formatBirthday(birthday) + "\nPlayed 2d 3h 4m • Last played 2d 2h ago\n"
				+ "Last location: world 120, 64, -31\n"
				+ "2 of 4 character slots used • Shared by all your characters\n"
				+ "Guild: Ironhand • Pouch: 12.50 denars • Bank: 300 denars", ui.last().bodyText());
		for (var part : ui.last().parts()) {
			if (part != ui.last().portraitPart()) assertEquals(300, part.width());
		}
		assertEquals(List.of("▶ Play", "New character", "Memorials (2)"), labels());
		assertEquals(3, columns());
		assertEquals("Play as Lady Wren", text(button("▶ Play").tooltip()));
		assertEquals("Start a new character", text(button("New character").tooltip()));
		assertEquals(List.of(110, 100, 90), ui.last().buttons().stream().map(ActionButton::width).toList());
		assertEquals("Leave", text(exit().label()));
		assertEquals("Disconnect from the server", text(exit().tooltip()));
	}

	@Test
	void navigationAndPortraitUpdatesKeepTheDialogOpenUntilTheServerReplacesIt() {
		roster("wren", "rowan");
		character("old", "Old Tom", Status.DEAD);
		CharacterSelectView.open(player, data, actions);
		link("rowan");
		loads.get("rowan").complete(skin(0xFFFF0000));
		drain();
		link("‹");
		click("Memorials (1)");
		click("Back");
		assertTrue(ui.opened.size() >= 6);
		for (CapturedDialog dialog : ui.opened) {
			assertFalse(dialog.base().pause(), "NONE is only valid for non-pausing dialogs");
			assertEquals(DialogBase.DialogAfterAction.NONE, dialog.base().afterAction(),
					"navigation must not close the screen and recenter the cursor");
		}
		verify(player, never()).closeDialog();
	}

	@Test
	void checkpointRenewsIdleCallbacksWithoutClosingOrChangingTheFocusedCharacter() {
		roster("wren", "rowan");
		CharacterSelectView.open(player, data, actions);
		link("rowan");
		ActionButton expiredPlay = button("▶ Play");
		int shown = ui.opened.size();
		CharacterSelectView.refreshAll();
		assertEquals(shown + 1, ui.opened.size());
		assertEquals("rowan", CharacterSelectView.session(OWNER).selected);
		ui.click(expiredPlay, player);
		verify(actions, never()).play(any());
		verify(player, never()).closeDialog();
		click("▶ Play");
		verify(actions).play(data.getCharacterById("rowan"));
	}

	@Test
	void checkpointClosesAnInvalidatedSelectorAndShutdownClosesItsDialogs() {
		roster("wren");
		CharacterSelectView.open(player, data, actions);
		when(actions.isCurrent(player, data)).thenReturn(false);
		CharacterSelectView.refreshAll();
		assertNull(CharacterSelectView.session(OWNER));
		verify(player).closeDialog();
		assertEquals(1, ui.opened.size());
		when(actions.isCurrent(player, data)).thenReturn(true);
		CharacterSelectView.open(player, data, actions);
		CharacterSelectView.cleanupAll();
		assertNull(CharacterSelectView.session(OWNER));
		verify(player, times(2)).closeDialog();
	}

	@Test
	void aBrokenSelectorDoesNotPreventOtherPlayersFromRefreshing() {
		roster("wren");
		when(wallet.available()).thenReturn(false);
		CharacterSelectView.portraits = (owner, id, skin) -> CompletableFuture.completedFuture(null);
		CharacterSelectView.open(player, data, actions);
		Player other = mock(Player.class);
		UUID otherOwner = UUID.randomUUID();
		when(other.getUniqueId()).thenReturn(otherOwner);
		when(other.getName()).thenReturn("Other");
		PlayerData otherData = new PlayerData(otherOwner);
		when(actions.isCurrent(other, otherData)).thenReturn(true);
		CharacterSelectView.open(other, otherData, actions);
		int shown = ui.opened.size();
		when(actions.blockReason(any())).thenThrow(new IllegalStateException("cooldown unavailable"));
		assertDoesNotThrow(CharacterSelectView::refreshAll);
		assertNull(CharacterSelectView.session(OWNER));
		assertNotNull(CharacterSelectView.session(otherOwner));
		assertEquals(shown + 1, ui.opened.size());
		verify(player).closeDialog();
		verify(other, never()).closeDialog();
	}

	@Test
	void blockedCharactersShowTheReasonOnTheCardAndAMutedPlayButton() {
		RPCharacter rowan = character("rowan", "Rowan", Status.ALIVE);
		when(actions.blockReason(rowan)).thenReturn("Rowan can be played again in 2d 4h.");
		CharacterSelectView.open(player, data, actions);
		assertTrue(ui.last().bodyText().startsWith("Rowan\nPlayed 0d 0h 0m\nRowan can be played again in 2d 4h.\n"));
		assertEquals("Rowan can be played again in 2d 4h.", text(button("▶ Play").tooltip()));
		assertEquals(CharacterSelectView.DIM, button("▶ Play").label().color());
		assertNull(ui.last().strip());
		click("▶ Play");
		verify(actions, never()).play(any());
		assertTrue(ui.last().bodyText().startsWith("Rowan can be played again in 2d 4h.\nRowan\n"));
		verify(player, never()).closeDialog();
	}

	@Test
	void portraitsLoadOnlyForTheFocusedCharacterOnceAndRepaintWhenStillShown() {
		roster("wren", "rowan", "ash", "birch");
		CharacterSelectView.open(player, data, actions);
		assertEquals(List.of("wren|" + ACCOUNT_SKIN.url()), requested);

		BufferedImage wrenSkin = skin(0xFFFF0000);
		loads.get("wren").complete(wrenSkin);
		assertEquals(1, ui.opened.size());
		drain();
		assertEquals(2, ui.opened.size());
		assertEquals(CharacterSelectView.bust(wrenSkin), ui.last().portrait());

		link("rowan");
		assertEquals(CharacterSelectView.placeholder(CharacterSelectView.LOADING), ui.last().portrait());
		link("wren");
		assertEquals(CharacterSelectView.bust(wrenSkin), ui.last().portrait());
		int shown = ui.opened.size();
		loads.get("rowan").complete(null);
		drain();
		assertEquals(shown, ui.opened.size(), "rowan is no longer focused, so nothing repaints");
		link("rowan");
		assertEquals(CharacterSelectView.placeholder(CharacterSelectView.NO_PORTRAIT), ui.last().portrait());
		link("›");
		link("‹");
		link("wren");
		assertEquals(List.of("wren|" + ACCOUNT_SKIN.url(), "rowan|" + ACCOUNT_SKIN.url(), "ash|" + ACCOUNT_SKIN.url()),
				requested, "each character is requested once per session and never prefetched");
		verify(actions, never()).play(any());
	}

	@Test
	void portraitRepaintKeepsTheViewsButtonsUsable() {
		roster("wren");
		CharacterSelectView.open(player, data, actions);
		ActionButton beforeRepaint = button("▶ Play");
		loads.get("wren").complete(skin(0xFF00FF00));
		drain();
		ui.click(beforeRepaint, player);
		verify(actions).play(data.getCharacterById("wren"));
	}

	@Test
	void failedPortraitsShowANeutralPlaceholderAndAreNotRetried() {
		roster("a", "b", "c", "d");
		CharacterSelectView.PortraitLoader loader = CharacterSelectView.portraits;
		CharacterSelectView.portraits = (owner, id, fallback) -> switch (id) {
			case "a" -> throw new IllegalStateException("offline");
			case "b" -> null;
			case "c" -> CompletableFuture.failedFuture(new IllegalStateException("timeout"));
			default -> loader.load(owner, id, fallback);
		};
		CharacterSelectView.open(player, data, actions);
		drain();
		assertEquals(CharacterSelectView.placeholder(CharacterSelectView.NO_PORTRAIT), ui.last().portrait());
		link("b");
		drain();
		assertEquals(CharacterSelectView.placeholder(CharacterSelectView.NO_PORTRAIT), ui.last().portrait());
		link("c");
		drain();
		assertEquals(CharacterSelectView.placeholder(CharacterSelectView.NO_PORTRAIT), ui.last().portrait());
		assertTrue(text(ui.last().portrait()).endsWith(CharacterSelectView.NO_PORTRAIT));
	}

	@Test
	void portraitsReadyImmediatelyAreShownWithoutASecondDialog() {
		roster("wren");
		BufferedImage wrenSkin = skin(0xFF0000FF);
		loads.put("wren", CompletableFuture.completedFuture(wrenSkin));
		CharacterSelectView.mainThread = Runnable::run;
		CharacterSelectView.open(player, data, actions);
		assertEquals(1, ui.opened.size());
		assertEquals(CharacterSelectView.bust(wrenSkin), ui.last().portrait());
	}

	@Test
	void portraitsArrivingAfterShutdownOrWithoutAMainThreadAreDropped() {
		roster("wren");
		CharacterSelectView.mainThread = task -> { throw new IllegalStateException("plugin disabled"); };
		CharacterSelectView.open(player, data, actions);
		assertDoesNotThrow(() -> loads.get("wren").complete(skin(0xFFFFFFFF)));
		assertEquals(1, ui.opened.size());

		CharacterSelectView.mainThread = mainQueue::add;
		loads.clear();
		CharacterSelectView.open(player, data, actions);
		CharacterSelectView.cleanupAll();
		loads.get("wren").complete(skin(0xFFFFFFFF));
		drain();
		assertEquals(2, ui.opened.size());
	}

	@Test
	void portraitsFallBackToTheSkinWornBeforeTheRoomSteve() {
		var saved = new net.tfminecraft.rpcharacters.wardrobe.SkinTextures(SelectionSkin.STEVE.getValue(), "signed");
		try (var cache = mockStatic(net.tfminecraft.rpcharacters.wardrobe.WardrobeCache.class);
				var profiles = mockStatic(SkinPortrait.class, CALLS_REAL_METHODS)) {
			profiles.when(() -> SkinPortrait.texture(player)).thenReturn(ACCOUNT_SKIN);
			assertSame(ACCOUNT_SKIN, CharacterSelectView.accountSkin(player));
			cache.when(() -> net.tfminecraft.rpcharacters.wardrobe.WardrobeCache.getAccountSkin(player)).thenReturn(saved);
			assertEquals("https://textures.minecraft.net/texture/1d40ce1f42f8bb0e5493822e3812894fa516fc2dc5dda8758128f13299ccb9d7",
					CharacterSelectView.accountSkin(player).url());
		}
	}

	@Test
	void unreadableAccountSkinsAreNotPassedOn() {
		roster("wren");
		CharacterSelectView.accountTextures = p -> { throw new IllegalStateException("no profile"); };
		CharacterSelectView.open(player, data, actions);
		assertEquals(List.of("wren|none"), requested);
	}

	@Test
	void reopeningStartsANewSessionAndRetiresTheOldOnesClicksAndPortraits() {
		roster("wren", "rowan");
		CharacterSelectView.open(player, data, actions);
		CharacterSelectView.Session first = CharacterSelectView.session(OWNER);
		ActionButton oldPlay = button("▶ Play");
		Component oldTab = strip().get("rowan");
		CompletableFuture<BufferedImage> oldLoad = loads.remove("wren");

		CharacterSelectView.open(player, data, actions);
		CharacterSelectView.Session second = CharacterSelectView.session(OWNER);
		assertNotSame(first, second);
		assertTrue(first.closed);
		assertNotEquals(first.id, second.id);
		assertEquals(2, requested.size(), "the new session has its own cache");
		int shown = ui.opened.size();
		oldLoad.complete(skin(0xFFFF0000));
		drain();
		ui.click(oldPlay, player);
		links.get(oldTab.clickEvent()).accept(player);
		assertEquals(shown, ui.opened.size());
		verify(actions, never()).play(any());
		assertFalse(first.live());
	}

	@Test
	void aNewConnectionWithTheSameUuidReplacesTheOldOneAndOnlyItsOwnQuitClosesIt() {
		roster("wren");
		CharacterSelectView.open(player, data, actions);
		ActionButton oldPlay = button("▶ Play");
		Player rejoined = mock(Player.class);
		when(rejoined.getUniqueId()).thenReturn(OWNER);
		CharacterSelectView.Actions next = mock(CharacterSelectView.Actions.class);
		when(next.isCurrent(rejoined, data)).thenReturn(true);
		CharacterSelectView.open(rejoined, data, next);
		verify(rejoined).showDialog(ui.last().dialog());

		ui.click(oldPlay, player);
		verify(actions, never()).play(any());
		CharacterSelectView.close(player);
		CharacterSelectView.close(null);
		assertSame(rejoined, CharacterSelectView.session(OWNER).player);

		ui.click(button("▶ Play"), player);
		verify(next, never()).play(any());
		ui.click(button("▶ Play"), rejoined);
		verify(next).play(data.getCharacterById("wren"));
		assertNull(CharacterSelectView.session(OWNER), "playing ends the session");

		CharacterSelectView.open(rejoined, data, next);
		CharacterSelectView.close(rejoined);
		assertNull(CharacterSelectView.session(OWNER));
	}

	@Test
	void callbacksAreSingleUseTenMinutesAndIgnoreOtherAudiencesAndStaleOwners() {
		RPCharacter wren = character("wren", "Wren", Status.ALIVE);
		when(wren.getLastPlayedAt()).thenReturn(Instant.now());
		character("rowan", "Rowan", Status.ALIVE);
		CharacterSelectView.open(player, data, actions);
		ClickCallback.Options options = ui.options.get(button("▶ Play").action());
		assertEquals(1, options.uses());
		assertEquals(Duration.ofMinutes(10), options.lifetime());
		assertSame(options, linkOptions.get(strip().get("Rowan").clickEvent()));
		ActionButton play = button("▶ Play");

		ui.click(play, Audience.empty());
		ui.click(play, mock(Player.class));
		link("Rowan", mock(Player.class));
		when(actions.isCurrent(player, data)).thenReturn(false);
		ui.click(play, player);
		link("Rowan");
		ui.click(exit(), player);
		verify(actions, never()).play(any());
		verify(actions, never()).leave();
		assertEquals(1, ui.opened.size());

		when(actions.isCurrent(player, data)).thenReturn(true);
		ui.click(play, player);
		verify(actions).play(wren);
		ui.click(exit(), player);
		verify(actions, never()).leave();
	}

	@Test
	void leaveEndsTheSessionBeforeTheOwnerDisconnects() {
		roster("wren");
		doAnswer(call -> { assertNull(CharacterSelectView.session(OWNER)); return null; }).when(actions).leave();
		CharacterSelectView.open(player, data, actions);
		ui.click(exit(), player);
		verify(actions).leave();
	}

	@Test
	void buttonsFromAnEarlierViewAreIgnoredAfterNavigating() {
		roster("wren", "rowan");
		CharacterSelectView.open(player, data, actions);
		ActionButton wrenPlay = button("▶ Play");
		Component oldNext = strip().get("›");
		link("›");
		assertEquals("rowan", CharacterSelectView.session(OWNER).selected);
		int shown = ui.opened.size();
		ui.click(wrenPlay, player);
		links.get(oldNext.clickEvent()).accept(player);
		assertEquals(shown, ui.opened.size());
		verify(actions, never()).play(any());
		verify(player, never()).closeDialog();
		click("▶ Play");
		verify(actions).play(data.getCharacterById("rowan"));
		var handover = inOrder(player, actions);
		handover.verify(player).closeDialog();
		handover.verify(actions).play(data.getCharacterById("rowan"));
	}

	@Test
	void playRechecksTheCharacterAndOwnerBeforeHandingOver() {
		RPCharacter wren = character("wren", "Wren", Status.ALIVE);
		CharacterSelectView.open(player, data, actions);

		when(actions.blockReason(wren)).thenReturn("Wren is on cooldown for 3h.");
		click("▶ Play");
		assertEquals(2, ui.opened.size());
		assertTrue(ui.last().bodyText().startsWith("Wren is on cooldown for 3h.\nWren\n"));

		when(wren.getStatus()).thenReturn(Status.DEAD);
		click("▶ Play");
		assertTrue(ui.last().bodyText().startsWith("That character can no longer be played.\n"));
		when(wren.getStatus()).thenReturn(Status.ALIVE);
		CharacterSelectView.open(player, data, actions);
		ActionButton play = button("▶ Play");
		data.getCharacters().clear();
		ui.click(play, player);
		assertTrue(ui.last().bodyText().startsWith("That character can no longer be played.\nYou have no characters yet.\n"));
		assertNull(ui.last().portrait());
		assertEquals(List.of("New character"), labels());
		assertEquals(1, columns());
		verify(actions, never()).play(any());

		data.addCharacter(wren);
		when(actions.blockReason(wren)).thenReturn(null);
		doAnswer(call -> {
			assertNull(CharacterSelectView.session(OWNER), "the session ends before the owner runs");
			CharacterSelectView.open(player, data, actions);
			return null;
		}).when(actions).play(wren);
		click("New character");
		verify(actions).create();
		var creation = inOrder(player, actions);
		creation.verify(player).closeDialog();
		creation.verify(actions).create();
		CharacterSelectView.open(player, data, actions);
		click("▶ Play");
		verify(actions).play(wren);
		assertNotNull(CharacterSelectView.session(OWNER), "a failed handover can reopen the selector");
	}

	@Test
	void newCharacterFollowsSlotsAndCooldownAndRechecksWhenClicked() {
		character("wren", "Wren", Status.ALIVE);
		CharacterSelectView.open(player, data, actions);
		ActionButton create = button("New character");
		assertEquals(CharacterSelectView.PALE, create.label().color());

		groups.when(() -> PermissionGroupService.hasCharacterSwitchCooldown(player, data)).thenReturn(true);
		groups.when(() -> PermissionGroupService.getRemainingCooldownMinutes(player, data)).thenReturn(1501);
		ui.click(create, player);
		verify(actions, never()).create();
		assertTrue(ui.last().bodyText().startsWith("New character available in 1d 1h 1m.\n"));
		assertEquals("New character available in 1d 1h 1m", text(button("New character").tooltip()));
		assertEquals(CharacterSelectView.DIM, button("New character").label().color());

		when(player.hasPermission(CharacterSelectView.NO_COOLDOWN_PERMISSION)).thenReturn(true);
		CharacterSelectView.open(player, data, actions);
		assertEquals("Start a new character", text(button("New character").tooltip()));

		for (int i = 0; i < 3; i++) character("x" + i, "Extra " + i, Status.ALIVE);
		CharacterSelectView.open(player, data, actions);
		assertEquals("No free character slot", text(button("New character").tooltip()));
		click("New character");
		assertTrue(ui.last().bodyText().startsWith("No free character slot.\n"));
		verify(actions, never()).create();
		assertNotNull(CharacterSelectView.session(OWNER));
		verify(player, never()).closeDialog();
	}

	@Test
	void firstCharacterIgnoresSwitchCooldown() {
		groups.when(() -> PermissionGroupService.hasCharacterSwitchCooldown(player, data)).thenReturn(true);
		assertNull(CharacterSelectView.createBlockReason(player, data));
		slots.when(() -> CharacterSlotService.hasFreeSlot(player, data)).thenReturn(false);
		assertEquals("No free character slot", CharacterSelectView.createBlockReason(player, data));
	}

	@Test
	void rosterStripShowsAWindowOfThreeAndArrowsWrap() {
		roster("a", "b", "c", "d", "e", "f");
		CharacterSelectView.open(player, data, actions);
		assertEquals(List.of("‹", "a", "b", "c", "›"), tabs());
		link("›");
		assertEquals(List.of("‹", "a", "b", "c", "›"), tabs());
		link("›");
		assertEquals(List.of("‹", "b", "c", "d", "›"), tabs());
		link("d");
		assertEquals(List.of("‹", "c", "d", "e", "›"), tabs());
		assertTrue(ui.last().bodyText().contains("\nd\n"));
		link("›");
		link("›");
		assertEquals(List.of("‹", "d", "e", "f", "›"), tabs());
		link("›");
		assertEquals("a", CharacterSelectView.session(OWNER).selected);
		link("‹");
		assertEquals("f", CharacterSelectView.session(OWNER).selected);
		assertEquals(CharacterSelectView.TEAL, strip().get("f").color());
		assertTrue(strip().get("f").hasDecoration(net.kyori.adventure.text.format.TextDecoration.UNDERLINED));
		assertEquals(CharacterSelectView.MUTED, strip().get("e").color());
	}

	@Test
	void focusFollowsTheCharacterThroughReordersAndFallsBackWhenItIsGone() {
		roster("a", "b", "c");
		CharacterSelectView.open(player, data, actions);
		link("c");
		when(data.getCharacterById("c").getLastPlayedAt()).thenReturn(Instant.now());
		link("›");
		assertEquals("a", CharacterSelectView.session(OWNER).selected, "c moved first, so next is a");
		assertEquals(List.of("‹", "c", "a", "b", "›"), tabs());

		when(data.getCharacterById("a").getStatus()).thenReturn(Status.DEAD);
		link("‹");
		assertEquals("b", CharacterSelectView.session(OWNER).selected, "a is gone, so step back from the first living one");
		when(data.getCharacterById("c").getStatus()).thenReturn(Status.MISSING);
		CharacterSelectView.open(player, data, actions);
		assertEquals("b", CharacterSelectView.session(OWNER).selected);
		assertNull(ui.last().strip());

		data.getCharacters().forEach(c -> when(c.getStatus()).thenReturn(Status.ALIVE));
		CharacterSelectView.open(player, data, actions);
		Component next = strip().get("›");
		data.getCharacters().forEach(c -> when(c.getStatus()).thenReturn(Status.DEAD));
		links.get(next.clickEvent()).accept(player);
		assertNull(CharacterSelectView.session(OWNER).selected);
		assertTrue(ui.last().bodyText().startsWith("You have no characters yet.\n"));
		assertEquals(List.of("New character", "Memorials (3)"), labels());
	}

	@Test
	void longNamesAreShortenedInTabsButWrapInFullOnTheCard() {
		roster("a");
		character("long", "Bartholomew the Unyielding of Greywater", Status.ALIVE);
		CharacterSelectView.open(player, data, actions);
		assertEquals(List.of("‹", "a", "Bartholome…", "›"), tabs());
		assertEquals("Bartholomew the Unyielding of Greywater",
				text((Component) strip().get("Bartholome…").hoverEvent().value()));
		link("Bartholome…");
		assertTrue(ui.last().bodyText().contains("\nBartholomew the Unyielding of Greywater\nPlayed"));
	}

	@Test
	void memorialsHaveTheirOwnPagedViewAndBack() {
		roster("wren");
		for (int i = 0; i < 8; i++) {
			RPCharacter c = character("d" + i, "Fallen " + i, Status.DEAD);
			when(c.getDiedAt()).thenReturn(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i * 86400L));
		}
		CharacterSelectView.open(player, data, actions);
		click("Memorials (8)");
		assertEquals("Memorials", text(ui.last().base().title()));
		String page = ui.last().bodyText();
		assertTrue(page.startsWith("† Fallen 7\nDeceased "));
		assertTrue(page.contains("\n\n† Fallen 6\n"));
		assertFalse(page.contains("Fallen 1"));
		assertTrue(page.endsWith("\nPage 1 of 2"));
		assertEquals(List.of("Older ›", "Back"), labels());
		assertEquals(2, columns());
		assertEquals("Leave", text(exit().label()));
		assertNull(button("Older ›").tooltip());

		int shown = ui.opened.size();
		loads.get("wren").complete(skin(0xFFFF0000));
		drain();
		assertEquals(shown, ui.opened.size(), "portraits don't repaint over memorials");

		click("Older ›");
		assertTrue(ui.last().bodyText().startsWith("† Fallen 1\n"));
		assertTrue(ui.last().bodyText().endsWith("Page 2 of 2"));
		assertEquals(List.of("‹ Newer", "Back"), labels());
		data.getCharacters().removeIf(c -> c.getStatus() == Status.DEAD && !c.getId().equals("d7"));
		click("‹ Newer");
		assertEquals("† Fallen 7\nDeceased " + FantasyCalendar.formatDate(
				Instant.parse("2026-01-08T00:00:00Z").toEpochMilli()), ui.last().bodyText());
		assertEquals(List.of("Back"), labels());
		data.getCharacters().removeIf(c -> c.getStatus() == Status.DEAD);
		CharacterSelectView.session(OWNER).memorialPage = 5;
		click("Back");
		assertEquals("Choose your character", text(ui.last().base().title()));
		assertEquals(List.of("▶ Play", "New character"), labels());
		assertEquals(CharacterSelectView.bust(skin(0xFFFF0000)), ui.last().portrait());
	}

	@Test
	void memorialsEmptiedWhileOpenSayNoMemorials() {
		for (int i = 0; i < 7; i++) character("d" + i, "Fallen " + i, Status.DEAD);
		CharacterSelectView.open(player, data, actions);
		assertEquals(List.of("New character", "Memorials (7)"), labels());
		click("Memorials (7)");
		click("Older ›");
		data.getCharacters().clear();
		click("‹ Newer");
		assertEquals("No memorials.", ui.last().bodyText());
		assertEquals(List.of("Back"), labels());
		click("Back");
		assertEquals(List.of("New character"), labels());
		click("New character");
		verify(actions).create();
	}

	@Test
	void ordersLivingByLastPlayedThenNameAndMemorialsByDeath() {
		RPCharacter beta = character("b", "beta", Status.ALIVE);
		RPCharacter alpha = character("a", "Alpha", Status.ALIVE);
		RPCharacter recent = character("r", "Zed", Status.ALIVE);
		when(recent.getLastPlayedAt()).thenReturn(Instant.now());
		RPCharacter unnamed = character("u", null, Status.ALIVE);
		RPCharacter older = character("o", "Older", Status.DEAD);
		when(older.getDiedAt()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
		RPCharacter newer = character("n", "Newer", Status.DEAD);
		when(newer.getDiedAt()).thenReturn(Instant.parse("2026-06-01T00:00:00Z"));
		RPCharacter missing = character("m", "Missing", Status.MISSING);
		assertEquals(List.of(recent, unnamed, alpha, beta, newer, older, missing), CharacterSelectView.cards(data));
		assertEquals("Unnamed\nPlayed 0d 0h 0m", text(CharacterSelectView.characterCard(unnamed, null)));
		when(unnamed.getName()).thenReturn(" ");
		assertTrue(text(CharacterSelectView.characterCard(unnamed, null)).startsWith("Unnamed"));
		assertEquals(List.of(), CharacterSelectView.page(List.of(), 3));
	}

	@Test
	void accountValuesSayUnavailableInsteadOfZeroAndOmitUnreadableGuild() {
		when(wallet.available()).thenReturn(false);
		CharacterSelectView.guilds = p -> null;
		assertEquals("Shared by all your characters\nPouch and bank: unavailable", text(CharacterSelectView.accountSummary(player)));
		verify(wallet, never()).balance(any(), any());
		CharacterSelectView.guilds = p -> "";
		assertEquals("Shared by all your characters\nGuild: none • Pouch and bank: unavailable",
				text(CharacterSelectView.accountSummary(player)));
	}

	@Test
	void cardsSkipBirthdaysThatCannotBeRead() {
		RPCharacter c = character("c", "Cora", Status.ALIVE);
		when(c.getBirthday()).thenReturn(" ");
		assertEquals("Cora\nPlayed 0d 0h 0m", text(CharacterSelectView.characterCard(c, null)));
		when(c.getBirthday()).thenReturn("not a date");
		assertEquals("Cora\nPlayed 0d 0h 0m", text(CharacterSelectView.characterCard(c, null)));
	}

	@Test
	void classNamesComeFromMmoCoreWithTheStoredIdAsFallback() throws ReflectiveOperationException {
		RPCharacter c = character("c", "Cora", Status.ALIVE);
		assertNull(CharacterSelectView.className(c));
		when(c.getMMOClass()).thenReturn(" ");
		assertNull(CharacterSelectView.className(c));
		when(c.getMMOClass()).thenReturn("ROGUE");
		assertEquals("Rogue", CharacterSelectView.className(c));

		MMOCore.plugin = mock(MMOCore.class);
		ClassManager classes = mock(ClassManager.class);
		Field field = MMOCore.class.getField("classManager");
		field.setAccessible(true);
		field.set(MMOCore.plugin, classes);
		PlayerClass rogue = mock(PlayerClass.class);
		when(rogue.getName()).thenReturn("&aShadow Rogue");
		when(classes.get("ROGUE")).thenReturn(rogue);
		assertEquals("Shadow Rogue", CharacterSelectView.className(c));
		when(rogue.getName()).thenReturn(null);
		assertEquals("Rogue", CharacterSelectView.className(c));
		when(classes.get("ROGUE")).thenReturn(null);
		assertEquals("Rogue", CharacterSelectView.className(c));
	}

	@Test
	void memorialsShowDeathWithoutDateWhenUnrecorded() {
		RPCharacter c = character("c", "Cora", Status.DEAD);
		assertEquals("† Cora\nDeceased", text(CharacterSelectView.memorialCard(c)));
		when(c.getStatus()).thenReturn(Status.MISSING);
		assertEquals("† Cora\nMissing", text(CharacterSelectView.memorialCard(c)));
	}

	@Test
	void formatsPlaytimeInDaysHoursAndMinutes() {
		assertEquals("0d 0h 0m", CharacterSelectView.formatPlaytime(-5));
		assertEquals("0d 0h 1m", CharacterSelectView.formatPlaytime(60));
		assertEquals("1d 1h 1m", CharacterSelectView.formatPlaytime(90060));
	}

	@Test
	void placeholderIsANeutralFigureTheHeightOfABust() {
		BufferedImage figure = CharacterSelectView.silhouette();
		assertEquals(0, figure.getRGB(0, 0) >>> 24, "corners stay clear");
		assertEquals(0xFF46525A, figure.getRGB(6, 3));
		assertEquals(0xFF333D3F, figure.getRGB(1, 10));
		assertEquals(0, figure.getRGB(6, 25) >>> 24, "no legs below the bust");
		Component loading = CharacterSelectView.placeholder(CharacterSelectView.LOADING);
		assertEquals(CharacterSelectView.BUST_ROWS - 1, text(loading).chars().filter(ch -> ch == '\n').count());
		assertEquals(CharacterSelectView.BUST_ROWS - 1,
				text(CharacterSelectView.bust(figure)).chars().filter(ch -> ch == '\n').count());
	}

	@Test
	void guildLookupUsesSimpleFactionsOnlyWhenEnabled() {
		PluginManager plugins = mock(PluginManager.class);
		try (var bukkit = mockStatic(Bukkit.class); var factions = mockStatic(FactionManager.class)) {
			bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
			assertNull(CharacterSelectView.simpleFactionsGuild(player));
			factions.verifyNoInteractions();

			when(plugins.isPluginEnabled("SimpleFactions")).thenReturn(true);
			assertEquals("", CharacterSelectView.simpleFactionsGuild(player));
			Guild guild = mock(Guild.class);
			factions.when(() -> FactionManager.getGuildByMember("Owner")).thenReturn(guild);
			assertEquals("", CharacterSelectView.simpleFactionsGuild(player));
			when(guild.getName()).thenReturn("&6Ironhand");
			assertEquals("Ironhand", CharacterSelectView.simpleFactionsGuild(player));
			factions.when(() -> FactionManager.getGuildByMember("Owner")).thenThrow(new IllegalStateException("loading"));
			assertNull(CharacterSelectView.simpleFactionsGuild(player));
		}
	}

	@Test
	void defaultsReadDenarEconomySimpleFactionsAndTheBukkitMainThread() {
		state.close();
		state = new RuntimeTestState(CharacterSelectView.class);
		assertEquals("net.tfminecraft.rpcharacters.paidchange.DenarEconomyWallet",
				CharacterSelectView.wallet.getClass().getName());
		PluginManager plugins = mock(PluginManager.class);
		BukkitScheduler scheduler = mock(BukkitScheduler.class);
		Runnable task = () -> {};
		try (var bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
			bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
			assertNull(CharacterSelectView.guilds.apply(player));
			CharacterSelectView.mainThread.execute(task);
			verify(scheduler).runTask(RPCharacters.plugin, task);
		}
	}

	/** Captures dialogs built through Paper's static builders. */
	static final class DialogCapture implements AutoCloseable {
		final List<CapturedDialog> opened = new ArrayList<>();
		final Map<DialogAction, DialogActionCallback> callbacks = new IdentityHashMap<>();
		final Map<DialogAction, ClickCallback.Options> options = new IdentityHashMap<>();
		private final List<MockedStatic<?>> statics = new ArrayList<>();

		DialogCapture() {
			var buttons = mockStatic(ActionButton.class);
			statics.add(buttons);
			buttons.when(() -> ActionButton.builder(any(Component.class))).thenAnswer(call -> {
				var button = mock(ActionButton.class);
				when(button.label()).thenReturn(call.getArgument(0));
				var builder = mock(ActionButton.Builder.class, RETURNS_SELF);
				doAnswer(c -> { when(button.tooltip()).thenReturn(c.getArgument(0)); return builder; }).when(builder).tooltip(any());
				doAnswer(c -> { when(button.width()).thenReturn(c.getArgument(0)); return builder; }).when(builder).width(anyInt());
				doAnswer(c -> { when(button.action()).thenReturn(c.getArgument(0)); return builder; }).when(builder).action(any());
				when(builder.build()).thenReturn(button);
				return builder;
			});
			var bases = mockStatic(DialogBase.class);
			statics.add(bases);
			bases.when(() -> DialogBase.builder(any(Component.class))).thenAnswer(call -> {
				var base = mock(DialogBase.class);
				when(base.title()).thenReturn(call.getArgument(0));
				when(base.pause()).thenReturn(true);
				when(base.afterAction()).thenReturn(DialogBase.DialogAfterAction.CLOSE);
				var builder = mock(DialogBase.Builder.class, RETURNS_SELF);
				doAnswer(c -> { when(base.pause()).thenReturn(c.getArgument(0)); return builder; }).when(builder).pause(anyBoolean());
				doAnswer(c -> { when(base.afterAction()).thenReturn(c.getArgument(0)); return builder; }).when(builder).afterAction(any());
				doAnswer(c -> { when(base.canCloseWithEscape()).thenReturn(c.getArgument(0)); return builder; }).when(builder).canCloseWithEscape(anyBoolean());
				doAnswer(c -> { when(base.body()).thenReturn(List.copyOf(c.getArgument(0))); return builder; }).when(builder).body(anyList());
				when(builder.build()).thenReturn(base);
				return builder;
			});
			var bodies = mockStatic(DialogBody.class);
			statics.add(bodies);
			bodies.when(() -> DialogBody.plainMessage(any(Component.class), anyInt())).thenAnswer(call -> {
				var body = mock(PlainMessageDialogBody.class);
				when(body.contents()).thenReturn(call.getArgument(0));
				when(body.width()).thenReturn(call.getArgument(1));
				return body;
			});
			var actions = mockStatic(DialogAction.class);
			statics.add(actions);
			actions.when(() -> DialogAction.customClick(any(DialogActionCallback.class), any(ClickCallback.Options.class))).thenAnswer(call -> {
				var action = mock(DialogAction.CustomClickAction.class);
				callbacks.put(action, call.getArgument(0));
				options.put(action, call.getArgument(1));
				return action;
			});
			var types = mockStatic(DialogType.class);
			statics.add(types);
			types.when(() -> DialogType.multiAction(anyList(), any(), anyInt())).thenAnswer(call -> {
				var type = mock(MultiActionType.class);
				when(type.actions()).thenReturn(List.copyOf(call.getArgument(0)));
				when(type.exitAction()).thenReturn(call.getArgument(1));
				when(type.columns()).thenReturn(call.getArgument(2));
				return type;
			});
			var dialogs = mockStatic(Dialog.class);
			statics.add(dialogs);
			dialogs.when(() -> Dialog.create(any())).thenAnswer(call -> {
				var entry = mock(DialogRegistryEntry.Builder.class, RETURNS_SELF);
				doAnswer(c -> { when(entry.base()).thenReturn(c.getArgument(0)); return entry; }).when(entry).base(any());
				doAnswer(c -> { when(entry.type()).thenReturn(c.getArgument(0)); return entry; }).when(entry).type(any());
				@SuppressWarnings("unchecked")
				RegistryBuilderFactory<Dialog, DialogRegistryEntry.Builder> factory = mock(RegistryBuilderFactory.class);
				when(factory.empty()).thenReturn(entry);
				java.util.function.Consumer<RegistryBuilderFactory<Dialog, DialogRegistryEntry.Builder>> config = call.getArgument(0);
				config.accept(factory);
				var dialog = mock(Dialog.class);
				opened.add(new CapturedDialog(dialog, entry.base(), entry.type()));
				return dialog;
			});
		}

		CapturedDialog last() {
			return opened.getLast();
		}

		void click(ActionButton button, Audience viewer) {
			callbacks.get(button.action()).accept(null, viewer);
		}

		@Override
		public void close() {
			for (var s : statics.reversed()) s.close();
		}
	}

	record CapturedDialog(Dialog dialog, DialogBase base, DialogType type) {
		List<ActionButton> buttons() {
			return ((MultiActionType) type).actions();
		}

		List<PlainMessageDialogBody> parts() {
			return base.body().stream().map(b -> (PlainMessageDialogBody) b).toList();
		}

		PlainMessageDialogBody portraitPart() {
			return parts().stream().filter(p -> p.width() == SkinPortrait.ROW_WIDTH).findFirst().orElse(null);
		}

		Component portrait() {
			PlainMessageDialogBody part = portraitPart();
			return part == null ? null : part.contents();
		}

		/** The roster strip, or null when it is not shown. */
		Component strip() {
			return parts().stream().map(PlainMessageDialogBody::contents).filter(c -> text(c).startsWith("‹"))
					.findFirst().orElse(null);
		}

		/** Body text without the portrait. */
		String bodyText() {
			return parts().stream().filter(p -> p != portraitPart()).map(p -> text(p.contents()))
					.collect(Collectors.joining("\n"));
		}
	}
}
