package net.tfminecraft.rpcharacters.pvp;

import org.bukkit.entity.Player;

import io.lumine.mythic.lib.api.player.MMOPlayerData;
import io.lumine.mythic.lib.message.actionbar.ActionBarPriority;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.tfminecraft.rpcharacters.utils.RPTexts;

/** Action bar text for PvP. MythicLib redraws MMOCore's bar every tick, so go through its queue when it tracks the player. */
final class PvpActionBar {

	/** How long a line stays up when nothing replaces it. */
	static final long TICKS = 40L;

	private PvpActionBar() {
	}

	static void show(Player player, String raw) {
		String text = RPTexts.formatDisplay(raw);
		MMOPlayerData data = MMOPlayerData.getOrNull(player.getUniqueId());
		if (data != null) {
			data.getActionBar().show(ActionBarPriority.HIGH, TICKS, text);
		} else {
			player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(text));
		}
	}

	static void clear(Player player) {
		MMOPlayerData data = MMOPlayerData.getOrNull(player.getUniqueId());
		if (data != null) {
			data.getActionBar().reset(ActionBarPriority.HIGH);
		} else {
			player.sendActionBar(Component.empty());
		}
	}
}
