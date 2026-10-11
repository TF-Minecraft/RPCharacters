package net.tfminecraft.rpcharacters.plots;

/** A character's standing stance towards plots: where their Intrigue bonus goes. */
public enum PlotApproach {
    OFFENSIVE("offensive", "Offensive", 15, 0),
    NEUTRAL("neutral", "Neutral", 8, 8),
    DEFENSIVE("defensive", "Defensive", 0, 15);

    private final String key, label;
    private final int defaultOffense, defaultDefense;

    PlotApproach(String key, String label, int defaultOffense, int defaultDefense) {
        this.key = key;
        this.label = label;
        this.defaultOffense = defaultOffense;
        this.defaultDefense = defaultDefense;
    }

    public String key() { return key; }
    public String label() { return label; }
    int defaultOffense() { return defaultOffense; }
    int defaultDefense() { return defaultDefense; }
    public PlotApproach next() { return values()[(ordinal() + 1) % values().length]; }

    public static PlotApproach parse(String text) {
        for (PlotApproach approach : values()) if (approach.key.equalsIgnoreCase(text)) return approach;
        return null;
    }
}
