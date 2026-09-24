package net.tfminecraft.rpcharacters.focus;

public final class FocusData {

    private String characterId;
    private String ownerUuid;
    private int points;
    private long lastRegenMs;

    public FocusData() {}

    public static FocusData createNew(String characterId, String ownerUuid) {
        FocusData data = new FocusData();
        data.characterId = characterId;
        data.ownerUuid = ownerUuid;
        data.points = FocusConfig.max;
        data.lastRegenMs = System.currentTimeMillis();
        return data;
    }

    public String getCharacterId() {
        return characterId;
    }

    public void setCharacterId(String characterId) {
        this.characterId = characterId;
    }

    public String getOwnerUuid() {
        return ownerUuid;
    }

    public void setOwnerUuid(String ownerUuid) {
        this.ownerUuid = ownerUuid;
    }

    public int getPoints() {
        return points;
    }

    public void setPoints(int points) {
        this.points = clamp(points);
    }

    public long getLastRegenMs() {
        return lastRegenMs;
    }

    public void setLastRegenMs(long lastRegenMs) {
        this.lastRegenMs = lastRegenMs;
    }

    public int applyRegenForElapsed(double hourlyRate, long intervalMs, long nowMs) {
        if (intervalMs <= 0 || hourlyRate <= 0) {
            return 0;
        }
        if (points >= FocusConfig.max) {
            lastRegenMs = nowMs;
            return 0;
        }
        long elapsed = nowMs - lastRegenMs;
        if (elapsed < intervalMs) {
            return 0;
        }
        long intervals = elapsed / intervalMs;
        double msPerHour = 3_600_000.0;
        int perInterval = Math.max(1, (int) Math.round(hourlyRate * intervalMs / msPerHour));
        int newPoints = Math.min(points + (int) intervals * perInterval, FocusConfig.max);
        int added = newPoints - points;
        points = newPoints;
        lastRegenMs += intervals * intervalMs;
        return added;
    }

    public boolean trySpend(int amount) {
        if (amount <= 0) {
            return true;
        }
        if (points < amount) {
            return false;
        }
        points -= amount;
        return true;
    }

    public void grant(int amount) {
        if (amount > 0) {
            points = clamp(points + amount);
        }
    }

    private static int clamp(int value) {
        return Math.min(Math.max(0, value), FocusConfig.max);
    }
}
