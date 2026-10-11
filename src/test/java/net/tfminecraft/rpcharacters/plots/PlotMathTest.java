package net.tfminecraft.rpcharacters.plots;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlotMathTest {
    private static final List<PlotMath.Contributor> TEAM = List.of(
            new PlotMath.Contributor(PlotRole.MASTER, null, 50),
            new PlotMath.Contributor(PlotRole.ASSASSIN, null, 60),
            new PlotMath.Contributor(PlotRole.COLLABORATOR, CollaboratorTask.PROGRESS, 80),
            new PlotMath.Contributor(PlotRole.COLLABORATOR, CollaboratorTask.HIDE, 100));

    @BeforeEach
    @AfterEach
    void defaults() { PlotConfig.load(new YamlConfiguration()); }

    private static Plot plot() {
        Plot plot = new Plot();
        plot.master = PlotModelTest.character("m");
        plot.target = PlotModelTest.character("t");
        PlotMember assassin = new PlotMember(PlotModelTest.character("a"));
        assassin.role = PlotRole.ASSASSIN;
        plot.members.add(assassin);
        return plot;
    }

    @Test
    void approachShiftsIntrigueWithinBounds() {
        assertEquals(65, PlotMath.offense(50, PlotApproach.OFFENSIVE));
        assertEquals(50, PlotMath.defense(50, PlotApproach.OFFENSIVE));
        assertEquals(100, PlotMath.defense(95, PlotApproach.DEFENSIVE));
        assertEquals(0, PlotMath.clamp(-4));
    }

    @Test
    void protectionFollowsTheDefender() {
        assertEquals(30, PlotMath.alone(60));
        assertEquals(37.5, PlotMath.withConfidant(60, 40));
        assertEquals(70, PlotMath.withSpymaster(70));
        assertEquals(7.5, PlotMath.betrayedByConfidant(60, 40));
        assertEquals(0, PlotMath.betrayedByConfidant(10, 100));
        assertEquals(59, PlotMath.required(30, Betrayal.NONE), 1e-9);
        assertEquals(0, PlotMath.required(30, Betrayal.CONFIDANT));
    }

    @Test
    void membersSetSpeedStrengthAndSecrecy() {
        var honest = new PlotMath.Snapshot(TEAM, 30, Betrayal.NONE);
        assertEquals(1.4, PlotMath.speed(honest), 1e-9);
        assertEquals(13.2, PlotMath.dailyStrength(honest), 1e-9);
        assertEquals(1.5, PlotMath.secrecyLoss(honest), 1e-9);
        var betrayed = new PlotMath.Snapshot(TEAM, 30, Betrayal.CONFIDANT);
        assertEquals(2.8, PlotMath.speed(betrayed), 1e-9);
        assertEquals(26.4, PlotMath.dailyStrength(betrayed), 1e-9);
        var config = new YamlConfiguration();
        config.set("building.secrecy.daily-loss", 0);
        config.set("building.secrecy.loss-per-member", 0);
        PlotConfig.load(config);
        assertEquals(.5, PlotMath.secrecyLoss(honest), 1e-9);
    }

    @Test
    void aDayMovesTheBarsAndTheDefenderRollsAgainstSecrecy() {
        var random = new ScriptedRandom();
        Plot quiet = plot();
        assertFalse(PlotMath.day(quiet, new PlotMath.Snapshot(TEAM, 30, Betrayal.NONE), random));
        assertEquals(1, quiet.day);
        assertEquals(20, quiet.progress, 1e-9);
        assertEquals(13.2, quiet.strength, 1e-9);
        assertEquals(59, quiet.required, 1e-9);
        assertEquals(98.5, quiet.secrecy, 1e-9);
        Plot watched = plot();
        assertTrue(PlotMath.day(watched, new PlotMath.Snapshot(TEAM, 100, Betrayal.NONE), random));
        assertEquals(95.5, watched.secrecy, 1e-9);
        Plot buried = plot();
        assertFalse(PlotMath.day(buried, new PlotMath.Snapshot(TEAM, 100, Betrayal.SPYMASTER), random));
        assertEquals(0, buried.required);
        buried.progress = 95;
        buried.secrecy = 1;
        PlotMath.day(buried, new PlotMath.Snapshot(TEAM, 100, Betrayal.SPYMASTER), random);
        assertEquals(100, buried.progress);
        assertEquals(0, buried.secrecy);
        Plot exposed = plot();
        exposed.secrecy = 1;
        assertTrue(PlotMath.watch(exposed, new PlotMath.Snapshot(TEAM, 100, Betrayal.NONE), random));
        assertEquals(0, exposed.secrecy);
        assertEquals(0, exposed.day, "waiting days don't move the plot bar");
    }

    @Test
    void fixedLuckNeedsNoRandomDraw() {
        var config = new YamlConfiguration();
        config.set("building.luck-min", 1.2);
        config.set("building.luck-max", 1.2);
        PlotConfig.load(config);
        var random = new ScriptedRandom();
        random.doubles.add(99.0);
        assertEquals(1.2, PlotMath.luck(random));
        assertEquals(1, random.doubles.size());
    }

    @Test
    void lowerSecrecyRevealsMoreButNamesOnlyAtZero() {
        Plot plot = plot();
        Map<String, String> known = new LinkedHashMap<>();
        plot.secrecy = 90;
        assertEquals(List.of("Someone is plotting against your life."), PlotMath.reveal(plot, known, List.of("M")));
        assertEquals(List.of(), PlotMath.reveal(plot, known, List.of("M")));
        plot.secrecy = 50;
        assertEquals(List.of("The plot against you counts 2 conspirators."), PlotMath.reveal(plot, known, List.of("M")));
        plot.secrecy = 30;
        plot.progress = 10;
        assertEquals(List.of("The plot against you is in its early days."), PlotMath.reveal(plot, known, List.of("M")));
        plot.progress = 50;
        assertEquals(List.of("The plot against you is well under way."), PlotMath.reveal(plot, known, List.of("M")));
        plot.secrecy = 10;
        plot.progress = 80;
        assertEquals(List.of("The plot against you is nearly complete.", "One blade has been hired against you."),
                PlotMath.reveal(plot, known, List.of("M")));
        PlotMember second = new PlotMember(PlotModelTest.character("b"));
        second.role = PlotRole.ASSASSIN;
        plot.members.add(second);
        plot.secrecy = 0;
        assertEquals(List.of("The plot against you counts 3 conspirators.", "2 blades have been hired against you.",
                "The conspirators are M (plot master), A."), PlotMath.reveal(plot, known, List.of("M (plot master)", "A")));
    }

    @Test
    void rollsWeighIntrigueKnowledgeBetrayalAndPosition() {
        assertEquals(75, PlotMath.roll(60, new ScriptedRandom()));
        var lucky = new ScriptedRandom();
        lucky.ints.addAll(List.of(75, 75, 75));
        assertEquals(150, PlotMath.roll(60, lucky));
        assertEquals(15, PlotMath.investigationModifier(true, Betrayal.NONE));
        assertEquals(-55, PlotMath.investigationModifier(false, Betrayal.SPYMASTER));
        assertEquals(-3, PlotMath.investigate(60, 15, 50, PlotRole.MASTER, new ScriptedRandom()));
        assertEquals(27, PlotMath.investigate(60, 15, 50, PlotRole.INFORMED, new ScriptedRandom()));
        assertEquals(-8, PlotMath.suspicion(60, 50, new ScriptedRandom()));
    }
}
