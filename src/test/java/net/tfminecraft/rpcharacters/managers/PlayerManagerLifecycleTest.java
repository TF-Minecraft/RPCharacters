package net.tfminecraft.rpcharacters.managers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.Permissions;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.clues.discovery.ClueAdminModeService;
import net.tfminecraft.rpcharacters.clues.discovery.ClueDiscoveryVisualManager;
import net.tfminecraft.rpcharacters.clues.discovery.InvestigationPointService;
import net.tfminecraft.rpcharacters.creation.CharacterCreation;
import net.tfminecraft.rpcharacters.creation.StageRevisions;
import net.tfminecraft.rpcharacters.database.Database;
import net.tfminecraft.rpcharacters.enums.FreezeReason;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.evilrp.EvilRpService;
import net.tfminecraft.rpcharacters.gate.DiscordGatePolicy;
import net.tfminecraft.rpcharacters.grave.GraveVisualManager;
import net.tfminecraft.rpcharacters.identity.TempAliasService;
import net.tfminecraft.rpcharacters.ingest.CharacterIngestService;
import net.tfminecraft.rpcharacters.injuries.InjuryHealingService;
import net.tfminecraft.rpcharacters.injuries.OffhandBlockService;
import net.tfminecraft.rpcharacters.lifecycle.CharacterLifecycle;
import net.tfminecraft.rpcharacters.loaders.StageLoader;
import net.tfminecraft.rpcharacters.mail.MailRecipientDirectory;
import net.tfminecraft.rpcharacters.mmocore.AttributePointService;
import net.tfminecraft.rpcharacters.mmocore.ClassService;
import net.tfminecraft.rpcharacters.mmocore.MmoCorePlayerReady;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.attributes.AttributeData;
import net.tfminecraft.rpcharacters.objects.trait.PotionData;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.objects.trait.TraitEffectResolver;
import net.tfminecraft.rpcharacters.paidchange.PaidChangeService;
import net.tfminecraft.rpcharacters.permadeath.PermadeathService;
import net.tfminecraft.rpcharacters.persona.CharacterSlotService;
import net.tfminecraft.rpcharacters.persona.PermissionGroupService;
import net.tfminecraft.rpcharacters.professions.ProfessionIntegrator;
import net.tfminecraft.rpcharacters.professions.ProfessionPointService;
import net.tfminecraft.rpcharacters.prosthetics.ProstheticFuelService;
import net.tfminecraft.rpcharacters.utils.ClueProgressFormatter;
import net.tfminecraft.rpcharacters.utils.Integrator;
import net.tfminecraft.rpcharacters.utils.ProstheticTraitRules;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.rpcharacters.wardrobe.WardrobeService;

class PlayerManagerLifecycleTest extends PlayerManagerFixture {
    @Test
    void registryFindsOnlineAndOfflineDataWithoutExposingItsBackingList() {
        PlayerData offline = new PlayerData(UUID.randomUUID());
        tracked.addFirst(offline);
        assertTrue(PlayerManager.exists(player));
        assertSame(data, PlayerManager.get(player));
        assertSame(data, PlayerManager.get(player.getUniqueId()));
        assertSame(offline, PlayerManager.get(offline.getUniqueId()));
        assertNull(PlayerManager.get((UUID) null));
        assertNull(PlayerManager.get(UUID.randomUUID()));
        Player stranger = mock(Player.class);
        assertFalse(PlayerManager.exists(stranger));
        assertNull(PlayerManager.get(stranger));
        List<PlayerData> snapshot = PlayerManager.getOnlineData();
        snapshot.clear();
        assertEquals(2, PlayerManager.getOnlineData().size());
        assertFalse(manager.hasTrait(stranger, "strong"));
        active.set(null);
        assertFalse(manager.hasTrait(player, "strong"));
        active.set(character);
        Trait other = mock(Trait.class), strong = mock(Trait.class);
        when(other.getId()).thenReturn("other"); when(strong.getId()).thenReturn("STRONG");
        when(character.getTraits()).thenReturn(List.of(other, strong));
        assertTrue(manager.hasTrait(player, "strong"));
        assertFalse(manager.hasTrait(player, "missing"));
    }

