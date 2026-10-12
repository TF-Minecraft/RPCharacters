package net.tfminecraft.rpcharacters.lifecycle;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired before a playing character returns to character selection without logging out,
 * at the start and on each second of the countdown. Deny it, with a reason the player can
 * read, when leaving now would skip a penalty that a logout applies.
 */
public class CharacterSelectionRequestEvent extends Event implements Cancellable {

	private static final HandlerList HANDLERS = new HandlerList();

	private final Player player;
	private String reason;

	public CharacterSelectionRequestEvent(Player player) {
		this.player = player;
	}

	public Player getPlayer() {
		return player;
	}

	/** Shown to the player when cancelled; null shows a general message. */
	public String getReason() {
		return reason;
	}

	public void deny(String reason) {
		this.reason = reason;
	}

	@Override
	public boolean isCancelled() {
		return reason != null;
	}

	@Override
	public void setCancelled(boolean cancel) {
		reason = cancel ? (reason == null ? "You cannot return to character selection right now." : reason) : null;
	}

	@Override
	public HandlerList getHandlers() {
		return HANDLERS;
	}

	public static HandlerList getHandlerList() {
		return HANDLERS;
	}
}
