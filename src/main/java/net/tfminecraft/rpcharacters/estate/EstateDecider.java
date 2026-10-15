package net.tfminecraft.rpcharacters.estate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import net.tfminecraft.rpcharacters.estate.EstateRecord.Decision;
import net.tfminecraft.rpcharacters.estate.EstateRecord.Named;
import net.tfminecraft.rpcharacters.estate.EstateRecord.Part;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterStatus;
import net.tfminecraft.tlibs.character.estate.DeathGuild;
import net.tfminecraft.tlibs.character.estate.GuildLookup;

/** The decision table (design E4) and the WAITING re-check. Reads only; the coordinator writes. */
final class EstateDecider {
    /** What the decision step concluded. */
    sealed interface Verdict {
        record Decided(Decision decision) implements Verdict {}

        record Postpone(String reason) implements Verdict {}

        record Cancel(String reason) implements Verdict {}
    }

    /** What a WAITING re-check concluded: a new revision, or nothing to change yet. */
    sealed interface Recheck {
        record Revised(Decision decision) implements Recheck {}

        record Unchanged(String reason) implements Recheck {}
    }

    private EstateDecider() {}

    static Verdict decide(EstateRecord estate, EstateWorld world, Instant now) {
        UUID testator = estate.testator.characterId();
        if (world.unreadable(testator)) return new Verdict.Postpone("the testator's account cannot be read");
        Optional<CharacterInfo> dead = world.info(testator);
        if (dead.isEmpty() || dead.get().status() != CharacterStatus.DEAD
                || !world.deathId(testator).equals(Optional.of(estate.deathId))) {
            return new Verdict.Cancel("the testator is no longer dead with this death");
        }
        Named heir = null;
        if (estate.willHeir != null) {
            if (world.unreadable(estate.willHeir)) return new Verdict.Postpone("the will heir's account cannot be read");
            heir = living(world, estate.willHeir, testator);
        }
        DeathGuild guild = estate.noGuild ? DeathGuild.NONE : world.deathGuild(testator, estate.deathId);
        if (guild instanceof DeathGuild.None) return guildless(now, heir);
        // UNAVAILABLE, and a missing answer, postpone: no answer is never read as "no guild" (design N2).
        if (!(guild instanceof DeathGuild.Guild g)) return new Verdict.Postpone("SimpleFactions has not answered");
        String guildName = name(world.guild(g.incarnation()));
        if (g.live()) {
            Part leader = g.leader().map(id -> living(world, id, testator)).map(Part::character).orElse(Part.WAITING);
            // Guild locks follow the guild leader, else the realm leader; property waits for a guild leader.
            Part locks = leader.recipient().isWaiting() ? realmLeader(realmOf(g, world), world, testator)
                    .map(Part::character).orElse(Part.WAITING) : leader;
            if (heir != null) {
                return decided(now, heir, g, Part.bank(heir), Part.character(heir), locks, Part.character(heir));
            }
            return decided(now, null, g, Part.treasury("guild:" + g.incarnation(), "GUILD", guildName), leader, locks,
                    Part.SENDER);
        }
        GuildLookup realm = realmOf(g, world);
        if (!(realm instanceof GuildLookup.Live) && !(realm instanceof GuildLookup.Gone)) {
            return new Verdict.Postpone("SimpleFactions cannot find the realm");
        }
        Optional<Named> realmLeader = realmLeader(realm, world, testator);
        if (heir != null) {
            Part locks = realmLeader.map(Part::character).orElse(Part.character(heir));
            return decided(now, heir, g, Part.bank(heir), Part.character(heir), locks, Part.character(heir));
        }
        Part money = realm instanceof GuildLookup.Live live
                ? Part.treasury(live.guild().treasuryAccount(), "REALM", live.guild().name()) : Part.FROZEN;
        return decided(now, null, g, money, Part.FROZEN, realmLeader.map(Part::character).orElse(Part.FROZEN),
                Part.SENDER);
    }

    /** Re-runs each WAITING part's row against the death guild as it is now (design "WAITING re-check"). */
    static Recheck recheck(EstateRecord estate, EstateWorld world) {
        Decision d = estate.decision;
        GuildLookup guild = world.guild(d.deathGuild());
        if (!(guild instanceof GuildLookup.Live) && !(guild instanceof GuildLookup.Gone)) {
            return new Recheck.Unchanged("SimpleFactions has not answered");
        }
        Part property = d.property();
        Part locks = d.guildLocks();
        UUID testator = estate.testator.characterId();
        GuildLookup realm = d.deathRealm() == null ? GuildLookup.GONE : world.guild(d.deathRealm());
        if (guild instanceof GuildLookup.Live live) {
            Named leader = live.guild().leader().map(id -> living(world, id, testator)).orElse(null);
            if (leader != null) {
                if (property.recipient().isWaiting()) property = Part.character(leader);
                if (locks.recipient().isWaiting()) locks = Part.character(leader);
            } else {
                Optional<Named> realmLeader = realmLeader(realm, world, testator);
                if (!locks.recipient().isWaiting() || realmLeader.isEmpty()) {
                    return new Recheck.Unchanged("the death guild has no living leader");
                }
                locks = Part.character(realmLeader.get());
            }
        } else {
            if (locks.recipient().isWaiting()) {
                if (!(realm instanceof GuildLookup.Live) && !(realm instanceof GuildLookup.Gone)) {
                    return new Recheck.Unchanged("SimpleFactions cannot find the realm");
                }
                locks = realmLeader(realm, world, testator).map(Part::character)
                        .orElse(d.heir() == null ? Part.FROZEN : Part.character(d.heir()));
            }
            if (property.recipient().isWaiting()) property = Part.FROZEN;
        }
        return new Recheck.Revised(d.revise(d.money(), property, locks));
    }

    /** The death realm as SimpleFactions answers now; GONE when the guild had none, null when unanswered. */
    private static GuildLookup realmOf(DeathGuild.Guild g, EstateWorld world) {
        return g.realm().isEmpty() ? GuildLookup.GONE : world.guild(g.realm().get());
    }

    /** The living leader of a live realm, other than the testator. */
    private static Optional<Named> realmLeader(GuildLookup realm, EstateWorld world, UUID testator) {
        if (!(realm instanceof GuildLookup.Live live)) return Optional.empty();
        return live.guild().leader().map(id -> living(world, id, testator));
    }

    private static Verdict guildless(Instant now, Named heir) {
        if (heir == null) return decided(now, null, null, Part.FROZEN, Part.FROZEN, Part.FROZEN, Part.SENDER);
        return decided(now, heir, null, Part.bank(heir), Part.character(heir), Part.character(heir), Part.character(heir));
    }

    private static Verdict decided(Instant now, Named heir, DeathGuild.Guild guild, Part money, Part property,
            Part locks, Part letters) {
        return new Verdict.Decided(new Decision(now, 0, heir, guild == null ? null : guild.incarnation(),
                guild == null ? null : guild.realm().orElse(null), money, property, locks, letters));
    }

    /** A living character other than the testator, or null. */
    private static Named living(EstateWorld world, UUID characterId, UUID testator) {
        if (characterId.equals(testator)) return null;
        return world.info(characterId).filter(info -> info.status() == CharacterStatus.ALIVE)
                .map(info -> new Named(info.ref(), info.name())).orElse(null);
    }

    private static String name(GuildLookup lookup) {
        return lookup instanceof GuildLookup.Live live ? live.guild().name() : null;
    }
}
