package net.tfminecraft.rpcharacters.plots;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlotModelTest {
    @TempDir Path directory;

    static PlotCharacter character(String id) {
        return new PlotCharacter(id, UUID.nameUUIDFromBytes(id.getBytes()), "player-" + id, "Name " + id);
    }

    @Test
    void enumsParseAndDescribeThemselves() {
        assertEquals(PlotApproach.DEFENSIVE, PlotApproach.parse("DEFENSIVE"));
        assertNull(PlotApproach.parse("sneaky"));
        assertEquals(PlotApproach.NEUTRAL, PlotApproach.OFFENSIVE.next());
        assertEquals(PlotApproach.OFFENSIVE, PlotApproach.DEFENSIVE.next());
        assertEquals("offensive", PlotApproach.OFFENSIVE.key());
        assertEquals(CollaboratorTask.PROGRESS, CollaboratorTask.parse("progress"));
        assertNull(CollaboratorTask.parse("bake"));
        assertEquals("hide", CollaboratorTask.HIDE.key());
        assertEquals("Assassin", PlotRole.ASSASSIN.label());
        assertTrue(PlotPhase.EXECUTION.active());
        assertFalse(PlotPhase.SUCCEEDED.active());
        assertTrue(PlotPhase.BUILDING.organising());
        assertFalse(PlotPhase.READY.organising());
        assertEquals("Recruiting", PlotPhase.RECRUITING.label());
        assertEquals("Your Spymaster", Defender.SPYMASTER.label());
        assertEquals(3, Betrayal.values().length);
    }

    @Test
    void plotAnswersWhoIsInvolvedAndHow() {
        Plot plot = new Plot();
        plot.master = character("m");
        plot.target = character("t");
        PlotMember assassin = new PlotMember(character("a"));
        assassin.role = PlotRole.ASSASSIN;
        PlotMember helper = new PlotMember(character("c"));
        helper.role = PlotRole.COLLABORATOR;
        helper.task = CollaboratorTask.STRENGTH;
        PlotMember idle = new PlotMember();
        idle.character = character("u");
        plot.members.addAll(List.of(assassin, helper, idle));
        plot.informed.add(character("i"));
        assertTrue(plot.involves("m"));
        assertTrue(plot.involves("a"));
        assertFalse(plot.involves("i"));
        assertEquals(PlotRole.MASTER, plot.role("m"));
        assertEquals(PlotRole.ASSASSIN, plot.role("a"));
        assertEquals(PlotRole.INFORMED, plot.role("i"));
        assertNull(plot.role("t"));
        assertTrue(plot.involvesAccount(plot.master.playerId));
        assertTrue(plot.involvesAccount(assassin.character.playerId));
        assertFalse(plot.involvesAccount(plot.target.playerId));
        assertFalse(plot.involvesAccount(null));
        assertEquals("i", plot.informed("i").characterId);
        assertNull(plot.informed("a"));
        assertEquals(2, plot.surplus(), "a plot needing no strength is as sharp as can be");
        plot.required = 50;
        plot.strength = 25;
        assertEquals(1, plot.surplus());
        plot.strength = 75;
        assertEquals(1.5, plot.surplus());
        assertEquals(List.of(assassin), plot.withRole(PlotRole.ASSASSIN));
        assertEquals(4, plot.conspirators());
        assertTrue(plot.isTarget("t"));
        assertFalse(plot.isTarget(null));
        Plot untargeted = new Plot();
        assertFalse(untargeted.isTarget("t"));
        assertEquals(List.of("m", "a", "c", "u", "i"), plot.suspects().stream().map(c -> c.characterId).toList());
        assertEquals("Collaborator (Strengthen the plot)", helper.roleLabel());
        assertEquals("Assassin", assassin.roleLabel());
        helper.task = null;
        assertEquals("Collaborator", helper.roleLabel());
        assertFalse(new PlotCharacter().is(null));
    }

    @Test
    void settingsDefaultToNeutral() {
        IntrigueSettings settings = new IntrigueSettings();
        settings.approach = null;
        assertEquals(PlotApproach.NEUTRAL, settings.approach());
        assertEquals("Your confidant", Defender.CONFIDANT.label());
        settings.approach = PlotApproach.OFFENSIVE;
        assertEquals(PlotApproach.OFFENSIVE, settings.approach());
    }

    @Test
    void storeSurvivesRestartsAndDropsDamagedPlots() throws Exception {
        Path file = directory.resolve("Cache/plots.json");
        PlotStore store = PlotStore.load(file);
        Plot plot = new Plot();
        plot.code = "P-ABCDE";
        plot.master = character("m");
        plot.target = character("t");
        store.plots.add(plot);
        Plot ended = new Plot();
        ended.code = "P-ENDED";
        ended.master = character("x");
        ended.phase = PlotPhase.FAILED;
        store.plots.add(ended);
        store.settings("m").approach = PlotApproach.DEFENSIVE;
        store.cooldowns.put("m|t", 5L);
        store.notices.put("m", new java.util.ArrayList<>(List.of("hello")));
        store.save();
        PlotStore loaded = PlotStore.load(file);
        assertEquals("P-ABCDE", loaded.plot("p-abcde").code);
        assertNull(loaded.plot("missing"));
        assertSame(loaded.plot("P-ABCDE"), loaded.plotOf("m"));
        assertNull(loaded.plotOf("x"));
        assertEquals(1, loaded.against("t").size());
        assertEquals(PlotApproach.DEFENSIVE, loaded.peek("m").approach());
        assertFalse(loaded.characters.containsKey("nobody") || loaded.peek("nobody") == null);
        assertFalse(loaded.characters.containsKey("nobody"));
        assertEquals(5L, loaded.cooldowns.get("m|t"));
        assertSame(loaded.plot("P-ABCDE"), loaded.plotOfAccount(loaded.plot("P-ABCDE").master.playerId));
        assertNull(loaded.plotOfAccount(java.util.UUID.randomUUID()));
        loaded.settings("w").confidant = character("c");
        assertEquals("w", loaded.confidantFor("c"));
        assertNull(loaded.confidantFor("z"));
        loaded.wardsOf("spy").add(character("ward"));
        assertEquals("spy", loaded.wardedBy("ward"));
        assertNull(loaded.wardedBy("nobody"));
        Files.writeString(file, "{\"plots\":[null,{\"code\":\"P-X\"}],\"characters\":null,\"cooldowns\":null,\"notices\":null,\"bans\":null,\"wards\":null}");
        PlotStore repaired = PlotStore.load(file);
        assertTrue(repaired.plots.isEmpty());
        assertTrue(repaired.characters.isEmpty() && repaired.cooldowns.isEmpty() && repaired.notices.isEmpty()
                && repaired.bans.isEmpty() && repaired.wards.isEmpty());
        Files.writeString(file, "{\"characters\":{\"a\":null,\"b\":{\"investigators\":null}},\"wards\":{\"s\":null}}");
        PlotStore guarded = PlotStore.load(file);
        assertEquals(List.of("b"), List.copyOf(guarded.characters.keySet()));
        assertTrue(guarded.peek("b").investigators.isEmpty());
        Files.writeString(file, "{}");
        PlotStore empty = PlotStore.load(file);
        assertTrue(empty.plots.isEmpty());
        Files.writeString(file, "");
        assertThrows(java.io.IOException.class, () -> PlotStore.load(file));
    }

    @Test
    void saveFallsBackWhenTheFilesystemCannotMoveAtomically() throws Exception {
        Path file = directory.resolve("plots.json");
        PlotStore store = PlotStore.load(file);
        AtomicInteger fallback = new AtomicInteger();
        try (var files = mockStatic(Files.class, call -> {
            if (call.getMethod().getName().equals("move")) {
                CopyOption[] options = (CopyOption[]) call.getRawArguments()[2];
                if (Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE))
                    throw new AtomicMoveNotSupportedException("a", "b", "test");
                fallback.incrementAndGet();
            }
            return call.callRealMethod();
        })) {
            store.save();
        }
        assertEquals(1, fallback.get());
        assertTrue(Files.exists(file));
    }
}
