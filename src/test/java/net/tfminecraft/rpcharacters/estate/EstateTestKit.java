package net.tfminecraft.rpcharacters.estate;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Logger;

import org.bukkit.event.Event;

import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterStatus;
import net.tfminecraft.tlibs.character.estate.DeathGuild;
import net.tfminecraft.tlibs.character.estate.EstateDecision;
import net.tfminecraft.tlibs.character.estate.EstateParticipant;
import net.tfminecraft.tlibs.character.estate.GuildLookup;
import net.tfminecraft.tlibs.character.estate.GuildRef;
import net.tfminecraft.tlibs.character.estate.Holdings;
import net.tfminecraft.tlibs.character.estate.Outcome;

/** A small estate world for tests: characters, guild answers, participants and a clock that moves on demand. */
final class EstateTestKit {
    static final Instant T0 = Instant.parse("2026-10-13T12:00:00Z");

    static final class MovingClock extends Clock {
        Instant now = T0;

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }
    }

    /** A participant answering whatever the test sets, and remembering what it was asked. */
    static class Participant implements EstateParticipant {
        final String id;
        Function<EstateDecision, Outcome> answer = decision -> Outcome.done();
        final List<EstateDecision> asked = new ArrayList<>();
        Holdings holdings = Holdings.NONE;

        Participant(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Outcome settle(EstateDecision decision) {
            asked.add(decision);
            return answer.apply(decision);
        }

        @Override
        public Holdings describe(UUID characterId) {
            return holdings;
        }
    }

    static final class World implements EstateWorld {
        final Map<UUID, CharacterInfo> infos = new HashMap<>();
        final Set<UUID> unreadable = new HashSet<>();
        final Map<UUID, UUID> deaths = new HashMap<>();
        final Map<UUID, DeathGuild> deathGuilds = new HashMap<>();
        final Map<UUID, GuildLookup> guilds = new HashMap<>();
        final Map<String, GuildLookup> guildNames = new HashMap<>();
        final List<EstateParticipant> participants = new ArrayList<>();
        final List<Dead> dead = new ArrayList<>();
        DeathGuild defaultDeathGuild = DeathGuild.NONE;

        CharacterRef alive(String name) {
            return add(name, CharacterStatus.ALIVE, UUID.randomUUID());
        }

        CharacterRef add(String name, CharacterStatus status, UUID owner) {
            CharacterRef ref = new CharacterRef(owner, UUID.randomUUID());
            infos.put(ref.characterId(), new CharacterInfo(ref, name, status, "human", Set.of(), 0, null));
            return ref;
        }

        void status(CharacterRef ref, CharacterStatus status) {
            CharacterInfo old = infos.get(ref.characterId());
            infos.put(ref.characterId(), new CharacterInfo(ref, old.name(), status, "human", Set.of(), 0, null));
        }

        /** A dead testator with a recorded death. */
        UUID kill(CharacterRef ref) {
            status(ref, CharacterStatus.DEAD);
            UUID death = UUID.randomUUID();
            deaths.put(ref.characterId(), death);
            return death;
        }

        @Override
        public List<Dead> dead() {
            return dead;
        }

        @Override
        public Optional<CharacterInfo> info(UUID characterId) {
            return unreadable.contains(characterId) ? Optional.empty() : Optional.ofNullable(infos.get(characterId));
        }

        @Override
        public boolean unreadable(UUID characterId) {
            return unreadable.contains(characterId);
        }

        @Override
        public List<CharacterInfo> findByName(String name) {
            return infos.values().stream().filter(i -> i.alive() && i.name().equalsIgnoreCase(name)).toList();
        }

        @Override
        public Optional<UUID> deathId(UUID characterId) {
            return Optional.ofNullable(deaths.get(characterId));
        }

        @Override
        public DeathGuild deathGuild(UUID characterId, UUID deathId) {
            return deathGuilds.getOrDefault(characterId, defaultDeathGuild);
        }

        @Override
        public GuildLookup guild(UUID incarnation) {
            return guilds.getOrDefault(incarnation, GuildLookup.GONE);
        }

        @Override
        public GuildLookup guildByName(String name) {
            return guildNames.getOrDefault(name, GuildLookup.GONE);
        }

        @Override
        public List<EstateParticipant> participants() {
            return participants;
        }

        /** A live guild with an optional leader, known to both lookups. */
        UUID liveGuild(String name, CharacterRef leader, UUID realm) {
            UUID incarnation = UUID.randomUUID();
            GuildLookup live = GuildLookup.live(new GuildRef(incarnation, name, Optional.ofNullable(realm),
                    Optional.ofNullable(leader).map(CharacterRef::characterId), "guild:" + incarnation));
            guilds.put(incarnation, live);
            guildNames.put(name, live);
            return incarnation;
        }
    }

    final MovingClock clock = new MovingClock();
    final World world = new World();
    final List<UUID> pushes = new ArrayList<>();
    final List<Event> events = new ArrayList<>();
    final Logger logger = Logger.getLogger("EstateTest");
    final EstateBook book;
    final EstateCoordinator coordinator;

    EstateTestKit(Path root) {
        this(root, DurableFiles.DISK);
    }

    EstateTestKit(Path root, DurableFiles files) {
        book = new EstateBook(root.resolve("estates"), files, clock, logger);
        coordinator = new EstateCoordinator(book, world, clock, Duration.ofHours(24), logger, pushes::add, events::add);
    }

    /** Opens an estate at death for {@code ref}, which must already be dead in the world. */
    EstateRecord died(CharacterRef ref, UUID heir) {
        UUID death = world.deaths.get(ref.characterId());
        coordinator.died(ref, world.infos.get(ref.characterId()).name(), death, clock.instant(), heir, heir == null ? 0 : 1,
                true, "COMMAND", null, null);
        return book.get(death).orElseThrow();
    }

    /** Dies, waits a day and decides. */
    EstateRecord decided(CharacterRef ref, UUID heir) {
        EstateRecord record = died(ref, heir);
        clock.advance(Duration.ofHours(24));
        coordinator.decideDue();
        return book.get(record.deathId).orElseThrow();
    }
}
