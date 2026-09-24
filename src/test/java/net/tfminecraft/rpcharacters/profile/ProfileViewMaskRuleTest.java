package net.tfminecraft.rpcharacters.profile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.objects.RPCharacter;

class ProfileViewMaskRuleTest {

	private static Player viewer() {
		Player viewer = mock(Player.class);
		when(viewer.getUniqueId()).thenReturn(UUID.randomUUID());
		when(viewer.hasPermission(Cache.profilePermission)).thenReturn(true);
		return viewer;
	}

	@Test
	void playerListSheetIgnoresMasks() {
		Player viewer = viewer();
		CharacterProfileViewEvent event = new CharacterProfileViewEvent(viewer, mock(Player.class),
				mock(RPCharacter.class), true, true, CharacterProfileViewEvent.Presentation.SHEET);
		new ProfileManager().onProfileViewDeny(event);
		assertFalse(event.isCancelled());
		verify(viewer, never()).sendMessage(anyString());
	}

	@Test
	void chatProfileStillRefusesMaskedPlayers() {
		CharacterProfileViewEvent event = new CharacterProfileViewEvent(viewer(), mock(Player.class),
				mock(RPCharacter.class), true, true);
		new ProfileManager().onProfileViewDeny(event);
		assertTrue(event.isCancelled());
	}
}
