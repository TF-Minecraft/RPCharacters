package net.tfminecraft.rpcharacters.playerlist;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerShowEntityEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.EventBus;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.user.User;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.lifecycle.CharacterTransitions;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.CharacterSessionEndEvent;
import net.tfminecraft.tlibs.character.CharacterSessionStartEvent;

class HiddenCharacterListingTest {

	private static final String HIDDEN = "rpchar.character.hidden";

	private final List<MockedStatic<?>> statics = new ArrayList<>();
	private final HiddenCharacterListing listener = new HiddenCharacterListing();
	/** Per viewer, like Paper: who they cannot see and who is unlisted in their TAB. */
	private final Map<Player, Set<Player>> invisible = new HashMap<>();
	private final Map<Player, Set<UUID>> unlisted = new HashMap<>();
	private MockedStatic<Bukkit> bukkit;
	private MockedStatic<RPCharacters> plugin;
	private MockedStatic<PlayerManager> players;
	private CharacterTransitions transitions;
	private Player target;
	private Player viewer;
	private Player staff;
	private RPCharacter character;
	private PlayerData data;

	@BeforeEach
	void setup() {
		Cache.personaCharacterHiddenPermission = HIDDEN;
		bukkit = track(mockStatic(Bukkit.class));
		plugin = track(mockStatic(RPCharacters.class));
		players = track(mockStatic(PlayerManager.class));
		transitions = mock(CharacterTransitions.class);
		plugin.when(RPCharacters::getCharacterTransitions).thenReturn(transitions);
		target = player("Haldorim");
		viewer = player("Viewer");
		staff = player("Staff");
		when(staff.hasPermission(HIDDEN)).thenReturn(true);
		online(target, viewer, staff);
		character = mock(RPCharacter.class);
		data = mock(PlayerData.class);
		when(data.getActiveCharacter()).thenReturn(character);
		players.when(() -> PlayerManager.get(target)).thenReturn(data);
		when(transitions.currentSession(target)).thenReturn(Optional.of(mock(CharacterSession.class)));
	}

	@AfterEach
	void teardown() {
		HiddenCharacterListing.reset();
		statics.forEach(MockedStatic::close);
	}

	private <T> MockedStatic<T> track(MockedStatic<T> mocked) {
		statics.add(mocked);
		return mocked;
	}

