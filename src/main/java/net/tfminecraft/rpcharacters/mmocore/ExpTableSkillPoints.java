package net.tfminecraft.rpcharacters.mmocore;

import java.lang.reflect.Field;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.Indyuce.mmocore.api.player.PlayerData;
import net.Indyuce.mmocore.api.player.profess.PlayerClass;
import net.Indyuce.mmocore.api.quest.trigger.CommandTrigger;
import net.Indyuce.mmocore.experience.droptable.ExperienceItem;
import net.tfminecraft.rpcharacters.RPCharacters;

/**
 * Skill points the current class's exp table has given through the rewards MMOCore recorded as claimed.
 * The tables give them with {@code command{format="mmocore admin skill-points give %player% N"}}, which
 * MMOCore runs through {@code Bukkit.dispatchCommand}. That fires no command event, so
 * {@link net.tfminecraft.rpcharacters.managers.SkillPointCommandListener} never sees those points.
 */
final class ExpTableSkillPoints {

	private static final Pattern GIVE = Pattern.compile(
			"/?(?:mmocore:)?mmocore\\s+admin\\s+skill-points\\s+give\\s+\\S+\\s+(\\d{1,9})",
			Pattern.CASE_INSENSITIVE);

	private static Field triggersField;
	private static Field commandField;
	private static boolean warned;

	private ExpTableSkillPoints() {}

	static int claimed(PlayerData mmoPd) {
		PlayerClass profess = mmoPd.getProfess();
		if (profess == null || !profess.hasExperienceTable()) {
			return 0;
		}
		long total = 0;
		for (ExperienceItem item : profess.getExperienceTable().getItems()) {
			int claims = mmoPd.getClaims(profess, item);
			if (claims > 0) {
				total += (long) claims * pointsPerClaim(item);
			}
		}
		return (int) Math.min(Integer.MAX_VALUE, total);
	}

	static int pointsPerClaim(ExperienceItem item) {
		long points = 0;
		try {
			if (triggersField == null) {
				triggersField = accessible(ExperienceItem.class, "triggers");
				commandField = accessible(CommandTrigger.class, "command");
			}
			for (Object trigger : (List<?>) triggersField.get(item)) {
				if (trigger instanceof CommandTrigger) {
					Matcher give = GIVE.matcher(String.valueOf(commandField.get(trigger)).trim());
					if (give.matches()) {
						points += Integer.parseInt(give.group(1));
					}
				}
			}
		} catch (ReflectiveOperationException | RuntimeException ex) {
			warnOnce(ex);
			return 0;
		}
		return (int) Math.min(Integer.MAX_VALUE, points);
	}

	private static Field accessible(Class<?> owner, String name) throws ReflectiveOperationException {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}

	private static void warnOnce(Exception ex) {
		if (warned) {
			return;
		}
		warned = true;
		RPCharacters.plugin.getLogger().warning("Cannot count exp-table skill points; level rewards will not"
				+ " reach the account skill pool: " + ex);
	}

	/** For tests: forget cached reflection results. */
	static void reset() {
		triggersField = null;
		commandField = null;
		warned = false;
	}
}
