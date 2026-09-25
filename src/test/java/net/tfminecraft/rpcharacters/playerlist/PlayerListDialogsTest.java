package net.tfminecraft.rpcharacters.playerlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
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
}
