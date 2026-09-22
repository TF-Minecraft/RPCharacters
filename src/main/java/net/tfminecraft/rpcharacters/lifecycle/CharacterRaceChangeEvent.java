package net.tfminecraft.rpcharacters.lifecycle;

import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import net.tfminecraft.rpcharacters.objects.RPCharacter;

public class CharacterRaceChangeEvent extends Event {

	private static final HandlerList HANDLERS = new HandlerList();

	private final Player owner;
	private final UUID ownerUuid;
	private final RPCharacter character;
	private final String oldRaceId;
	private final String newRaceId;

	public CharacterRaceChangeEvent(Player owner, UUID ownerUuid, RPCharacter character,
			String oldRaceId, String newRaceId) {
		this.owner = owner;
		this.ownerUuid = ownerUuid;
		this.character = character;
		this.oldRaceId = oldRaceId;
		this.newRaceId = newRaceId;
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

	public String getOldRaceId() {
		return oldRaceId;
	}

	public String getNewRaceId() {
		return newRaceId;
	}

	@Override
	public HandlerList getHandlers() {
		return HANDLERS;
	}

	public static HandlerList getHandlerList() {
		return HANDLERS;
	}
}
