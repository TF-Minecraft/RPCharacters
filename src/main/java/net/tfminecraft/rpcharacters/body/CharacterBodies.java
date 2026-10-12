package net.tfminecraft.rpcharacters.body;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.Component;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.database.Database;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.lifecycle.CharacterSelectionRequestEvent;
import net.tfminecraft.rpcharacters.managers.CreationManager;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.persona.PermissionGroupService;
import net.tfminecraft.rpcharacters.progress.CharacterProgressService;
import net.tfminecraft.rpcharacters.professions.ProfessionIntegrator;
import net.tfminecraft.rpcharacters.pvp.ArmourDonning;
import net.tfminecraft.rpcharacters.pvp.Duels;
import net.tfminecraft.rpcharacters.pvp.PvpSituations;
import net.tfminecraft.rpcharacters.pvp.PvpStartSessions;
import net.tfminecraft.rpcharacters.permadeath.PermadeathBattleExemption;
import net.tfminecraft.rpcharacters.wardrobe.SkinApplyHelper;
import net.tfminecraft.rpcharacters.wardrobe.WardrobeCache;
import net.tfminecraft.rpcharacters.wardrobe.WardrobeService;
import net.tfminecraft.tlibs.character.CharacterSessionEndEvent.Reason;

/** Coordinates the single live body with stowed bodies. Selection never swaps one live body for another. */
public final class CharacterBodies {
    private final RPCharacters plugin;
    private final Database database;
    private final BodyStore store;
    private final BodyLeaseStore leases;
    private final CharacterBodyMigration migration;
    private final CharacterProgressService progress;
    private final CharacterPackGate packs;
    private final Location room;
    private final Set<NamespacedKey> bodyKeys;
    private final Map<UUID, Location> originalSpawns = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerData> pendingAccounts = new HashMap<>();
    private final Map<UUID, RoomEntry> enteringRoom = new HashMap<>();
    private final Set<UUID> deferredPrepare = new HashSet<>();
    private final Map<UUID, BodyTag.Live> live = new HashMap<>();
    private final Map<UUID, Location> deathLocations = new HashMap<>();
    private final Set<UUID> selecting = ConcurrentHashMap.newKeySet();
    private final Set<UUID> ready = new HashSet<>();
    private final Set<UUID> failed = new HashSet<>();
    private final Set<UUID> applying = new HashSet<>();
    private final Map<UUID, Object> requests = new HashMap<>();
    private final Map<UUID, PendingRespawn> pendingRespawns = new HashMap<>();
    private final Map<UUID, Countdown> returning = new HashMap<>();
    private final int returnSeconds;

    private static final class RoomEntry {
        final Player player;
        final PlayerData account;
        BodyTag recoveredFrom;
        boolean detached;
        boolean entered;
        boolean notified;
        RoomEntry(Player player, PlayerData account) { this.player = player; this.account = account; }
    }

    /** A playing character standing still before returning to selection. */
    private static final class Countdown {
        final PlayerData account;
        final Location start;
        int remaining;
        BukkitTask task;
        Countdown(PlayerData account, Location start, int remaining) {
            this.account = account; this.start = start; this.remaining = remaining;
        }
    }

    private static final class PendingRespawn {
        final BodyTag body;
        boolean started;
        boolean completed;
        PendingRespawn(BodyTag body) { this.body = body; }
    }

    public CharacterBodies(RPCharacters plugin, ConfigurationSection config) throws IOException {
        this.plugin = plugin;
        database = new Database();
        Path data = plugin.getDataFolder().toPath().resolve("data");
        store = new BodyStore(data.resolve("bodies"));
        leases = new BodyLeaseStore(data.resolve("body-leases"));
        World mainWorld = Bukkit.getWorld(config.getString("spawn-world", Bukkit.getWorlds().getFirst().getName()));
        if (mainWorld == null) throw new IOException("Character body spawn world is not loaded");
        migration = new CharacterBodyMigration(data.resolve("body-migrations"),
                plugin.getDataFolder().toPath().resolve("character-body-heirs.yml"),
                config.getString("heirs-sha256", ""), store, mainWorld.getSpawnLocation());
        bodyKeys = new HashSet<>();
        for (String value : config.getStringList("persistent-keys")) {
            NamespacedKey key = NamespacedKey.fromString(value);
            if (key == null || key.getNamespace().equals("rpcharacters") && key.getKey().startsWith("body")) {
                throw new IOException("Invalid character body persistent-data key: " + value);
            }
            bodyKeys.add(key);
        }
        String roomName = config.getString("selection-world", "character_selection");
        if (mainWorld.getName().equals(roomName)) throw new IOException("Selection must use its own world");
        room = CharacterSelectionWorld.create(roomName);
        progress = new CharacterProgressService(data.resolve("progress"));
        packs = new CharacterPackGate(plugin, config.getBoolean("require-resource-pack", false));
        returnSeconds = Math.max(1, config.getInt("return-seconds", 5));
        Bukkit.getPluginManager().registerEvents(new CharacterSelectionGuard(this, plugin), plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::checkpoint, 6000L, 6000L);
    }

