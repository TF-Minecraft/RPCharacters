package net.tfminecraft.rpcharacters.will;

import java.util.Optional;
import java.util.UUID;

import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterStatus;

/** Design W2: who may change a will and whom it may name. Checked on every change and again at the decision. */
public final class WillRules {
    public enum Verdict { OK, NOT_OWNER, TESTATOR_DEAD, HEIR_UNKNOWN, HEIR_NOT_ALIVE, HEIR_SELF, HEIR_UNREADABLE }

    /** Character lookups, so the rules need no running server. */
    public interface Lookup {
        Optional<CharacterInfo> info(UUID characterId);

        boolean unreadable(UUID characterId);
    }

    private WillRules() {}

    /**
     * Only the testator's account may change the will, only while the testator is alive. The heir must be
     * another living character, on any account; {@code heir} null revokes.
     */
    public static Verdict check(UUID requester, UUID owner, RPCharacter testator, UUID heir, Lookup lookup) {
        if (!owner.equals(requester)) return Verdict.NOT_OWNER;
        if (testator.getStatus() != Status.ALIVE) return Verdict.TESTATOR_DEAD;
        if (heir == null) return Verdict.OK;
        if (heir.toString().equalsIgnoreCase(testator.getId())) return Verdict.HEIR_SELF;
        if (lookup.unreadable(heir)) return Verdict.HEIR_UNREADABLE;
        Optional<CharacterInfo> info = lookup.info(heir);
        if (info.isEmpty()) return Verdict.HEIR_UNKNOWN;
        return info.get().status() == CharacterStatus.ALIVE ? Verdict.OK : Verdict.HEIR_NOT_ALIVE;
    }
}
