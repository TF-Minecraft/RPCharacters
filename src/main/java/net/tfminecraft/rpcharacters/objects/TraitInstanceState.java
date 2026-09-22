package net.tfminecraft.rpcharacters.objects;

public final class TraitInstanceState {

	private long durationRemainingMs = -1L;
	private double fuel = -1D;

	public boolean hasDuration() {
		return durationRemainingMs >= 0L;
	}

	public long getDurationRemainingMs() {
		return durationRemainingMs;
	}

	public void setDurationRemainingMs(long durationRemainingMs) {
		this.durationRemainingMs = durationRemainingMs;
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
