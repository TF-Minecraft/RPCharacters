package net.tfminecraft.rpcharacters.mmocore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.lumine.mythic.lib.api.MMOLineConfig;
import net.Indyuce.mmocore.api.player.PlayerData;
import net.Indyuce.mmocore.api.player.profess.PlayerClass;
import net.Indyuce.mmocore.api.quest.trigger.CommandTrigger;
import net.Indyuce.mmocore.api.quest.trigger.Trigger;
import net.Indyuce.mmocore.experience.droptable.ExperienceItem;
import net.Indyuce.mmocore.experience.droptable.ExperienceTable;
import net.tfminecraft.rpcharacters.RPCharacters;

class ExpTableSkillPointsTest {

	private RPCharacters oldPlugin;
	private Logger logger;

	@BeforeEach
	void setUp() {
		ExpTableSkillPoints.reset();
		oldPlugin = RPCharacters.plugin;
		RPCharacters.plugin = mock(RPCharacters.class);
		logger = mock(Logger.class);
		when(RPCharacters.plugin.getLogger()).thenReturn(logger);
	}

	@AfterEach
	void tearDown() {
		RPCharacters.plugin = oldPlugin;
		ExpTableSkillPoints.reset();
	}

	@Test
	void countsSkillPointGiveCommandsInAReward() {
		assertEquals(2, ExpTableSkillPoints.pointsPerClaim(item("skill_points_7",
				command("mmocore admin skill-points give %player% 2"))));
		assertEquals(5, ExpTableSkillPoints.pointsPerClaim(item("slot",
				command("mmocore admin slot unlock %player% 2"),
				command("  /MMOCORE:mmocore Admin Skill-Points give %player% 3 "),
				command("mmocore admin skill-points give %player% 2"))));
	}

	@Test
	void ignoresOtherCommandsAndTriggers() {
		assertEquals(0, ExpTableSkillPoints.pointsPerClaim(item("other",
				command("mmocore admin skill-points take %player% 2"),
				command("mmocore admin skill-points give %player% two"),
				command("mmocore admin attribute-points give %player% 2"),
				command("say mmocore admin skill-points give %player% 2"),
				mock(Trigger.class))));
		assertEquals(0, ExpTableSkillPoints.pointsPerClaim(item("empty")));
	}

	@Test
	void multipliesPointsByClaimsOfTheCurrentClassTable() {
		ExperienceItem seven = item("skill_points_7", command("mmocore admin skill-points give %player% 2"));
		ExperienceItem slot = item("skill_slot_2", command("mmocore admin slot unlock %player% 2"),
				command("mmocore admin skill-points give %player% 2"));
		ExperienceItem unclaimed = item("skill_points_17", command("mmocore admin skill-points give %player% 2"));
		PlayerData mmo = mock(PlayerData.class);
		PlayerClass profess = classWith(seven, slot, unclaimed);
		when(mmo.getProfess()).thenReturn(profess);
		when(mmo.getClaims(profess, seven)).thenReturn(1);
		when(mmo.getClaims(profess, slot)).thenReturn(2);
		assertEquals(6, ExpTableSkillPoints.claimed(mmo));
	}

	@Test
	void classesWithoutATableGiveNothing() {
		PlayerData mmo = mock(PlayerData.class);
		assertEquals(0, ExpTableSkillPoints.claimed(mmo));
		PlayerClass profess = mock(PlayerClass.class);
		when(mmo.getProfess()).thenReturn(profess);
		assertEquals(0, ExpTableSkillPoints.claimed(mmo));
	}

	@Test
	void unreadableRewardsCountNothingAndWarnOnce() throws Exception {
		ExperienceItem item = item("skill_points_7", command("mmocore admin skill-points give %player% 2"));
		Field triggers = ExperienceItem.class.getDeclaredField("triggers");
		triggers.setAccessible(true);
		triggers.set(item, null);
		assertEquals(0, ExpTableSkillPoints.pointsPerClaim(item));
		ExpTableSkillPoints.reset();
		// A field of another class stands in for an MMOCore that renamed or retyped "triggers".
		Field cached = ExpTableSkillPoints.class.getDeclaredField("triggersField");
		cached.setAccessible(true);
		cached.set(null, ExpTableSkillPointsTest.class.getDeclaredField("logger"));
		assertEquals(0, ExpTableSkillPoints.pointsPerClaim(item("seven", command("mmocore admin skill-points give %player% 2"))));
		assertEquals(0, ExpTableSkillPoints.pointsPerClaim(item("seven", command("mmocore admin skill-points give %player% 2"))));
		verify(logger, times(2)).warning(contains("Cannot count exp-table skill points"));
	}

	private static PlayerClass classWith(ExperienceItem... items) {
		PlayerClass profess = mock(PlayerClass.class);
		ExperienceTable table = mock(ExperienceTable.class);
		when(profess.hasExperienceTable()).thenReturn(true);
		when(profess.getExperienceTable()).thenReturn(table);
		when(table.getItems()).thenReturn(List.of(items));
		return profess;
	}

	private static ExperienceItem item(String id, Trigger... triggers) {
		return new ExperienceItem(id, 1, 7, 7, 1, 0, new ArrayList<>(List.of(triggers)));
	}

	private static CommandTrigger command(String format) {
		return new CommandTrigger(new MMOLineConfig("command{format=\"" + format + "\"}"));
	}
}
