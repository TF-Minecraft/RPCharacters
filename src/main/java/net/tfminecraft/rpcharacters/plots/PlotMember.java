package net.tfminecraft.rpcharacters.plots;

/** A recruited conspirator. The plot master assigns the role and a collaborator's task. */
public final class PlotMember {
    public PlotCharacter character;
    public PlotRole role = PlotRole.UNASSIGNED;
    public CollaboratorTask task;
    /** When an assassin set out to hunt; zero when not hunting. A restart ends every hunt. */
    public transient long huntingSince;
    public transient boolean huntAnnounced;
    public transient long areaToldAt;

    public PlotMember() {}

    public PlotMember(PlotCharacter character) { this.character = character; }

    public String roleLabel() {
        return role == PlotRole.COLLABORATOR && task != null ? role.label() + " (" + task.label() + ")" : role.label();
    }
}