	private void online(Player... list) {
		bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(list));
	}

	/** A player whose list/unlist calls behave like Paper 1.21.10's CraftPlayer. */
	private Player player(String name) {
		Player player = mock(Player.class);
		UUID id = UUID.randomUUID();
		Set<Player> hiddenFrom = new HashSet<>();
		Set<UUID> tab = new HashSet<>();
		invisible.put(player, hiddenFrom);
		unlisted.put(player, tab);
		when(player.getUniqueId()).thenReturn(id);
		when(player.getName()).thenReturn(name);
		when(player.isOnline()).thenReturn(true);
		when(player.canSee(any(Player.class))).thenAnswer(call -> !hiddenFrom.contains(call.getArgument(0)));
		when(player.isListed(any(Player.class))).thenAnswer(call -> !tab.contains(call.<Player>getArgument(0).getUniqueId()));
		when(player.unlistPlayer(any(Player.class))).thenAnswer(call -> {
			Player other = call.getArgument(0);
			return !hiddenFrom.contains(other) && tab.add(other.getUniqueId());
		});
		when(player.listPlayer(any(Player.class))).thenAnswer(call -> {
			Player other = call.getArgument(0);
			if (hiddenFrom.contains(other)) throw new IllegalStateException("Player cannot see other player");
			return tab.remove(other.getUniqueId());
		});
		bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
		return player;
	}

	private boolean inTab(Player of, Player other) {
		return !unlisted.get(of).contains(other.getUniqueId());
	}

	private void show(Player to, Entity entity) {
		listener.onShow(new PlayerShowEntityEvent(to, entity));
	}

	private void startHidden() {
		when(character.isHidden()).thenReturn(true);
		listener.onSessionStart(new CharacterSessionStartEvent(target, mock(CharacterSession.class)));
	}

	private void endSession() {
		when(transitions.currentSession(target)).thenReturn(Optional.empty());
		listener.onSessionEnd(new CharacterSessionEndEvent(target, mock(CharacterSession.class),
				CharacterSessionEndEvent.Reason.SWITCH));
	}

	@Test
	void hiddenSessionLeavesOtherPlayersListsButStaffAndSelfKeepIt() {
		startHidden();
		assertFalse(inTab(viewer, target));
		assertTrue(inTab(staff, target));
		verify(target, never()).unlistPlayer(target);
		assertTrue(HiddenCharacterListing.isConcealed(target));
		assertTrue(HiddenCharacterListing.isConcealedFrom(viewer, target));
		assertFalse(HiddenCharacterListing.isConcealedFrom(staff, target));
		assertFalse(HiddenCharacterListing.isConcealedFrom(target, target));
		assertFalse(HiddenCharacterListing.isConcealedFrom(target, viewer));

		// Refreshing again changes nothing; ending the session lists them again.
		HiddenCharacterListing.refresh(target);
		assertFalse(inTab(viewer, target));
		endSession();
		assertTrue(inTab(viewer, target));
		assertFalse(HiddenCharacterListing.isConcealedFrom(viewer, target));
		verify(staff, never()).listPlayer(target);
	}

	@Test
	void normalCharactersNeverTouchAnyTabList() {
		HiddenCharacterListing.refresh(target);
		HiddenCharacterListing.refresh(null);
		endSession();
		show(viewer, target);
		verifyNoInteractions(viewer, staff);
		assertFalse(HiddenCharacterListing.isConcealed(target));
	}

	@Test
	void onlyARunningSessionOnAHiddenCharacterCounts() {
		when(character.isHidden()).thenReturn(true);
		assertTrue(HiddenCharacterListing.playsHidden(target));
		when(transitions.currentSession(target)).thenReturn(Optional.empty());
		assertFalse(HiddenCharacterListing.playsHidden(target));
		plugin.when(RPCharacters::getCharacterTransitions).thenReturn(null);
		assertFalse(HiddenCharacterListing.playsHidden(target));

		plugin.when(RPCharacters::getCharacterTransitions).thenReturn(transitions);
		when(transitions.currentSession(viewer)).thenReturn(Optional.of(mock(CharacterSession.class)));
		assertFalse(HiddenCharacterListing.playsHidden(viewer), "No loaded data");
		players.when(() -> PlayerManager.get(viewer)).thenReturn(mock(PlayerData.class));
		assertFalse(HiddenCharacterListing.playsHidden(viewer), "No active character");

		// An offline target is never concealed, whatever its flag says.
		when(transitions.currentSession(target)).thenReturn(Optional.of(mock(CharacterSession.class)));
		when(target.isOnline()).thenReturn(false);
		HiddenCharacterListing.refresh(target);
		assertFalse(HiddenCharacterListing.isConcealed(target));
	}

	@Test
	void selectionRoomVisibilityDefersBothChangesToTheShowEvent() {
		// Play starts the session while the room still hides the player from everyone.
		invisible.get(viewer).add(target);
		invisible.get(staff).add(target);
		startHidden();
		verify(viewer, never()).unlistPlayer(target);
		// Paper then shows them and sends the TAB entry; the show event unlists it.
		invisible.get(viewer).remove(target);
		show(viewer, target);
		assertFalse(inTab(viewer, target));
		invisible.get(staff).remove(target);
		show(staff, target);
		assertTrue(inTab(staff, target));
		show(viewer, mock(Entity.class));

		// Back in the room: hidden again, so Paper would throw on a list call; it waits.
		invisible.get(viewer).add(target);
		endSession();
		assertFalse(inTab(viewer, target));
		// Their next character is normal: shown again, they are listed again.
		invisible.get(viewer).remove(target);
		show(viewer, target);
		assertTrue(inTab(viewer, target));
		show(viewer, target);
		verify(viewer).listPlayer(target);
	}

	@Test
	@SuppressWarnings("unchecked")
	void permissionChangesUpdateThatViewerAtOnce() {
		org.bukkit.plugin.Plugin owner = mock(org.bukkit.plugin.Plugin.class);
		var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
		List<Runnable> ticks = new ArrayList<>();
		doAnswer(call -> { ticks.add(call.getArgument(1)); return null; }).when(scheduler).runTask(eq(owner), any(Runnable.class));
		bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
		LuckPerms luckPerms = mock(LuckPerms.class);
		EventBus bus = mock(EventBus.class);
		when(luckPerms.getEventBus()).thenReturn(bus);
		try (MockedStatic<LuckPermsProvider> provider = mockStatic(LuckPermsProvider.class)) {
			provider.when(LuckPermsProvider::get).thenThrow(new IllegalStateException("LuckPerms not loaded"));
			HiddenCharacterListing.hookPermissionChanges(owner);
			provider.when(LuckPermsProvider::get).thenReturn(luckPerms);
			HiddenCharacterListing.hookPermissionChanges(owner);
		}
		ArgumentCaptor<java.util.function.Consumer<UserDataRecalculateEvent>> handler = ArgumentCaptor.forClass(java.util.function.Consumer.class);
		verify(bus).subscribe(eq(owner), eq(UserDataRecalculateEvent.class), handler.capture());
		java.util.function.Consumer<Player> recalculated = player -> {
			UUID id = player.getUniqueId();
			UserDataRecalculateEvent event = mock(UserDataRecalculateEvent.class);
			User user = mock(User.class);
			when(user.getUniqueId()).thenReturn(id);
			when(event.getUser()).thenReturn(user);
			handler.getValue().accept(event);
			ticks.remove(0).run();
		};

		recalculated.accept(viewer); // nobody hidden yet: nothing to do
		verify(viewer, never()).unlistPlayer(any(Player.class));
		verify(viewer, never()).listPlayer(any(Player.class));
		startHidden();
		assertFalse(inTab(viewer, target));
		when(viewer.hasPermission(HIDDEN)).thenReturn(true);
		recalculated.accept(viewer);
		assertTrue(inTab(viewer, target), "Granted mid-session: listed again without a rejoin");
		when(viewer.hasPermission(HIDDEN)).thenReturn(false);
		recalculated.accept(viewer);
		assertFalse(inTab(viewer, target), "Revoked mid-session: unlisted again without a rejoin");

		// A viewer who already left, and a hidden player who already left, are skipped.
		bukkit.when(() -> Bukkit.getPlayer(viewer.getUniqueId())).thenReturn(null);
		recalculated.accept(viewer);
		bukkit.when(() -> Bukkit.getPlayer(viewer.getUniqueId())).thenReturn(viewer);
		bukkit.when(() -> Bukkit.getPlayer(target.getUniqueId())).thenReturn(null);
		when(viewer.hasPermission(HIDDEN)).thenReturn(true);
		recalculated.accept(viewer);
		assertFalse(inTab(viewer, target));
	}

	@Test
	void joiningPlayersDoNotReceiveHiddenCharacters() {
		Player newcomer = player("Newcomer");
		PlayerJoinEvent join = mock(PlayerJoinEvent.class);
		when(join.getPlayer()).thenReturn(newcomer);
		listener.onJoin(join);
		verifyNoInteractions(newcomer);

		startHidden();
		Player gone = player("Gone");
		players.when(() -> PlayerManager.get(gone)).thenReturn(data);
		when(transitions.currentSession(gone)).thenReturn(Optional.of(mock(CharacterSession.class)));
		HiddenCharacterListing.refresh(gone);
		bukkit.when(() -> Bukkit.getPlayer(gone.getUniqueId())).thenReturn(null);

		listener.onJoin(join);
		assertFalse(inTab(newcomer, target));
		verify(newcomer, never()).unlistPlayer(gone);

		// Staff, and a hidden player rejoining, keep their own entries.
		when(join.getPlayer()).thenReturn(staff);
		listener.onJoin(join);
		assertTrue(inTab(staff, target));
		when(join.getPlayer()).thenReturn(target);
		listener.onJoin(join);
		verify(target, never()).unlistPlayer(target);
	}

	@Test
	void quittingHiddenPlayerIsRelistedAndLeavingViewersAreForgotten() {
		PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
		when(quit.getPlayer()).thenReturn(target);
		listener.onQuit(quit);
		verifyNoInteractions(viewer, staff);

		Player other = player("Other");
		online(target, viewer, staff, other);
		startHidden();
		listener.onQuit(quit);
		assertTrue(inTab(viewer, target));
		assertTrue(inTab(other, target));
		assertFalse(HiddenCharacterListing.isConcealed(target));

		// A viewer who leaves takes their entries with them; the other stays pending.
		when(transitions.currentSession(target)).thenReturn(Optional.of(mock(CharacterSession.class)));
		startHidden();
		invisible.get(other).add(target);
		when(quit.getPlayer()).thenReturn(viewer);
		listener.onQuit(quit);
		online(target, staff, other);
		endSession();
		assertFalse(inTab(other, target), "Still hidden from them, so it waits for the show event");
		invisible.get(other).remove(target);
		show(other, target);
		assertTrue(inTab(other, target));
		show(viewer, target);
		verify(viewer, times(1)).listPlayer(target);
	}
}
