package net.tfminecraft.rpcharacters.will;

import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.database.Database;
import net.tfminecraft.rpcharacters.identity.CharacterIdentityService;
import net.tfminecraft.rpcharacters.ingest.RosterSyncService;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.tlibs.character.CharacterInfo;

/** Applies will changes from the game and the website: validated, saved durably, then pushed to the site. */
public final class WillService {
    /** A change's verdict, and the will in force afterwards. */
    public record Change(WillRules.Verdict verdict, boolean saved, CharacterWill will) {
        public boolean applied() {
            return verdict == WillRules.Verdict.OK && saved;
        }
    }

    static Database database = new Database();
    static LongSupplier clock = System::currentTimeMillis;
    static WillRules.Lookup lookup = new WillRules.Lookup() {
        @Override
        public Optional<CharacterInfo> info(UUID characterId) {
            CharacterIdentityService identity = RPCharacters.getCharacterIdentity();
            return identity == null ? Optional.empty() : identity.info(characterId);
        }

        @Override
        public boolean unreadable(UUID characterId) {
            CharacterIdentityService identity = RPCharacters.getCharacterIdentity();
            return identity == null || identity.unreadable(characterId);
        }
    };

    private WillService() {}

    public static WillRules.Lookup lookup() {
        return lookup;
    }

    /** The heir's current name, or null when it cannot be read. */
    public static String heirName(CharacterWill will) {
        return will.heir() == null ? null : lookup.info(will.heir()).map(CharacterInfo::name).orElse(null);
    }

    /**
     * Names {@code heir} (null revokes) for {@code character}, owned by {@code data}'s account. A save that
     * fails restores the previous will.
     */
    public static Change change(PlayerData data, RPCharacter character, UUID requester, UUID heir, String requestId) {
        CharacterWill before = character.getWill();
        WillRules.Verdict verdict = WillRules.check(requester, data.getUniqueId(), character, heir, lookup);
        if (verdict != WillRules.Verdict.OK) return new Change(verdict, false, before);
        character.setWill(before.change(heir, clock.getAsLong(), requestId));
        if (!database.trySaveCharacterDurably(data, character)) {
            character.setWill(before);
            return new Change(verdict, false, before);
        }
        RosterSyncService.pushRosterAsync(data.getUniqueId());
        return new Change(verdict, true, character.getWill());
    }
}