    @Test
    void freezesAtOriginalCoordinatesAndRestoresPositionWithoutChangingViewDirection() {
        Cache.noCharacterFreeze = true;
        active.set(null);
        Location origin = player.getLocation();
        manager.toFreezeLoc(player);
        assertTrue(manager.isAtFreezeLoc(player));
        manager.reevaluateFreeze(player);
        for (Location moved : List.of(origin.clone().add(1, 0, 0), origin.clone().add(0, 1, 0), origin.clone().add(0, 0, 1))) {
            moved.setYaw(30); moved.setPitch(15);
            player.teleport(moved);
            assertFalse(manager.isAtFreezeLoc(player));
            manager.reevaluateFreeze(player);
            manager.toFreezeLoc(player);
            assertEquals(origin.getX(), player.getLocation().getX());
            assertEquals(origin.getY(), player.getLocation().getY());
            assertEquals(origin.getZ(), player.getLocation().getZ());
            assertEquals(30, player.getLocation().getYaw());
            assertEquals(15, player.getLocation().getPitch());
        }
        manager.releaseFreeze(player);
        player.teleport(origin.clone().add(3, 0, 0));
        assertTrue(manager.isAtFreezeLoc(player));
        manager.reevaluateFreeze(player);
        player.setGameMode(GameMode.CREATIVE);
        assertTrue(manager.isAtFreezeLoc(player));
        manager.reevaluateFreeze(player);
        assertFalse(frozen().containsKey(player));
    }

    @Test
    void freezeLocationIncludesTheWorld() {
        Cache.noCharacterFreeze = true;
        active.set(null);
        manager.reevaluateFreeze(player);
        Location otherWorld = player.getLocation();
        otherWorld.setWorld(server.addSimpleWorld("other"));
        player.teleport(otherWorld);
        assertFalse(manager.isAtFreezeLoc(player), "Equal coordinates in a different world are not the frozen location");
    }

    @Test
    void freezePolicyCoversDiscordExcessCharactersCluesAndCreationBypass() {
        manager.setDiscordGate((UUID) null, true);
        manager.setDiscordGate((Player) null, true);
        assertFalse(manager.isDiscordGate(null));
        UUID offline = UUID.randomUUID();
        manager.setDiscordGate(offline, true);
        assertTrue(manager.isDiscordGate(offline));
        manager.setDiscordGate(player, true);
        assertTrue(frozen().containsKey(player));
        player.addAttachment(plugin, DiscordGatePolicy.BYPASS_PERMISSION, true);
        manager.reevaluateFreeze(player);
        assertFalse(frozen().containsKey(player));
        manager.setDiscordGate(player.getUniqueId(), false);
        assertFalse(manager.isDiscordGate(player.getUniqueId()));
        Cache.excessCharactersFreeze = true;
        slots.when(() -> CharacterSlotService.getMaxAliveCharacters(player)).thenReturn(0);
        manager.reevaluateFreeze(player);
        assertTrue(frozen().containsKey(player));
        slots.when(() -> CharacterSlotService.getMaxAliveCharacters(player)).thenReturn(1);
        Cache.lackingCluesFreeze = true;
        when(character.hasEnoughClues()).thenReturn(false);
        manager.reevaluateFreeze(player);
        assertTrue(frozen().containsKey(player));
        when(character.hasEnoughClues()).thenReturn(true);
        manager.reevaluateFreeze(player);
        assertFalse(frozen().containsKey(player));
        Cache.noCharacterFreeze = true; active.set(null);
        manager.reevaluateFreeze(player);
        CreationManager.activeCreators.put(player, mock(CharacterCreation.class));
        manager.reevaluateFreeze(player);
        assertFalse(frozen().containsKey(player));
        CreationManager.activeCreators.clear();
        tracked.clear();
        manager.reevaluateFreeze(player);
        assertTrue(frozen().isEmpty());
        tracked.add(data);
        deaths.when(() -> PermadeathService.isAwaitingPermakillRespawn(player)).thenReturn(true);
        manager.reevaluateFreeze(player);
        assertTrue(frozen().isEmpty());
        deaths.when(() -> PermadeathService.isAwaitingPermakillRespawn(player)).thenReturn(false);
        player.setHealth(0);
        manager.reevaluateFreeze(player);
        assertTrue(frozen().isEmpty());
    }

