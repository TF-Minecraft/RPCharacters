package net.tfminecraft.rpcharacters.chat.smart;

import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;
import net.tfminecraft.rpcharacters.utils.RPTexts;

public final class MuffleEngine {

	private MuffleEngine() {}

	public static String apply(String message, double intelligibility, UUID utteranceId, UUID viewerId,
			SmartMessageSettings settings) {
		return applyDetailed(message, intelligibility, utteranceId, viewerId, settings).getText();
	}

	public static MuffleOutcome applyDetailed(String message, double intelligibility, UUID utteranceId, UUID viewerId,
			SmartMessageSettings settings) {
		if (message == null || message.isBlank() || settings == null) {
			return new MuffleOutcome("", "empty-input");
		}
		if (intelligibility < settings.getMinAudible()) {
			return new MuffleOutcome("", "inaudible-below-min");
		}
		if (intelligibility >= settings.getFullClarity()) {
			return new MuffleOutcome(message, "full-clarity");
		}
		if (intelligibility < settings.getLowIntelligibilityThreshold()) {
			return new MuffleOutcome(formatPlaceholder(settings.getLowIntelligibilityPlaceholder()), "placeholder");
		}

		long seed = utteranceId.getMostSignificantBits() ^ viewerId.getMostSignificantBits();
		Random random = new Random(seed);
		String result = applyConfiguredRules(message, intelligibility, settings, random);
		if (!result.equals(message)) {
			return new MuffleOutcome(result, "muffle-rules");
		}

		if (settings.isGenericMuffle()) {
			String generic = PhoneticMuffler.muffle(message, intelligibility, random);
			if (!generic.equals(message)) {
				return new MuffleOutcome(generic, "generic-muffle");
			}
		}
		return new MuffleOutcome(message, "unchanged");
	}

	private static String applyConfiguredRules(String message, double intelligibility, SmartMessageSettings settings,
			Random random) {
		String result = message;
		double distortion = 1.0 - intelligibility;

		List<MuffleRule> rules = settings.getMuffleRules();
		for (MuffleRule rule : rules) {
			if (!rule.appliesTo(intelligibility)) {
				continue;
			}
			int effectiveChance = (int) Math.round(rule.getPercentage() * distortion);
			if (effectiveChance <= 0) {
				continue;
			}
			result = applyRule(result, rule, random, effectiveChance);
		}
		return result;
	}

	private static String applyRule(String text, MuffleRule rule, Random random, int effectiveChance) {
		String replace = rule.getReplace();
		if ("-end-".equals(replace)) {
			if (random.nextInt(100) < effectiveChance && !text.endsWith("...")) {
				return text + "...";
			}
			return text;
		}

		String lower = text.toLowerCase(Locale.ROOT);
		String token = replace.toLowerCase(Locale.ROOT);
		int index = lower.indexOf(token);
		if (index < 0) {
			return text;
		}
		if (random.nextInt(100) >= effectiveChance) {
			return text;
		}
		return text.substring(0, index) + rule.getTo() + text.substring(index + replace.length());
	}

	private static String formatPlaceholder(String placeholder) {
		if (placeholder == null || placeholder.isEmpty()) {
			return RPTexts.formatDisplay(RPTexts.MUTED + "*muffled voices*");
		}
		return StringFormatter.formatHex(placeholder.replace('&', '\u00A7'));
	}
}
