package net.tfminecraft.rpcharacters.lifecycle;

import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import net.tfminecraft.rpcharacters.objects.RPCharacter;

public class CharacterCreatedEvent extends Event {

	private static final HandlerList HANDLERS = new HandlerList();

	private final Player owner;
	private final UUID ownerUuid;
	private final RPCharacter character;

	public CharacterCreatedEvent(Player owner, UUID ownerUuid, RPCharacter character) {
		this.owner = owner;
		this.ownerUuid = ownerUuid;
		this.character = character;
	}

	public Player getOwner() {
		return owner;
	}

	public UUID getOwnerUuid() {
		return ownerUuid;
	}

	public RPCharacter getCharacter() {
		return character;
	}

	@Override
	public HandlerList getHandlers() {
		return HANDLERS;
	}

	public static HandlerList getHandlerList() {
		return HANDLERS;
	}
}
