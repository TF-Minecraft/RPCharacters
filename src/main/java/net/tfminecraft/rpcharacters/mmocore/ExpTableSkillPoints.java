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
	private static boolean unreadable;

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
		List<?> triggers = read(triggersField(), item, List.class);
		if (triggers == null) {
			return 0;
		}
		long points = 0;
		for (Object trigger : triggers) {
			if (!(trigger instanceof CommandTrigger)) {
				continue;
			}
			String command = read(commandField(), trigger, String.class);
			if (command == null) {
				continue;
			}
			Matcher give = GIVE.matcher(command.trim());
			if (give.matches()) {
				points += Integer.parseInt(give.group(1));
			}
		}
		return (int) Math.min(Integer.MAX_VALUE, points);
	}

	private static Field triggersField() {
		if (triggersField == null && !unreadable) {
			triggersField = accessible(ExperienceItem.class, "triggers");
		}
		return triggersField;
	}

	private static Field commandField() {
		if (commandField == null && !unreadable) {
			commandField = accessible(CommandTrigger.class, "command");
		}
		return commandField;
	}

	private static Field accessible(Class<?> owner, String name) {
		try {
			Field field = owner.getDeclaredField(name);
			field.setAccessible(true);
			return field;
		} catch (ReflectiveOperationException | RuntimeException ex) {
			unreadable("no " + owner.getSimpleName() + "." + name + " field", ex);
			return null;
		}
	}

	private static <T> T read(Field field, Object owner, Class<T> type) {
		if (field == null) {
			return null;
		}
		try {
			Object value = field.get(owner);
			return type.isInstance(value) ? type.cast(value) : null;
		} catch (ReflectiveOperationException | RuntimeException ex) {
			unreadable("could not read " + field.getName(), ex);
			return null;
		}
	}

	private static void unreadable(String reason, Exception ex) {
		if (unreadable) {
			return;
		}
		unreadable = true;
		if (RPCharacters.plugin != null) {
			RPCharacters.plugin.getLogger().warning("Cannot count exp-table skill points (" + reason
					+ "); level rewards will not reach the account skill pool: " + ex);
		}
	}

	/** For tests: forget cached reflection results. */
	static void reset() {
		triggersField = null;
		commandField = null;
		unreadable = false;
	}
}
