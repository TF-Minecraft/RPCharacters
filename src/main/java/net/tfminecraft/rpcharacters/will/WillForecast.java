package net.tfminecraft.rpcharacters.will;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

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
         */
        record Member(String name, Optional<CharacterInfo> leader, boolean youLead,
                Optional<CharacterInfo> realmLeader) implements Guild {}
    }

    static final Guild UNKNOWN = new Guild.Unknown();
    static final Guild NONE = new Guild.None();
    static Function<UUID, Guild> guilds = WillForecast::read;

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
                    living(realmLeader, characterId));
        }
    }
}
