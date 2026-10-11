package net.tfminecraft.rpcharacters.plots;

/** Who rolls against plots aimed at a character: the faction Spymaster, a personal confidant, or themselves. */
public enum Defender {
    SELF("Yourself"),
    CONFIDANT("Your confidant"),
    SPYMASTER("Your Spymaster");

    private final String label;

    Defender(String label) { this.label = label; }

    public String label() { return label; }
}
