package net.tfminecraft.rpcharacters.permadeath;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;

import net.tfminecraft.rpcharacters.RPCharacters;

public final class PermadeathDependencyListener implements Listener {

	private static final String SF_BRIDGE =
			"net.tfminecraft.rpcharacters.permadeath.SimpleFactionsRegionBridge";
	private static final String SF_LISTENER =
			"net.tfminecraft.rpcharacters.permadeath.SimpleFactionsPermadeathListener";

	private boolean simpleFactionsListenerRegistered;

	public void registerSimpleFactionsIfPresent() {
		if (!simpleFactionsPluginEnabled()) {
			return;
		}
		try {
			Class.forName(SF_BRIDGE).getMethod("init").invoke(null);
			registerSimpleFactionsListener();
		} catch (ClassNotFoundException | NoClassDefFoundError ignored) {
			// SimpleFactions jar not visible.
		} catch (Exception ex) {
			RPCharacters.plugin.getLogger().warning(
					"Failed to enable SimpleFactions permadeath bridge: " + ex.getMessage());
		}
	}

	@EventHandler
	public void onPluginEnable(PluginEnableEvent event) {
		Plugin enabled = event.getPlugin();
		if (enabled == null || !"SimpleFactions".equals(enabled.getName())) {
			return;
		}
		registerSimpleFactionsIfPresent();
	}

	private void registerSimpleFactionsListener() throws Exception {
		if (simpleFactionsListenerRegistered) {
			return;
		}
		RPCharacters plugin = RPCharacters.plugin;
		Listener listener = (Listener) Class.forName(SF_LISTENER).getConstructor().newInstance();
		plugin.getServer().getPluginManager().registerEvents(listener, plugin);
		simpleFactionsListenerRegistered = true;
	}

	private static boolean simpleFactionsPluginEnabled() {
		return Bukkit.getPluginManager() != null
				&& Bukkit.getPluginManager().isPluginEnabled("SimpleFactions");
	}
}
