package net.tfminecraft.rpcharacters.identity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.races.Race;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.tlibs.character.CharacterInfo;

/**
 * A live character's last {@link CharacterDirectory#snapshot} with every value that snapshot read.
 * While those values are unchanged the snapshot comes out the same, so it is reused, not rebuilt.
 * Main thread only, like the directory.
 */
final class LiveSnapshot {
	private final UUID ownerId;
	private final String id;
	private final String name;
	private final Status status;
	private final boolean hasRace;
	private final String raceId;
	private final String[] traitIds;
	private final int playtimeSeconds;
	private final Instant lastPlayedAt;
	private final CharacterInfo info;

	/** Records the values {@code info} was just built from; the build succeeded, so none is missing. */
	LiveSnapshot(UUID ownerId, RPCharacter character, CharacterInfo info) {
		this.ownerId = ownerId;
		this.id = character.getId();
		this.name = character.getName();
		this.status = character.getStatus();
		Race race = character.getRace();
		this.hasRace = race != null;
		this.raceId = race == null ? null : race.getId();
		List<Trait> traits = character.getTraits();
		this.traitIds = new String[traits.size()];
		for (int i = 0; i < traitIds.length; i++) traitIds[i] = traits.get(i).getId();
		this.playtimeSeconds = character.getOnlinePlaytimeSeconds();
		this.lastPlayedAt = character.getLastPlayedAt();
		this.info = Objects.requireNonNull(info, "info");
	}

	CharacterInfo info() {
		return info;
	}

	/** True when every value the snapshot read still matches, so it would come out the same. */
	boolean matches(UUID ownerId, RPCharacter character) {
		if (!this.ownerId.equals(ownerId) || character.getOnlinePlaytimeSeconds() != playtimeSeconds
				|| character.getStatus() != status || !Objects.equals(character.getId(), id)
				|| !Objects.equals(character.getName(), name)
				|| !Objects.equals(character.getLastPlayedAt(), lastPlayedAt)) return false;
		Race race = character.getRace();
		if (race == null ? hasRace : !hasRace || !Objects.equals(race.getId(), raceId)) return false;
		List<Trait> traits = character.getTraits();
		if (traits == null || traits.size() != traitIds.length) return false;
		for (int i = 0; i < traitIds.length; i++) {
			Trait trait = traits.get(i);
			if (trait == null || !Objects.equals(trait.getId(), traitIds[i])) return false;
		}
		return true;
	}
}
