package net.tfminecraft.rpcharacters.factions;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;

import net.tfminecraft.rpcharacters.chat.ChatRecipientResolverRegistry;

/**
 * Registers the realm OOC recipient resolver whenever SimpleFactions is enabled.
 * <p>
 * SimpleFactions enables after RPCharacters, so the startup check alone never sees it.
 */
public final class RealmChatDependencyListener implements Listener {

	public static final String RESOLVER_ID = "simplefactions:realm";
	private static final String SIMPLE_FACTIONS = "SimpleFactions";

	public void registerIfPresent() {
		if (Bukkit.getPluginManager() == null || !Bukkit.getPluginManager().isPluginEnabled(SIMPLE_FACTIONS)) {
			return;
		}
		ChatRecipientResolverRegistry.register(RESOLVER_ID, new RealmOocChatRecipientResolver());
	}

	@EventHandler
	public void onPluginEnable(PluginEnableEvent event) {
		if (event.getPlugin() != null && SIMPLE_FACTIONS.equals(event.getPlugin().getName())) {
			registerIfPresent();
		}
	}

	@EventHandler
	public void onPluginDisable(PluginDisableEvent event) {
		if (event.getPlugin() != null && SIMPLE_FACTIONS.equals(event.getPlugin().getName())) {
			ChatRecipientResolverRegistry.unregister(RESOLVER_ID);
		}
	}
}