    @Test
    void freezePulseReturnsMovingPlayersAndRateLimitsEveryReasonNotification() {
        Cache.noCharacterFreeze = true;
        active.set(null);
        manager.start();
        injuries.verify(InjuryHealingService::start);
        offhand.verify(OffhandBlockService::start);
        fuel.verify(ProstheticFuelService::start);
        server.getScheduler().performOneTick();
        assertTrue(frozen().containsKey(player), "The pulse creates the initial freeze without an external reevaluation");
        manager.releaseFreeze(player);
        for (FreezeReason reason : FreezeReason.values()) {
            Cache.noCharacterFreeze = reason == FreezeReason.NO_CHARACTER;
            Cache.lackingCluesFreeze = reason == FreezeReason.LACKING_CLUES;
            Cache.excessCharactersFreeze = reason == FreezeReason.EXCESS_CHARACTERS;
            active.set(reason == FreezeReason.NO_CHARACTER ? null : character);
            when(character.hasEnoughClues()).thenReturn(reason != FreezeReason.LACKING_CLUES);
            slots.when(() -> CharacterSlotService.getMaxAliveCharacters(player)).thenReturn(0);
            manager.setDiscordGate(player, reason == FreezeReason.DISCORD_REQUIRED);
            cooldown().clear();
            server.getScheduler().performTicks(5);
            Location origin = player.getLocation();
            player.teleport(origin.clone().add(3, 0, 0));
            server.getScheduler().performTicks(5);
            assertEquals(origin, player.getLocation());
            String title = switch (reason) {
                case DISCORD_REQUIRED -> "Discord Required!";
                case NO_CHARACTER -> "No Character!";
                case LACKING_CLUES -> "More Clues Needed!";
                case EXCESS_CHARACTERS -> "Too Many Characters!";
            };
            texts.verify(() -> RPTexts.title(player, " ", RPTexts.ERROR + title, 5, 50, 5), times(1));
            player.teleport(origin.clone().add(2, 0, 0));
            server.getScheduler().performTicks(5);
            texts.verify(() -> RPTexts.title(player, " ", RPTexts.ERROR + title, 5, 50, 5), times(1));
        }
        Cache.noCharacterFreeze = false; Cache.excessCharactersFreeze = false; Cache.lackingCluesFreeze = false;
        manager.setDiscordGate(player, false);
        server.getScheduler().performTicks(5);
        assertFalse(frozen().containsKey(player));
    }

    @Test
    void freezePulseSkipsDeadUnloadedRespawningCreativeAndCreatingPlayers() {
        Cache.noCharacterFreeze = true; active.set(null);
        manager.start();
        tracked.clear(); server.getScheduler().performTicks(5);
        assertTrue(frozen().isEmpty());
        tracked.add(data);
        player.setGameMode(GameMode.CREATIVE); server.getScheduler().performTicks(5);
        assertTrue(frozen().isEmpty());
        player.setGameMode(GameMode.SURVIVAL);
        CreationManager.activeCreators.put(player, mock(CharacterCreation.class));
        server.getScheduler().performTicks(5); assertTrue(frozen().isEmpty());
        CreationManager.activeCreators.clear();
        deaths.when(() -> PermadeathService.isAwaitingPermakillRespawn(player)).thenReturn(true);
        server.getScheduler().performTicks(5); assertTrue(frozen().isEmpty());
        deaths.when(() -> PermadeathService.isAwaitingPermakillRespawn(player)).thenReturn(false);
        player.setHealth(0); server.getScheduler().performTicks(5);
        assertTrue(frozen().isEmpty());
    }

    @Test
    void potionPulseAppliesStrongestValidTraitEffectForSixtyTicks() {
        Trait empty = mock(Trait.class), first = mock(Trait.class), second = mock(Trait.class);
        when(character.getTraits()).thenReturn(List.of(empty, first, second));
        List<PotionData> firstEffects = List.of(potion(null, 1), potion(PotionEffectType.SPEED, 1));
        List<PotionData> secondEffects = List.of(potion(PotionEffectType.SPEED, 3), potion(PotionEffectType.SPEED, 0), potion(PotionEffectType.JUMP_BOOST, 2));
        potions.when(() -> TraitEffectResolver.resolvePotionEffects(character, empty)).thenReturn(List.of());
        potions.when(() -> TraitEffectResolver.resolvePotionEffects(character, first)).thenReturn(firstEffects);
        potions.when(() -> TraitEffectResolver.resolvePotionEffects(character, second)).thenReturn(secondEffects);
        server.addPlayer("Unloaded");
        PlayerMock noCharacter = server.addPlayer("NoCharacter");
        PlayerData blank = mock(PlayerData.class); when(blank.getPlayer()).thenReturn(noCharacter);
        tracked.add(blank);
        manager.traitPotionPulse(); server.getScheduler().performOneTick();
        var speed = player.getPotionEffect(PotionEffectType.SPEED);
        assertNotNull(speed); assertEquals(3, speed.getAmplifier());
        assertEquals(60, speed.getDuration()); assertFalse(speed.isAmbient());
        assertFalse(speed.hasParticles()); assertFalse(speed.hasIcon());
        assertEquals(2, player.getPotionEffect(PotionEffectType.JUMP_BOOST).getAmplifier());
        assertTrue(noCharacter.getActivePotionEffects().isEmpty());
    }

