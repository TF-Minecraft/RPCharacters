package net.tfminecraft.rpcharacters.plots;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import net.tfminecraft.rpcharacters.permadeath.PermadeathBattleExemption;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PlotHuntTest {
    @TempDir Path directory;
    PlotWorld world;
    PlayerMock master, assassin, helper, target, guard, stranger;
    CommandSender staff = mock(CommandSender.class);

    @BeforeEach
    void setup() throws Exception {
        world = new PlotWorld(directory);
        master = world.player("Master", "c-master", 50);
        assassin = world.player("Assassin", "c-assassin", 60);
        helper = world.player("Helper", "c-helper", 80);
        target = world.player("Target", "c-target", 40);
        guard = world.player("Guard", "c-guard", 70);
        stranger = world.player("Stranger", null, null);
        when(staff.getName()).thenReturn("Staffer");
    }

    @AfterEach
    void close() { world.close(); }

    Plot building(PlayerMock leader) {
        Plot plot = PlotService.request(leader);
        PlotService.approve(staff, plot.code, "Target");
        PlotService.invite(leader, assassin);
        PlotService.accept(assassin, plot.code);
        PlotService.assign(leader, "c-assassin", PlotRole.ASSASSIN, null);
        PlotService.begin(leader);
        return plot;
    }

    Plot hunting() {
        Plot plot = building(master);
        plot.phase = PlotPhase.EXECUTION;
        plot.phaseStartedAt = plot.huntDayStartedAt = world.clock.get();
        return plot;
    }

    void hunts(Plot plot, PlayerMock blade) {
        plot.member(world.character(blade).characterId).huntingSince = world.clock.get() - PlotConfig.huntWarmup();
    }

    @Test
    void assassinsHuntOnlyOnceTheirAssassinsMayStrikeAndNeverFromAWarband() {
        assertFalse(PlotHunt.hunt(stranger));
        assertFalse(PlotHunt.hunt(helper));
        Plot plot = building(master);
        assertFalse(PlotHunt.hunt(assassin));
        assertTrue(world.last(assassin).contains("Only an assassin can hunt"));
        plot.phase = PlotPhase.EXECUTION;
        plot.phaseStartedAt = plot.huntDayStartedAt = world.clock.get();
        assertFalse(PlotHunt.hunt(master));
        world.battles.when(() -> PermadeathBattleExemption.isInStartedBattle(assassin)).thenReturn(true);
        assertFalse(PlotHunt.hunt(assassin));
        world.battles.when(() -> PermadeathBattleExemption.isInStartedBattle(assassin)).thenReturn(false);
        world.factions.when(() -> PlotFactions.inWarband(assassin)).thenReturn(true);
        assertFalse(PlotHunt.hunt(assassin));
        assertTrue(world.last(assassin).contains("warband or battle"));
        world.factions.when(() -> PlotFactions.inWarband(assassin)).thenReturn(false);
        assertFalse(PlotHunt.hunting(assassin));
        assertTrue(PlotHunt.hunt(assassin));
        world.characters.verify(() -> PlotCharacters.keepEvilSession(assassin, true));
        assertTrue(PlotHunt.hunting(assassin));
        assertTrue(PlotsApi.hunting(assassin));
        assertFalse(PlotHunt.hunting(stranger));
        assertFalse(PlotHunt.hunting(master));
        assertTrue(world.last(assassin).contains("You set out to hunt"));
        assertFalse(PlotHunt.hunting(plot.member("c-assassin")));
        assertFalse(PlotHunt.hunting((PlotMember) null));
        PlotService.tick();
        assertFalse(world.heard(assassin, "The hunt is on"));
        world.characters.verify(() -> PlotCharacters.keepEvilSession(assassin, false));
        world.pass(PlotConfig.huntWarmup());
        PlotService.tick();
        assertTrue(world.heard(assassin, "The hunt is on"));
        assertTrue(world.said(target).isEmpty(), "a target who knows nothing feels nothing");
        assertFalse(PlotHunt.hunt(assassin));
        assertTrue(world.last(assassin).contains("committed to the hunt"));
        world.pass(PlotConfig.huntMinimum());
        assertTrue(PlotHunt.hunt(assassin));
        assertTrue(world.last(assassin).contains("call off the hunt"));
    }

    @Test
    void aKnowingTargetFeelsTheHuntOnlyWhenItTakesEffect() {
        Plot plot = hunting();
        plot.discovered = true;
        plot.secrecy = PlotConfig.huntAlertSecrecy() + 1;
        assertFalse(PlotHunt.alerted(plot));
        plot.secrecy = PlotConfig.huntAlertSecrecy();
        assertTrue(PlotHunt.alerted(plot));
        assertTrue(PlotHunt.hunt(assassin));
        world.pass(PlotConfig.huntWarmup());
        PlotService.tick();
        assertTrue(world.heard(target, "You feel hunted"));
        int heard = world.said(target).size();
        PlotService.deliver(target);
        assertEquals(heard, world.said(target).size(), "no reminder on login");
        world.pass(PlotConfig.huntMinimum());
        assertTrue(PlotHunt.hunt(assassin));
        assertEquals(heard, world.said(target).size(), "no word when a hunt ends");
        assertTrue(PlotHunt.hunt(assassin));
        world.pass(PlotConfig.huntWarmup());
        world.logOff(assassin);
        PlotService.tick();
        assertEquals(0, plot.member("c-assassin").huntingSince, "logging off ends the hunt");
    }

    @Test
    void huntDaysCountOnlyWhileTheTargetIsOutInTheOpen() {
        Plot plot = hunting();
        PlotService.tick();
        assertEquals(0, plot.exposure, "the first check only starts the clock");
        world.pass(60_000);
        PlotService.tick();
        assertEquals(60_000, plot.exposure);
        world.logOff(target);
        world.pass(60_000);
        PlotService.tick();
        assertEquals(60_000, plot.exposure, "offline time doesn't count");
        world.play(target, "c-target");
        doReturn(100).when(world.world).getHighestBlockYAt(0, 0);
        world.pass(60_000);
        PlotService.tick();
        assertEquals(60_000, plot.exposure, "time underground doesn't count");
        doReturn(70).when(world.world).getHighestBlockYAt(0, 0);
        world.pass(3_600_000);
        PlotService.tick();
        assertEquals(120_000, plot.exposure, "under a roof counts, and a long gap adds at most a minute");
        for (int minute = 0; minute < 28; minute++) {
            world.pass(60_000);
            PlotService.tick();
        }
        assertEquals(1, plot.huntDays);
        assertTrue(plot.dayCounted);
        assertTrue(world.heard(master, "hunt day §e1§7 of §e4§7 counts"));
        long exposure = plot.exposure;
        world.pass(60_000);
        PlotService.tick();
        assertEquals(exposure, plot.exposure, "a counted day stops counting");
        world.pass(PlotConfig.DAY);
        PlotService.tick();
        assertFalse(plot.dayCounted);
        assertEquals(60_000, plot.exposure);
        assertTrue(PlotService.passDay(staff, plot.code));
        assertEquals(2, plot.huntDays);
    }

    @Test
    void findableMeansAHuntingWorldAndNearTheSurface() {
        assertTrue(PlotHunt.findable(target));
        var config = new YamlConfiguration();
        config.set("execution.findable.worlds", List.of("TFMC_Map"));
        PlotConfig.load(config);
        assertFalse(PlotHunt.findable(target));
        config.set("execution.findable.worlds", List.of(world.world.getName()));
        PlotConfig.load(config);
        assertTrue(PlotHunt.findable(target));
        PlayerMock nowhere = spyLocation(new Location(null, 0, 64, 0));
        assertFalse(PlotHunt.findable(nowhere));
    }

    private PlayerMock spyLocation(Location location) {
        PlayerMock player = org.mockito.Mockito.spy(world.player("Nowhere", "c-nowhere", 50));
        doReturn(location).when(player).getLocation();
        return player;
    }

    @Test
    void aTargetWhoOutlastsTheHuntSurvivesAndIsSafeFromThoseConspiratorsForever() {
        Plot plot = hunting();
        plot.discovered = true;
        plot.huntDays = PlotHunt.totalDays(plot);
        plot.dayCounted = true;
        PlotService.tick();
        assertEquals(PlotPhase.EXECUTION, plot.phase, "the last hunt day runs to its end");
        plot.huntDays = PlotHunt.totalDays(plot) - 1;
        assertTrue(PlotService.passDay(staff, plot.code));
        assertEquals(PlotPhase.FAILED, plot.phase);
        assertEquals("The target survived", plot.outcome);
        assertTrue(world.last(assassin).contains("survived the plot"));
        assertTrue(world.last(target).contains("You survived the plot"));
        assertTrue(PlotHunt.banned(world.character(master), world.character(target)));
        assertTrue(PlotHunt.banned(world.character(assassin), world.character(target)));
        Plot again = PlotService.request(master);
        assertFalse(PlotService.approve(staff, again.code, "Target"));
        PlotService.cancel(staff, again.code);

        Plot capped = PlotService.request(helper);
        PlotService.approve(staff, capped.code, "Target");
        capped.phase = PlotPhase.EXECUTION;
        capped.phaseStartedAt = capped.huntDayStartedAt = world.clock.get() - PlotConfig.maxHunt();
        int heard = world.said(target).size();
        PlotService.tick();
        assertEquals(PlotPhase.FAILED, capped.phase, "the hard cap ends the hunt even with no day counted");
        assertEquals(heard, world.said(target).size(), "a target who never knew isn't told");
    }

    @Test
    void spareStrengthBuysHuntDaysAndASharperHunt() {
        Plot plot = hunting();
        plot.required = 50;
        plot.strength = 50;
        assertEquals(4, PlotHunt.totalDays(plot));
        assertEquals(1, PlotHunt.sharpness(plot));
        plot.strength = 75;
        assertEquals(5, PlotHunt.totalDays(plot));
        plot.strength = 500;
        assertEquals(6, PlotHunt.totalDays(plot));
        assertEquals(2, PlotHunt.sharpness(plot));
        world.at(target, 1000, -1000);
        assertTrue(PlotHunt.area(plot, assassin).contains("around §ex 1062, z -938"), PlotHunt.area(plot, assassin));
    }

    @Test
    void huntingAssassinsHearSightingsAndTheTargetsRoughWhereabouts() {
        Plot plot = hunting();
        plot.required = 50;
        plot.strength = 50;
        hunts(plot, assassin);
        world.at(target, 1000, -1000);
        PlotService.tick();
        assertTrue(world.heard(assassin, "Word comes that §eChar Target§7 is out in the open."));
        assertTrue(world.heard(assassin, "around §ex 1125, z -875§7, about 1450 blocks north-east of you."));
        int heard = world.said(assassin).size();
        PlotService.tick();
        assertEquals(heard, world.said(assassin).size(), "neither again before their time");
        world.pass(PlotConfig.areaInterval());
        world.at(target, 10, 10);
        PlotService.tick();
        assertTrue(world.last(assassin).contains("close to you"));
        world.logOff(target);
        PlotService.tick();
        world.play(target, "c-target");
        world.pass(60_000);
        PlotService.tick();
        assertFalse(world.last(assassin).contains("Word comes"), "no new sighting before the gap");
        world.logOff(target);
        PlotService.tick();
        world.play(target, "c-target");
        world.pass(PlotConfig.sightingGap());
        PlotService.tick();
        assertTrue(world.heard(assassin, "Word comes"));
        world.elsewhere(assassin);
        assertTrue(PlotHunt.area(plot, assassin).endsWith("around §ex 125, z 125§7."));
        world.factions.when(() -> PlotFactions.lands(any())).thenReturn("Thalendor");
        assertTrue(PlotHunt.area(plot, assassin).endsWith(", in the lands of §eThalendor§7."));
        doReturn(200).when(world.world).getHighestBlockYAt(10, 10);
        assertTrue(PlotHunt.area(plot, assassin).startsWith("Nobody has seen"));
        world.logOff(target);
        assertTrue(PlotHunt.area(plot, assassin).startsWith("Nobody has seen"));
        assertEquals("south", PlotHunt.direction(0, 10));
        assertEquals("west", PlotHunt.direction(-10, 0));
        var config = new YamlConfiguration();
        config.set("execution.area.interval-seconds", 0);
        PlotConfig.load(config);
        world.pass(PlotConfig.DAY);
        heard = world.said(assassin).size();
        PlotService.tick();
        assertEquals(heard, world.said(assassin).size(), "no hints when turned off");
    }

    @Test
    void onlyAKnowingHuntedTargetIsLockedToTheirCharacter() {
        Plot plot = hunting();
        assertFalse(PlotHunt.locked("c-target"));
        assertFalse(PlotHunt.locked(target));
        plot.discovered = true;
        assertTrue(PlotHunt.locked("c-target"));
        assertTrue(PlotHunt.locked(target));
        assertFalse(PlotHunt.locked(stranger));
        plot.phase = PlotPhase.INVESTIGATION;
        assertFalse(PlotHunt.locked("c-target"));
    }

    @Test
    void investigatorsRollOncePerConspiratorTheyComeNearAndEncountersAreLogged() throws Exception {
        Plot plot = building(master);
        plot.informed.add(world.character(helper));
        plot.phase = PlotPhase.INVESTIGATION;
        PlayerMock watcher = world.player("Watcher", "c-watcher", 100);
        world.store().settings("c-target").investigators.add(world.character(watcher));
        world.store().settings("c-target").investigators.add(new PlotCharacter("c-gone", null, "Gone", "Gone"));
        world.at(master, 100);
        world.at(helper, 100);
        world.at(assassin, 100);
        world.logOff(target);
        PlotHunt.scan();
        assertTrue(plot.investigated.isEmpty());
        plot.discovered = true;
        world.intrigue.put("c-assassin", 0);
        world.at(assassin, 2);
        PlotHunt.scan();
        assertEquals(List.of("c-assassin"), List.copyOf(plot.investigated));
        assertTrue(world.last(watcher).contains("watches you the way a hunter watches prey"));
        world.intrigue.put("c-helper", 100);
        world.at(helper, 2);
        world.intrigue.put("c-watcher", 0);
        PlotHunt.scan();
        assertTrue(plot.investigated.contains("c-helper"));
        assertFalse(world.heard(watcher, "Char Helper"));
        world.play(target, "c-target");
        world.at(target, 98);
        world.at(watcher, 500);
        world.intrigue.put("c-target", 60);
        world.intrigue.put("c-master", 32);
        PlotHunt.scan();
        assertTrue(world.last(target).contains("Char Master takes an unusual interest"));
        PlotHunt.scan();
        String log = String.join("\n", PlotLog.read(plot.code));
        assertTrue(log.contains("ENCOUNTER  Char Master (Master) (Plot master) within 2 blocks"), log);
        assertTrue(log.contains("INVESTIGATE"));
        plot.phase = PlotPhase.READY;
        PlotHunt.scan();
        PlotService.request(guard);
        PlotHunt.scan();
    }

    @Test
    void anInvestigatorNeverSuspectsThemselves() {
        Plot plot = building(master);
        plot.phase = PlotPhase.INVESTIGATION;
        plot.discovered = true;
        world.store().settings("c-target").investigators.add(world.character(assassin));
        world.at(target, 500);
        world.at(master, 500);
        PlotHunt.scan();
        assertFalse(plot.investigated.contains("c-assassin"));
    }

    @Test
    void theTargetDiesForGoodToAHuntingAssassinAndLeavesClues() {
        Plot plot = building(master);
        PlotHunt.died(target, assassin);
        assertEquals(PlotPhase.BUILDING, plot.phase);
        plot.phase = PlotPhase.EXECUTION;
        PlotHunt.died(stranger, assassin);
        PlotHunt.died(target, null);
        PlotHunt.died(target, helper);
        PlotHunt.damaged(target, assassin);
        world.pass(PlotConfig.hitWindow() + 1);
        PlotHunt.died(target, null);
        PlotHunt.damaged(target, assassin);
        PlotHunt.died(target, null);
        assertEquals(PlotPhase.EXECUTION, plot.phase, "only a hunting assassin's kill counts");
        plot.member("c-assassin").huntingSince = world.clock.get();
        PlotHunt.died(target, assassin);
        assertEquals(PlotPhase.EXECUTION, plot.phase, "not while the hunt is warming up");
        hunts(plot, assassin);
        PlotService.invite(master, helper);
        plot.members.add(new PlotMember(world.character(helper)));
        plot.member("c-helper").role = PlotRole.ASSASSIN;
        hunts(plot, helper);
        PlayerMock far = world.player("Far", "c-far", 50);
        plot.members.add(new PlotMember(world.character(far)));
        plot.member("c-far").role = PlotRole.ASSASSIN;
        hunts(plot, far);
        world.at(far, 500);
        PlotHunt.damaged(target, assassin);
        PlotService.tick();
        PlotHunt.died(target, null);
        assertEquals(PlotPhase.SUCCEEDED, plot.phase);
        assertEquals(List.of("c-target"), world.permakilled);
        assertEquals(List.of("Assassin", "Assassin", "Helper"), world.clues);
        assertTrue(world.last(master).contains("is dead. The plot has succeeded"));

        world.store().cooldowns.clear();
        Plot idle = PlotService.request(guard);
        PlotService.approve(staff, idle.code, "Target");
        idle.phase = PlotPhase.EXECUTION;
        Plot striking = PlotService.request(stranger == null ? helper : world.player("Other", "c-other", 50));
        PlotService.approve(staff, striking.code, "Target");
        striking.phase = PlotPhase.EXECUTION;
        PlotMember blade = new PlotMember(world.character(assassin));
        blade.role = PlotRole.ASSASSIN;
        striking.members.add(blade);
        hunts(striking, assassin);
        PlotHunt.died(target, assassin);
        assertEquals(PlotPhase.SUCCEEDED, striking.phase);
        assertEquals(PlotPhase.EXECUTION, idle.phase);
    }

    @Test
    void aFallenHuntingAssassinDiesForGoodAndIsRevealed() {
        Plot plot = building(master);
        PlayerMock second = world.player("Second", "c-second", 50);
        PlotService.invite(master, second);
        PlotService.accept(second, plot.code);
        PlotService.assign(master, "c-second", PlotRole.ASSASSIN, null);
        plot.phase = PlotPhase.EXECUTION;
        PlotHunt.died(assassin, helper);
        assertTrue(world.permakilled.isEmpty(), "an assassin who is not hunting dies like anyone else");
        hunts(plot, assassin);
        hunts(plot, second);
        PlotHunt.died(assassin, null);
        assertEquals(2, plot.withRole(PlotRole.ASSASSIN).size());
        PlotHunt.died(assassin, helper);
        assertEquals(List.of("c-assassin"), world.permakilled);
        assertTrue(world.last(helper).contains("fallen assassin is revealed"));
        assertTrue(world.last(target).contains("Char Assassin"));
        assertTrue(world.last(master).contains("has fallen"));
        int targetHeard = world.said(target).size();
        PlotHunt.damaged(second, target);
        PlotService.tick();
        PlotHunt.died(second, null);
        assertEquals(targetHeard + 1, world.said(target).size());
        assertEquals(PlotPhase.FAILED, plot.phase);
        world.store().cooldowns.clear();
        Plot quiet = building(guard);
        quiet.phase = PlotPhase.EXECUTION;
        world.play(assassin, "c-assassin");
        hunts(quiet, assassin);
        world.logOff(target);
        PlotHunt.died(assassin, helper);
        assertEquals(PlotPhase.FAILED, quiet.phase);
    }

    @Test
    void permanentDeathsEndOrThinThePlotsAndProtectionsAroundTheCharacter() {
        Plot plot = building(master);
        plot.informed.add(world.character(helper));
        world.store().settings("c-target").investigators.add(world.character(helper));
        world.store().settings("c-target").confidant = world.character(helper);
        world.store().wardsOf("c-spy").add(world.character(helper));
        world.store().settings("c-helper");
        PlotHunt.characterDied("c-helper");
        assertTrue(plot.informed.isEmpty());
        assertTrue(world.store().peek("c-target").investigators.isEmpty());
        assertNull(world.store().peek("c-target").confidant);
        assertTrue(world.store().wardsOf("c-spy").isEmpty());
        assertFalse(world.store().characters.containsKey("c-helper"));
        world.store().wardsOf("c-dead-spy").add(world.character(target));
        PlotHunt.characterDied("c-dead-spy");
        assertFalse(world.store().wards.containsKey("c-dead-spy"));
        PlotHunt.characterDied("c-nobody");
        PlayerMock second = world.player("Second", "c-second", 50);
        PlotService.invite(master, second);
        PlotService.accept(second, plot.code);
        PlotHunt.characterDied("c-second");
        assertNull(plot.member("c-second"));
        assertTrue(world.last(master).contains("is dead"));
        plot.phase = PlotPhase.INVESTIGATION;
        PlotHunt.characterDied("c-assassin");
        assertEquals(PlotPhase.FAILED, plot.phase);
        PlotHunt.characterDied("c-master");

        world.store().cooldowns.clear();
        world.play(assassin, "c-assassin");
        Plot led = building(guard);
        PlotHunt.characterDied("c-guard");
        assertEquals(PlotPhase.DISSOLVED, led.phase);
        assertTrue(world.last(assassin).contains("With the death of"));
        Plot aimed = building(helper);
        PlotHunt.characterDied("c-target");
        assertEquals(PlotPhase.DISSOLVED, aimed.phase);
        assertTrue(world.last(assassin).contains("Fate has claimed"));
        world.play(target, "c-target2");
        world.offline.clear();
        Plot thinned = PlotService.request(world.player("Fourth", "c-fourth", 50));
        PlotService.approve(staff, thinned.code, "Target");
        PlayerMock third = world.player("Third", "c-third", 50);
        thinned.members.add(new PlotMember(world.character(third)));
        PlotMember remaining = new PlotMember(world.character(world.player("Fifth", "c-fifth", 50)));
        remaining.role = PlotRole.ASSASSIN;
        thinned.members.add(remaining);
        thinned.phase = PlotPhase.EXECUTION;
        PlotHunt.characterDied("c-third");
        assertEquals(PlotPhase.EXECUTION, thinned.phase);
    }
}
