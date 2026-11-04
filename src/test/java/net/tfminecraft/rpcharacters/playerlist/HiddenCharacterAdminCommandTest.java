package net.tfminecraft.rpcharacters.playerlist;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;

class HiddenCharacterAdminCommandTest {

	private static final String HIDDEN = "rpchar.character.hidden";

	private final List<MockedStatic<?>> statics = new ArrayList<>();
	private final Map<CommandSender, List<String>> chat = new java.util.HashMap<>();
	private MockedStatic<Bukkit> bukkit;
	private MockedStatic<PlayerManager> players;
	private MockedStatic<HiddenCharacterListing> listing;
	private PlayerManager manager;
	private Player staff;
	private Player target;
	private PlayerData data;
	private RPCharacter character;
	private boolean hidden;

	@BeforeEach
	void setup() {
		Cache.personaCharacterHiddenPermission = HIDDEN;
		bukkit = track(mockStatic(Bukkit.class));
		players = track(mockStatic(PlayerManager.class));
		listing = track(mockStatic(HiddenCharacterListing.class));
		MockedStatic<RPCharacters> plugin = track(mockStatic(RPCharacters.class));
		manager = mock(PlayerManager.class);
		plugin.when(RPCharacters::getPlayerManager).thenReturn(manager);
		staff = recording(mock(Player.class));
		target = recording(mock(Player.class));
		when(target.getName()).thenReturn("Wren");
		bukkit.when(() -> Bukkit.getPlayerExact("Wren")).thenReturn(target);
		data = mock(PlayerData.class);
		players.when(() -> PlayerManager.get(target)).thenReturn(data);
		character = mock(RPCharacter.class);
		when(character.getName()).thenReturn("Haldorim");
		when(character.isHidden()).thenAnswer(call -> hidden);
		doAnswer(call -> { hidden = call.getArgument(0); return null; }).when(character).setHidden(anyBoolean());
		when(data.getActiveCharacter()).thenReturn(character);
	}

	@AfterEach
	void teardown() {
		statics.forEach(MockedStatic::close);
	}

	private <T> MockedStatic<T> track(MockedStatic<T> mocked) {
		statics.add(mocked);
		return mocked;
	}

	private <T extends CommandSender> T recording(T sender) {
		List<String> lines = new ArrayList<>();
		chat.put(sender, lines);
		doAnswer(call -> lines.add(call.<String>getArgument(0).replaceAll("§.", ""))).when(sender).sendMessage(anyString());
		return sender;
	}

	private String run(CommandSender sender, String... rest) {
		chat.get(sender).clear();
		List<String> args = new ArrayList<>(List.of("admin", "hide"));
		args.addAll(List.of(rest));
		assertTrue(HiddenCharacterAdminCommand.handle(sender, args.toArray(String[]::new), 2));
		return String.join("\n", chat.get(sender));
	}

	@Test
	void onlyHiddenPermissionOrAdminCanUseIt() {
		assertTrue(run(staff, "Wren", "on").contains("do not have permission"));
		assertFalse(hidden);
		when(staff.hasPermission("rpcharacters.admin")).thenReturn(true);
		assertTrue(HiddenCharacterAdminCommand.canUse(staff));
		when(staff.hasPermission("rpcharacters.admin")).thenReturn(false);
		when(staff.hasPermission(HIDDEN)).thenReturn(true);
		assertTrue(HiddenCharacterAdminCommand.canUse(staff));
	}

	@Test
	void hidesAndUnhidesAnotherPlayersActiveCharacterAndTellsThem() {
		when(staff.hasPermission(HIDDEN)).thenReturn(true);
		assertTrue(run(staff, "Wren").contains("Haldorim (Wren) is not hidden."));

		assertTrue(run(staff, "Wren", "ON").contains("Haldorim (Wren) is now hidden from TAB and /players."));
		assertTrue(hidden);
		verify(manager).savePlayer(target);
		listing.verify(() -> HiddenCharacterListing.refresh(target));
		assertTrue(String.join("\n", chat.get(target)).contains("Staff hid your character"));
		assertTrue(run(staff, "Wren").contains("is hidden."));
		assertTrue(run(staff, "Wren", "on").contains("is already hidden."));

		assertTrue(run(staff, "Wren", "off").contains("is no longer hidden."));
		assertFalse(hidden);
		assertTrue(String.join("\n", chat.get(target)).contains("Staff unhid your character"));
		assertTrue(run(staff, "Wren", "off").contains("is not hidden."));
		verify(manager, times(2)).savePlayer(target);
	}

	@Test
	void hidingYourselfDoesNotSendASecondMessage() {
		when(target.hasPermission(HIDDEN)).thenReturn(true);
		String reply = run(target, "Wren", "on");
		assertTrue(reply.contains("is now hidden"));
		assertFalse(reply.contains("Staff hid your character"));
	}

	@Test
	void badInputIsRefusedWithoutChanges() {
		when(staff.hasPermission(HIDDEN)).thenReturn(true);
		assertTrue(run(staff).contains(HiddenCharacterAdminCommand.USAGE));
		assertTrue(run(staff, "Wren", "on", "extra").contains(HiddenCharacterAdminCommand.USAGE));
		assertTrue(run(staff, "Wren", "maybe").contains(HiddenCharacterAdminCommand.USAGE));
		assertTrue(run(staff, "Nobody", "on").contains("No online player called Nobody."));
		players.when(() -> PlayerManager.get(target)).thenReturn(null);
		assertTrue(run(staff, "Wren", "on").contains("No active character for Wren."));
		players.when(() -> PlayerManager.get(target)).thenReturn(data);
		when(data.getActiveCharacter()).thenReturn(null);
		assertTrue(run(staff, "Wren", "on").contains("No active character for Wren."));
		assertFalse(hidden);
		verify(manager, never()).savePlayer(target);
		listing.verifyNoInteractions();
	}
}
