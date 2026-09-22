package net.tfminecraft.rpcharacters.objects;

import org.bukkit.configuration.ConfigurationSection;

public final class FuelTemplate {

	private final String id;
	private final String item;
	private final double amountPerItem;
	private final double burnRate;
	private final long burnIntervalMs;

	public FuelTemplate(String id, ConfigurationSection config) {
		this.id = id;
		item = config.getString("item", "");
		amountPerItem = config.getDouble("amount-per-item", 0);
		burnRate = config.getDouble("burn-rate", 0);
		burnIntervalMs = parseBurnIntervalMs(config.getString("burn-interval"));
	}

	private static long parseBurnIntervalMs(String raw) {
		if (raw == null || raw.isBlank()) {
			return -1L;
		}
		long shortMs = net.tfminecraft.rpcharacters.utils.DurationParser.parseShortDurationMs(raw);
		if (shortMs > 0) {
			return shortMs;
		}
		return net.tfminecraft.rpcharacters.utils.DurationParser.parseLockTimeMs(raw);
	}

	public String getId() {
		return id;
	}

	public String getItem() {
		return item;
	}

	public double getAmountPerItem() {
		return amountPerItem;
	}

	public double getBurnRate() {
		return burnRate;
	}

	public long getBurnIntervalMs() {
		return burnIntervalMs;
	}

	public boolean isValid() {
		return item != null && !item.isBlank()
				&& amountPerItem > 0
				&& burnRate > 0
				&& burnIntervalMs > 0;
	}
}
