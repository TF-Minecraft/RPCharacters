package net.tfminecraft.rpcharacters.estate;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import net.tfminecraft.rpcharacters.identity.CharacterDirectory;
import net.tfminecraft.rpcharacters.identity.CharacterIdentityService;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.estate.DeathGuild;
import net.tfminecraft.tlibs.character.estate.EstateParticipant;
import net.tfminecraft.tlibs.character.estate.Estates;
import net.tfminecraft.tlibs.character.estate.GuildLookup;

/** The live server behind {@link EstateWorld}: RPCharacters' identity service, character files and TLibs. */
final class ServerWorld implements EstateWorld {
    private final Supplier<CharacterIdentityService> identity;
    private final Path characters;

    ServerWorld(Supplier<CharacterIdentityService> identity, Path characters) {
        this.identity = identity;
        this.characters = characters;
    }

    @Override
    public List<Dead> dead() {
        List<Dead> found = new ArrayList<>();
        for (CharacterDirectory.Death death : identity.get().deaths()) {
            JSONObject saved = read(death.info());
            Instant diedAt = saved.get("died-at-ms") instanceof Number at && saved.get("death-id") != null
                    ? Instant.ofEpochMilli(at.longValue()) : null;
            UUID heir = heir(saved.get("will-heir"));
            long revision = saved.get("will-revision") instanceof Number number ? number.longValue() : 0;
            found.add(new Dead(death.info(), death.deathId(), diedAt, heir, revision));
        }
        return found;
    }

    /** The saved file; a death is durable, so the file holds its time and the will made while alive. */
    private JSONObject read(CharacterInfo info) {
        Path file = characters.resolve(info.ref().ownerId().toString()).resolve(info.ref().characterId() + ".json");
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return new JSONParser().parse(reader) instanceof JSONObject json ? json : new JSONObject();
        } catch (Exception unreadable) {
            return new JSONObject();
        }
    }

    private static UUID heir(Object value) {
        try {
            return value instanceof String text ? UUID.fromString(text) : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    @Override
    public Optional<CharacterInfo> info(UUID characterId) {
        return identity.get().info(characterId);
    }

    @Override
    public boolean unreadable(UUID characterId) {
        return identity.get().unreadable(characterId);
    }

    @Override
    public List<CharacterInfo> findByName(String name) {
        return identity.get().findByName(name);
    }

    @Override
    public Optional<UUID> deathId(UUID characterId) {
        return identity.get().deathId(characterId);
    }

    @Override
    public DeathGuild deathGuild(UUID characterId, UUID deathId) {
        return Estates.deathGuild(characterId, deathId);
    }

    @Override
    public GuildLookup guild(UUID incarnation) {
        return Estates.guild(incarnation);
    }

    @Override
    public GuildLookup guildByName(String name) {
        return Estates.guildByName(name);
    }

    @Override
    public List<EstateParticipant> participants() {
        return Estates.participants();
    }
}
