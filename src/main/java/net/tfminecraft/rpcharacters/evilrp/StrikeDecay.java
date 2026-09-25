package net.tfminecraft.rpcharacters.evilrp;

/** One strike wears off for each full decay period since the latest strike. */
public final class StrikeDecay {

	private StrikeDecay() {
	}

	public static Result apply(int strikes, long lastStrikeAtMs, long nowMs, long periodMs) {
		if (strikes <= 0 || periodMs <= 0L) {
			return new Result(Math.max(0, strikes), lastStrikeAtMs);
		}
		// Strikes from before decay was tracked start their clock now.
		if (lastStrikeAtMs <= 0L) {
			return new Result(strikes, nowMs);
		}
		long periods = Math.max(0L, (nowMs - lastStrikeAtMs) / periodMs);
		int removed = (int) Math.min(periods, strikes);
		return new Result(strikes - removed, lastStrikeAtMs + removed * periodMs);
	}

	public record Result(int strikes, long lastStrikeAtMs) {
	}
}
