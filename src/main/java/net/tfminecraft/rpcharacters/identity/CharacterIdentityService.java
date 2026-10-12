package net.tfminecraft.rpcharacters.identity;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.body.CharacterBodies;
import net.tfminecraft.rpcharacters.lifecycle.CharacterTransitions;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.tlibs.character.CharacterIdentity;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterSession;

/** Complete saved identities with live, unsaved edits overlaid on the main thread. */
public final class CharacterIdentityService implements CharacterIdentity {
    private final CharacterDirectory directory;
    private final CharacterTransitions transitions;

    public CharacterIdentityService(CharacterDirectory directory, CharacterTransitions transitions) {
        this.directory = directory;
        this.transitions = transitions;
    }

    /**
     * Overlays live edits for one owner's loaded account, and for any other loaded account sharing
     * one of its character IDs, so a live duplicate still quarantines both owners.
     */
    private void refreshOwner(UUID ownerId) {
        CharacterTransitions.requireMainThread();
        Set<String> ids = new HashSet<>();
        for (PlayerData data : PlayerManager.getOnlineData()) {
            if (!ownerId.equals(data.getUniqueId())) continue;
            for (RPCharacter character : data.getCharacters()) {
                if (character.getId() != null) ids.add(character.getId().toLowerCase(Locale.ROOT));
            }
        }
        for (PlayerData data : PlayerManager.getOnlineData()) {
            if (ownerId.equals(data.getUniqueId()) || data.getCharacters().stream().anyMatch(
                    character -> character.getId() != null && ids.contains(character.getId().toLowerCase(Locale.ROOT)))) {
                refresh(data);
            }
        }
    }

    /** Overlays every loaded account holding this character, so live duplicates still quarantine. */
    private void refreshCharacter(UUID characterId) {
        CharacterTransitions.requireMainThread();
        String id = characterId.toString();
        for (PlayerData data : PlayerManager.getOnlineData()) {
            if (data.getCharacters().stream().anyMatch(character -> id.equalsIgnoreCase(character.getId()))) refresh(data);
        }
    }

    private void refresh(PlayerData data) {
        for (RPCharacter character : data.getCharacters()) directory.upsert(data.getUniqueId(), character);
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
        refreshCharacter(characterId);
        return directory.info(characterId);
    }

    @Override
    public Optional<UUID> deathId(UUID characterId) {
        refreshCharacter(characterId);
        return directory.deathId(characterId);
    }

    @Override
    public Optional<List<CharacterInfo>> characters(UUID ownerId) {
        refreshOwner(ownerId);
        return directory.characters(ownerId);
    }

    @Override
    public Optional<CharacterInfo> lastPlayed(UUID ownerId) {
        refreshOwner(ownerId);
        return directory.lastPlayed(ownerId);
    }

    /** The reviewed body heir, which also inherits account-wide money and memberships. */
    @Override
    public Optional<CharacterRef> legacyHeir(UUID ownerId) {
        CharacterTransitions.requireMainThread();
        CharacterBodies bodies = RPCharacters.getCharacterBodies();
        UUID heir = bodies == null ? null : bodies.migrationHeir(ownerId);
        return heir == null ? Optional.empty() : Optional.of(new CharacterRef(ownerId, heir));
    }

    /** Heirs come from the hash-checked manifest, so they are known only once bodies have loaded. */
    @Override
    public boolean legacyHeirsReady() {
        CharacterTransitions.requireMainThread();
        return RPCharacters.getCharacterBodies() != null;
    }

    @Override
    public String displayName(Player player) {
        CharacterTransitions.requireMainThread();
        return PlayerManager.get(player) == null ? player.getName() : DisplayIdentityService.resolveDisplay(player);
    }

    public static void saved(UUID owner, RPCharacter character) {
        if (RPCharacters.getCharacterIdentity() == null) return;
        if (Bukkit.isPrimaryThread()) {
            onMainThread(directory -> directory.upsert(owner, character));
            return;
        }
        // Snapshot now: the live character may change before the queued update runs.
        CharacterInfo info;
        try {
            info = CharacterDirectory.snapshot(owner, character);
        } catch (RuntimeException invalid) {
            onMainThread(directory -> directory.upsert(owner, character));
            return;
        }
        boolean active = Boolean.TRUE.equals(character.isActive());
        UUID deathId = character.getDeathId();
        onMainThread(directory -> directory.upsert(owner, info, active, deathId));
    }

    public static void removed(UUID owner, String characterId) {
        onMainThread(directory -> directory.remove(owner, characterId));
    }

    /** A save off the main thread has already succeeded, so its directory update is queued, never thrown. */
    private static void onMainThread(Consumer<CharacterDirectory> update) {
        CharacterIdentityService service = RPCharacters.getCharacterIdentity();
        if (service == null) return;
        if (Bukkit.isPrimaryThread()) {
            update.accept(service.directory);
            return;
        }
        try {
            Bukkit.getScheduler().runTask(RPCharacters.plugin, () -> onMainThread(update));
        } catch (RuntimeException error) {
            // Disabled plugin: shutdown is ending and the next startup reloads the directory from disk.
            RPCharacters.plugin.getLogger().warning("Character directory update skipped: " + error.getMessage());
        }
    }
}
