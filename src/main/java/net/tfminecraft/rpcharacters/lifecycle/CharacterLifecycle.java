package net.tfminecraft.rpcharacters.lifecycle;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.races.Race;

public final class CharacterLifecycle {

	private CharacterLifecycle() {
	}

	public static void fireCreated(Player owner, UUID ownerUuid, RPCharacter character) {
		Bukkit.getPluginManager().callEvent(new CharacterCreatedEvent(owner, ownerUuid, character));
	}

	public static void fireActivated(Player owner, UUID ownerUuid, RPCharacter character, RPCharacter previous) {
		Bukkit.getPluginManager().callEvent(new CharacterActivatedEvent(owner, ownerUuid, character, previous));
	}

	public static void notifyClassChange(Player owner, UUID ownerUuid, RPCharacter character,
			String oldClassId, String newClassId) {
		if (oldClassId == null || newClassId == null || oldClassId.equalsIgnoreCase(newClassId)) {
			return;
		}
		Bukkit.getPluginManager().callEvent(new CharacterClassChangeEvent(
				owner, ownerUuid, character, oldClassId, newClassId));
	}

	public static void notifyRaceChange(Player owner, UUID ownerUuid, RPCharacter character,
			String oldRaceId, String newRaceId) {
		if (oldRaceId == null || newRaceId == null || oldRaceId.equalsIgnoreCase(newRaceId)) {
			return;
		}
		Bukkit.getPluginManager().callEvent(new CharacterRaceChangeEvent(
				owner, ownerUuid, character, oldRaceId, newRaceId));
	}

	public static String raceId(Race race) {
		return race == null ? null : race.getId();
	}
}
