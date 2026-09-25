package net.tfminecraft.rpcharacters.mmocore;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.bukkit.entity.Player;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import net.Indyuce.mmocore.api.event.PlayerLevelChangeEvent;
import net.Indyuce.mmocore.api.event.PlayerLevelChangeEvent.Reason;
import net.Indyuce.mmocore.experience.Profession;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.professions.ProfessionDefinition;
import net.tfminecraft.rpcharacters.professions.ProfessionListener;
import net.tfminecraft.rpcharacters.professions.ProfessionRegistry;

class MmoCoreLevelChangeTest {

	@ParameterizedTest
	@EnumSource(Reason.class)
	void tracksProgressionOnlyForLevelUps(Reason reason) {
		Player player = mock(Player.class);
		try (var classes = mockStatic(ClassService.class)) {
			new PlayerManager().levelUp(event(player, null, 2, 3, reason));
			if (reason == Reason.LEVEL_UP) {
				classes.verify(() -> ClassService.trackFromPlayer(player));
			} else {
				classes.verifyNoInteractions();
			}
		}
	}

	@ParameterizedTest
	@CsvSource({
		"LEVEL_UP, true, true, 2, 3, 1",
		"LEVEL_UP, true, true, 2, 5, 3",
		"LEVEL_UP, false, true, 2, 3, 0",
		"LEVEL_UP, true, false, 2, 3, 0",
		"LEVEL_UP, true, true, 2, 2, 0",
		"LEVEL_UP, true, true, 3, 2, 0",
		"COMMAND, true, true, 2, 5, 0",
		"RESET, true, true, 2, 5, 0",
		"CHOOSE_CLASS, true, true, 2, 5, 0",
		"CHOOSE_PROFILE, true, true, 2, 5, 0",
		"UNKNOWN, true, true, 2, 5, 0",
		"OTHER, true, true, 2, 5, 0"
	})
	void awardsOnlyEarnedProfessionLevels(Reason reason, boolean hasProfession, boolean registered,
			int oldLevel, int newLevel, int expectedPoints) {
		Player player = mock(Player.class);
		PlayerData account = mock(PlayerData.class);
		PlayerManager manager = mock(PlayerManager.class);
		Profession profession = mock(Profession.class);
		ProfessionDefinition definition = mock(ProfessionDefinition.class);
		when(profession.getId()).thenReturn("mining");
		when(definition.getId()).thenReturn("mining");
		when(definition.getName()).thenReturn("Mining");
		try (var players = mockStatic(PlayerManager.class);
				var plugin = mockStatic(RPCharacters.class);
				var registry = mockStatic(ProfessionRegistry.class)) {
			players.when(() -> PlayerManager.get(player)).thenReturn(account);
			plugin.when(RPCharacters::getPlayerManager).thenReturn(manager);
			registry.when(() -> ProfessionRegistry.getProfession("mining"))
					.thenReturn(registered ? definition : null);
			new ProfessionListener().onProfessionLevelUp(event(player, hasProfession ? profession : null,
					oldLevel, newLevel, reason));
			if (expectedPoints > 0) {
				verify(account).addAccountProfessionPoints("mining", expectedPoints);
				verify(manager).savePlayer(player);
				verify(player).sendMessage("§7Gained §e" + expectedPoints + " §aMining§7 "
						+ (expectedPoints == 1 ? "point!" : "points!"));
			} else {
				verifyNoInteractions(account, manager, player);
			}
		}
	}

	private PlayerLevelChangeEvent event(Player player, Profession profession, int oldLevel, int newLevel,
			Reason reason) {
		net.Indyuce.mmocore.api.player.PlayerData data = mock(net.Indyuce.mmocore.api.player.PlayerData.class);
		when(data.getPlayer()).thenReturn(player);
		return new PlayerLevelChangeEvent(data, profession, oldLevel, newLevel, reason);
	}
}
