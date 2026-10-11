package net.tfminecraft.rpcharacters.plots;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PlotServiceTest {
    @TempDir Path directory;
    PlotWorld world;
    PlayerMock master, assassin, helper, target, guard, stranger;
    CommandSender staff;
    final List<String> staffSaid = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        world = new PlotWorld(directory);
        master = world.player("Master", "c-master", 50);
        assassin = world.player("Assassin", "c-assassin", 60);
        helper = world.player("Helper", "c-helper", 80);
        target = world.player("Target", "c-target", 40);
        guard = world.player("Guard", "c-guard", 70);
        stranger = world.player("Stranger", null, null);
        staff = mock(CommandSender.class);
        when(staff.getName()).thenReturn("Staffer");
        doAnswer(call -> staffSaid.add(call.getArgument(0))).when(staff).sendMessage(anyString());
    }

    @AfterEach
    void close() { world.close(); }

    Plot approved() {
        Plot plot = PlotService.request(master);
        assertTrue(PlotService.approve(staff, plot.code, "Target"));
        return plot;
    }

    Plot recruited() {
        Plot plot = approved();
        assertTrue(PlotService.invite(master, assassin));
        assertTrue(PlotService.accept(assassin, plot.code));
        assertTrue(PlotService.assign(master, "c-assassin", PlotRole.ASSASSIN, null));
        return plot;
    }

    Plot building() {
        Plot plot = recruited();
        assertTrue(PlotService.begin(master));
        return plot;
    }

    @Test
    void intrigueUsesTheRollRollsOnFirstPlayAndOtherwiseTheBase() {
        assertEquals(50, PlotService.intrigue(world.character(master)));
        world.intrigue.remove("c-master");
        assertEquals(40, PlotService.intrigue(world.character(master)));
        world.intrigue.remove("c-master");
        world.intrigues.when(() -> Intrigue.of(any(), anyString())).thenThrow(new IOException("disk"));
        assertEquals(50, PlotService.intrigue(world.character(master)));
        world.intrigues.when(() -> Intrigue.of(any(), anyString())).thenThrow(new IllegalStateException("no character"));
        assertEquals(50, PlotService.intrigue(world.character(master)));
        world.logOff(helper);
        world.intrigue.remove("c-helper");
        assertEquals(50, PlotService.intrigue(new PlotCharacter("c-helper", helper.getUniqueId(), "Helper", "H")));
        world.intrigue.put("c-master", 50);
        assertEquals(58, PlotService.offense(world.character(master)));
        assertEquals(58, PlotService.defense(world.character(master)));
    }

    @Test
    void approachChangesHaveACooldown() {
        assertFalse(PlotService.setApproach(stranger, PlotApproach.OFFENSIVE));
        assertTrue(world.heard(stranger, "living active character"));
        assertFalse(PlotService.setApproach(master, PlotApproach.NEUTRAL));
        assertTrue(PlotService.setApproach(master, PlotApproach.OFFENSIVE));
        assertTrue(world.last(master).contains("+15 Intrigue when plotting, +0 when defending"));
        assertFalse(PlotService.setApproach(master, PlotApproach.DEFENSIVE));
        assertTrue(world.last(master).contains("again in"));
        world.pass(PlotConfig.approachCooldown());
        assertTrue(PlotService.setApproach(master, PlotApproach.DEFENSIVE));
    }

    @Test
    void durationsReadAsDaysHoursAndMinutes() {
        assertEquals("1m", PlotService.duration(0));
        assertEquals("1d 2h 3m", PlotService.duration(PlotConfig.DAY + 2 * 3_600_000 + 3 * 60_000));
        assertEquals("2d", PlotService.duration(2 * PlotConfig.DAY));
    }

    @Test
    void requestsNeedStaffApprovalAndOnePlotPerPlayer() {
        assertNull(PlotService.request(stranger));
        Plot plot = PlotService.request(master);
        assertEquals("P-ABCDE", plot.code);
        assertTrue(world.last(master).contains("P-ABCDE"));
        assertNull(PlotService.request(master));
        world.play(master, "c-alt");
        assertNull(PlotService.request(master), "another character of the same player");
        assertTrue(world.last(master).contains("with this or another character"));
        world.play(master, "c-master");
        assertFalse(PlotService.approve(staff, "P-NONE", "Target"));
        assertFalse(PlotService.approve(staff, plot.code, "Nobody"));
        assertFalse(PlotService.approve(staff, plot.code, "Master"));
        world.store().cooldowns.put(master.getUniqueId() + "|c-target", world.clock.get() + 1000);
        assertFalse(PlotService.approve(staff, plot.code, "Target"));
        assertTrue(staffSaid.getLast().contains("with any character"));
        world.store().cooldowns.put(master.getUniqueId() + "|c-target", world.clock.get() - 1);
        world.logOff(target);
        target.disconnect();
        world.offline.put("Target", new PlotCharacter("c-target", target.getUniqueId(), "Target", "Char Target"));
        assertTrue(PlotService.approve(staff, plot.code, "Target"));
        assertEquals(PlotPhase.RECRUITING, plot.phase);
        assertTrue(world.last(master).contains("has been approved"));
        assertFalse(PlotService.approve(staff, plot.code, "Target"));
        assertFalse(PlotService.deny(staff, plot.code));
        assertFalse(PlotService.deny(staff, "P-NONE"));

        Plot second = PlotService.request(helper);
        assertEquals("P-FGHJK", second.code);
        assertTrue(PlotService.deny(staff, second.code));
        assertEquals(PlotPhase.DISSOLVED, second.phase);
        assertTrue(world.last(helper).contains("was not approved"));
        Plot colliding = new Plot();
        colliding.code = "P-LMNPQ";
        colliding.master = new PlotCharacter("x", null, "x", "x");
        colliding.phase = PlotPhase.FAILED;
        world.store().plots.add(colliding);
        assertEquals("P-RSTUV", PlotService.request(guard).code);
    }

    @Test
    void staffCanEndAPlotOrPassItsDays() {
        assertFalse(PlotService.cancel(staff, "P-NONE"));
        Plot request = PlotService.request(master);
        assertTrue(PlotService.cancel(staff, request.code));
        assertTrue(world.last(master).contains("The plot has come to nothing"));
        assertFalse(PlotService.cancel(staff, request.code));
        assertFalse(PlotService.passDay(staff, request.code));
        Plot plot = building();
        assertTrue(PlotService.passDay(staff, plot.code));
        assertEquals(1, plot.day);
        assertTrue(staffSaid.getLast().contains("day 1"));
        Plot other = PlotService.request(helper);
        assertTrue(PlotService.passDay(staff, other.code));
        assertEquals(PlotPhase.REQUESTED, other.phase);
    }

    @Test
    void proposalsAreInPersonHideTheTargetAndRolesAreLimited() {
        assertFalse(PlotService.invite(stranger, assassin));
        assertFalse(PlotService.invite(helper, assassin));
        assertTrue(world.last(helper).contains("not leading a plot"));
        Plot plot = approved();
        assertFalse(PlotService.invite(master, target));
        assertTrue(world.last(master).contains("cannot invite"));
        assertFalse(PlotService.invite(master, master));
        PlayerMock alt = world.player("Alt", null, null);
        world.active.put(alt.getUniqueId(), new PlotCharacter("c-alt", target.getUniqueId(), "Target", "Alt of Target"));
        assertFalse(PlotService.invite(master, alt));
        world.at(helper, 30);
        assertFalse(PlotService.invite(master, helper));
        world.at(helper, 0);
        assertTrue(PlotService.invite(master, assassin));
        assertTrue(world.last(assassin).contains("You will learn against whom if you accept"));
        assertFalse(world.last(assassin).contains("Char Target"));
        assertFalse(PlotService.accept(stranger, plot.code));
        assertFalse(PlotService.accept(helper, plot.code));
        assertTrue(world.last(helper).contains("no longer open"));
        assertTrue(PlotService.accept(assassin, plot.code));
        assertTrue(world.last(assassin).contains("joined the plot against §eChar Target"));
        assertTrue(world.last(master).contains("has joined your plot"));
        assertFalse(PlotService.invite(master, assassin));

        assertTrue(PlotService.invite(master, helper));
        assertFalse(PlotService.decline(stranger, plot.code));
        assertFalse(PlotService.decline(guard, plot.code));
        assertTrue(PlotService.decline(helper, plot.code));
        assertTrue(world.last(master).contains("turned down"));
        assertNull(plot.informed("c-helper"), "declining knows nothing of the target");
        assertTrue(PlotService.invite(master, helper));
        world.pass(PlotConfig.inviteDuration() + 1);
        assertFalse(PlotService.accept(helper, plot.code));

        PlayerMock third = world.player("Third", "c-third", 30), fourth = world.player("Fourth", "c-fourth", 30);
        PlayerMock fifth = world.player("Fifth", "c-fifth", 30);
        for (PlayerMock recruit : List.of(helper, third, fourth)) {
            assertTrue(PlotService.invite(master, recruit));
            assertTrue(PlotService.accept(recruit, plot.code));
        }
        assertFalse(PlotService.invite(master, fifth));
        assertTrue(world.last(master).contains("no room"));
        plot.invitations.put("c-fifth", world.clock.get() + 1000);
        assertFalse(PlotService.accept(fifth, plot.code));
        assertTrue(world.last(fifth).contains("cannot join"));

        assertFalse(PlotService.assign(master, "nobody", PlotRole.ASSASSIN, null));
        assertTrue(PlotService.assign(master, "c-assassin", PlotRole.ASSASSIN, null));
        assertTrue(PlotService.assign(master, "c-helper", PlotRole.ASSASSIN, null));
        assertFalse(PlotService.assign(master, "c-third", PlotRole.ASSASSIN, null));
        assertTrue(PlotService.assign(master, "c-third", PlotRole.COLLABORATOR, null));
        assertEquals(CollaboratorTask.HIDE, plot.member("c-third").task);
        assertTrue(PlotService.assign(master, "c-third", PlotRole.COLLABORATOR, CollaboratorTask.PROGRESS));
        assertTrue(PlotService.assign(master, "c-fourth", PlotRole.COLLABORATOR, CollaboratorTask.STRENGTH));
        assertTrue(world.last(fourth).contains("Collaborator (Strengthen the plot)"));
        assertTrue(PlotService.assign(master, "c-helper", PlotRole.UNASSIGNED, null));
        assertFalse(PlotService.assign(master, "c-helper", PlotRole.COLLABORATOR, CollaboratorTask.HIDE));
        assertNull(plot.member("c-helper").task);

        assertFalse(PlotService.dismiss(master, "nobody"));
        assertTrue(PlotService.dismiss(master, "c-helper"));
        assertTrue(world.last(helper).contains("no longer part of the plot against"));
        assertNotNull(plot.informed("c-helper"), "a dismissed member still knows");
        assertFalse(PlotService.dismiss(stranger, "c-third"));
    }

    @Test
    void anAccountTakesPartInOnePlotAndBannedCharactersNeverJoin() {
        Plot plot = approved();
        Plot other = PlotService.request(guard);
        PlotService.approve(staff, other.code, "Helper");
        PlotService.invite(guard, assassin);
        PlotService.accept(assassin, other.code);
        assertFalse(PlotService.invite(master, assassin) && PlotService.accept(assassin, plot.code));
        world.play(assassin, "c-assassin-alt");
        assertTrue(PlotService.invite(master, assassin));
        assertFalse(PlotService.accept(assassin, plot.code), "another character of an account already in a plot");
        world.store().bans.put("c-helper|c-target", "P-OLD");
        assertFalse(PlotService.invite(master, helper));
        plot.invitations.put("c-helper", world.clock.get() + 1000);
        assertFalse(PlotService.accept(helper, plot.code));
    }

    @Test
    void conspiratorsMarkWhoElseKnows() {
        assertFalse(PlotService.inform(stranger, helper));
        assertFalse(PlotService.inform(master, helper));
        Plot request = PlotService.request(master);
        assertFalse(PlotService.inform(master, helper));
        assertTrue(world.last(master).contains("not part of a running plot"));
        PlotService.cancel(staff, request.code);
        Plot running = recruited();
        assertFalse(PlotService.inform(assassin, stranger));
        assertFalse(PlotService.inform(assassin, target));
        assertFalse(PlotService.inform(assassin, master));
        assertTrue(PlotService.inform(assassin, helper));
        assertTrue(world.last(helper).contains("You now know of a plot"));
        assertEquals(PlotRole.INFORMED, running.role("c-helper"));
        PlotService.invite(master, helper);
        PlotService.accept(helper, running.code);
        assertNull(running.informed("c-helper"), "joining replaces knowing");
    }

    @Test
    void thePlotStartsWithAnAssassinAndLaunchesWhenReady() {
        assertFalse(PlotService.begin(stranger));
        Plot plot = approved();
        assertFalse(PlotService.launch(master));
        assertFalse(PlotService.begin(master));
        assertTrue(world.last(master).contains("at least one conspirator"));
        PlotService.invite(master, assassin);
        PlotService.accept(assassin, plot.code);
        PlotService.assign(master, "c-assassin", PlotRole.ASSASSIN, null);
        assertTrue(PlotService.begin(master));
        assertEquals(PlotPhase.BUILDING, plot.phase);
        assertEquals(51.2, plot.required, 1e-9);
        assertTrue(world.last(assassin).contains("is under way"));
        assertFalse(PlotService.begin(master));
        assertFalse(PlotService.launch(stranger));
        assertFalse(PlotService.launch(master));
        plot.phase = PlotPhase.READY;
        plot.members.getFirst().role = PlotRole.UNASSIGNED;
        assertFalse(PlotService.launch(master));
        assertTrue(world.last(master).contains("no assassin left"));
        plot.members.getFirst().role = PlotRole.ASSASSIN;
        assertTrue(PlotService.launch(master));
        assertEquals(PlotPhase.INVESTIGATION, plot.phase);
        assertFalse(world.heard(target, "danger"));
        plot.phase = PlotPhase.READY;
        plot.discovered = true;
        assertTrue(PlotService.launch(master));
        assertTrue(world.last(target).contains("danger closing in"));
    }

    @Test
    void membersMayWithdrawUntilTheFinalPhaseKnowingItAndMastersAbandon() {
        assertFalse(PlotService.leave(stranger));
        assertFalse(PlotService.leave(helper));
        Plot plot = recruited();
        assertFalse(PlotService.leave(master));
        assertTrue(world.last(master).contains("abandons it instead"));
        plot.phase = PlotPhase.INVESTIGATION;
        assertFalse(PlotService.leave(assassin));
        assertTrue(world.last(assassin).contains("too late"));
        plot.phase = PlotPhase.READY;
        assertTrue(PlotService.leave(assassin));
        assertTrue(world.last(master).contains("has withdrawn"));
        assertNotNull(plot.informed("c-assassin"));
        assertFalse(PlotService.abandon(stranger));
        plot.phase = PlotPhase.BUILDING;
        PlotService.invite(master, helper);
        PlotService.accept(helper, plot.code);
        assertTrue(PlotService.abandon(master));
        assertEquals(PlotPhase.DISSOLVED, plot.phase);
        assertTrue(world.last(helper).contains("has abandoned the plot against"));
        assertTrue(world.store().cooldowns.get(master.getUniqueId() + "|c-target") > world.clock.get());
    }

    @Test
    void messagesWaitForTheCharacterAndRollTheirIntrigue() {
        PlotCharacter away = world.character(helper);
        world.logOff(helper);
        for (int index = 0; index < 32; index++) PlotService.tell(away, "note " + index);
        assertEquals(30, world.store().notices.get("c-helper").size());
        PlotService.deliver(helper);
        assertFalse(world.heard(helper, "note"));
        world.active.put(helper.getUniqueId(), away);
        world.intrigue.remove("c-helper");
        PlotService.deliver(helper);
        assertTrue(world.heard(helper, "note 31"));
        assertFalse(world.heard(helper, "note 1 "));
        assertEquals(40, world.intrigue.get("c-helper"));
        assertNull(world.store().notices.get("c-helper"));
        PlotService.deliver(helper);
    }

    @Test
    void timersExpireRequestsRecruitingInvitationsAndStartTheHunt() {
        Plot request = PlotService.request(helper);
        world.pass(PlotConfig.requestExpiry());
        PlotService.tick();
        assertEquals(PlotPhase.DISSOLVED, request.phase);
        assertTrue(world.last(helper).contains("expired without approval"));

        Plot plot = approved();
        PlotService.invite(master, assassin);
        world.pass(PlotConfig.inviteDuration() + 1);
        PlotService.tick();
        assertTrue(plot.invitations.isEmpty());
        world.pass(PlotConfig.recruiting());
        PlotService.tick();
        assertEquals(PlotPhase.DISSOLVED, plot.phase);
        assertTrue(world.last(master).contains("never got under way"));
        assertNotNull(world.store().cooldowns.get(master.getUniqueId() + "|c-target"));

        world.store().cooldowns.clear();
        Plot running = building();
        running.phase = PlotPhase.INVESTIGATION;
        running.phaseStartedAt = world.clock.get();
        PlotService.tick();
        assertEquals(PlotPhase.INVESTIGATION, running.phase);
        world.pass(PlotConfig.investigation());
        PlotService.tick();
        assertEquals(PlotPhase.EXECUTION, running.phase);
        assertTrue(world.last(assassin).contains("may now hunt"));
        assertEquals(running.phaseStartedAt, running.huntDayStartedAt);
    }

    @Test
    void buildingDaysEndReadyOrFailedAndAReadyPlotKeepsLosingSecrecy() {
        Plot plot = building();
        plot.progress = 99;
        plot.strength = 1000;
        world.pass(PlotConfig.DAY);
        PlotService.tick();
        assertEquals(PlotPhase.READY, plot.phase);
        assertTrue(world.heard(master, "Begin the final phase within"));
        assertTrue(world.last(assassin).contains("is ready to strike"));
        double secrecy = plot.secrecy;
        world.pass(PlotConfig.DAY);
        PlotService.tick();
        assertTrue(plot.secrecy < secrecy);
        plot.secrecy = 1;
        assertTrue(PlotService.passDay(staff, plot.code));
        assertTrue(plot.exposed);
        assertTrue(world.heard(target, "The conspirators are"));
        world.pass(PlotConfig.ready());
        PlotService.tick();
        assertEquals(PlotPhase.FAILED, plot.phase);
        assertTrue(world.last(assassin).contains("never launched"));

        world.store().cooldowns.clear();
        Plot weak = building();
        weak.progress = 99;
        world.pass(PlotConfig.DAY);
        PlotService.tick();
        assertEquals(PlotPhase.FAILED, weak.phase);
        assertTrue(world.last(assassin).contains("without enough strength"));
    }

    @Test
    void summariesShowHuntDaysOnlyInTheHunt() {
        Plot plot = building();
        assertFalse(PlotService.summary(plot).contains("hunt days"));
        plot.phase = PlotPhase.EXECUTION;
        assertTrue(PlotService.summary(plot).endsWith("hunt days 0/4"));
        assertEquals(List.of("Char Master (plot master)", "Char Assassin"), PlotService.conspiratorNames(plot));
    }

    @Test
    void aFailedSaveIsLoggedAndTheGameCarriesOn() throws Exception {
        Files.writeString(directory.resolve("not-a-folder"), "");
        PlotService.load(directory.resolve("not-a-folder/plots.json"));
        var logger = mock(java.util.logging.Logger.class);
        when(world.plugin.getLogger()).thenReturn(logger);
        assertNotNull(PlotService.request(master));
        org.mockito.Mockito.verify(logger).log(org.mockito.ArgumentMatchers.eq(java.util.logging.Level.SEVERE), anyString(), any(IOException.class));
    }
}
