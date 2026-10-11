package net.tfminecraft.rpcharacters.plots;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/** Plot numbers without any server state. Intrigue values are 0-100. */
public final class PlotMath {
    private PlotMath() {}

    public record Contributor(PlotRole role, CollaboratorTask task, int offense) {}

    /** The plot's people and the target's protection on one day. */
    public record Snapshot(List<Contributor> contributors, double protection, Betrayal betrayal) {}

    public static int offense(int intrigue, PlotApproach approach) {
        return clamp(intrigue + PlotConfig.offenseBonus(approach));
    }

    public static int defense(int intrigue, PlotApproach approach) {
        return clamp(intrigue + PlotConfig.defenseBonus(approach));
    }

    public static double alone(int targetDefense) { return PlotConfig.aloneShare() * targetDefense; }

    public static double withConfidant(int targetDefense, int confidantDefense) {
        return PlotConfig.confidantShare() * (targetDefense + confidantDefense) / 2;
    }

    public static double withSpymaster(int spymasterDefense) { return PlotConfig.spymasterShare() * spymasterDefense; }

    /** A confidant inside the plot counts against the target instead. */
    public static double betrayedByConfidant(int targetDefense, int confidantOffense) {
        return Math.max(0, PlotConfig.confidantShare() * (targetDefense - confidantOffense) / 2);
    }

    static int clamp(int value) { return Math.max(0, Math.min(100, value)); }

    /** Intrigue x multiplier plus luck averaged over several draws: extremes are rare but possible. */
    public static int roll(int intrigue, RandomGenerator random) {
        int luck = 0;
        for (int draw = 0; draw < PlotConfig.luckDraws(); draw++)
            luck += random.nextInt(-PlotConfig.luckSpread(), PlotConfig.luckSpread() + 1);
        return (int) Math.round(clamp(intrigue) * PlotConfig.rollMultiplier() + luck / (double) PlotConfig.luckDraws());
    }

    /** The betrayed character's roll against the betrayer's. Zero or more means they sense it. */
    public static int suspicion(int targetDefense, int betrayerOffense, RandomGenerator random) {
        return roll(targetDefense, random) - roll(betrayerOffense, random) - PlotConfig.suspicionDifficulty();
    }

    public static double required(double protection, Betrayal betrayal) {
        return betrayal != Betrayal.NONE ? 0 : PlotConfig.requiredBase() + protection * PlotConfig.requiredPerProtection();
    }

    public static double speed(Snapshot snapshot) {
        double speed = 1;
        for (Contributor contributor : snapshot.contributors())
            if (contributor.role() == PlotRole.COLLABORATOR && contributor.task() == CollaboratorTask.PROGRESS)
                speed += contributor.offense() / 100.0 * PlotConfig.progressPerCollaborator();
        return speed * betrayalMultiplier(snapshot);
    }

    public static double dailyStrength(Snapshot snapshot) {
        double strength = 0;
        for (Contributor contributor : snapshot.contributors())
            strength += PlotConfig.strengthWeight(contributor.role(), contributor.task()) * contributor.offense();
        return strength * betrayalMultiplier(snapshot);
    }

    public static double secrecyLoss(Snapshot snapshot) {
        double loss = PlotConfig.dailyLoss() + PlotConfig.lossPerMember() * snapshot.contributors().size();
        for (Contributor contributor : snapshot.contributors())
            if (contributor.role() == PlotRole.COLLABORATOR && contributor.task() == CollaboratorTask.HIDE)
                loss -= contributor.offense() / 100.0 * PlotConfig.hidePerCollaborator();
        return Math.max(PlotConfig.minimumLoss(), loss);
    }

    private static double betrayalMultiplier(Snapshot snapshot) {
        return snapshot.betrayal() == Betrayal.NONE ? 1 : PlotConfig.betrayalSpeed();
    }

    static double luck(RandomGenerator random) {
        return PlotConfig.luckMax() > PlotConfig.luckMin() ? random.nextDouble(PlotConfig.luckMin(), PlotConfig.luckMax()) : PlotConfig.luckMin();
    }

    /** One building day: the bars move and the defender rolls against the plot's secrecy. True on a leak. */
    public static boolean day(Plot plot, Snapshot snapshot, RandomGenerator random) {
        plot.day++;
        plot.progress = Math.min(100, plot.progress + 100 / PlotConfig.buildDays() * speed(snapshot) * luck(random));
        plot.strength += dailyStrength(snapshot) * luck(random);
        plot.required = required(snapshot.protection(), snapshot.betrayal());
        return watch(plot, snapshot, random);
    }

    /** Secrecy falls and the defender rolls against it, also while a ready plot waits. True on a leak. */
    public static boolean watch(Plot plot, Snapshot snapshot, RandomGenerator random) {
        plot.secrecy = Math.max(0, plot.secrecy - secrecyLoss(snapshot));
        // A betraying Spymaster buries every trace until the final phase.
        if (snapshot.betrayal() == Betrayal.SPYMASTER) return false;
        boolean leak = roll((int) Math.round(snapshot.protection()), random) > roll((int) Math.round(plot.secrecy), random);
        if (leak) plot.secrecy = Math.max(0, plot.secrecy - PlotConfig.leakLoss());
        return leak;
    }

    /** What a leak tells the target at the plot's secrecy. Returns the lines that are new or changed. */
    public static List<String> reveal(Plot plot, java.util.Map<String, String> known, List<String> conspiratorNames) {
        List<String> lines = new ArrayList<>();
        learn(known, lines, "threat", "Someone is plotting against your life.");
        if (plot.secrecy <= PlotConfig.revealMembers())
            learn(known, lines, "members", "The plot against you counts " + plot.conspirators() + " conspirators.");
        if (plot.secrecy <= PlotConfig.revealProgress())
            learn(known, lines, "progress", "The plot against you is " + (plot.progress < PlotConfig.earlyProgress()
                    ? "in its early days." : plot.progress < PlotConfig.lateProgress() ? "well under way." : "nearly complete."));
        if (plot.secrecy <= PlotConfig.revealAssassins()) {
            int blades = plot.withRole(PlotRole.ASSASSIN).size();
            learn(known, lines, "assassins", blades == 1 ? "One blade has been hired against you."
                    : blades + " blades have been hired against you.");
        }
        if (plot.secrecy <= 0) learn(known, lines, "names", "The conspirators are " + String.join(", ", conspiratorNames) + ".");
        return lines;
    }

    private static void learn(java.util.Map<String, String> known, List<String> lines, String key, String line) {
        if (!line.equals(known.put(key, line))) lines.add(line);
    }

    /** The investigator's modifier: knowing of the plot helps, a betrayal hurts. */
    public static int investigationModifier(boolean discovered, Betrayal betrayal) {
        return (discovered ? PlotConfig.knownBonus() : -PlotConfig.unknownPenalty()) - PlotConfig.betrayalPenalty(betrayal);
    }

    /** Investigator roll minus the conspirator's roll and their position's difficulty. Zero or more succeeds. */
    public static int investigate(int investigatorDefense, int modifier, int suspectOffense, PlotRole role, RandomGenerator random) {
        return roll(investigatorDefense, random) + modifier - roll(suspectOffense, random) - PlotConfig.difficulty(role);
    }
}
