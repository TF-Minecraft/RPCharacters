package net.tfminecraft.rpcharacters.lifecycle;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.database.Database;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.identity.CharacterDirectory;
import net.tfminecraft.rpcharacters.mail.MailRecipientDirectory;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.permadeath.CharacterPermakillEvent;
import net.tfminecraft.rpcharacters.permadeath.PermakillCause;
import net.tfminecraft.rpcharacters.utils.ProstheticTraitRules;
import net.tfminecraft.tlibs.character.CharacterDeathEvent;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.CharacterSessionEndEvent;
import net.tfminecraft.tlibs.character.CharacterSessionEndEvent.Reason;
import net.tfminecraft.tlibs.character.CharacterSessionStartEvent;

/** Owns session boundaries; end listeners always run before outgoing state is saved or removed. */
public final class CharacterTransitions {
    private static final AtomicLong NEXT_SESSION = new AtomicLong();
    private static final String SUSPENDED = "Your character could not be saved. Please contact staff.";
    private final Map<UUID, CharacterSession> sessions = new HashMap<>();
    private final Set<UUID> changing = new HashSet<>();
    private final Set<PlayerData> joining = new HashSet<>();
    private final Database database;
    private final CharacterDirectory directory;
    private boolean stopped;

    public CharacterTransitions(Database database, CharacterDirectory directory) {
        this.database = database;
        this.directory = directory;
    }