    @Test
    @SuppressWarnings("deprecation")
    void joiningLoadsOnceRecoversPaidChangesAndDefersMmoUntilReady() {
        tracked.clear();
        sanitizing.when(() -> ProstheticTraitRules.sanitize(data)).thenReturn(true);
        paid.when(() -> PaidChangeService.recover(character)).thenReturn("Refund recovered");
        manager.onJoin(new PlayerJoinEvent(player, "joined"));
        assertSame(data, PlayerManager.get(player));
        verify(database).loadPlayerData(player.getUniqueId()); verify(database).savePlayer(data);
        texts.verify(() -> RPTexts.send(player, "Refund recovered"));
        wardrobe.verify(() -> WardrobeService.refreshActiveAsync(player));
        ingestion.verify(() -> CharacterIngestService.tryPullForPlayerAsync(plugin, player.getUniqueId()));
        verify(character, never()).applyStoredClass();
        assertEquals(1, mmoReady.size());
        mmoReady.getFirst().run();
        verify(character).applyStoredClass();
        attributes.verify(() -> AttributePointService.syncOnActivate(character));
        lifecycle.verify(() -> CharacterLifecycle.fireActivated(player, player.getUniqueId(), character, null));
        verify(integrators.constructed().getFirst()).tryApplyPendingRemoves(player, List.of("strength"));
        verify(mmo).setAttributeReallocationPoints(0);
        classes.verify(() -> ClassService.applyFreeSkillPoints(player));
        manager.initiatePlayer(player);
        verify(database, times(1)).loadPlayerData(player.getUniqueId());
    }

    @Test
    void firstJoinCreatesDataAndInitializesMmoWithoutACharacter() {
        tracked.clear();
        when(database.loadPlayerData(player.getUniqueId())).thenReturn(new PlayerData(player));
        manager.initiatePlayer(player);
        PlayerData loaded = PlayerManager.get(player);
        assertNotNull(loaded); assertSame(player, loaded.getPlayer());
        assertFalse(loaded.hasActiveCharacter());
        mmoReady.getFirst().run();
        verify(mmo).setAttributePoints(0);
        verify(mmo).setAttributeReallocationPoints(0);
        verify(database, never()).savePlayer(any());
        wardrobe.verifyNoInteractions();
    }

    @Test
    void corruptExistingAccountCannotBecomeAnEmptyRegisteredPlayer() {
        tracked.clear();
        when(database.loadPlayerData(player.getUniqueId())).thenReturn(null);
        manager.initiatePlayer(player);
        assertNull(PlayerManager.get(player), "Unreadable saved data must not become a new empty account");
        manager.savePlayer(player);
        verify(database, never()).savePlayer(any());
        assertFalse(player.isOnline(), "Disconnect before gameplay or autosave can replace the failed load");
        assertTrue(mmoReady.isEmpty());
    }

    @Test
    void loadsFirstLivingCharacterAndPersistsRevisionRefresh() {
        tracked.clear(); active.set(null);
        revisions.when(() -> StageRevisions.refresh(character, StageLoader.oList)).thenReturn(true);
        manager.initiatePlayer(player);
        assertSame(character, active.get());
        verify(data).setActiveCharacter(character);
        verify(database).savePlayer(data);
        groups.verify(() -> PermissionGroupService.enforceNameColourOnLogin(player, data));
        investigations.verify(() -> InvestigationPointService.bootstrap(player));
    }

