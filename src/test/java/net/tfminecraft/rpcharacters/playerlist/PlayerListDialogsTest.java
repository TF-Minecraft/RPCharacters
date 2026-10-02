package net.tfminecraft.rpcharacters.playerlist;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.bukkit.entity.Player;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ObjectComponent;
import net.kyori.adventure.text.TextComponent;

class PlayerListDialogsTest {

	@Test
	void primaryGroupWinsOverInheritedStaffPermission() {
		Player player = mock(Player.class);
		UUID id = UUID.randomUUID();
		when(player.getUniqueId()).thenReturn(id);
		when(player.isPermissionSet("group.staff")).thenReturn(true);
		when(player.hasPermission("group.staff")).thenReturn(true);
		Map<String, String> tags = new LinkedHashMap<>();
		tags.put("staff", "[Staff]");
		tags.put("staff_player", "[Commoner]");
		var settings = new PlayerListSettings("p", true, true, "t", "h", "q", tags);
		LuckPerms api = mock(LuckPerms.class);
		UserManager users = mock(UserManager.class);
		User user = mock(User.class);
		when(api.getUserManager()).thenReturn(users);
		when(users.getUser(id)).thenReturn(user);
		try (var provider = mockStatic(LuckPermsProvider.class)) {
			provider.when(LuckPermsProvider::get).thenReturn(api);
			when(user.getPrimaryGroup()).thenReturn("staff_player");
			assertEquals("[Commoner]", settings.tag(PlayerListDialogs.rank(player, settings)));
			// An unlisted primary group must not promote an inherited staff role.
			when(user.getPrimaryGroup()).thenReturn("unlisted");
			assertNull(PlayerListDialogs.rank(player, settings));
			when(users.getUser(id)).thenReturn(null);
			assertNull(PlayerListDialogs.rank(player, settings));
			// Permission matching is retained only when the provider is unavailable.
			provider.when(LuckPermsProvider::get).thenThrow(new IllegalStateException("Unavailable"));
			assertEquals("staff", PlayerListDialogs.rank(player, settings));
		}
	}

	@Test
	void profileTextCannotChangePortraitRows() {
		BufferedImage front = new BufferedImage(16, 32, BufferedImage.TYPE_INT_ARGB);
		var shortCard = PlayerListDialogs.sheetSections(List.of("Short"), front);
		String longLine = "Wide custom-font profile text ".repeat(20);
		var longCard = PlayerListDialogs.sheetSections(List.of("§l(Name)", longLine), front);
		assertEquals(2, longCard.size());
		assertEquals(shortCard.get(0), longCard.get(0));
		assertEquals(512, objects(longCard.get(0)));
		assertEquals(("\u3000\u3000\n").repeat(31) + "\u3000\u3000", text(longCard.get(0)));
		// No padding or server-estimated wraps: the client lays out its own font.
		assertEquals("(Name)\n" + longLine, text(longCard.get(1)));
		assertEquals(0, objects(longCard.get(1)));
	}

	@Test
	void unavailablePortraitStillShowsProfile() {
		var sections = PlayerListDialogs.sheetSections(List.of("§aName", "Description"), null);
		assertEquals(1, sections.size());
		assertEquals("Name\nDescription", text(sections.get(0)));
	}

	private static String text(Component component) {
		StringBuilder out = new StringBuilder(component instanceof TextComponent text ? text.content() : "");
		for (Component child : component.children()) out.append(text(child));
		return out.toString();
	}

	private static int objects(Component component) {
		return (component instanceof ObjectComponent ? 1 : 0)
				+ component.children().stream().mapToInt(PlayerListDialogsTest::objects).sum();
	}

