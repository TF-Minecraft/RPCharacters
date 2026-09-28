package net.tfminecraft.rpcharacters.paidchange;

import java.util.ArrayList;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;

import net.tfminecraft.rpcharacters.creation.CharacterCreation;
import net.tfminecraft.rpcharacters.managers.CreationManager;

/**
 * DenarEconomy disables before RPCharacters on shutdown, so held payments are settled here,
 * while its accounts can still take a refund.
 */
public final class PaidChangeListener implements Listener {

	@EventHandler
	public void onPluginDisable(PluginDisableEvent event) {
		if ("DenarEconomy".equals(event.getPlugin().getName())) {
			settleAll();
		}
	}

	public static void settleAll() {
		for (CharacterCreation cc : new ArrayList<>(CreationManager.activeCreators.values())) {
			PaidChangeService.settle(cc);
		}
	}
}
