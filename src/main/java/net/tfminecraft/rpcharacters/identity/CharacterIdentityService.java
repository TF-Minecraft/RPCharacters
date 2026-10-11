package net.tfminecraft.rpcharacters.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.lifecycle.CharacterTransitions;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.tlibs.character.CharacterIdentity;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterSession;

/** Complete saved identities with live, unsaved edits overlaid on the main thread. */
public final class CharacterIdentityService implements CharacterIdentity {
    private final CharacterDirectory directory;
    private final CharacterTransitions transitions;

    public CharacterIdentityService(CharacterDirectory directory, CharacterTransitions transitions) {
        this.directory = directory;
        this.transitions = transitions;
    }

    private void refresh() {
        CharacterTransitions.requireMainThread();
        for (PlayerData data : PlayerManager.getOnlineData()) {
            for (RPCharacter character : data.getCharacters()) directory.upsert(data.getUniqueId(), character);
        }
    }

    @Override
    public Optional<CharacterSession> currentSession(Player player) {
        CharacterTransitions.requireMainThread();
        return directory.characters(player.getUniqueId()).isEmpty() ? Optional.empty() : transitions.currentSession(player);
    }

    @Override
    public boolean isCurrent(CharacterSession session) {
        CharacterTransitions.requireMainThread();
        return directory.characters(session.ref().ownerId()).isPresent() && transitions.isCurrent(session);
    }

    @Override
    public Optional<CharacterInfo> info(UUID characterId) {
        refresh();
        return directory.info(characterId);
    }

    @Override
    public Optional<List<CharacterInfo>> characters(UUID ownerId) {
        refresh();
        return directory.characters(ownerId);
    }

    @Override
    public Optional<CharacterInfo> lastPlayed(UUID ownerId) {
        refresh();
        return directory.lastPlayed(ownerId);
    }

    @Override
    public String displayName(Player player) {
        CharacterTransitions.requireMainThread();
        return PlayerManager.get(player) == null ? player.getName() : DisplayIdentityService.resolveDisplay(player);
    }

    public static void saved(UUID owner, RPCharacter character) {
        CharacterIdentityService service = RPCharacters.getCharacterIdentity();
        if (service != null) {
            CharacterTransitions.requireMainThread();
            service.directory.upsert(owner, character);
        }
    }

    public static void removed(UUID owner, String characterId) {
        CharacterIdentityService service = RPCharacters.getCharacterIdentity();
        if (service != null) {
            CharacterTransitions.requireMainThread();
            service.directory.remove(owner, characterId);
        }
    }
}
