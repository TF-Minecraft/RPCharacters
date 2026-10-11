package net.tfminecraft.rpcharacters.plots;

import java.util.ArrayList;
import java.util.List;

/** A character's plot approach, personal confidant and chosen investigators. */
public final class IntrigueSettings {
    public PlotApproach approach = PlotApproach.NEUTRAL;
    public long approachChangedAt;
    /** The character who watches over this one; they serve only one character. */
    public PlotCharacter confidant;
    public long confidantChangedAt;
    /** Characters who investigate plots this character has learned of. */
    public List<PlotCharacter> investigators = new ArrayList<>();

    PlotApproach approach() { return approach == null ? PlotApproach.NEUTRAL : approach; }
}
