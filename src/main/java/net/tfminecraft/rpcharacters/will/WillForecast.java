package net.tfminecraft.rpcharacters.will;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import org.bukkit.Bukkit;

import net.tfminecraft.rpcharacters.utils.ClueFormatter;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterStatus;
import net.tfminecraft.tlibs.character.Characters;

/**
 * The guild side of "if you died today", read from SimpleFactions as it is now. It only previews the
 * estate rules (design E4): the estate itself reads the guild recorded at the death.
 */
final class WillForecast {
    /** What SimpleFactions says about the character's guild now. */
    sealed interface Guild {
        /** SimpleFactions is missing, not ready or failed: say the rules, not names. */
        record Unknown() implements Guild {}

        record None() implements Guild {}

        /**
         * @param leader      the guild's living leader other than this character, if any
         * @param youLead     this character leads the guild, so a successor takes over at the death
         * @param realmLeader the realm's living leader other than this character, if any
         * @param soloRealm   the realm, when this character is its only member and its heir would take it on
         */
        record Member(String name, Optional<CharacterInfo> leader, boolean youLead,
                Optional<CharacterInfo> realmLeader, Optional<Realm> soloRealm) implements Guild {}
    }

    /**
     * A realm held after its last member dies; SimpleFactions 4.15.0 seats that member's will heir once
     * {@code heirWaitHours} have passed, if the heir is in no faction by then.
     */
    record Realm(String name, double heirWaitHours) {}

    static final Guild UNKNOWN = new Guild.Unknown();
    static final Guild NONE = new Guild.None();
    static Function<UUID, Guild> guilds = WillForecast::read;
    /** SimpleFactions' {@code held-realm-heir-wait-hours}, or null where held realms never pass by will. */
    static Supplier<Double> heirWaitHours = WillForecast::readHeirWait;

    private WillForecast() {}

    static Guild guild(UUID characterId) {
        try {
            Guild guild = guilds.apply(characterId);
            return guild == null ? UNKNOWN : guild;
        } catch (RuntimeException | LinkageError unreadable) {
            return UNKNOWN;
        }
    }

    private static Guild read(UUID characterId) {
        if (!Bukkit.getPluginManager().isPluginEnabled("SimpleFactions")) return UNKNOWN;
        return SimpleFactionsGuild.read(characterId);
    }

    /** Read without a SimpleFactions 4.15.0 dependency; older versions have no such setting. */
    static Double readHeirWait() {
        return readHeirWait("net.tfminecraft.simplefactions.Cache");
    }

    static Double readHeirWait(String cache) {
        try {
            return Class.forName(cache).getField("heldRealmHeirWaitHours").getDouble(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError older) {
            return null;
        }
    }

    /** A living character other than {@code self}, from a SimpleFactions member key. */
    static Optional<CharacterInfo> living(UUID characterId, UUID self) {
        if (characterId == null || characterId.equals(self)) return Optional.empty();
        return Characters.info(characterId).filter(info -> info.status() == CharacterStatus.ALIVE);
    }

    /** Keeps SimpleFactions types out of the will screen so it loads without SimpleFactions. */
    static final class SimpleFactionsGuild {
        private SimpleFactionsGuild() {}

        static Guild read(UUID characterId) {
            if (!net.tfminecraft.simplefactions.api.SimpleFactionsApi.ready()) return UNKNOWN;
            net.tfminecraft.simplefactions.guild.Guild guild =
                    net.tfminecraft.simplefactions.managers.FactionManager.getGuildByMember(characterId);
            if (guild == null) return NONE;
            UUID leader = net.tfminecraft.simplefactions.identity.Members.characterId(guild.getLeader());
            var realm = guild.getFaction();
            UUID realmLeader = realm == null ? null
                    : net.tfminecraft.simplefactions.identity.Members.characterId(realm.getLeader());
            String name = guild.getName() == null ? "your guild" : ClueFormatter.stripColor(guild.getName()).trim();
            return new Guild.Member(name, living(leader, characterId), characterId.equals(leader),
                    living(realmLeader, characterId), realm == null ? Optional.empty() : solo(realm, characterId));
        }

        /** The realm is held at this character's death only when nobody else is in it. */
        static Optional<Realm> solo(net.tfminecraft.simplefactions.objects.Faction realm, UUID characterId) {
            List<String> members = realm.getMembers();
            if (members == null || members.size() != 1
                    || !characterId.equals(net.tfminecraft.simplefactions.identity.Members.characterId(members.getFirst()))) {
                return Optional.empty();
            }
            Double hours = heirWaitHours.get();
            if (hours == null) return Optional.empty();
            String name = realm.getName() == null ? "your realm" : ClueFormatter.stripColor(realm.getName()).trim();
            return Optional.of(new Realm(name, hours));
        }
    }
}
