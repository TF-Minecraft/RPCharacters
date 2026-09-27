package net.tfminecraft.rpcharacters.objects;

public final class TraitInstanceState {

	/** Wall-clock time the trait's duration runs out, so it counts down while the player is offline too. */
	private long expiresAtMs = -1L;
	private double fuel = -1D;

	public boolean hasDuration() {
		return expiresAtMs >= 0L;
	}

	public long getExpiresAtMs() {
		return expiresAtMs;
	}

	public void setExpiresAtMs(long expiresAtMs) {
		this.expiresAtMs = expiresAtMs;
	}

	public long getDurationRemainingMs() {
		return getDurationRemainingMs(System.currentTimeMillis());
	}

	public long getDurationRemainingMs(long nowMs) {
		return hasDuration() ? Math.max(0L, expiresAtMs - nowMs) : -1L;
	}

	public void setDurationRemainingMs(long durationRemainingMs) {
		setDurationRemainingMs(durationRemainingMs, System.currentTimeMillis());
	}

	public void setDurationRemainingMs(long durationRemainingMs, long nowMs) {
		long remaining = Math.max(0L, durationRemainingMs);
		expiresAtMs = remaining > Long.MAX_VALUE - nowMs ? Long.MAX_VALUE : nowMs + remaining;
	}

	public boolean hasFuel() {
		return fuel >= 0D;
	}

	public double getFuel() {
		return fuel;
	}

	public void setFuel(double fuel) {
		this.fuel = fuel;
	}

	public boolean isEmpty() {
		return !hasDuration() && !hasFuel();
	}
}
