package net.tfminecraft.rpcharacters.plots;

import java.io.File;
import java.util.logging.Level;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Starts plots with the plugin: settings, Intrigue, saved plots, the /plot command, listener and timer. */
public final class Plots {
    private static BukkitTask task;
    private static boolean listening;
    private static JavaPlugin owner;

    private Plots() {}

    public static void start(JavaPlugin plugin) {
        stop();
        owner = plugin;
        File folder = plugin.getDataFolder();
        try {
            PlotConfig.load(new File(folder, "plots.yml"));
            Intrigue.load(folder.toPath().resolve("data/intrigue.json"),
                    folder.toPath().getParent().resolve("SimpleFactions/Cache/character-aptitudes.json"));
            PlotService.load(folder.toPath().resolve("data/plots.json"));
        } catch (Exception error) {
            // Never run on empty data that would overwrite the saved plots.
            plugin.getLogger().log(Level.SEVERE, "Cannot load plots; plots are disabled", error);
            return;
        }
        PlotLog.start(folder.toPath().resolve("plots/logs"));
        var command = plugin.getCommand("plot");
        if (command != null) {
            PlotCommand executor = new PlotCommand();
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        if (!listening) plugin.getServer().getPluginManager().registerEvents(new PlotListener(), plugin);
        listening = true;
        long period = PlotConfig.scanSeconds() * 20L;
        task = org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            PlotService.tick();
            PlotHunt.scan();
        }, period, period);
    }

    /** Re-reads plots.yml; saved plots and Intrigue stay as they are. */
    static void reload(CommandSender sender) {
        try {
            PlotConfig.load(new File(owner.getDataFolder(), "plots.yml"));
            sender.sendMessage("§aPlot settings reloaded.");
        } catch (Exception error) {
            owner.getLogger().log(Level.SEVERE, "Plot settings reload failed", error);
            sender.sendMessage("§cCould not reload plots.yml. Check the server log; the old settings stay.");
        }
    }

    public static void stop() {
        if (task != null) task.cancel();
        task = null;
    }
}