	/** Paper's registry implementation is external; retain all submitted UI data and callbacks. */
	static final class DialogCapture implements AutoCloseable {
		final java.util.List<CapturedDialog> opened = new java.util.ArrayList<>();
		final java.util.Map<io.papermc.paper.registry.data.dialog.action.DialogAction, io.papermc.paper.registry.data.dialog.action.DialogActionCallback> callbacks = new java.util.IdentityHashMap<>();
		final java.util.List<org.mockito.MockedStatic<?>> statics = new java.util.ArrayList<>();
		DialogCapture() {
			var buttons = mockStatic(io.papermc.paper.registry.data.dialog.ActionButton.class); statics.add(buttons);
			buttons.when(() -> io.papermc.paper.registry.data.dialog.ActionButton.builder(any(Component.class))).thenAnswer(call -> {
				var button = mock(io.papermc.paper.registry.data.dialog.ActionButton.class);
				when(button.label()).thenReturn(call.getArgument(0));
				var builder = mock(io.papermc.paper.registry.data.dialog.ActionButton.Builder.class, RETURNS_SELF);
				doAnswer(c -> { when(button.tooltip()).thenReturn(c.getArgument(0)); return builder; }).when(builder).tooltip(any());
				doAnswer(c -> { when(button.width()).thenReturn(c.getArgument(0)); return builder; }).when(builder).width(anyInt());
				doAnswer(c -> { when(button.action()).thenReturn(c.getArgument(0)); return builder; }).when(builder).action(any());
				when(builder.build()).thenReturn(button); return builder;
			});
			var bases = mockStatic(io.papermc.paper.registry.data.dialog.DialogBase.class); statics.add(bases);
			bases.when(() -> io.papermc.paper.registry.data.dialog.DialogBase.builder(any(Component.class))).thenAnswer(call -> {
				var base = mock(io.papermc.paper.registry.data.dialog.DialogBase.class);
				when(base.title()).thenReturn(call.getArgument(0));
				var builder = mock(io.papermc.paper.registry.data.dialog.DialogBase.Builder.class, RETURNS_SELF);
				doAnswer(c -> { when(base.externalTitle()).thenReturn(c.getArgument(0)); return builder; }).when(builder).externalTitle(any());
				doAnswer(c -> { when(base.canCloseWithEscape()).thenReturn(c.getArgument(0)); return builder; }).when(builder).canCloseWithEscape(anyBoolean());
				doAnswer(c -> { when(base.body()).thenReturn(List.copyOf(c.getArgument(0))); return builder; }).when(builder).body(anyList());
				when(builder.build()).thenReturn(base); return builder;
			});
			var bodies = mockStatic(io.papermc.paper.registry.data.dialog.body.DialogBody.class); statics.add(bodies);
			bodies.when(() -> io.papermc.paper.registry.data.dialog.body.DialogBody.plainMessage(any(Component.class), anyInt())).thenAnswer(call -> {
				var body = mock(io.papermc.paper.registry.data.dialog.body.PlainMessageDialogBody.class);
				when(body.contents()).thenReturn(call.getArgument(0)); when(body.width()).thenReturn(call.getArgument(1)); return body;
			});
			var actions = mockStatic(io.papermc.paper.registry.data.dialog.action.DialogAction.class); statics.add(actions);
			actions.when(() -> io.papermc.paper.registry.data.dialog.action.DialogAction.customClick(any(io.papermc.paper.registry.data.dialog.action.DialogActionCallback.class), any(net.kyori.adventure.text.event.ClickCallback.Options.class))).thenAnswer(call -> {
				var action = mock(io.papermc.paper.registry.data.dialog.action.DialogAction.CustomClickAction.class);
				callbacks.put(action, call.getArgument(0)); return action;
			});
			var types = mockStatic(io.papermc.paper.registry.data.dialog.type.DialogType.class); statics.add(types);
			types.when(() -> io.papermc.paper.registry.data.dialog.type.DialogType.multiAction(anyList(), any(), anyInt())).thenAnswer(call -> {
				var type = mock(io.papermc.paper.registry.data.dialog.type.MultiActionType.class);
				when(type.actions()).thenReturn(List.copyOf(call.getArgument(0))); when(type.exitAction()).thenReturn(call.getArgument(1)); when(type.columns()).thenReturn(call.getArgument(2)); return type;
			});
			types.when(() -> io.papermc.paper.registry.data.dialog.type.DialogType.notice(any())).thenAnswer(call -> {
				var type = mock(io.papermc.paper.registry.data.dialog.type.NoticeType.class); when(type.action()).thenReturn(call.getArgument(0)); return type;
			});
			var dialogs = mockStatic(io.papermc.paper.dialog.Dialog.class); statics.add(dialogs);
			dialogs.when(() -> io.papermc.paper.dialog.Dialog.create(any())).thenAnswer(call -> {
				var entry = mock(io.papermc.paper.registry.data.dialog.DialogRegistryEntry.Builder.class, RETURNS_SELF);
				doAnswer(c -> { when(entry.base()).thenReturn(c.getArgument(0)); return entry; }).when(entry).base(any());
				doAnswer(c -> { when(entry.type()).thenReturn(c.getArgument(0)); return entry; }).when(entry).type(any());
				@SuppressWarnings("unchecked") io.papermc.paper.registry.RegistryBuilderFactory<io.papermc.paper.dialog.Dialog, io.papermc.paper.registry.data.dialog.DialogRegistryEntry.Builder> factory = mock(io.papermc.paper.registry.RegistryBuilderFactory.class);
				when(factory.empty()).thenReturn(entry);
				java.util.function.Consumer<io.papermc.paper.registry.RegistryBuilderFactory<io.papermc.paper.dialog.Dialog, io.papermc.paper.registry.data.dialog.DialogRegistryEntry.Builder>> config = call.getArgument(0);
				config.accept(factory);
				var dialog = mock(io.papermc.paper.dialog.Dialog.class);
				opened.add(new CapturedDialog(dialog, entry.base(), entry.type())); return dialog;
			});
		}
		CapturedDialog last() { return opened.getLast(); }
		void click(io.papermc.paper.registry.data.dialog.ActionButton button, net.kyori.adventure.audience.Audience viewer) { callbacks.get(button.action()).accept(null, viewer); }
		@Override public void close() { for (var s : statics.reversed()) s.close(); }
	}
	static record CapturedDialog(io.papermc.paper.dialog.Dialog dialog, io.papermc.paper.registry.data.dialog.DialogBase base, io.papermc.paper.registry.data.dialog.type.DialogType type) {
		List<io.papermc.paper.registry.data.dialog.ActionButton> buttons() { return ((io.papermc.paper.registry.data.dialog.type.MultiActionType) type).actions(); }
		String bodyText() { return base.body().stream().map(b -> text(((io.papermc.paper.registry.data.dialog.body.PlainMessageDialogBody) b).contents())).collect(java.util.stream.Collectors.joining("\n")); }
	}

