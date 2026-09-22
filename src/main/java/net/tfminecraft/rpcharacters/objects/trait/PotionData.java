package net.tfminecraft.rpcharacters.objects.trait;

import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public class PotionData {
	private final String id;
	private final PotionEffectType type;
	private final int amplifier;

	public PotionData(String input) {
		String value = input == null ? "" : input.trim();
		String effectId = value;
		int effectAmplifier = 0;

		if(value.contains("(") && value.endsWith(")")) {
			int start = value.indexOf('(');
			effectId = value.substring(0, start).trim();
			String rawAmplifier = value.substring(start + 1, value.length() - 1).trim();
			try {
				effectAmplifier = Integer.parseInt(rawAmplifier);
			} catch (NumberFormatException ex) {
				effectAmplifier = 0;
			}
		}

		id = effectId.toLowerCase();
		type = PotionEffectType.getByName(effectId.toUpperCase());
		amplifier = Math.max(0, effectAmplifier);
	}

	public boolean isValid() {
		return type != null;
	}

	public String getId() {
		return id;
	}

	public PotionEffectType getType() {
		return type;
	}

	public int getAmplifier() {
		return amplifier;
	}

	public PotionEffect getEffect(int durationTicks) {
		return new PotionEffect(type, durationTicks, amplifier);
	}
}