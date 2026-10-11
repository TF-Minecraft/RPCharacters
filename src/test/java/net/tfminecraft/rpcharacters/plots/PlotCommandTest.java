package net.tfminecraft.rpcharacters.plots;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PlotCommandTest {
    @TempDir Path directory;
    PlotWorld world;
    PlayerMock master, assassin, helper, target;
    CommandSender console;
    final List<String> consoleSaid = new ArrayList<>();
    final PlotCommand command = new PlotCommand();
    final Command bukkitCommand = mock(Command.class);

    @BeforeEach
    void setup() throws Exception {
        world = new PlotWorld(directory);
        master = world.player("Master", "c-master", 50);
        assassin = world.player("Assassin", "c-assassin", 60);
        helper = world.player("Helper", "c-helper", 80);
        target = world.player("Target", "c-target", 40);
        console = mock(CommandSender.class);
        when(console.getName()).thenReturn("CONSOLE");
        doAnswer(call -> consoleSaid.add(call.getArgument(0))).when(console).sendMessage(anyString());
        when(console.hasPermission("rpchar.plots.staff")).thenReturn(true);
        var owner = Plots.class.getDeclaredField("owner");
        owner.setAccessible(true);
        owner.set(null, world.plugin);
    }

    @AfterEach
    void close() { world.close(); }

    void run(CommandSender sender, String line) {
        assertTrue(command.onCommand(sender, bukkitCommand, "plot", line.isEmpty() ? new String[0] : line.split(" ")));
    }

    String last() { return consoleSaid.getLast(); }

    @Test
    void playersRunTheWholePlotByCommand() {
        run(console, "request");
        assertTrue(last().contains("Only players"));
        run(master, "");
        assertTrue(master.getOpenInventory().getTopInventory().getHolder() instanceof PlotMenu.Holder);
        run(master, "request");
        Plot plot = world.store().plotOf("c-master");
        run(console, "approve " + plot.code + " Target");
        run(master, "invite");
        assertTrue(world.last(master).contains("/plot invite <player or character>"));
        run(master, "invite Nobody");
        assertTrue(world.last(master).contains("No online player"));
        run(master, "invite Char Assassin");
        run(assassin, "accept");
        run(assassin, "accept " + plot.code);
        run(master, "invite Helper");
        run(helper, "decline " + plot.code);
        run(master, "role Assassin");
        run(master, "role Assassin juggle");
        run(master, "role Nobody assassin");
        assertTrue(world.last(master).contains("Nobody by that name"));
        run(master, "role Char Assassin assassin");
        assertEquals(PlotRole.ASSASSIN, plot.member("c-assassin").role);
        run(master, "role assassin strength");
        assertEquals(CollaboratorTask.STRENGTH, plot.member("c-assassin").task);
        run(master, "role Assassin none");
        run(master, "role Assassin assassin");
        run(helper, "role Assassin assassin");
        run(master, "inform Helper");
        run(master, "start");
        assertEquals(PlotPhase.BUILDING, plot.phase);
        run(master, "launch");
        run(master, "hunt");
        run(master, "status");
        assertTrue(world.last(master).contains("Plot against"));
        plot.knowledge.put("threat", "Someone");
        run(target, "status");
        assertTrue(world.last(target).contains("Someone"));
        run(world.player("Nobody2", null, null), "status");
        plot.discovered = true;
        run(target, "investigator Helper");
        run(helper, "warn Target");
        run(master, "approach");
        run(master, "approach offensive");
        run(target, "confidant");
        run(target, "confidant Helper");
        run(helper, "confidant accept");
        run(target, "status");
        assertTrue(world.last(target).contains("Watched over by §eChar Helper") || world.heard(target, "Watched over by §eChar Helper"));
        run(helper, "warn Target");
        run(target, "confidant end");
        run(target, "confidant Nobody");
        run(helper, "ward Target");
        run(assassin, "leave");
        run(master, "invite Assassin");
        run(assassin, "accept " + plot.code);
        run(master, "dismiss Assassin");
        assertNull(plot.member("c-assassin"));
        run(master, "abandon");
        assertTrue(world.last(master).contains("/plot abandon confirm"));
        run(master, "abandon confirm");
        assertEquals(PlotPhase.DISSOLVED, plot.phase);
        run(master, "dance");
        assertTrue(world.last(master).contains("request|accept"));
        run(helper, "request");
        run(helper, "status");
        assertTrue(world.heard(helper, "awaits approval"));
    }

    @Test
    void namesResolveByAccountOrByAUniqueCharacter() {
        assertEquals(helper, PlotCommand.find(master, "helper"));
        assertEquals(helper, PlotCommand.find(master, "Char Helper"));
        PlayerMock twin = world.player("Twin", "c-twin", 50);
        world.active.put(twin.getUniqueId(), new PlotCharacter("c-twin", twin.getUniqueId(), "Twin", "Char Helper"));
        assertNull(PlotCommand.find(master, "Char Helper"));
        assertTrue(world.last(master).contains("Several characters share that name"));
    }

    @Test
    void staffApproveInspectCorrectAndEndPlots() throws Exception {
        CommandSender player = mock(CommandSender.class);
        run(player, "list");
        verify(player).sendMessage("§cYou do not have permission to manage plots.");
        run(console, "list");
        assertEquals("§7No plots are running.", last());
        run(master, "request");
        Plot plot = world.store().plotOf("c-master");
        run(console, "list");
        assertTrue(last().contains("-> ?"));
        run(console, "info " + plot.code);
        assertTrue(consoleSaid.stream().anyMatch(line -> line.contains("none yet")));
        run(console, "approve " + plot.code);
        assertTrue(last().contains("/plot approve <code> <target player>"));
        run(console, "deny");
        run(console, "approve " + plot.code + " Target");
        run(console, "list");
        assertTrue(last().contains("Char Target (Target)"));
        PlotService.invite(master, assassin);
        PlotService.accept(assassin, plot.code);
        PlotService.assign(master, "c-assassin", PlotRole.ASSASSIN, null);
        plot.members.getFirst().huntingSince = 1;
        plot.informed.add(world.character(helper));
        run(console, "info " + plot.code);
        assertTrue(consoleSaid.stream().anyMatch(line -> line.contains("Protection 24 by SELF")));
        assertTrue(consoleSaid.stream().anyMatch(line -> line.contains("Assassin, hunting")));
        run(console, "info P-NONE");
        assertEquals("§cNo plot has that code.", last());
        run(console, "day " + plot.code);
        run(console, "set " + plot.code + " strength 99");
        assertEquals(99, plot.strength);
        run(console, "set " + plot.code + " progress 150");
        assertEquals(100, plot.progress);
        run(console, "set " + plot.code + " secrecy -4");
        assertEquals(0, plot.secrecy);
        run(console, "set " + plot.code + " huntdays 2");
        assertEquals(2, plot.huntDays);
        run(console, "set " + plot.code + " phase ready");
        assertEquals(PlotPhase.READY, plot.phase);
        run(console, "set " + plot.code + " phase succeeded");
        assertTrue(last().contains("Invalid value"));
        run(console, "set " + plot.code + " strength lots");
        run(console, "set " + plot.code + " colour red");
        assertTrue(last().contains("Unknown field"));
        run(console, "set P-NONE strength 1");
        run(console, "set " + plot.code);
        run(console, "kick " + plot.code);
        run(console, "kick " + plot.code + " Nobody");
        assertTrue(last().contains("Nobody by that name"));
        run(console, "kick " + plot.code + " Char Assassin");
        assertNull(plot.member("c-assassin"));
        assertNotNull(plot.informed("c-assassin"));
        run(console, "kick P-NONE Assassin");
        run(console, "log " + plot.code);
        assertTrue(consoleSaid.stream().anyMatch(line -> line.contains("log, page 1 of")));
        assertTrue(consoleSaid.stream().anyMatch(line -> line.contains("STAFF-KICK")));
        run(console, "log " + plot.code + " 99");
        run(console, "log " + plot.code + " x");
        assertEquals("§cCould not read that log page.", last());
        run(console, "log P-NONE");
        assertEquals("§cNo log for that code.", last());
        run(console, "log");
        run(console, "cancel " + plot.code);
        run(console, "info " + plot.code);
        assertTrue(consoleSaid.stream().anyMatch(line -> line.contains("(Ended by staff)")));
        world.store().bans.put("c-master|c-target", plot.code);
        world.store().bans.put("c-other|c-target", "P-OTHER");
        world.store().cooldowns.put(PlotService.cooldownKey(plot.master, plot.target), 5L);
        run(console, "unban " + plot.code);
        assertTrue(last().contains("Lifted 1 bans and the retarget cooldown"));
        assertEquals(1, world.store().bans.size());
        run(console, "unban " + plot.code);
        assertTrue(last().contains("Lifted 0 bans from"));
        run(console, "unban P-NONE");
        run(helper, "request");
        Plot request = world.store().plotOf("c-helper");
        run(console, "unban " + request.code);
        run(console, "deny " + request.code);
        run(console, "reload");
        assertEquals("§aPlot settings reloaded.", last());
        Files.writeString(directory.resolve("plots.yml"), "bad: [unterminated");
        run(console, "reload");
        assertTrue(last().contains("Could not reload plots.yml"));
    }

    @Test
    void tabCompletionOffersStaffCommandsOnlyToStaff() {
        assertEquals(List.of(), command.onTabComplete(master, bukkitCommand, "plot", new String[]{"a", "b"}));
        assertEquals(List.of("accept", "abandon", "approach"), command.onTabComplete(master, bukkitCommand, "plot", new String[]{"A"}));
        assertEquals(List.of("accept", "abandon", "approach", "approve"), command.onTabComplete(console, bukkitCommand, "plot", new String[]{"a"}));
    }
}
