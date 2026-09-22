package net.tfminecraft.rpcharacters.chat.smart;

public final class SpeechPowerSnapshot {

	private final double intelligibility;
	private final String reason;
	private final double distance;
	private final int range;
	private final double distanceFactor;
	private final double occlusionFactor;
	private final double occlusionWeight;
	private final int blockingBlocks;
	private final int anchorRays;
	private final String anchorOffset;
	private final double rawIntelligibility;
	private final double charismaBoost;
	private final int listenerCharisma;

	private SpeechPowerSnapshot(double intelligibility, String reason, double distance, int range,
			double distanceFactor, double occlusionFactor, double occlusionWeight, int blockingBlocks, int anchorRays,
			String anchorOffset, double rawIntelligibility, double charismaBoost, int listenerCharisma) {
		this.intelligibility = intelligibility;
		this.reason = reason;
		this.distance = distance;
		this.range = range;
		this.distanceFactor = distanceFactor;
		this.occlusionFactor = occlusionFactor;
		this.occlusionWeight = occlusionWeight;
		this.blockingBlocks = blockingBlocks;
		this.anchorRays = anchorRays;
		this.anchorOffset = anchorOffset;
		this.rawIntelligibility = rawIntelligibility;
		this.charismaBoost = charismaBoost;
		this.listenerCharisma = listenerCharisma;
	}

	public static SpeechPowerSnapshot skipped(String reason) {
		return new SpeechPowerSnapshot(0.0, reason, 0.0, 0, 0.0, 0.0, 0.0, 0, 0, null, 0.0, 0.0, 0);
	}

	public static SpeechPowerSnapshot resolved(double intelligibility, String reason, double distance, int range,
			double distanceFactor, double occlusionFactor, double occlusionWeight, int blockingBlocks, int anchorRays,
			String anchorOffset) {
		return new SpeechPowerSnapshot(intelligibility, reason, distance, range, distanceFactor, occlusionFactor,
				occlusionWeight, blockingBlocks, anchorRays, anchorOffset, intelligibility, 0.0, 0);
	}

	public SpeechPowerSnapshot withCharismaBoost(double boostedIntelligibility, int charisma, double boostApplied) {
		return new SpeechPowerSnapshot(boostedIntelligibility, reason, distance, range, distanceFactor, occlusionFactor,
				occlusionWeight, blockingBlocks, anchorRays, anchorOffset, rawIntelligibility, boostApplied, charisma);
	}

	public double getIntelligibility() {
		return intelligibility;
	}

	public double getRawIntelligibility() {
		return rawIntelligibility;
	}

	public double getCharismaBoost() {
		return charismaBoost;
	}

	public int getListenerCharisma() {
		return listenerCharisma;
	}

	public String getReason() {
		return reason;
	}

	public double getDistance() {
		return distance;
	}

	public int getRange() {
		return range;
	}

	public double getDistanceFactor() {
		return distanceFactor;
	}

	public double getOcclusionFactor() {
		return occlusionFactor;
	}

	public double getOcclusionWeight() {
		return occlusionWeight;
	}

	public int getBlockingBlocks() {
		return blockingBlocks;
	}

	public int getAnchorRays() {
		return anchorRays;
	}

	public String getAnchorOffset() {
		return anchorOffset;
	}

	public String formatBreakdown() {
		if (reason != null && !reason.isBlank()) {
			return "reason=" + reason + ", intelligibility=" + format(intelligibility);
		}
		StringBuilder breakdown = new StringBuilder();
		breakdown.append("distance=").append(format(distance))
				.append(", range=").append(range)
				.append(", distFactor=").append(format(distanceFactor))
				.append(", occWeight=").append(format(occlusionWeight))
				.append(", blockingBlocks=").append(blockingBlocks)
				.append(", occFactor=").append(format(occlusionFactor));
		if (anchorRays > 0) {
			breakdown.append(", anchorRays=").append(anchorRays);
		}
		if (anchorOffset != null && !anchorOffset.isBlank()) {
			breakdown.append(", anchorOffset=").append(anchorOffset);
		}
		if (charismaBoost > 0.0) {
			breakdown.append(", rawIntelligibility=").append(format(rawIntelligibility))
					.append(", charisma=").append(listenerCharisma)
					.append(", chaBoost=").append(format(charismaBoost))
					.append(", intelligibility=").append(format(intelligibility));
		} else {
			breakdown.append(", intelligibility=").append(format(intelligibility));
		}
		return breakdown.toString();
	}

	private static String format(double value) {
		return String.format("%.3f", value);
	}
}