    public Location room() { return room.clone(); }
    public boolean isSelecting(Player player) { return selecting.contains(player.getUniqueId()); }
    public boolean isApplying(Player player) { return applying.contains(player.getUniqueId()); }
    public boolean hasLiveBody(Player player) { return live.containsKey(player.getUniqueId()); }
    public boolean isReadyToChoose(Player player) {
        return current(player, PlayerManager.get(player)) && ready.contains(player.getUniqueId()) && packs.ready(player);
    }
    public boolean isRespawning(Player player) {
        return pendingRespawns.containsKey(player.getUniqueId());
    }
    public UUID liveCharacter(Player player) {
        BodyTag.Live body = live.get(player.getUniqueId());
        return body == null ? null : body.characterId();
    }
    public UUID migrationHeir(UUID owner) { return migration.heir(owner); }

    public void rememberSpawn(UUID owner, Location original) {
        originalSpawns.put(owner, original.clone());
    }

    /** Runs at join LOWEST, before ordinary character activation and before any room inventory can be used. */
    public void join(Player player) {
        UUID owner = player.getUniqueId();
        selecting.add(owner);
        failed.remove(owner);
        Location original = originalSpawns.remove(owner);
        if (original == null) original = player.getLocation();
        try {
            PlayerData account = database.loadPlayerData(owner);
            if (account == null) throw new IOException("Character account could not be loaded");
            RoomEntry entry = new RoomEntry(player, account);
            enteringRoom.put(owner, entry);
            progress.recordLegacyActive(account, account.getActiveCharacter());
            BodyTag tag = BodyTag.read(player);
            leases.validate(owner, tag);
            if (tag instanceof BodyTag.Live active && leases.pendingRespawn(owner, active)
                    && leases.respawnCompleted(owner, active) == player.isDead()) {
                throw new IOException("Interrupted respawn does not match the saved vanilla player");
            }
            if (player.isDead()) {
                // Dead characters and stale copies retain their original body before the room's
                // vanilla entity respawns. Only an authoritative living character owns that respawn.
                if (!ownsRespawn(account, tag)) {
                    recover(account, tag, original);
                    entry.recoveredFrom = tag;
                    tag = BodyTag.read(player);
                } else if (tag instanceof BodyTag.Live active) {
                    leases.markRespawn(owner, active);
                }
                pendingRespawns.put(owner, new PendingRespawn(tag));
            } else {
                recover(account, tag, original);
                entry.recoveredFrom = tag;
            }
            for (RPCharacter character : account.getCharacters()) {
                ProfessionIntegrator.remove(player, character);
                character.setActiveState(false);
            }
            pendingAccounts.put(owner, account);
            hide(player);
            Bukkit.getScheduler().runTask(plugin, () -> enterRoom(entry));
        } catch (IOException | RuntimeException error) {
            // A failed first migration must retain the original location along with the vanilla body.
            applying.add(owner);
            try { player.teleport(original); } finally { applying.remove(owner); }
            fail(player, "recovering the character body", error);
        }
    }

