package net.tfminecraft.rpcharacters.profile;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import net.tfminecraft.rpcharacters.objects.RPCharacter;

public class CharacterProfileViewEvent extends Event implements Cancellable {

	private static final HandlerList HANDLERS = new HandlerList();

	private final Player viewer;
	private final Player target;
	private final RPCharacter targetCharacter;
	private final boolean masked;
	private final boolean fromCommand;

	private boolean cancelled;

	public CharacterProfileViewEvent(Player viewer, Player target, RPCharacter targetCharacter,
			boolean masked, boolean fromCommand) {
		this.viewer = viewer;
		this.target = target;
		this.targetCharacter = targetCharacter;
		this.masked = masked;
		this.fromCommand = fromCommand;
	}

	public Player getViewer() {
		return viewer;
	}

	public Player getTarget() {
		return target;
	}

	public RPCharacter getTargetCharacter() {
		return targetCharacter;
	}

	public boolean isMasked() {
		return masked;
	}

	public boolean isFromCommand() {
		return fromCommand;
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