    public static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Character transitions require the main thread");
    }

    public Optional<CharacterSession> currentSession(Player player) {
        requireMainThread();
        return player.isOnline() ? Optional.ofNullable(sessions.get(player.getUniqueId())) : Optional.empty();
    }

    public boolean isCurrent(CharacterSession session) {
        requireMainThread();
        Player player = Bukkit.getPlayer(session.ref().ownerId());
        return player != null && player.isOnline() && session.equals(sessions.get(session.ref().ownerId()));
    }

    public void beginJoin(PlayerData data) {
        requireMainThread();
        joining.add(data);
    }

    public void ready(PlayerData data) {
        enter(data.getUniqueId());
        try {
            if (joining.remove(data)) start(data, null);
        } finally {
            changing.remove(data.getUniqueId());
        }
    }

    /** Body and progression have both been restored; publish the new ready session. */
    public void selected(PlayerData data) {
        enter(data.getUniqueId());
        try {
            joining.remove(data);
            start(data, null);
        } finally {
            changing.remove(data.getUniqueId());
        }
    }

    public void leave(PlayerData data, Reason reason) {
        leave(data, reason, () -> {});
    }

    /** Ends the session, then runs the caller's outgoing save under the same transition guard. */
    public void leave(PlayerData data, Reason reason, Runnable outgoing) {
        requireMainThread();
        // A failed save can kick during a transition; quit still has to save and unload the account.
        boolean ownsGuard = changing.add(data.getUniqueId());
        if (!ownsGuard && reason != Reason.QUIT) throw new IllegalStateException("Character transition already in progress");
        try {
            joining.remove(data);
            end(data, reason);
            outgoing.run();
        } finally {
            if (ownsGuard) changing.remove(data.getUniqueId());
        }
    }

    public void shutdown() {
        requireMainThread();
        if (stopped) return;
        stopped = true;
        for (PlayerData data : PlayerManager.getOnlineData()) end(data, Reason.SHUTDOWN);
        joining.clear();
        sessions.clear();
    }

    private void enter(UUID owner) {
        requireMainThread();
        if (stopped || !changing.add(owner)) throw new IllegalStateException("Character transition already in progress or stopped");
    }

    private void end(PlayerData data, Reason reason) {
        CharacterSession session = sessions.remove(data.getUniqueId());
        if (session != null) {
            Bukkit.getPluginManager().callEvent(new CharacterSessionEndEvent(data.getPlayer(), session, reason));
        }
    }

    private void start(PlayerData data, RPCharacter previous) {
        Player player = data.getPlayer();
        RPCharacter character = data.getActiveCharacter();
        if (joining.contains(data) || player == null || !player.isOnline() || character == null
                || character.getStatus() != Status.ALIVE || sessions.containsKey(data.getUniqueId())) return;
        if (RPCharacters.getCharacterBodies() != null && !RPCharacters.getCharacterBodies().hasLiveBody(player)) return;
        directory.upsert(data.getUniqueId(), character);
        if (directory.characters(data.getUniqueId()).isEmpty()) {
            suspend(player, "Character records are incomplete for " + data.getUniqueId());
            throw new IllegalStateException("Character records are incomplete for " + data.getUniqueId());
        }
        CharacterRef ref = CharacterDirectory.snapshot(data.getUniqueId(), character).ref();
        character.setLastPlayedAt(Instant.now());
        if (!database.trySavePlayer(data)) {
            suspend(player, "Could not save character activation for " + ref);
            throw new IllegalStateException("Could not save character activation for " + ref);
        }
        CharacterSession session = new CharacterSession(ref, NEXT_SESSION.incrementAndGet());
        sessions.put(data.getUniqueId(), session);
        Bukkit.getPluginManager().callEvent(new CharacterSessionStartEvent(player, session));
        if (isCurrent(session)) CharacterLifecycle.fireActivated(player, data.getUniqueId(), character, previous);
    }

    private static void suspend(Player player, String message) {
        RPCharacters.plugin.getLogger().severe(message);
        if (player != null && player.isOnline()) player.kickPlayer(SUSPENDED);
    }

    /**
     * For callers that already committed their own change: a failed switch disconnects the player,
     * whose quit saves the account, instead of throwing, so the caller can finish its bookkeeping.
     */
    public static void switchAfterCommit(PlayerData data, RPCharacter next) {
        try {
            data.setActiveCharacter(next);
        } catch (RuntimeException error) {
            abandon(data.getPlayer() != null ? data.getPlayer() : Bukkit.getPlayer(data.getUniqueId()),
                    "Could not activate character " + next.getId() + " for " + data.getUniqueId(), error);
        }
    }

    private static void abandon(Player player, String message, RuntimeException error) {
        RPCharacters.plugin.getLogger().log(Level.SEVERE, message, error);
        if (player != null && player.isOnline()) player.kickPlayer(SUSPENDED);
    }

    public static void switchCharacter(PlayerData data, RPCharacter next, RPCharacter ended) {
        if (next == null) return;
        if (RPCharacters.getCharacterBodies() != null) {
            RPCharacters.getCharacterBodies().requestSwitch(data, next);
            return;
        }
        CharacterTransitions runtime = RPCharacters.getCharacterTransitions();
        if (runtime != null) runtime.enter(data.getUniqueId());
        try {
            applySwitch(data, next, ended, runtime);
        } finally {
            if (runtime != null) runtime.changing.remove(data.getUniqueId());
        }
    }

    private static void applySwitch(PlayerData data, RPCharacter next, RPCharacter ended, CharacterTransitions runtime) {
        if (runtime != null && (!data.getCharacters().contains(next) || next.getStatus() != Status.ALIVE)) {
            throw new IllegalArgumentException("Only a living character on this account can be selected");
        }
        boolean stripped = ProstheticTraitRules.stripReplacedInjuries(next);
        RPCharacter current = data.getActiveCharacter();
        if (current != null && current.getId() != null && current.getId().equals(next.getId())) {
            if (stripped) next.update();
            return;
        }
        Player player = data.getPlayer() != null ? data.getPlayer() : Bukkit.getPlayer(data.getUniqueId());
        if (runtime != null) {
            runtime.end(data, Reason.SWITCH);
            if (player == null || !player.isOnline()) return;
        }
        if (current != null && player != null) {
            current.stampLastLocation(player.getLocation());
            MailRecipientDirectory.upsert(data.getUniqueId(), current);
        }
        if (runtime != null && current != null && !runtime.joining.contains(data) && !runtime.database.trySavePlayer(data)) {
            suspend(player, "Could not save outgoing character for " + data.getUniqueId());
            throw new IllegalStateException("Could not save outgoing character for " + data.getUniqueId());
        }
        data.clearTempAlias();
        for (RPCharacter character : data.getCharacters()) {
            if (character.isActive()) {
                if (runtime != null && runtime.joining.contains(data)) character.setActiveState(false);
                else character.deactivate();
                data.recordCharacterSwitch();
            }
        }
        if (runtime != null && runtime.joining.contains(data)) {
            next.setActiveState(true);
        } else {
            if (runtime != null) {
                if (!next.tryActivate()) {
                    suspend(player, "Character class was not ready for " + next.getId());
                    throw new IllegalStateException("Character class was not ready for " + next.getId());
                }
                runtime.start(data, current != null ? current : ended);
            } else {
                next.activate();
                CharacterLifecycle.fireActivated(player, data.getUniqueId(), next, current != null ? current : ended);
            }
        }
    }

    public record DeathResult(boolean killed, boolean wasActive, String replacementName) {}

    public static DeathResult kill(Player player, RPCharacter character, PermakillCause cause, Player killer) {
        CharacterTransitions runtime = RPCharacters.getCharacterTransitions();
        if (runtime != null) runtime.enter(player.getUniqueId());
        try {
            if (character.getStatus() != Status.ALIVE) return new DeathResult(false, false, null);
            CharacterPermakillEvent event = new CharacterPermakillEvent(player, character, cause, killer);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) return new DeathResult(false, false, null);
            PlayerData data = PlayerManager.get(player);
            boolean wasActive = character.isActive();
            CharacterRef killerRef = runtime == null || killer == null ? null : runtime.currentSession(killer).map(CharacterSession::ref).orElse(null);
            if (runtime != null) {
                if (data == null || !data.getCharacters().contains(character)) return new DeathResult(false, false, null);
                if (wasActive) runtime.end(data, Reason.DEATH);
                if (!player.isOnline()) return new DeathResult(false, false, null);
            }
            UUID oldDeathId = character.getDeathId();
            Instant oldDiedAt = character.getDiedAt();
            if (wasActive && RPCharacters.getCharacterBodies() != null
                    && !RPCharacters.getCharacterBodies().prepareDeath(data, character)) {
                return new DeathResult(false, false, null);
            }
            character.setStatus(Status.DEAD);
            character.setDeathRecord(UUID.randomUUID(), Instant.now());
            if (runtime != null) {
                character.setActiveState(false);
                if (!runtime.database.trySaveCharacter(data, character)) {
                    character.setStatus(Status.ALIVE);
                    character.setActiveState(wasActive);
                    character.setDeathRecord(oldDeathId, oldDiedAt);
                    suspend(player, "Permanent death was not saved for " + character.getId());
                    return new DeathResult(false, false, null);
                }
                Bukkit.getPluginManager().callEvent(new CharacterDeathEvent(
                        CharacterDirectory.snapshot(data.getUniqueId(), character).ref(), character.getDeathId(),
                        character.getDiedAt(), killer == null ? null : killer.getUniqueId(), killerRef, cause.name()));
            }
            if (runtime != null && !player.isOnline()) return new DeathResult(true, wasActive, null);
            String replacementName = null;
            if (wasActive && data != null) data.clearTempAlias();
            if (wasActive && RPCharacters.getCharacterBodies() != null) {
                RPCharacters.getCharacterBodies().markDead(data, character);
                net.tfminecraft.rpcharacters.professions.ProfessionIntegrator.remove(player, character);
                return new DeathResult(true, true, null);
            }
            // The death is saved; a failed replacement must not stop its post-processing.
            if (wasActive) {
                try {
                    if (runtime == null || !runtime.joining.contains(data)) character.deactivate();
                    if (data != null) {
                        if (!data.getCharacters(Status.ALIVE).isEmpty()) {
                            RPCharacter next = data.getCharacters(Status.ALIVE).getFirst();
                            applySwitch(data, next, character, runtime);
                            replacementName = next.getName();
                            net.tfminecraft.rpcharacters.wardrobe.WardrobeService.refreshActiveAsync(player);
                        }
                    }
                } catch (RuntimeException error) {
                    abandon(player, "Could not activate a replacement after the death of " + character.getId(), error);
                }
            }
            return new DeathResult(true, wasActive, replacementName);
        } finally {
            if (runtime != null) runtime.changing.remove(player.getUniqueId());
        }
    }
}
