package net.tfminecraft.rpcharacters.plots;

public enum PlotPhase {
    REQUESTED("Awaiting staff approval"),
    RECRUITING("Recruiting"),
    BUILDING("Building the plot"),
    READY("Ready to strike"),
    INVESTIGATION("Final preparations"),
    EXECUTION("Assassination"),
    SUCCEEDED("Succeeded"),
    FAILED("Failed"),
    DISSOLVED("Dissolved");

    private final String label;

    PlotPhase(String label) { this.label = label; }

    public String label() { return label; }

    /** Requested and running plots; ended ones are history. */
    public boolean active() { return ordinal() <= EXECUTION.ordinal(); }

    /** Phases in which the master may still change the roster and roles. */
    public boolean organising() { return this == RECRUITING || this == BUILDING; }
}
