package net.tfminecraft.rpcharacters.permadeath;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import net.tfminecraft.rpcharacters.objects.RPCharacter;

/**
 * Fired before a character is permanently killed. Cancelling prevents character
 * death only — it does not cancel or undo player entity death.
 */
public class CharacterPermakillEvent extends Event implements Cancellable {

	private static final HandlerList HANDLERS = new HandlerList();

	private final Player player;
	private final RPCharacter character;
	private final PermakillCause cause;
	private final Player killer;
	private boolean cancelled;

	public CharacterPermakillEvent(Player player, RPCharacter character, PermakillCause cause) {
		this(player, character, cause, null);
	}

	public CharacterPermakillEvent(Player player, RPCharacter character, PermakillCause cause, Player killer) {
		this.player = player;
		this.character = character;
		this.cause = cause;
		this.killer = killer;
	}

	public Player getPlayer() {
		return player;
	}

	public RPCharacter getCharacter() {
		return character;
	}

	public PermakillCause getCause() {
		return cause;
	}

	public Player getKiller() {
		return killer;
	}

	public boolean isFromPermadeathZone() {
		return cause == PermakillCause.PERMADEATH_ZONE;
	}

	@Override
	public boolean isCancelled() {
		return cancelled;
	}

	@Override
	public void setCancelled(boolean cancel) {
		this.cancelled = cancel;
	}

	@Override
	public HandlerList getHandlers() {
		return HANDLERS;
	}

	public static HandlerList getHandlerList() {
		return HANDLERS;
	}
}
