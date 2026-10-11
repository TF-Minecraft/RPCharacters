package net.tfminecraft.rpcharacters.plots;

/** A character's position in a plot. Informed characters only know of it. */
public enum PlotRole {
    MASTER("master", "Plot master", 30),
    ASSASSIN("assassin", "Assassin", 20),
    COLLABORATOR("collaborator", "Collaborator", 10),
    UNASSIGNED("unassigned", "Unassigned", 10),
    INFORMED("informed", "Informed", 0);

    private final String key, label;
    private final int defaultDifficulty;

    PlotRole(String key, String label, int defaultDifficulty) {
        this.key = key;
        this.label = label;
        this.defaultDifficulty = defaultDifficulty;
    }

    public String key() { return key; }
    public String label() { return label; }
    int defaultDifficulty() { return defaultDifficulty; }
}