    private void enterRoom(RoomEntry entry) {
        Player player = entry.player;
        PlayerData account = entry.account;
        UUID owner = player.getUniqueId();
        if (enteringRoom.get(owner) != entry || !current(player, account)) return;
        try {
            // Paper restores RootVehicle after PlayerJoinEvent. Keep that tree in its saved world.
            if (!entry.detached) {
                SavedMount.detach(player);
                entry.detached = true;
            }
            if (entry.recoveredFrom != null) {
                saveRecoveredRoom(player, entry.recoveredFrom);
                entry.recoveredFrom = null;
            }
            if (!pendingRespawns.containsKey(owner) && !entry.entered) {
                applying.add(owner);
                try {
                    if (!player.teleport(room)) {
                        if (current(player, account)) {
                            if (!packs.ready(player)) {
                                packs.await(player, () -> enteringRoom.get(owner) == entry && current(player, account),
                                        () -> enterRoom(entry));
                                return;
                            }
                            if (!entry.notified) {
                                player.sendMessage("Loading character selection. Please wait for your resource pack to finish.");
                                entry.notified = true;
                            }
                            Bukkit.getScheduler().runTaskLater(plugin, () -> enterRoom(entry), 20L);
                        }
                        return;
                    }
                } finally {
                    applying.remove(owner);
                }
                if (!current(player, account)) return;
                player.saveData();
                entry.entered = true;
            }
            if (!packs.ready(player)) {
                packs.await(player, () -> enteringRoom.get(owner) == entry && current(player, account), () -> enterRoom(entry));
                return;
            }
            enteringRoom.remove(owner, entry);
            wearRoomSkin(player);
            if (deferredPrepare.remove(owner)) prepare(account);
        } catch (IOException | RuntimeException error) {
            fail(player, "entering character selection", error);
        }
    }

    private void enterAfterRecovery(PlayerData account) {
        RoomEntry entry = new RoomEntry(account.getPlayer(), account);
        enteringRoom.put(account.getUniqueId(), entry);
        deferredPrepare.add(account.getUniqueId());
        enterRoom(entry);
    }

    private boolean ownsRespawn(PlayerData account, BodyTag tag) throws IOException {
        if (tag instanceof BodyTag.Untagged) throw new IOException("Legacy dead player must respawn before character-body migration");
        if (!(tag instanceof BodyTag.Live active)) return false;
        RPCharacter character = account.getCharacterById(active.characterId().toString());
        if (character == null || character.getStatus() != Status.ALIVE) return false;
        BodyRecord saved = store.read(account.getUniqueId(), active.characterId())
                .orElseThrow(() -> new IOException("Live character body is missing"));
        return saved.rev() == active.rev();
    }

    private void recover(PlayerData account, BodyTag tag, Location original) throws IOException {
        Player player = account.getPlayer();
        UUID owner = account.getUniqueId();
        player.closeInventory();
        BodySnapshot current = BodySnapshot.capture(player, bodyKeys).withLocation(original);
        UUID previous;
        if (tag instanceof BodyTag.Live active) {
            BodyRecord saved = store.read(owner, active.characterId())
                    .orElseThrow(() -> new IOException("Live character body is missing"));
            if (saved.rev() < active.rev()) throw new IOException("Live character body revision is ahead of storage");
            if (saved.rev() == active.rev()) {
                RPCharacter character = account.getCharacterById(active.characterId().toString());
                if (character != null && character.getStatus() == Status.ALIVE && original.getWorld().equals(room.getWorld())) {
                    throw new IOException("Live character body cannot be recovered from the selection room");
                }
                if (character != null && character.getStatus() == Status.ALIVE && !player.isDead()
                        && leases.pendingRespawn(owner, active)) current = current.withFullHealth();
                store.write(owner, active.characterId(), active.rev(), "join-recovery", current);
            } else {
                store.quarantine(owner, "stale-live-copy", current);
            }
            previous = active.characterId();
        } else if (tag instanceof BodyTag.Room waiting) {
            if (!migration.migrated(owner)) throw new IOException("Room body has no completed migration");
            if (hasItems(player)) store.quarantine(owner, "items-returned-after-stow", current);
            previous = waiting.lastCharacterId();
        } else {
            previous = migration.initialize(account, current);
            if (previous == null) progress.recordFreshAccount(account);
        }
        clear(player);
        BodyTag.room(player, previous);
    }

    private void saveRecoveredRoom(Player player, BodyTag tag) throws IOException {
        player.saveData();
        if (tag instanceof BodyTag.Live active) leases.complete(player.getUniqueId(), active);
    }

    public PlayerData takeAccount(Player player) {
        return pendingAccounts.remove(player.getUniqueId());
    }

