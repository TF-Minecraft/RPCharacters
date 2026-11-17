package net.tfminecraft.rpcharacters.blind;

import java.util.List;
import java.util.Locale;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;

/** Values from blind.yml: help for characters with the Blind trait, who keep the Blindness fog. */
public record BlindAidSettings(
		boolean enabled,
		List<String> traits,
		List<String> caneItems,
		int checkTicks,
		boolean strideEnabled,
		double strideBonus,
		int strideMinFood,
		float strideExhaustionPerBlock,
		boolean senseEnabled,
		double senseRadius,
		boolean senseLineOfSight,
		double senseMinMove,
		boolean tapEnabled,
		int tapRadius,
		int tapTicks,
		long tapCooldownMillis,
		int tapMaxMarkers,
		String tapSound,
		boolean warnEnabled,
		int warnLookAhead,
		int warnDropDepth,
		long warnCooldownMillis,
		String warnDropMessage,
		String warnHazardMessage,
		String warnSound) {

	public static BlindAidSettings defaults() {
		return from(new YamlConfiguration());
	}

	public static BlindAidSettings from(ConfigurationSection c) {
		return new BlindAidSettings(
				c.getBoolean("enabled", true),
				lower(c.isList("traits") ? c.getStringList("traits") : List.of("blind")),
				lower(c.isList("cane-items") ? c.getStringList("cane-items")
						: List.of("m.props.walking_cane", "m.props.forearm_crutch", "m.props.forearm_crutch_slim")),
				Math.max(1, c.getInt("check-ticks", 5)),
				c.getBoolean("stride.enabled", true),
				Math.max(0, c.getDouble("stride.speed-bonus", 0.25)),
				c.getInt("stride.min-food", 7),
				(float) Math.max(0, c.getDouble("stride.exhaustion-per-block", 0.1)),
				c.getBoolean("sound-sense.enabled", true),
				Math.max(0, c.getDouble("sound-sense.radius", 10)),
				c.getBoolean("sound-sense.line-of-sight", true),
				Math.max(0, c.getDouble("sound-sense.min-move", 0.2)),
				c.getBoolean("cane-tap.enabled", true),
				Math.max(1, c.getInt("cane-tap.radius", 8)),
				Math.max(1, (int) Math.round(c.getDouble("cane-tap.seconds", 3) * 20)),
				Math.max(0, Math.round(c.getDouble("cane-tap.cooldown-seconds", 5) * 1000)),
				Math.max(1, c.getInt("cane-tap.max-markers", 60)),
				c.getString("cane-tap.sound", "minecraft:block.wood.hit"),
				c.getBoolean("warnings.enabled", true),
				Math.max(1, c.getInt("warnings.look-ahead", 3)),
				Math.max(1, c.getInt("warnings.drop-depth", 3)),
				Math.max(0, Math.round(c.getDouble("warnings.cooldown-seconds", 3) * 1000)),
				StringFormatter.formatHex(c.getString("warnings.drop-message", "&cThe ground falls away ahead.")),
				StringFormatter.formatHex(c.getString("warnings.hazard-message", "&6Something dangerous is just ahead.")),
				c.getString("warnings.sound", "minecraft:block.note_block.bass"));
	}

	private static List<String> lower(List<String> values) {
		return values.stream().map(v -> v.trim().toLowerCase(Locale.ROOT)).filter(v -> !v.isEmpty()).toList();
	}
}
