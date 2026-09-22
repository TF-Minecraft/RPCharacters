package net.tfminecraft.rpcharacters.grave;

public final class GraveExpiryLogic {

	private GraveExpiryLogic() {
	}

	public static boolean isExpired(long createdMillis, long nowMillis, int expireSeconds) {
		if (expireSeconds <= 0) {
			return false;
		}
		return nowMillis >= createdMillis + expireSeconds * 1000L;
	}

	public static long remainingMillis(long createdMillis, long nowMillis, int expireSeconds) {
		if (expireSeconds <= 0) {
			return 0L;
		}
		long deadline = createdMillis + expireSeconds * 1000L;
		return Math.max(0L, deadline - nowMillis);
	}

	public static String formatRemainingTime(long remainingMillis) {
		long totalSeconds = Math.max(0L, remainingMillis / 1000L);
		long minutes = totalSeconds / 60L;
		long seconds = totalSeconds % 60L;
		return minutes + ":" + (seconds < 10 ? "0" : "") + seconds;
	}
}