    public void prepare(PlayerData account) {
        Player player = account.getPlayer();
        if (!current(player, account) || failed.contains(account.getUniqueId())) return;
        if (enteringRoom.containsKey(account.getUniqueId())) {
            deferredPrepare.add(account.getUniqueId());
            return;
        }
        if (!packs.ready(player)) {
            packs.await(player, () -> current(player, account), () -> prepare(account));
            return;
        }
        try {
            PendingRespawn pending = pendingRespawns.get(account.getUniqueId());
            if (pending != null) {
                if (pending.started) return;
                pending.started = true;
                applying.add(account.getUniqueId());
                try {
                    if (!pending.completed) BodyRespawn.respawn(player);
                    if (!pending.completed || player.isDead() || failed.contains(account.getUniqueId())) {
                        throw new IOException("Vanilla respawn did not complete");
                    }
                } finally {
                    applying.remove(account.getUniqueId());
                }
                Bukkit.getScheduler().runTaskLater(plugin, () -> finishRespawn(account, pending), 2L);
                return;
            }
            progress.prepare(account);
            for (RPCharacter character : account.getCharacters()) migration.ensureBody(account, character);
            if (!database.trySavePlayer(account)) throw new IOException("Prepared character account could not be saved");
            ready.add(account.getUniqueId());
            show(player);
        } catch (IOException | RuntimeException error) {
            fail(player, "preparing character progression", error);
        }
    }

    /** Paper fires this only after the actual vanilla reset and teleport, unlike a health change. */
    public void respawned(Player player) {
        UUID owner = player.getUniqueId();
        PendingRespawn pending = pendingRespawns.get(owner);
        if (pending == null) return;
        try {
            if (!pending.body.equals(BodyTag.read(player))) throw new IOException("Respawned body's tag changed");
            if (pending.body instanceof BodyTag.Live active) leases.completeRespawn(owner, active);
            pending.completed = true;
        } catch (IOException | RuntimeException error) {
            fail(player, "recording the completed vanilla respawn", error);
        }
    }

    private void finishRespawn(PlayerData account, PendingRespawn pending) {
        Player player = account.getPlayer();
        UUID owner = account.getUniqueId();
        if (!current(player, account) || pendingRespawns.get(owner) != pending) return;
        try {
            if (player.isDead()) throw new IOException("Player died before respawn recovery completed");
            if (!pending.body.equals(BodyTag.read(player))) throw new IOException("Respawning body's tag changed");
            leases.validate(owner, pending.body);
            recover(account, pending.body, player.getLocation());
            saveRecoveredRoom(player, pending.body);
            pendingRespawns.remove(owner);
            enterAfterRecovery(account);
        } catch (IOException | RuntimeException error) {
            fail(player, "saving the respawned character body", error);
        }
    }

    public void show(Player player) {
        PlayerData account = PlayerManager.get(player);
        if (!isReadyToChoose(player)) return;
        CharacterSelectView.open(player, account, new CharacterSelectView.Actions() {
            public boolean isCurrent(Player viewer, PlayerData data) {
                return viewer == player && data == account && isReadyToChoose(player);
            }
            public String blockReason(RPCharacter character) { return CharacterBodies.this.blockReason(account, character); }
            public void play(RPCharacter character) { select(account, character); }
            public void create() { CreationManager.initiateCreation(player); }
            public void leave() { player.kickPlayer("See you next time."); }
        });
    }

    private boolean current(Player player, PlayerData account) {
        return player != null && player.isOnline() && account != null && PlayerManager.get(player) == account
                && isSelecting(player) && !failed.contains(player.getUniqueId());
    }