	@org.junit.jupiter.api.Nested
	class RuntimeCoverage {
		net.tfminecraft.rpcharacters.RuntimeTestState state;
		@org.junit.jupiter.api.BeforeEach void setup() {
			org.mockbukkit.mockbukkit.MockBukkit.mock();
			state = new net.tfminecraft.rpcharacters.RuntimeTestState(net.tfminecraft.rpcharacters.RPCharacters.class, SkinPortrait.class);
			net.tfminecraft.rpcharacters.Cache.playerList = new PlayerListSettings("list", true, true, "&aPlayers", "Online: {online}", "Players", new LinkedHashMap<>(Map.of("staff", "[Staff]")));
			net.tfminecraft.rpcharacters.Cache.profilePermission = "profile";
		}
		@org.junit.jupiter.api.AfterEach void teardown() { state.close(); org.mockbukkit.mockbukkit.MockBukkit.unmock(); }
		Player player(String name, int ping) {
			Player p = mock(Player.class); when(p.getUniqueId()).thenReturn(UUID.randomUUID()); when(p.getName()).thenReturn(name); when(p.getPing()).thenReturn(ping); when(p.isOnline()).thenReturn(true); when(p.hasPermission("list")).thenReturn(true);
			var profile = mock(com.destroystokyo.paper.profile.PlayerProfile.class);
			when(profile.getProperties()).thenReturn(new java.util.LinkedHashSet<>(List.of(new com.destroystokyo.paper.profile.ProfileProperty("ignored", "x"), new com.destroystokyo.paper.profile.ProfileProperty("textures", "value", "signed"))));
			when(p.getPlayerProfile()).thenReturn(profile); return p;
		}
		@Test void onlineListSortsVisibleCharactersShowsAccountRankPingAndRechecksClickVisibility() {
			Player viewer = player("Viewer", 20), staff = player("Staff", 100), hidden = player("Hidden", 350), other = player("Other", 200);
			when(viewer.canSee(staff)).thenReturn(true); when(viewer.canSee(other)).thenReturn(true); when(viewer.hasPermission("profile")).thenReturn(true);
			when(staff.isPermissionSet("group.staff")).thenReturn(true); when(staff.hasPermission("group.staff")).thenReturn(true);
			try (DialogCapture ui = new DialogCapture(); var bukkit = mockStatic(org.bukkit.Bukkit.class, CALLS_REAL_METHODS); var lp = mockStatic(LuckPermsProvider.class); var identities = mockStatic(net.tfminecraft.rpcharacters.identity.DisplayIdentityService.class); var profiles = mockStatic(net.tfminecraft.rpcharacters.profile.ProfileManager.class)) {
				lp.when(LuckPermsProvider::get).thenThrow(new IllegalStateException("No provider"));
				bukkit.when(org.bukkit.Bukkit::getOnlinePlayers).thenReturn(List.of(hidden, other, viewer, staff));
				for (Player p : List.of(viewer, staff, other, hidden)) { String display = "§a" + p.getName(); UUID id = p.getUniqueId(); identities.when(() -> net.tfminecraft.rpcharacters.identity.DisplayIdentityService.resolveDisplaySafe(p)).thenReturn(display); bukkit.when(() -> org.bukkit.Bukkit.getPlayer(id)).thenReturn(p); }
				PlayerListDialogs.openList(viewer);
				CapturedDialog list = ui.last(); verify(viewer).showDialog(list.dialog());
				assertEquals("Players", text(list.base().title())); assertEquals("Online: 3", list.bodyText()); assertTrue(list.base().canCloseWithEscape());
				assertEquals(List.of(" [Staff] - Staff", " Other", " Viewer"), list.buttons().stream().map(b -> text(b.label())).toList());
				assertTrue(text(list.buttons().getFirst().tooltip()).contains("(Staff)\n[Staff]  |  100ms\nClick to view their character"));
				assertTrue(text(list.buttons().get(1).tooltip()).contains("No rank  |  200ms"));
				assertTrue(list.buttons().stream().allMatch(b -> b.width() >= 100 && b.width() <= 220)); assertEquals(1, objects(list.buttons().getFirst().label()));
				ui.click(list.buttons().getFirst(), viewer); profiles.verify(() -> net.tfminecraft.rpcharacters.profile.ProfileManager.showProfileSheet(viewer, staff));
				profiles.clearInvocations(); when(viewer.canSee(staff)).thenReturn(false); ui.click(list.buttons().getFirst(), viewer);
				bukkit.when(() -> org.bukkit.Bukkit.getPlayer(staff.getUniqueId())).thenReturn(null); ui.click(list.buttons().getFirst(), viewer); ui.click(list.buttons().getFirst(), net.kyori.adventure.audience.Audience.empty()); profiles.verifyNoInteractions();
			}
		}
		@Test void emptyListAndProfilePermissionDoNotAddMisleadingActions() {
			Player viewer = player("Viewer", 350);
			try (DialogCapture ui = new DialogCapture(); var bukkit = mockStatic(org.bukkit.Bukkit.class, CALLS_REAL_METHODS); var lp = mockStatic(LuckPermsProvider.class); var identities = mockStatic(net.tfminecraft.rpcharacters.identity.DisplayIdentityService.class)) {
				lp.when(LuckPermsProvider::get).thenThrow(new NoClassDefFoundError("optional")); bukkit.when(org.bukkit.Bukkit::getOnlinePlayers).thenReturn(List.of());
				PlayerListDialogs.openList(viewer); assertTrue(ui.last().buttons().isEmpty()); assertEquals("Online: 0", ui.last().bodyText());
				bukkit.when(org.bukkit.Bukkit::getOnlinePlayers).thenReturn(List.of(viewer)); identities.when(() -> net.tfminecraft.rpcharacters.identity.DisplayIdentityService.resolveDisplaySafe(viewer)).thenReturn("Long name ".repeat(40));
				PlayerListDialogs.openList(viewer); assertEquals(220, ui.last().buttons().getFirst().width()); assertFalse(text(ui.last().buttons().getFirst().tooltip()).contains("Click to view"));
				assertEquals("§a", PlayerListDialogs.pingColour(79)); assertEquals("§e", PlayerListDialogs.pingColour(80)); assertEquals("§6", PlayerListDialogs.pingColour(150)); assertEquals("§c", PlayerListDialogs.pingColour(300));
			}
		}
		@Test void characterSheetKeepsDetailsIndependentOfPortraitAndBackButtonRoutesToList() {
			Player viewer = player("Viewer", 10), target = player("Target", 10); when(viewer.canSee(target)).thenReturn(true);
			java.util.List<Runnable> scheduled = new java.util.ArrayList<>(); var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
			doAnswer(c -> { scheduled.add(c.getArgument(1)); return null; }).when(scheduler).runTask(nullable(org.bukkit.plugin.Plugin.class), any(Runnable.class));
			var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB); image.setRGB(8, 8, 0xff123456); var texture = new SkinPortrait.SkinTexture("https://textures.minecraft.net/example", false);
			java.util.concurrent.CompletableFuture<BufferedImage> downloaded = new java.util.concurrent.CompletableFuture<>();
			try (DialogCapture ui = new DialogCapture(); var bukkit = mockStatic(org.bukkit.Bukkit.class, CALLS_REAL_METHODS); var skins = mockStatic(SkinPortrait.class, call -> call.getMethod().getName().equals("fetch") ? java.util.concurrent.CompletableFuture.completedFuture(null) : call.callRealMethod()); var lp = mockStatic(LuckPermsProvider.class); var identity = mockStatic(net.tfminecraft.rpcharacters.identity.DisplayIdentityService.class)) {
				lp.when(LuckPermsProvider::get).thenThrow(new IllegalStateException()); bukkit.when(org.bukkit.Bukkit::getScheduler).thenReturn(scheduler); bukkit.when(() -> org.bukkit.Bukkit.getPlayer(target.getUniqueId())).thenReturn(target); bukkit.when(org.bukkit.Bukkit::getOnlinePlayers).thenReturn(List.of());
				identity.when(() -> net.tfminecraft.rpcharacters.identity.DisplayIdentityService.resolveDisplaySafe(target)).thenReturn("Sir Target");
				skins.when(() -> SkinPortrait.texture(target)).thenReturn(texture); skins.when(() -> SkinPortrait.fetch(texture)).thenReturn(downloaded);
				java.util.List<String> lines = new java.util.ArrayList<>(List.of("§aName", "Biography")); PlayerListDialogs.openCharacterSheet(viewer, target, lines); lines.set(0, "Mutated"); assertTrue(ui.opened.isEmpty()); downloaded.complete(image); scheduled.removeFirst().run();
				CapturedDialog sheet = ui.last(); assertEquals(" Sir Target", text(sheet.base().title())); assertEquals(2, sheet.base().body().size()); assertTrue(sheet.bodyText().endsWith("Name\nBiography\n\nTarget")); assertEquals(512, objects(((io.papermc.paper.registry.data.dialog.body.PlainMessageDialogBody) sheet.base().body().getFirst()).contents()));
				var back = ((io.papermc.paper.registry.data.dialog.type.NoticeType) sheet.type()).action(); ui.click(back, net.kyori.adventure.audience.Audience.empty()); assertEquals(1, ui.opened.size()); ui.click(back, viewer); assertEquals("Online: 0", ui.last().bodyText());
			}
		}
		@Test void profileBackButtonCannotBypassRevokedPlayerListPermission() {
			Player viewer = player("Viewer", 10), target = player("Target", 10); when(viewer.canSee(target)).thenReturn(true);
			var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class); doAnswer(c -> { c.getArgument(1, Runnable.class).run(); return null; }).when(scheduler).runTask(nullable(org.bukkit.plugin.Plugin.class), any(Runnable.class));
			net.tfminecraft.rpcharacters.Cache.playerList = new PlayerListSettings("list", false, false, "Players", "{online}", "Players", Map.of());
			try (DialogCapture ui = new DialogCapture(); var bukkit = mockStatic(org.bukkit.Bukkit.class, CALLS_REAL_METHODS); var lp = mockStatic(LuckPermsProvider.class); var identity = mockStatic(net.tfminecraft.rpcharacters.identity.DisplayIdentityService.class)) {
				lp.when(LuckPermsProvider::get).thenThrow(new IllegalStateException()); bukkit.when(org.bukkit.Bukkit::getScheduler).thenReturn(scheduler); bukkit.when(() -> org.bukkit.Bukkit.getPlayer(target.getUniqueId())).thenReturn(target); bukkit.when(org.bukkit.Bukkit::getOnlinePlayers).thenReturn(List.of()); identity.when(() -> net.tfminecraft.rpcharacters.identity.DisplayIdentityService.resolveDisplaySafe(target)).thenReturn("Target");
				PlayerListDialogs.openCharacterSheet(viewer, target, List.of("Profile allowed")); assertEquals(1, ui.opened.size()); var back = ((io.papermc.paper.registry.data.dialog.type.NoticeType) ui.last().type()).action(); when(viewer.hasPermission("list")).thenReturn(false); ui.click(back, viewer);
				assertEquals(1, ui.opened.size(), "The profile back button must enforce the player-list permission"); verify(viewer).sendMessage(contains("do not have permission"));
			}
		}

		@Test void delayedSheetDoesNotShowToDisconnectedViewerOrForHiddenOrDisconnectedTarget() {
			Player viewer = player("Viewer", 10), target = player("Target", 10); when(viewer.canSee(target)).thenReturn(true);
			var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class); java.util.List<Runnable> tasks = new java.util.ArrayList<>(); doAnswer(c -> { tasks.add(c.getArgument(1)); return null; }).when(scheduler).runTask(nullable(org.bukkit.plugin.Plugin.class), any(Runnable.class));
			net.tfminecraft.rpcharacters.Cache.playerList = new PlayerListSettings("list", false, false, "Players", "{online}", "Players", Map.of());
			try (DialogCapture ui = new DialogCapture(); var bukkit = mockStatic(org.bukkit.Bukkit.class, CALLS_REAL_METHODS); var lp = mockStatic(LuckPermsProvider.class); var identity = mockStatic(net.tfminecraft.rpcharacters.identity.DisplayIdentityService.class)) {
				lp.when(LuckPermsProvider::get).thenThrow(new IllegalStateException()); bukkit.when(org.bukkit.Bukkit::getScheduler).thenReturn(scheduler); bukkit.when(() -> org.bukkit.Bukkit.getPlayer(target.getUniqueId())).thenReturn(target); identity.when(() -> net.tfminecraft.rpcharacters.identity.DisplayIdentityService.resolveDisplaySafe(target)).thenReturn("Target");
				PlayerListDialogs.openCharacterSheet(viewer, target, List.of("Details")); when(viewer.isOnline()).thenReturn(false); tasks.removeFirst().run(); assertTrue(ui.opened.isEmpty()); when(viewer.isOnline()).thenReturn(true);
				PlayerListDialogs.openCharacterSheet(viewer, target, List.of("Details")); when(viewer.canSee(target)).thenReturn(false); tasks.removeFirst().run(); assertTrue(ui.opened.isEmpty()); when(viewer.canSee(target)).thenReturn(true);
				PlayerListDialogs.openCharacterSheet(viewer, target, List.of("Details")); bukkit.when(() -> org.bukkit.Bukkit.getPlayer(target.getUniqueId())).thenReturn(null); tasks.removeFirst().run(); assertTrue(ui.opened.isEmpty()); bukkit.when(() -> org.bukkit.Bukkit.getPlayer(target.getUniqueId())).thenReturn(target);
				PlayerListDialogs.openCharacterSheet(viewer, target, List.of("Details")); tasks.removeFirst().run(); assertEquals("Details\n\nTarget", ui.last().bodyText()); assertEquals(1, ui.last().base().body().size());
			}
		}
		@Test void playerCommandsAndQuickActionsEnforcePermissionsAndServerThreadDispatch() {
			Player p = player("Player", 0); when(p.hasPermission("list")).thenReturn(false); var console = mock(org.bukkit.command.CommandSender.class); var command = new PlayerListCommand(); var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class); java.util.List<Runnable> tasks = new java.util.ArrayList<>(); doAnswer(c -> { tasks.add(c.getArgument(1)); return null; }).when(scheduler).runTask(nullable(org.bukkit.plugin.Plugin.class), any(Runnable.class));
			try (var bukkit = mockStatic(org.bukkit.Bukkit.class, CALLS_REAL_METHODS); var dialogs = mockStatic(PlayerListDialogs.class)) {
				bukkit.when(org.bukkit.Bukkit::getScheduler).thenReturn(scheduler); assertTrue(command.onCommand(console, null, "players", new String[0])); verify(console).sendMessage(contains("Only players")); assertTrue(command.onCommand(p, null, "players", new String[0])); verify(p).sendMessage(contains("do not have permission")); dialogs.verifyNoInteractions();
				when(p.hasPermission("list")).thenReturn(true); command.onCommand(p, null, "players", new String[0]); dialogs.verify(() -> PlayerListDialogs.openList(p)); dialogs.clearInvocations();
				var connection = mock(io.papermc.paper.connection.PlayerGameConnection.class); when(connection.getPlayer()).thenReturn(p); var event = mock(io.papermc.paper.event.player.PlayerCustomClickEvent.class); when(event.getIdentifier()).thenReturn(PlayerListDialogs.OPEN_ACTION); when(event.getCommonConnection()).thenReturn(connection);
				command.onCustomClick(event); dialogs.verifyNoInteractions(); tasks.removeFirst().run(); dialogs.verify(() -> PlayerListDialogs.openList(p));
				when(event.getCommonConnection()).thenReturn(null); command.onCustomClick(event); assertTrue(tasks.isEmpty());
			}
		}
		@Test void portraitsReadValidTexturesAndFetchDecodeCacheAndEvictWithoutExternalNetwork() throws Exception {
			Player p = player("Skin", 0); var profile = p.getPlayerProfile();
			when(profile.getProperties()).thenReturn(java.util.Set.of()); assertNull(SkinPortrait.texture(p));
			for (String json : List.of("{\"textures\":{}}", "{\"textures\":{\"SKIN\":{}}}")) {
				String value = java.util.Base64.getEncoder().encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)); when(profile.getProperties()).thenReturn(java.util.Set.of(new com.destroystokyo.paper.profile.ProfileProperty("textures", value))); assertNull(SkinPortrait.texture(p));
			}
			String textureJson = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/test\",\"metadata\":{\"model\":\"slim\"}}}}";
			when(profile.getProperties()).thenReturn(java.util.Set.of(new com.destroystokyo.paper.profile.ProfileProperty("textures", java.util.Base64.getEncoder().encodeToString(textureJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)))));
			assertEquals(new SkinPortrait.SkinTexture("https://textures.minecraft.net/texture/test", true), SkinPortrait.texture(p)); assertNull(SkinPortrait.fetch(null).join());
			assertEquals(32, SkinPortrait.rows(null).size());
			var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB); image.setRGB(8, 8, 0xff654321); var bytes = new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(image, "png", bytes); byte[] png = bytes.toByteArray();
			var tiny = new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB), "png", tiny);
			java.util.Map<String, java.util.concurrent.atomic.AtomicInteger> requests = new java.util.concurrent.ConcurrentHashMap<>();
			var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
			server.createContext("/", exchange -> { String path = exchange.getRequestURI().getPath(); requests.computeIfAbsent(path, ignored -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet(); byte[] data = path.equals("/tiny") ? tiny.toByteArray() : path.equals("/garbage") ? new byte[]{1, 2, 3} : path.equals("/truncated") ? java.util.Arrays.copyOf(png, 24) : png; exchange.sendResponseHeaders(path.equals("/missing") ? 404 : 200, data.length); try (var out = exchange.getResponseBody()) { out.write(data); } }); server.start();
			String root = "http://127.0.0.1:" + server.getAddress().getPort();
			try {
				var skin = new SkinPortrait.SkinTexture(root + "/first", false); var downloaded = SkinPortrait.fetch(skin).get(5, java.util.concurrent.TimeUnit.SECONDS); assertEquals(0xff654321, downloaded.getRGB(8, 8)); assertSame(downloaded, SkinPortrait.fetch(skin).join()); assertEquals(1, requests.get("/first").get());
				for (String failure : List.of("missing", "tiny", "garbage", "truncated")) assertNull(SkinPortrait.fetch(new SkinPortrait.SkinTexture(root + "/" + failure, false)).get(5, java.util.concurrent.TimeUnit.SECONDS));
				for (int start = 0; start < 257; start += 16) { var loads = java.util.stream.IntStream.range(start, Math.min(start + 16, 257)).mapToObj(i -> SkinPortrait.fetch(new SkinPortrait.SkinTexture(root + "/cached" + i, false))).toList(); java.util.concurrent.CompletableFuture.allOf(loads.toArray(java.util.concurrent.CompletableFuture[]::new)).get(5, java.util.concurrent.TimeUnit.SECONDS); assertTrue(loads.stream().allMatch(f -> f.join() != null)); }
				assertNotNull(SkinPortrait.fetch(skin).get(5, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(2, requests.get("/first").get());
			} finally { server.stop(0); }
			assertNull(SkinPortrait.fetch(new SkinPortrait.SkinTexture(root + "/stopped", false)).get(5, java.util.concurrent.TimeUnit.SECONDS));
		}

		@Test void quickActionsPackLogsOnlyChangesAndReportsFilesystemFailure(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
			var logger = mock(java.util.logging.Logger.class); var enabled = net.tfminecraft.rpcharacters.Cache.playerList;
			QuickActionPack.syncMainWorld(dir, enabled, logger); verify(logger).info(contains("added")); clearInvocations(logger); QuickActionPack.syncMainWorld(dir, enabled, logger); verifyNoInteractions(logger);
			var disabled = new PlayerListSettings("list", false, false, "Players", "header", "Players", Map.of()); QuickActionPack.syncMainWorld(dir, disabled, logger); verify(logger).info(contains("removed"));
			java.nio.file.Files.writeString(dir.resolve("datapacks").resolve(QuickActionPack.FOLDER), "blocked"); QuickActionPack.syncMainWorld(dir, enabled, logger); verify(logger).warning(contains("Could not update"));
		}
	}

}
