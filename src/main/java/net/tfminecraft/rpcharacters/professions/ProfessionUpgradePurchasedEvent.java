package net.tfminecraft.rpcharacters.professions;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import net.tfminecraft.rpcharacters.objects.RPCharacter;

public class ProfessionUpgradePurchasedEvent extends Event {

	private static final HandlerList HANDLERS = new HandlerList();

	private final Player player;
	private final RPCharacter character;
	private final String upgradeId;
	private final int cost;

	public ProfessionUpgradePurchasedEvent(Player player, RPCharacter character, String upgradeId, int cost) {
		this.player = player;
		this.character = character;
		this.upgradeId = upgradeId;
		this.cost = cost;
	}

	public Player getPlayer() {
		return player;
	}

	public RPCharacter getCharacter() {
		return character;
	}

	public String getUpgradeId() {
		return upgradeId;
	}

	public int getCost() {
		return cost;
	}

	@Override
	public HandlerList getHandlers() {
		return HANDLERS;
	}

	public static HandlerList getHandlerList() {
		return HANDLERS;
	}
}