    public String blockReason(PlayerData account, RPCharacter character) {
        Player player = account.getPlayer();
        if (!current(player, account) || !ready.contains(account.getUniqueId())) return "Character data is still loading.";
        if (!packs.ready(player)) return "The TFMC resource pack is still loading.";
        if (!account.getCharacters().contains(character) || character.getStatus() != Status.ALIVE) return "This character cannot be played.";
        if (CreationManager.activeCreators.containsKey(player)) return "Finish or cancel character creation first.";
        if (net.tfminecraft.rpcharacters.gate.DiscordGatePolicy.freezes(
                RPCharacters.getPlayerManager().isDiscordGate(account.getUniqueId()),
                player.hasPermission(net.tfminecraft.rpcharacters.gate.DiscordGatePolicy.BYPASS_PERMISSION))) return "Link your Discord account before playing.";
        if (net.tfminecraft.rpcharacters.evilrp.EvilRpService.blocksCharacterSwitch(player)) return "Your current roleplay situation must finish first.";
        BodyTag currentTag;
        try { currentTag = BodyTag.read(player); }
        catch (IOException error) { return "Your saved body needs recovery. Please contact staff."; }
        if (currentTag instanceof BodyTag.Room tag && tag.lastCharacterId() != null
                && !tag.lastCharacterId().toString().equals(character.getId())) {
            RPCharacter previous = account.getCharacterById(tag.lastCharacterId().toString());
            if (previous != null && previous.getStatus() == Status.ALIVE
                    && !player.hasPermission("rpcharacters.no_cooldown")
                    && PermissionGroupService.hasCharacterSwitchCooldown(player, account)) {
                long minutes = PermissionGroupService.getRemainingCooldownMinutes(player, account);
                return "You can switch in " + (minutes / 1440) + "d " + (minutes / 60 % 24) + "h " + (minutes % 60) + "m. You can resume your current character.";
            }
        }
        return null;
    }

