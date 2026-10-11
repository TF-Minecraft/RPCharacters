package net.tfminecraft.rpcharacters.plots;

/** What a collaborator works on, chosen by the plot master. */
public enum CollaboratorTask {
    HIDE("hide", "Hide the plot", "Secrecy falls more slowly."),
    PROGRESS("progress", "Progress the plot", "The plot bar fills faster."),
    STRENGTH("strength", "Strengthen the plot", "Adds the most plot strength.");

    private final String key, label, effect;

    CollaboratorTask(String key, String label, String effect) {
        this.key = key;
        this.label = label;
        this.effect = effect;
    }

    public String key() { return key; }
    public String label() { return label; }
    public String effect() { return effect; }

    public static CollaboratorTask parse(String text) {
        for (CollaboratorTask task : values()) if (task.key.equalsIgnoreCase(text)) return task;
        return null;
    }
}