    @Test
    void mmoClassLoadRetriesThenContinuesAfterTheBoundedLimit() {
        tracked.clear(); when(character.applyStoredClass()).thenReturn(false);
        manager.initiatePlayer(player); mmoReady.getFirst().run();
        attributes.verify(() -> AttributePointService.syncOnActivate(character), never());
        server.getScheduler().performTicks(15);
        verify(character, times(4)).applyStoredClass();
        verify(plugin.getLogger()).warning(contains("not applied after 3 retries"));
        attributes.verify(() -> AttributePointService.syncOnActivate(character));
        verify(mmo).setAttributeReallocationPoints(0);
    }

    @Test
    void mmoRetrySucceedsLaterAndDoesNotRunForDisconnectedPlayers() {
        tracked.clear(); when(character.applyStoredClass()).thenReturn(false, true);
        manager.initiatePlayer(player); mmoReady.getFirst().run();
        server.getScheduler().performTicks(5);
        verify(character, times(2)).applyStoredClass();
        attributes.verify(() -> AttributePointService.syncOnActivate(character));
        clearInvocations(character);
        player.disconnect(); mmoReady.getFirst().run();
        verify(character, never()).applyStoredClass();
    }

    @Test
    void disconnectDuringMmoClassRetryAbandonsIt() {
        tracked.clear(); when(character.applyStoredClass()).thenReturn(false);
        manager.initiatePlayer(player); mmoReady.getFirst().run();
        player.disconnect(); server.getScheduler().performTicks(5);
        verify(character, times(1)).applyStoredClass();
        attributes.verify(() -> AttributePointService.syncOnActivate(character), never());
    }

    @Test
    void missingPluginCannotScheduleClassRetryButStillFinishesSetup() {
        tracked.clear(); when(character.applyStoredClass()).thenReturn(false);
        manager.initiatePlayer(player);
        RPCharacters.plugin = null;
        mmoReady.getFirst().run();
        attributes.verify(() -> AttributePointService.syncOnActivate(character));
        verify(character, times(1)).applyStoredClass();
    }

    @Test
    @SuppressWarnings("deprecation")
    void quitSettlesEditClearsMmoBasesStampsLocationAndSavesBeforeEviction() {
        CharacterCreation editing = mock(CharacterCreation.class);
        when(editing.isEditing()).thenReturn(true);
        CreationManager.activeCreators.put(player, editing);
        Location departure = player.getLocation();
        manager.onLeave(new PlayerQuitEvent(player, "quit"));
        paid.verify(() -> PaidChangeService.settle(editing));
        ready.verify(() -> MmoCorePlayerReady.cancel(player.getUniqueId()));
        attributes.verify(() -> AttributePointService.clearMmoAttributeBases(player));
        verify(character).stampLastLocation(departure);
        mail.verify(() -> MailRecipientDirectory.upsert(player.getUniqueId(), character));
        verify(database).savePlayer(data);
        assertFalse(PlayerManager.exists(player));
        assertFalse(CreationManager.activeCreators.containsKey(player));
        verify(clueVisuals).clearViewer(player.getUniqueId());
        verify(graveVisuals).clearViewer(player.getUniqueId());
        manager.savePlayer(player);
        verify(database, times(1)).savePlayer(data);
        manager.onLeave(new PlayerQuitEvent(player, "again"));
        PlayerManager.stampActiveCharacterLocation(null);
    }

    @Test
    @SuppressWarnings("deprecation")
    void quittingWithoutAnActiveCharacterDoesNotClearMmoOrStampOne() {
        active.set(null);
        CharacterCreation draft = mock(CharacterCreation.class);
        CreationManager.activeCreators.put(player, draft);
        PlayerManager.stampActiveCharacterLocation(player);
        manager.onLeave(new PlayerQuitEvent(player, "quit"));
        attributes.verify(() -> AttributePointService.clearMmoAttributeBases(player), never());
        verify(character, never()).stampLastLocation(any());
        verify(database).savePlayer(data);
    }

    private Map<Player, Location> frozen() { return field(manager, "frozen"); }
    private Map<Player, Long> cooldown() { return field(manager, "cooldown"); }
    private PotionData potion(PotionEffectType type, int amplifier) {
        PotionData potion = mock(PotionData.class);
        when(potion.getType()).thenReturn(type); when(potion.getAmplifier()).thenReturn(amplifier);
        return potion;
    }
}