    public void select(PlayerData account, RPCharacter character) {
        Player player = account.getPlayer();
        String blocked = blockReason(account, character);
        if (blocked != null) { player.sendMessage(blocked); return; }
        UUID owner = account.getUniqueId();
        Object request = new Object();
        requests.put(owner, request);
        try {
            progress.ensureFresh(account, character);
            migration.ensureBody(account, character);
            BodyRecord body = store.read(owner, UUID.fromString(character.getId())).orElseThrow();
            Location destination = body.body().location();
            if (destination.getWorld().equals(room.getWorld())) throw new IOException("Character body points to the selection room");
            destination.getWorld().getChunkAtAsync(destination).whenComplete((chunk, error) ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (requests.get(owner) != request || !current(player, account)) return;
                        requests.remove(owner);
                        if (error != null) { fail(player, "loading the character location", error); return; }
                        String changed = blockReason(account, character);
                        if (changed != null) { player.sendMessage(changed); return; }
                        activate(account, character, body, destination);
                    }));
        } catch (IOException | RuntimeException error) {
            requests.remove(owner);
            fail(player, "loading the selected body", error);
        }
    }

    private void activate(PlayerData account, RPCharacter character, BodyRecord body, Location destination) {
        Player player = account.getPlayer();
        UUID owner = account.getUniqueId();
        applying.add(owner);
        try {
            BodyTag.Room previous = (BodyTag.Room) BodyTag.read(player);
            player.closeInventory();
            BodyRecord latest = store.read(owner, body.characterId()).orElseThrow();
            if (latest.rev() != body.rev()) throw new IOException("Selected body changed while its location loaded");
            try {
                progress.preflight(account, character);
            } catch (IOException error) {
                plugin.getLogger().warning("Character " + character.getId() + " cannot activate for " + owner + ": " + error.getMessage());
                player.sendMessage("This character needs attention. Choose another character or contact staff.");
                show(player);
                return;
            }
            var resources = progress.resources(account, character);
            if (!player.teleport(destination)) {
                if (current(player, account)) {
                    player.sendMessage("Your character could not be entered yet. If a resource pack is loading, wait for it to finish and try again.");
                    show(player);
                }
                return;
            }
            if (!current(player, account)) return;
            leases.begin(owner, new BodyTag.Live(body.characterId(), body.rev()));
            account.clearTempAlias();
            character.setActiveState(true);
            progress.activate(account, character);
            if (!character.tryActivate()) throw new IOException("Character class is not ready");
            body.body().apply(player, bodyKeys);
            MmoInventorySlots.refresh(player);
            progress.restoreResources(account, character, resources);
            body.body().applyHealth(player);
            if (previous.lastCharacterId() != null && !previous.lastCharacterId().equals(body.characterId())) {
                account.recordCharacterSwitch();
            }
            BodyTag.live(player, body.characterId(), body.rev());
            live.put(owner, new BodyTag.Live(body.characterId(), body.rev()));
            selecting.remove(owner);
            ready.remove(owner);
            player.saveData();
            RPCharacters.getCharacterTransitions().selected(account);
            if (player.isOnline() && hasLiveBody(player)) {
                reveal(player);
                RPCharacters.getPlayerManager().releaseFreeze(player);
                RPCharacters.getPlayerManager().reevaluateFreeze(player);
                net.tfminecraft.rpcharacters.joinsafety.JoinUnstuckListener.unstick(player);
                takeOffRoomSkin(player);
                WardrobeService.refreshActiveAsync(player);
                var session = net.tfminecraft.tlibs.character.Characters.currentSession(player).orElse(null);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (session != null && net.tfminecraft.tlibs.character.Characters.isCurrent(session)) {
                        try {
                            progress.restoreResources(account, character, resources);
                            body.body().applyHealth(player);
                            progress.save(account, character);
                        } catch (IOException | RuntimeException error) {
                            fail(player, "restoring character resources", error);
                        }
                    }
                });
            }
        } catch (IOException | RuntimeException error) {
            fail(player, "activating the selected character", error);
        } finally {
            applying.remove(owner);
        }
    }

    /** Existing activation callers can only ask the player to return to selection. */
    public void requestSwitch(PlayerData account, RPCharacter next) {
        Player player = account.getPlayer();
        if (player == null || !player.isOnline()) return;
        if (hasLiveBody(player)) {
            if (account.getActiveCharacter() == next) return;
            requestSelection(player);
        } else {
            rosterChanged(account);
        }
    }

    /** Opens selection, or starts the stand-still countdown back to it from a playing character. */
    public void requestSelection(Player player) {
        PlayerData account = PlayerManager.get(player);
        if (account == null) return;
        if (!hasLiveBody(player)) {
            show(player);
            return;
        }
        UUID owner = account.getUniqueId();
        if (returning.containsKey(owner)) {
            player.sendMessage("You are already returning to character selection.");
            return;
        }
        String blocked = returnBlockReason(player, account);
        if (blocked != null) {
            player.sendMessage(blocked);
            return;
        }
        Countdown countdown = new Countdown(account, player.getLocation(), returnSeconds);
        returning.put(owner, countdown);
        player.sendMessage("Returning to character selection in " + returnSeconds + " seconds. Stand still.");
        countdown.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(countdown), 20L, 20L);
    }

    /** Damage, like movement, keeps the player with their character. */
    public void interruptReturn(Player player, String message) {
        if (stopReturn(player.getUniqueId())) player.sendMessage(message);
    }

    private boolean stopReturn(UUID owner) {
        Countdown countdown = returning.remove(owner);
        if (countdown == null) return false;
        if (countdown.task != null) countdown.task.cancel();
        return true;
    }

    private void tick(Countdown countdown) {
        PlayerData account = countdown.account;
        Player player = account.getPlayer();
        UUID owner = account.getUniqueId();
        if (returning.get(owner) != countdown) return;
        if (!player.isOnline() || PlayerManager.get(player) != account || !hasLiveBody(player)) {
            stopReturn(owner);
            return;
        }
        Location now = player.getLocation();
        if (!now.getWorld().equals(countdown.start.getWorld()) || now.distanceSquared(countdown.start) > 0.04) {
            interruptReturn(player, CharacterSelectionGuard.MOVED);
            return;
        }
        String blocked = returnBlockReason(player, account);
        if (blocked != null) {
            interruptReturn(player, blocked);
            return;
        }
        if (--countdown.remaining > 0) {
            player.sendActionBar(Component.text("Character selection in " + countdown.remaining + "..."));
            return;
        }
        stopReturn(owner);
        returnToRoom(account);
    }

    private String returnBlockReason(Player player, PlayerData account) {
        if (player.isDead() || awaitingDeadBody(player) || isRespawning(player)) return "You cannot change character while dead.";
        if (isApplying(player)) return "Your character is still loading.";
        if (player.isInsideVehicle()) return "Dismount before returning to character selection.";
        if (player.getFallDistance() > 0) return "Land before returning to character selection.";
        if (CreationManager.activeCreators.containsKey(player)) return "Finish or cancel character editing first.";
        if (Duels.isDuelling(account.getUniqueId())) return "Finish your duel first.";
        if (PvpSituations.involves(account.getUniqueId()) || PvpStartSessions.isActive(account.getUniqueId(), System.currentTimeMillis())) {
            return "Finish your fight first.";
        }
        // Logging out of a started battle counts against the player's side; selection must not skip that.
        if (PermadeathBattleExemption.isInStartedBattle(player)) return "You cannot leave a battle for character selection.";
        var knockouts = RPCharacters.getPvpKnockoutManager();
        if (knockouts != null && knockouts.isDown(player)) return "You cannot change character while knocked out.";
        if (net.tfminecraft.rpcharacters.evilrp.EvilRpService.blocksCharacterSwitch(player)) return "Your current roleplay situation must finish first.";
        CharacterSelectionRequestEvent event = new CharacterSelectionRequestEvent(player);
        Bukkit.getPluginManager().callEvent(event);
        return event.isCancelled() ? event.getReason() : null;
    }

    /** Ends the session as a switch, then stows the body and enters the room as at login. */
    private void returnToRoom(PlayerData account) {
        Player player = account.getPlayer();
        UUID owner = account.getUniqueId();
        try {
            RPCharacters.getCharacterTransitions().leave(account, Reason.SWITCH, () -> {
                Duels.forget(player);
                PvpStartSessions.end(owner);
                ArmourDonning.cancel(player);
                account.clearTempAlias();
                selecting.add(owner);
                hide(player);
                stow(account, "selection");
            });
        } catch (IllegalStateException busy) {
            player.sendMessage("Your character is busy. Try again in a moment.");
            return;
        }
        if (failed.contains(owner)) return;
        if (live.containsKey(owner)) {
            fail(player, "stowing the outgoing character", new IOException("Live body was not stowed"));
            return;
        }
        enterAfterRecovery(account);
    }

    /** Only once the account skin is known, so Play and portraits can still restore it. */
    private void wearRoomSkin(Player player) {
        WardrobeCache.captureAccountSkinIfNeeded(player);
        if (WardrobeCache.getAccountSkin(player) != null) SkinApplyHelper.apply(player, SelectionSkin.STEVE);
    }

    /** The cached wardrobe or account skin goes on now, in case the wardrobe refresh cannot reach the site. */
    private static void takeOffRoomSkin(Player player) {
        if (WardrobeCache.get(player) != null) WardrobeService.applyFor(player);
        else SkinApplyHelper.apply(player, WardrobeCache.getAccountSkin(player));
    }

    public void rosterChanged(PlayerData account) {
        Player player = account.getPlayer();
        if (!current(player, account) || !ready.contains(account.getUniqueId())) return;
        try {
            for (RPCharacter character : account.getCharacters()) {
                progress.ensureFresh(account, character);
                migration.ensureBody(account, character);
            }
            Bukkit.getScheduler().runTask(plugin, () -> show(player));
        } catch (IOException | RuntimeException error) {
            fail(player, "preparing the new character", error);
        }
    }

    public void markDead(PlayerData account, RPCharacter character) {
        if (live.containsKey(account.getUniqueId())) deathLocations.put(account.getUniqueId(), account.getPlayer().getLocation());
    }

    public boolean prepareDeath(PlayerData account, RPCharacter character) {
        try {
            progress.save(account, character);
            return true;
        } catch (IOException | RuntimeException error) {
            fail(account.getPlayer(), "saving progression before permanent death", error);
            return false;
        }
    }

    public boolean awaitingDeadBody(Player player) { return deathLocations.containsKey(player.getUniqueId()); }

    public void finishDeath(Player player) {
        PlayerData account = PlayerManager.get(player);
        if (account == null || !awaitingDeadBody(player)) return;
        selecting.add(player.getUniqueId());
        // Runs inside PlayerRespawnEvent, so the room entity never spawns visible to other selecting players.
        hide(player);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || PlayerManager.get(player) != account || !awaitingDeadBody(player)) return;
            stow(account, "permadeath");
            if (!failed.contains(account.getUniqueId())) enterAfterRecovery(account);
        }, 2L);
    }

    /** Called after session-end listeners and before MMOCore's MONITOR quit unload. */
    public void stow(PlayerData account, String reason) {
        Player player = account.getPlayer();
        UUID owner = account.getUniqueId();
        BodyTag.Live body = live.get(owner);
        if (body == null || failed.contains(owner)) return;
        try {
            RPCharacter character = account.getCharacterById(body.characterId().toString());
            if (character == null) throw new IOException("Live body's character is unavailable");
            player.closeInventory();
            player.leaveVehicle();
            if (character.getStatus() == Status.ALIVE) {
                progress.save(account, character);
                // Paper refuses respawn once disconnect starts. Keep vanilla's dead entity and its lease
                // until the next connection can complete the real bed/spawn, inventory and XP rules.
                if (player.isDead()) return;
            }
            BodySnapshot snapshot = BodySnapshot.capture(player, bodyKeys);
            if (deathLocations.containsKey(owner)) snapshot = snapshot.withLocation(deathLocations.get(owner));
            store.write(owner, body.characterId(), body.rev(), reason, snapshot);
            character.stampLastLocation(snapshot.location());
            ProfessionIntegrator.remove(player, character);
            character.setActiveState(false);
            if (!database.trySavePlayer(account)) throw new IOException("Character account could not be saved");
            clear(player);
            BodyTag.room(player, body.characterId());
            player.saveData();
            leases.complete(owner, body);
            live.remove(owner);
            deathLocations.remove(owner);
        } catch (IOException | RuntimeException error) {
            fail(player, "saving the outgoing character", error);
        }
    }

    public void quit(Player player) {
        UUID owner = player.getUniqueId();
        stopReturn(owner);
        packs.quit(player);
        CharacterSelectView.close(player);
        RoomEntry entry = enteringRoom.get(owner);
        if (entry != null && entry.player == player && entry.recoveredFrom != null) {
            try {
                // Recovery already committed the body; finish its deferred ROOM save even if
                // the connection closes before mount detachment or room entry can finish.
                saveRecoveredRoom(player, entry.recoveredFrom);
            } catch (IOException | RuntimeException error) {
                plugin.getLogger().log(Level.SEVERE, "Failed completing recovered character body on quit for " + owner, error);
            }
        }
        pendingAccounts.remove(owner);
        enteringRoom.remove(owner);
        deferredPrepare.remove(owner);
        originalSpawns.remove(owner);
        requests.remove(owner);
        selecting.remove(owner);
        ready.remove(owner);
        applying.remove(owner);
        live.remove(owner);
        deathLocations.remove(owner);
        pendingRespawns.remove(owner);
        failed.remove(owner);
    }

    public void shutdown() {
        packs.close();
        CharacterSelectView.cleanupAll();
        for (PlayerData account : PlayerManager.getOnlineData()) stow(account, "shutdown");
        progress.close();
    }

    public void checkpoint() {
        for (PlayerData account : PlayerManager.getOnlineData()) {
            BodyTag.Live body = live.get(account.getUniqueId());
            if (body == null || failed.contains(account.getUniqueId())) continue;
            RPCharacter character = account.getCharacterById(body.characterId().toString());
            if (character == null || character.getStatus() != Status.ALIVE) continue;
            try { progress.save(account, character); }
            catch (IOException | RuntimeException error) { fail(account.getPlayer(), "saving character progression", error); }
        }
        CharacterSelectView.refreshAll();
    }

    private void fail(Player player, String operation, Throwable error) {
        failed.add(player.getUniqueId());
        requests.remove(player.getUniqueId());
        plugin.getLogger().log(Level.SEVERE, "Failed " + operation + " for " + player.getUniqueId(), error);
        if (player.isOnline()) player.kickPlayer("Your character data needs attention. Please contact staff.");
    }

    private void hide(Player player) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other == player) continue;
            other.hidePlayer(plugin, player);
            player.hidePlayer(plugin, other);
        }
    }

    private void reveal(Player player) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other == player || isSelecting(other)) continue;
            other.showPlayer(plugin, player);
            player.showPlayer(plugin, other);
        }
    }

    private static boolean hasItems(Player player) {
        return !player.getInventory().isEmpty() || !player.getEnderChest().isEmpty();
    }

    private void clear(Player player) {
        player.getInventory().clear();
        player.getEnderChest().clear();
        player.setItemOnCursor(null);
        for (var effect : player.getActivePotionEffects()) player.removePotionEffect(effect.getType());
        for (NamespacedKey key : bodyKeys) player.getPersistentDataContainer().remove(key);
        player.setFireTicks(0);
        player.setFreezeTicks(0);
        player.setFallDistance(0);
    }
}
