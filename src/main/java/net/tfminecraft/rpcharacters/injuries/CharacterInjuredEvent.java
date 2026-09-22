package net.tfminecraft.rpcharacters.injuries;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import net.tfminecraft.rpcharacters.objects.RPCharacter;

public class CharacterInjuredEvent extends Event {

	private static final HandlerList HANDLERS = new HandlerList();

	private final Player target;
	private final Player attacker;
	private final RPCharacter character;
	private final String traitId;

	public CharacterInjuredEvent(Player target, Player attacker, RPCharacter character, String traitId) {
		this.target = target;
		this.attacker = attacker;
		this.character = character;
		this.traitId = traitId;
	}

	public Player getTarget() {
		return target;
	}

	public Player getAttacker() {
		return attacker;
	}

	public RPCharacter getCharacter() {
		return character;
	}

	public String getTraitId() {
		return traitId;
	}

	@Override
	public HandlerList getHandlers() {
		return HANDLERS;
	}

	public static HandlerList getHandlerList() {
		return HANDLERS;
	}
}