/** Shared fixture for the two PlayerManager test files; all runtime boundaries are restored. */
abstract class PlayerManagerFixture {
    final List<AutoCloseable> boundaries = new ArrayList<>();
    RuntimeTestState state;
    ServerMock server;
    PlayerMock player;
    RPCharacters plugin;
    PlayerManager manager;
    PlayerData data;
    RPCharacter character;
    Database database;
    List<PlayerData> tracked;
    AtomicReference<RPCharacter> active;
    net.Indyuce.mmocore.api.player.PlayerData mmo;
    List<Runnable> mmoReady;
    MockedConstruction<InventoryManager> inventories;
    MockedConstruction<Integrator> integrators;
    MockedStatic<RPTexts> texts;
    MockedStatic<ClassService> classes;
    MockedStatic<AttributePointService> attributes;
    MockedStatic<MmoCorePlayerReady> ready;
    MockedStatic<CharacterSlotService> slots;
    MockedStatic<Permissions> permissions;
    MockedStatic<EvilRpService> evil;
    MockedStatic<PermadeathService> deaths;
    MockedStatic<ProstheticTraitRules> sanitizing;
    MockedStatic<PaidChangeService> paid;
    MockedStatic<StageRevisions> revisions;
    MockedStatic<PermissionGroupService> groups;
    MockedStatic<InvestigationPointService> investigations;
    MockedStatic<WardrobeService> wardrobe;
    MockedStatic<CharacterIngestService> ingestion;
    MockedStatic<CharacterLifecycle> lifecycle;
    MockedStatic<MailRecipientDirectory> mail;
    MockedStatic<InjuryHealingService> injuries;
    MockedStatic<OffhandBlockService> offhand;
    MockedStatic<ProstheticFuelService> fuel;
    MockedStatic<TraitEffectResolver> potions;
    ClueDiscoveryVisualManager clueVisuals;
    GraveVisualManager graveVisuals;

