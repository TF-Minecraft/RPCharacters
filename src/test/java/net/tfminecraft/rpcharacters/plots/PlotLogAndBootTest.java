package net.tfminecraft.rpcharacters.plots;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlotLogAndBootTest {
    @TempDir Path directory;
    RuntimeTestState state;
    org.mockito.MockedStatic<org.bukkit.Bukkit> bukkit;
    final RPCharacters plugin = mock(RPCharacters.class);
    final Server server = mock(Server.class);
    final PluginManager plugins = mock(PluginManager.class);
    final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    final BukkitTask task = mock(BukkitTask.class);
    final Logger logger = mock(Logger.class);

    @BeforeEach
    void setup() throws Exception {
        state = new RuntimeTestState(RPCharacters.class, PlotService.class, PlotLog.class, Plots.class, Intrigue.class);
        RPCharacters.plugin = plugin;
        when(plugin.getDataFolder()).thenReturn(directory.resolve("RPCharacters").toFile());
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(logger);
        when(server.getPluginManager()).thenReturn(plugins);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(100L), eq(100L))).thenReturn(task);
        bukkit = mockStatic(org.bukkit.Bukkit.class);
        bukkit.when(org.bukkit.Bukkit::getScheduler).thenReturn(scheduler);
        for (String name : new String[]{"listening"}) {
            Field field = Plots.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, false);
        }
    }

    @AfterEach
    void close() {
        Plots.stop();
        bukkit.close();
        state.close();
    }

    @Test
    void thePlotLogRecordsEventsAndThrottlesEncounters() throws Exception {
        Plot plot = new Plot();
        plot.code = "P-ABCDE";
        plot.master = new PlotCharacter("m", null, "Master", "Char Master");
        PlotLog.folder = null;
        PlotLog.write(plot, "IGNORED", "no folder yet");
        PlotLog.start(directory.resolve("logs"));
        assertEquals(java.util.List.of(), PlotLog.read("P-ABCDE"));
        PlotLog.write(plot, "REQUEST", "details");
        assertTrue(PlotLog.read("p-abcde").getFirst().endsWith("REQUEST  details"));
        assertEquals("-", PlotLog.who(null));
        assertEquals("Char Master (Master)", PlotLog.who(plot.master));
        assertEquals("-", PlotLog.where((Location) null));
        assertEquals("-", PlotLog.where(new Location(null, 0, 0, 0)));
        assertEquals("-", PlotLog.where((Player) null));
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        Location here = new Location(world, 1.5, 64, -2.5);
        assertEquals("world 1 64 -3", PlotLog.where(here));
        Player near = mock(Player.class), target = mock(Player.class);
        when(near.getLocation()).thenReturn(here);
        when(target.getLocation()).thenReturn(new Location(world, 4.5, 64, -2.5));
        assertEquals("world 1 64 -3", PlotLog.where(near));
        PlotService.clock = () -> 1_000_000;
        PlotLog.encounter(plot, plot.master, near, target);
        PlotLog.encounter(plot, plot.master, near, target);
        PlotService.clock = () -> 1_000_000 + PlotConfig.encounterGap();
        PlotLog.encounter(plot, plot.master, near, target);
        assertEquals(3, PlotLog.read("P-ABCDE").stream().filter(line -> line.contains("ENCOUNTER")).count() + 1);
        assertTrue(PlotLog.read("P-ABCDE").get(1).contains("Char Master (Master) (Plot master) within 3 blocks of the target at world 4 64 -3"));
        Files.writeString(directory.resolve("not-a-folder"), "");
        PlotLog.start(directory.resolve("not-a-folder"));
        PlotLog.write(plot, "FAILS", "the folder is a file");
        verify(logger).log(eq(Level.WARNING), anyString(), any(java.io.IOException.class));
    }

    @Test
    void startLoadsEverythingRegistersOnceAndRunsTheTimer() throws Exception {
        PluginCommand command = mock(PluginCommand.class);
        when(plugin.getCommand("plot")).thenReturn(command);
        Plots.start(plugin);
        verify(command).setExecutor(any(PlotCommand.class));
        verify(command).setTabCompleter(any(PlotCommand.class));
        verify(plugins).registerEvents(any(PlotListener.class), eq(plugin));
        assertTrue(Files.exists(directory.resolve("RPCharacters/plots.yml")));
        when(plugin.getCommand("plot")).thenReturn(null);
        Plots.start(plugin);
        verify(task).cancel();
        verify(plugins, times(1)).registerEvents(any(PlotListener.class), eq(plugin));
        var timer = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, times(2)).runTaskTimer(eq(plugin), timer.capture(), eq(100L), eq(100L));
        timer.getValue().run();
        CommandSender sender = mock(CommandSender.class);
        Plots.reload(sender);
        verify(sender).sendMessage("§aPlot settings reloaded.");
        Plots.stop();
        Plots.stop();
    }

    @Test
    void damagedPlotsDisablePlotsInsteadOfBeingOverwritten() throws Exception {
        Path file = directory.resolve("RPCharacters/data/plots.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{not json");
        Plots.start(plugin);
        verify(logger).log(eq(Level.SEVERE), anyString(), any(Throwable.class));
        verify(plugins, never()).registerEvents(any(), any());
        assertEquals("{not json", Files.readString(file));
    }

    @Test
    void otherPluginsAskForIntrigueAndHunts() {
        Player player = mock(Player.class);
        try (var characters = mockStatic(PlotCharacters.class); var intrigue = mockStatic(Intrigue.class)) {
            assertNull(PlotsApi.intrigue(player));
            characters.when(() -> PlotCharacters.active(player)).thenReturn(new PlotCharacter("c1", null, "P", "Ana"));
            intrigue.when(() -> Intrigue.of(player, "c1")).thenReturn(61);
            assertEquals(61, PlotsApi.intrigue(player));
            intrigue.when(() -> Intrigue.of(player, "c1")).thenThrow(new java.io.IOException("disk"));
            assertNull(PlotsApi.intrigue(player));
            assertFalse(PlotsApi.hunting(player));
        }
    }
}
