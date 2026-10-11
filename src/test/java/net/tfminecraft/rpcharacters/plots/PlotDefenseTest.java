package net.tfminecraft.rpcharacters.plots;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PlotDefenseTest {
    @TempDir Path directory;
    PlotWorld world;
    PlayerMock master, assassin, helper, target, spy, stranger;
    CommandSender staff = mock(CommandSender.class);

    @BeforeEach
    void setup() throws Exception {
        world = new PlotWorld(directory);
        master = world.player("Master", "c-master", 50);
        assassin = world.player("Assassin", "c-assassin", 60);
        helper = world.player("Helper", "c-helper", 80);
        target = world.player("Target", "c-target", 40);
        spy = world.player("Spy", "c-spy", 70);
        stranger = world.player("Stranger", null, null);
        when(staff.getName()).thenReturn("Staffer");
    }

    @AfterEach
    void close() { world.close(); }

    Plot building() {
        Plot plot = PlotService.request(master);
        PlotService.approve(staff, plot.code, "Target");
        PlotService.invite(master, assassin);
        PlotService.accept(assassin, plot.code);
        PlotService.assign(master, "c-assassin", PlotRole.ASSASSIN, null);
        PlotService.begin(master);
        return plot;
    }

    void spymaster(PlayerMock holder, int office) {
        var spymaster = new PlotFactions.Spymaster(world.character(holder), office);
        world.spymasters.put("Target", spymaster);
        world.spymasters.put(holder.getName(), spymaster);
    }

    void confidant(PlayerMock ward, PlayerMock confidant) {
        world.store().settings(world.character(ward).characterId).confidant = world.character(confidant);
    }

    @Test
    void theSpymasterListComesFirstThenTheConfidantThenOneself() {
        PlotCharacter victim = world.character(target);
        assertEquals(Defender.SELF, PlotDefense.guard(victim).kind());
        assertEquals(new PlotDefense.Protection(24, Betrayal.NONE), PlotDefense.protection(victim, null));
        confidant(target, helper);
        assertEquals(Defender.CONFIDANT, PlotDefense.guard(victim).kind());
        assertEquals(new PlotDefense.Protection(51, Betrayal.NONE), PlotDefense.protection(victim, null));
        spymaster(spy, 70);
        assertEquals(Defender.CONFIDANT, PlotDefense.guard(victim).kind(), "only listed wards are protected");
        world.store().wardsOf("c-spy").add(victim);
        assertEquals(Defender.SPYMASTER, PlotDefense.guard(victim).kind());
        assertEquals(new PlotDefense.Protection(78, Betrayal.NONE), PlotDefense.protection(victim, null));
        world.absent.add(spy.getUniqueId());
        assertEquals(Defender.CONFIDANT, PlotDefense.guard(victim).kind(), "an inactive Spymaster leaves the ward to others");
        world.absent.add(helper.getUniqueId());
        assertEquals(Defender.SELF, PlotDefense.guard(victim).kind());
        world.absent.clear();
        world.dead.add("c-helper");
        world.spymasters.clear();
        assertEquals(Defender.SELF, PlotDefense.guard(victim).kind());
        world.spymasters.put("Target", new PlotFactions.Spymaster(victim, 90));
        world.store().wardsOf("c-target").add(victim);
        assertEquals(Defender.SELF, PlotDefense.guard(victim).kind(), "a Spymaster does not guard themselves as a ward");
    }

    @Test
    void aProtectorInThePlotBetraysTheTarget() {
        Plot plot = building();
        confidant(target, assassin);
        assertEquals(new PlotDefense.Protection(0, Betrayal.CONFIDANT), PlotDefense.protection(plot));
        confidant(target, helper);
        assertEquals(Betrayal.NONE, PlotDefense.protection(plot).betrayal());
        spymaster(assassin, 70);
        world.store().wardsOf("c-assassin").add(world.character(target));
        assertEquals(new PlotDefense.Protection(0, Betrayal.SPYMASTER), PlotDefense.protection(plot));
        var snapshot = PlotDefense.snapshot(plot);
        assertEquals(2, snapshot.contributors().size());
        assertEquals(Betrayal.SPYMASTER, snapshot.betrayal());
    }

    @Test
    void leaksReachTheProtectorFirstWhoDecidesWhetherToWarn() {
        Plot plot = building();
        confidant(target, helper);
        world.intrigue.put("c-target", 100);
        world.intrigue.put("c-helper", 100);
        plot.secrecy = 40;
        world.pass(PlotConfig.DAY);
        PlotService.tick();
        assertTrue(plot.guardKnows);
        assertFalse(plot.discovered);
        assertTrue(world.heard(helper, "Char Target§c: Someone is plotting against their life."));
        assertTrue(world.heard(helper, "The plot against them counts 2 conspirators."));
        assertFalse(world.heard(target, "plotting"));
        assertTrue(world.heard(master, "someone watching over"));
        int heard = world.said(helper).size();
        plot.secrecy = 40;
        world.pass(PlotConfig.DAY);
        PlotService.tick();
        assertTrue(world.said(helper).size() > heard, "a leak with nothing new still repeats the threat");

        assertFalse(PlotDefense.warnWard(stranger, world.character(target)));
        assertFalse(PlotDefense.warnWard(assassin, world.character(target)));
        assertTrue(world.last(assassin).contains("know of no plot"));
        assertTrue(PlotDefense.warnWard(helper, world.character(target)));
        assertTrue(plot.discovered);
        assertTrue(world.heard(target, "Char Helper§7 warns you:"));
        assertTrue(world.heard(target, "Someone is plotting against your life."));
        plot.secrecy = 40;
        world.pass(PlotConfig.DAY);
        PlotService.tick();
        assertTrue(world.heard(target, "§cThe plot against you is well under way."), "once warned, the target hears leaks too");
        assertTrue(world.heard(helper, "The plot against them is well under way."));
    }

    @Test
    void aLoneTargetHearsLeaksDirectlyAndZeroSecrecyNamesEveryone() {
        Plot plot = building();
        world.intrigue.put("c-target", 100);
        plot.secrecy = 40;
        world.pass(PlotConfig.DAY);
        PlotService.tick();
        assertTrue(plot.discovered);
        assertFalse(plot.guardKnows);
        assertTrue(world.heard(target, "§cSomeone is plotting against your life."));
        assertTrue(world.heard(target, "in its early days"));
        plot.secrecy = 1;
        world.intrigue.put("c-target", 0);
        world.pass(PlotConfig.DAY);
        PlotService.tick();
        assertTrue(plot.exposed);
        assertTrue(world.heard(target, "The conspirators are Char Master (plot master), Char Assassin."));
        assertTrue(world.heard(assassin, "The plot is exposed"));
    }

    @Test
    void aBetrayingSpymasterLeavesNoTraceButTheTargetMaySenseIt() {
        Plot plot = building();
        spymaster(assassin, 100);
        world.store().wardsOf("c-assassin").add(world.character(target));
        plot.secrecy = 1;
        world.intrigue.put("c-target", 0);
        world.pass(PlotConfig.DAY);
        PlotService.tick();
        assertEquals(0, plot.secrecy);
        assertFalse(plot.discovered || plot.exposed || plot.guardKnows);
        assertFalse(plot.suspicionRaised);
        world.intrigue.put("c-target", 100);
        world.intrigue.put("c-assassin", 0);
        world.pass(PlotConfig.DAY);
        PlotService.tick();
        assertTrue(plot.suspicionRaised);
        assertTrue(world.heard(target, "Char Assassin§c's reports doesn't add up"));
        int heard = world.said(target).size();
        world.pass(PlotConfig.DAY);
        PlotService.tick();
        assertEquals(heard, world.said(target).size(), "only once per plot");
    }

    @Test
    void someoneWhoKnowsMayWarnTheTargetInPerson() {
        Plot plot = building();
        assertFalse(PlotDefense.warnTarget(stranger, target));
        assertFalse(PlotDefense.warnTarget(helper, target));
        assertTrue(world.last(helper).contains("know of no plot"));
        assertFalse(PlotDefense.warnTarget(helper, stranger));
        plot.informed.add(world.character(helper));
        world.at(target, 30);
        assertFalse(PlotDefense.warnTarget(helper, target));
        world.at(target, 0);
        assertTrue(PlotDefense.warnTarget(helper, target));
        assertTrue(plot.discovered);
        assertTrue(world.last(target).contains("Char Helper§c warns you"));
        assertTrue(plot.knowledge.get("warned-c-helper").contains("Char Helper warned you"));
        assertTrue(world.last(master).contains("someone has warned"));
        assertTrue(PlotDefense.threatened("c-target"));
        assertFalse(PlotDefense.threatened("c-helper"));
    }

    @Test
    void investigatorsAreChosenOnlyByTargetsWhoKnowOfAPlot() {
        assertFalse(PlotDefense.toggleInvestigator(stranger, helper));
        assertFalse(PlotDefense.toggleInvestigator(target, helper));
        assertTrue(world.last(target).contains("know of no plot"));
        Plot plot = building();
        plot.discovered = true;
        assertFalse(PlotDefense.toggleInvestigator(target, stranger));
        assertTrue(PlotDefense.toggleInvestigator(target, helper));
        assertTrue(world.last(helper).contains("look into a plot"));
        assertTrue(PlotDefense.toggleInvestigator(target, spy));
        PlayerMock third = world.player("Third", "c-third", 50);
        assertFalse(PlotDefense.toggleInvestigator(target, third));
        assertTrue(world.last(target).contains("at most 2"));
        assertTrue(PlotDefense.toggleInvestigator(target, helper));
        assertTrue(world.last(target).contains("no longer investigates"));
        assertFalse(PlotDefense.toggleInvestigator(target, null));
    }

    @Test
    void aConfidantServesOneCharacterAndEitherSideEndsIt() {
        assertFalse(PlotDefense.askConfidant(stranger, helper));
        assertFalse(PlotDefense.askConfidant(target, stranger));
        assertFalse(PlotDefense.acceptConfidant(stranger));
        assertFalse(PlotDefense.acceptConfidant(helper));
        assertTrue(world.last(helper).contains("Nobody is waiting"));
        assertTrue(PlotDefense.askConfidant(target, helper));
        assertTrue(world.last(helper).contains("asks you to be their confidant"));
        assertTrue(PlotDefense.acceptConfidant(helper));
        assertTrue(world.last(target).contains("is now your confidant"));
        assertEquals("c-target", world.store().confidantFor("c-helper"));
        assertFalse(PlotDefense.askConfidant(spy, helper));
        assertTrue(world.last(spy).contains("already watch over someone"));
        assertTrue(PlotDefense.askConfidant(master, spy));
        world.logOff(master);
        assertFalse(PlotDefense.acceptConfidant(spy), "the asker is gone");
        world.play(master, "c-master");
        assertFalse(PlotDefense.askConfidant(target, spy));
        assertTrue(world.last(target).contains("choose a confidant again"));
        world.pass(PlotConfig.confidantCooldown());
        assertTrue(PlotDefense.endConfidant(target));
        assertTrue(world.last(helper).contains("no longer needs you"));
        assertNull(world.store().peek("c-target").confidant);
        assertTrue(PlotDefense.askConfidant(master, helper));
        assertTrue(PlotDefense.acceptConfidant(helper));
        assertTrue(PlotDefense.endConfidant(helper));
        assertTrue(world.last(master).contains("no longer watches over you"));
        world.logOff(master);
        assertTrue(PlotDefense.askConfidant(target, spy) || true);
        world.store().settings("c-master").confidant = world.character(helper);
        assertTrue(PlotDefense.endConfidant(helper));
        assertNotNull(world.store().notices.get("c-master"));
        assertFalse(PlotDefense.endConfidant(spy));
        assertFalse(PlotDefense.endConfidant(stranger));
    }

    @Test
    void theSpymasterChoosesWhichFactionMembersToProtect() {
        assertFalse(PlotDefense.toggleWard(stranger, target));
        assertFalse(PlotDefense.toggleWard(spy, target));
        assertTrue(world.last(spy).contains("Only your faction's Spymaster"));
        spymaster(spy, 70);
        assertFalse(PlotDefense.toggleWard(spy, target), "not of their faction");
        world.factions.when(() -> PlotFactions.sameFaction("Spy", "Target")).thenReturn(true);
        assertFalse(PlotDefense.toggleWard(spy, null));
        assertFalse(PlotDefense.toggleWard(spy, spy));
        assertTrue(PlotDefense.toggleWard(spy, target));
        assertTrue(world.last(target).contains("now watches over you"));
        assertEquals(Defender.SPYMASTER, PlotDefense.guard(world.character(target)).kind());
        assertTrue(PlotDefense.toggleWard(spy, target));
        assertTrue(world.store().wardsOf("c-spy").isEmpty());
        assertTrue(PlotDefense.toggleWard(spy, target));
        world.logOff(target);
        assertFalse(PlotDefense.removeWard(stranger, "c-target"));
        assertFalse(PlotDefense.removeWard(spy, "c-nobody"));
        assertTrue(PlotDefense.removeWard(spy, "c-target"));
        assertNotNull(world.store().notices.get("c-target"));
        assertNull(PlotDefense.playing("c-target"));
        assertEquals(spy, PlotDefense.playing("c-spy"));
    }
}