    @BeforeEach
    void setUpPlayerManager() {
        server = MockBukkit.mock();
        state = new RuntimeTestState(RPCharacters.class, PlayerManager.class, CreationManager.class, StageLoader.class);
        CreationManager.activeCreators.clear();
        Cache.attributes = new ArrayList<>(); Cache.professions = new ArrayList<>();
        Cache.noCharacterFreeze = false; Cache.lackingCluesFreeze = false; Cache.excessCharactersFreeze = false;
        Cache.characterSlots = new ArrayList<>(List.of(10, 11, 12)); Cache.deadSlot = 26;
        StageLoader.oList = new ArrayList<>();
        plugin = mock(RPCharacters.class);
        when(plugin.getName()).thenReturn("RPCharacters"); when(plugin.namespace()).thenReturn("rpcharacters");
        when(plugin.getServer()).thenReturn(server); when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getLogger()).thenReturn(mock(Logger.class)); RPCharacters.plugin = plugin;
        player = server.addPlayer("Ada");
        character = mock(RPCharacter.class);
        when(character.getOwner()).thenReturn(player); when(character.getId()).thenReturn("character");
        when(character.getName()).thenReturn("Aria"); when(character.getStatus()).thenReturn(Status.ALIVE);
        when(character.getTraits()).thenReturn(List.of()); when(character.hasEnoughClues()).thenReturn(true);
        when(character.getAttributeData()).thenReturn(new AttributeData());
        when(character.applyStoredClass()).thenReturn(true); when(character.isActive()).thenReturn(true);
        AtomicReference<String> profession = new AtomicReference<>("warrior");
        when(character.getMMOClass()).thenAnswer(call -> profession.get());
        doAnswer(call -> { profession.set(call.getArgument(0)); return null; }).when(character).setMMOClass(anyString());
        data = mock(PlayerData.class);
        UUID id = player.getUniqueId();
        when(data.getPlayer()).thenReturn(player); when(data.getUniqueId()).thenReturn(id);
        active = new AtomicReference<>(character);
        when(data.hasActiveCharacter()).thenAnswer(call -> active.get() != null);
        when(data.getActiveCharacter()).thenAnswer(call -> active.get());
        doAnswer(call -> { active.set(call.getArgument(0)); return null; }).when(data).setActiveCharacter(any());
        when(data.getCharacters()).thenReturn(List.of(character));
        when(data.getCharacters(Status.ALIVE)).thenReturn(List.of(character));
        when(data.getCharacters(Status.DEAD)).thenReturn(List.of());
        when(data.getCharacterById("character")).thenReturn(character);
        when(data.takePendingMmoAttributeRemoves()).thenReturn(List.of("strength"));
        tracked = field(null, "data"); tracked.clear(); tracked.add(data);
        // Initialize the ingest singleton with a real database before constructor mocking.
        ingestion = boundary(CharacterIngestService.class);
        var databases = mockConstruction(Database.class, (db, context) -> {
            when(db.loadPlayerData(id)).thenReturn(data);
        });
        boundaries.add(databases);
        manager = new PlayerManager(); database = databases.constructed().getLast();
        boundary(RPCharacters.class).when(RPCharacters::getPlayerManager).thenReturn(manager);
        inventories = mockConstruction(InventoryManager.class); boundaries.add(inventories);
        integrators = mockConstruction(Integrator.class, (integrator, context) -> when(integrator.tryApplyPendingRemoves(any(), anyList())).thenReturn(true)); boundaries.add(integrators);
        texts = boundary(RPTexts.class); classes = boundary(ClassService.class);
        attributes = boundary(AttributePointService.class); ready = boundary(MmoCorePlayerReady.class);
        mmoReady = new ArrayList<>();
        ready.when(() -> MmoCorePlayerReady.runWhenLoaded(eq(player), any(Runnable.class)))
            .thenAnswer(call -> { mmoReady.add(call.getArgument(1)); return null; });
        mmo = mock(net.Indyuce.mmocore.api.player.PlayerData.class);
        boundary(net.Indyuce.mmocore.api.player.PlayerData.class)
            .when(() -> net.Indyuce.mmocore.api.player.PlayerData.get(player)).thenReturn(mmo);
        slots = boundary(CharacterSlotService.class);
        slots.when(() -> CharacterSlotService.getMaxAliveCharacters(any())).thenReturn(2);
        slots.when(() -> CharacterSlotService.hasFreeSlot(any(), any())).thenReturn(true);
        slots.when(() -> CharacterSlotService.isSlotUnlocked(any(), anyInt())).thenReturn(true);
        permissions = boundary(Permissions.class); evil = boundary(EvilRpService.class);
        deaths = boundary(PermadeathService.class); sanitizing = boundary(ProstheticTraitRules.class);
        paid = boundary(PaidChangeService.class); revisions = boundary(StageRevisions.class);
        groups = boundary(PermissionGroupService.class); investigations = boundary(InvestigationPointService.class);
        wardrobe = boundary(WardrobeService.class);
        lifecycle = boundary(CharacterLifecycle.class); mail = boundary(MailRecipientDirectory.class);
        injuries = boundary(InjuryHealingService.class); offhand = boundary(OffhandBlockService.class);
        fuel = boundary(ProstheticFuelService.class); potions = boundary(TraitEffectResolver.class);
        boundary(ProfessionIntegrator.class); boundary(ProfessionPointService.class);
        boundary(TempAliasService.class); boundary(ClueAdminModeService.class);
        boundary(ClueProgressFormatter.class).when(() -> ClueProgressFormatter.lackingCluesMessage(character)).thenReturn("Add more clues");
        clueVisuals = mock(ClueDiscoveryVisualManager.class);
        boundary(ClueDiscoveryVisualManager.class).when(ClueDiscoveryVisualManager::get).thenReturn(clueVisuals);
        graveVisuals = mock(GraveVisualManager.class);
        boundary(GraveVisualManager.class).when(GraveVisualManager::get).thenReturn(graveVisuals);
    }

    @AfterEach
    void tearDownPlayerManager() throws Exception {
        server.getScheduler().cancelTasks(plugin);
        for (int i = boundaries.size() - 1; i >= 0; i--) boundaries.get(i).close();
        state.close(); MockBukkit.unmock();
    }

    <T> MockedStatic<T> boundary(Class<T> type) {
        MockedStatic<T> value = mockStatic(type); boundaries.add(value); return value;
    }
    InventoryManager lastInventory() { return inventories.constructed().getLast(); }
    void message(String message) { texts.verify(() -> RPTexts.send(player, RPTexts.ERROR + message), atLeastOnce()); }
    @SuppressWarnings("unchecked")
    static <T> T field(PlayerManager manager, String name) {
        try {
            Field field = PlayerManager.class.getDeclaredField(name); field.setAccessible(true);
            return (T) field.get(manager);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
