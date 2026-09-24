package net.tfminecraft.rpcharacters.focus;

import java.util.ArrayList;
import java.util.List;

public final class FocusConfig {

    public static int max = 150;
    public static int basePerHour = 10;
    public static long regenIntervalTicks = 72000L;
    public static boolean offlineRegen = true;
    public static final List<RegenBonus> regenBonuses = new ArrayList<>();

    private FocusConfig() {}

    public static final class RegenBonus {
        public final String mmocoreId;
        public final double extraPerHourPerPoint;

        public RegenBonus(String mmocoreId, double extraPerHourPerPoint) {
            this.mmocoreId = mmocoreId;
            this.extraPerHourPerPoint = extraPerHourPerPoint;
        }
    }
}
