package net.tfminecraft.rpcharacters.lifecycle;

import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import net.tfminecraft.rpcharacters.objects.RPCharacter;

public class CharacterClassChangeEvent extends Event {

	private static final HandlerList HANDLERS = new HandlerList();

	private final Player owner;
	private final UUID ownerUuid;
	private final RPCharacter character;
	private final String oldClassId;
	private final String newClassId;

	public CharacterClassChangeEvent(Player owner, UUID ownerUuid, RPCharacter character,
			String oldClassId, String newClassId) {
		this.owner = owner;
		this.ownerUuid = ownerUuid;
		this.character = character;
		this.oldClassId = oldClassId;
		this.newClassId = newClassId;
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

	public String getOldClassId() {
		return oldClassId;
	}

	public String getNewClassId() {
		return newClassId;
	}

	@Override
	public HandlerList getHandlers() {
		return HANDLERS;
	}

	public static HandlerList getHandlerList() {
		return HANDLERS;
	}
}
