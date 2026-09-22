package net.tfminecraft.rpcharacters.objects.trait;

import java.util.ArrayList;
import java.util.List;

import net.tfminecraft.rpcharacters.objects.attributes.AttributeData;
import net.tfminecraft.rpcharacters.objects.attributes.AttributeModifier;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.utils.RPTexts;

public final class TraitEffectResolver {

	private TraitEffectResolver() {
	}

	public static boolean isDepowered(RPCharacter character, Trait trait) {
		if (character == null || trait == null || !trait.hasFuelTemplate()) {
			return false;
		}
		return character.getFuel(trait.getId()) <= 0D && trait.getDepoweredVariant() != null;
	}

	public static String resolveDisplayName(RPCharacter character, Trait trait) {
		if (trait == null) {
			return "";
		}
		TraitVariant variant = resolveActiveVariant(character, trait);
		if (variant != null && variant.getName() != null && !variant.getName().isBlank()) {
			return variant.getName();
		}
		return trait.getName();
	}

	public static List<String> resolveDescription(RPCharacter character, Trait trait) {
		if (trait == null) {
			return List.of();
		}
		List<String> lines;
		TraitVariant variant = resolveActiveVariant(character, trait);
		if (variant != null && !variant.getDescription().isEmpty()) {
			lines = new ArrayList<>(variant.getDescription());
		} else {
			lines = new ArrayList<>(trait.getDesc());
		}
		appendBlockOffhandLore(trait, lines);
		return lines;
	}

	public static void appendBlockOffhandLore(Trait trait, List<String> lore) {
		if (lore == null || trait == null || trait.getTraitData() == null
				|| !trait.getTraitData().blocksOffhand()) {
			return;
		}
		lore.add(RPTexts.MUTED + "Cannot use the offhand or two-handed items.");
	}

	public static List<PotionData> resolvePotionEffects(RPCharacter character, Trait trait) {
		if (trait == null) {
			return List.of();
		}
		TraitVariant variant = resolveActiveVariant(character, trait);
		if (variant != null) {
			return variant.getPotionEffects();
		}
		if (trait.getTraitData().hasPotionEffects()) {
			return trait.getTraitData().getPotionEffects();
		}
		return List.of();
	}

	public static AttributeData resolveAttributeData(RPCharacter character, Trait trait) {
		if (trait == null) {
			return new AttributeData();
		}
		AttributeData source = resolveBaseAttributeData(character, trait);
		double scale = resolveHealingScale(character, trait);
		if (scale >= 1D) {
			return source;
		}
		return scaleAttributeData(source, scale);
	}

	private static TraitVariant resolveActiveVariant(RPCharacter character, Trait trait) {
		if (!trait.hasFuelTemplate()) {
			return null;
		}
		if (character != null && character.getFuel(trait.getId()) > 0D) {
			return trait.getPoweredVariant();
		}
		return trait.getDepoweredVariant();
	}

	private static AttributeData resolveBaseAttributeData(RPCharacter character, Trait trait) {
		TraitVariant variant = resolveActiveVariant(character, trait);
		if (variant != null) {
			return variant.getAttributeData();
		}
		return trait.getTraitData().getAttributeData();
	}

	private static double resolveHealingScale(RPCharacter character, Trait trait) {
		if (character == null || !trait.hasDuration()) {
			return 1D;
		}
		long totalMs = trait.getDurationMs();
		if (totalMs <= 0L) {
			return 1D;
		}
		long remainingMs = character.getDurationRemainingMs(trait.getId());
		if (remainingMs < 0L) {
			return 1D;
		}
		double progress = 1D - clamp01((double) remainingMs / (double) totalMs);
		return 1D - progress;
	}

	private static AttributeData scaleAttributeData(AttributeData source, double scale) {
		AttributeData out = new AttributeData();
		out.clearAll();
		for (AttributeModifier modifier : source.getModifiers()) {
			int fullAmount = modifier.getAmount();
			if (fullAmount == 0) {
				continue;
			}
			int amount = scaleModifierAmount(fullAmount, scale);
			if (amount != 0) {
				out.addModifier(new AttributeModifier(modifier.getType(), amount));
			}
		}
		for (var xpModifier : source.getExperienceModifiers()) {
			out.addXPModifier(xpModifier);
		}
		return out;
	}

	private static int scaleModifierAmount(int fullAmount, double scale) {
		if (fullAmount == 0 || scale <= 0D) {
			return 0;
		}
		int scaled = (int) Math.round(fullAmount * scale);
		if (fullAmount < 0) {
			return Math.min(0, scaled);
		}
		return Math.max(0, scaled);
	}

	private static double clamp01(double value) {
		if (value < 0D) {
			return 0D;
		}
		if (value > 1D) {
			return 1D;
		}
		return value;
	}
}
