package net.tfminecraft.rpcharacters.focus;

import java.io.File;

import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

/** Owns focus startup, configuration, listeners and shutdown inside RPCharacters. */
public final class FocusModule {
    private final JavaPlugin plugin;
    private FocusService service;
    private FocusListener listener;

    public FocusModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean start() {
        if (service != null) return true;
        try {
            File config = new File(plugin.getDataFolder(), "focus.yml");
            if (!config.exists()) plugin.saveResource("focus.yml", false);
            if (!FocusConfigLoader.load(config, plugin.getLogger())) return false;
            var store = new FocusStore(new File(plugin.getDataFolder(), "data/focus"));
            service = new FocusService(plugin, store);
            listener = new FocusListener(service);
            plugin.getServer().getPluginManager().registerEvents(listener, plugin);
            service.start();
            return true;
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("Focus startup stopped; existing data was preserved. Repair the reported "
                    + "file issue and reload RPCharacters: " + ex.getMessage());
            shutdown();
            return false;
        }
    }

    public boolean reloadConfig() {
        if (service == null) return start();
        if (!FocusConfigLoader.load(new File(plugin.getDataFolder(), "focus.yml"), plugin.getLogger())) return false;
        service.restartRegen();
        return true;
    }

    public FocusService getService() {
        return service;
    }

    public void shutdown() {
        if (listener != null) HandlerList.unregisterAll(listener);
        if (service != null) service.shutdown();
        listener = null;
        service = null;
    }
}
