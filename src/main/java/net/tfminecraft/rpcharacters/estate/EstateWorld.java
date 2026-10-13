package net.tfminecraft.rpcharacters.estate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.estate.DeathGuild;
import net.tfminecraft.tlibs.character.estate.EstateParticipant;
import net.tfminecraft.tlibs.character.estate.GuildLookup;

/** What estates read from the server: characters, SimpleFactions' answers and the participants. Main thread. */
interface EstateWorld {
    /** A dead character in a readable account, with its death time and saved will when known. */
    record Dead(CharacterInfo info, UUID deathId, Instant diedAt, UUID willHeir, long willRevision) {}

    /** Every dead character in a readable account. */
    List<Dead> dead();

    Optional<CharacterInfo> info(UUID characterId);

    /** The character's account is quarantined or unreadable: wait rather than guess. */
    boolean unreadable(UUID characterId);

    /** Living characters with this real name, ignoring case. */
    List<CharacterInfo> findByName(String name);

    Optional<UUID> deathId(UUID characterId);

    DeathGuild deathGuild(UUID characterId, UUID deathId);

    GuildLookup guild(UUID incarnation);

    GuildLookup guildByName(String name);

    List<EstateParticipant> participants();
}
