package net.tfminecraft.rpcharacters.chat.smart;

public final class MuffleRule {

	private final String replace;
	private final String to;
	private final double minIntelligibility;
	private final double maxIntelligibility;
	private final int percentage;

	public MuffleRule(String replace, String to, double minIntelligibility, double maxIntelligibility, int percentage) {
		this.replace = replace != null ? replace : "";
		this.to = to != null ? to : "";
		this.minIntelligibility = minIntelligibility;
		this.maxIntelligibility = maxIntelligibility;
		this.percentage = Math.max(0, Math.min(100, percentage));
	}

	public String getReplace() {
		return replace;
	}

	public String getTo() {
		return to;
	}

	public double getMinIntelligibility() {
		return minIntelligibility;
	}

	public double getMaxIntelligibility() {
		return maxIntelligibility;
	}

	public int getPercentage() {
		return percentage;
	}

	public boolean appliesTo(double intelligibility) {
		return intelligibility >= minIntelligibility && intelligibility <= maxIntelligibility;
	}
}
