package net.tfminecraft.rpcharacters.chat;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import net.tfminecraft.rpcharacters.objects.RPCharacter;

public class CharacterChatEvent extends Event implements Cancellable {

	private static final HandlerList HANDLERS = new HandlerList();

	private final Player sender;
	private final RPCharacter character;
	private final boolean masked;
	private final boolean wasCommand;

	private String channel;
	private String message;
	private String displayName;
	private Set<Player> recipients;
	private boolean cancelled;

	public CharacterChatEvent(Player sender, RPCharacter character, String channel, String message,
			String displayName, Set<Player> recipients, boolean masked, boolean wasCommand) {
		super(false);
		this.sender = sender;
		this.character = character;
		this.channel = channel;
		this.message = message;
		this.displayName = displayName;
		this.recipients = new HashSet<>(recipients);
		this.masked = masked;
		this.wasCommand = wasCommand;
	}

	public Player getSender() {
		return sender;
	}

	public RPCharacter getCharacter() {
		return character;
	}

	public String getChannel() {
		return channel;
	}

	public void setChannel(String channel) {
		this.channel = channel;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	public String getDisplayName() {
		return displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}

	public Set<Player> getRecipients() {
		return Collections.unmodifiableSet(recipients);
	}

	public void setRecipients(Set<Player> recipients) {
		this.recipients = recipients != null ? new HashSet<>(recipients) : new HashSet<>();
	}

	public boolean isMasked() {
		return masked;
	}

	public boolean wasCommand() {
		return wasCommand;
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
