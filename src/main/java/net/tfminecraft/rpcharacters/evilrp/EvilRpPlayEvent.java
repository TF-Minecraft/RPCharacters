package net.tfminecraft.rpcharacters.evilrp;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import net.tfminecraft.rpcharacters.objects.RPCharacter;

/**
 * An evil play (lockpicking, robbing, pickpocketing, looting a locked grave) was recorded for the
 * player's active character. {@link #isSessionStarted()} is true when it started a new evil RP
 * session and false when it only reset a running one, so listeners can count deeds once per session.
 */
public class EvilRpPlayEvent extends Event {

	private static final HandlerList HANDLERS = new HandlerList();

	private final Player player;
	private final RPCharacter character;
	private final boolean sessionStarted;

	public EvilRpPlayEvent(Player player, RPCharacter character, boolean sessionStarted) {
		this.player = player;
		this.character = character;
		this.sessionStarted = sessionStarted;
	}

	public Player getPlayer() {
		return player;
	}

	public RPCharacter getCharacter() {
		return character;
	}

	public boolean isSessionStarted() {
		return sessionStarted;
	}

	@Override
	public HandlerList getHandlers() {
		return HANDLERS;
	}

	public static HandlerList getHandlerList() {
		return HANDLERS;
	}
}
