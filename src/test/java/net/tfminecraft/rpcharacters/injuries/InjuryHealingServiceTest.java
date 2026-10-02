package net.tfminecraft.rpcharacters.injuries;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.*;
import java.util.*;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.api.HealingInjuries;
import net.tfminecraft.rpcharacters.database.Database;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.loaders.InjuryPoolLoader;
import net.tfminecraft.rpcharacters.managers.*;
import net.tfminecraft.rpcharacters.mmocore.AttributePointService;
import net.tfminecraft.rpcharacters.objects.*;
import net.tfminecraft.rpcharacters.objects.attributes.AttributeModifier;
import net.tfminecraft.rpcharacters.objects.races.Race;
import net.tfminecraft.rpcharacters.objects.trait.*;
import net.tfminecraft.rpcharacters.permadeath.PermadeathService;
import net.tfminecraft.rpcharacters.utils.Integrator;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class InjuryHealingServiceTest extends HealingRuntimeFixture {
    @Test void configuredTimerInitializesLegacyDurationsAndSavesOnce() throws Exception {
        new InjuryPoolLoader().load(Files.writeString(folder.resolve("injuries.yml"), "healing-tick-interval: 2s").toFile());
        var wound = trait("wound", "injury", "1h"); character.getTraits().add(wound);
        InjuryHealingService.start(); assertEquals(40, delay); assertEquals(40, period); assertNotNull(timer);
        timer.run(); assertTrue(character.getDurationRemainingMs("wound") > 3_590_000);
        verify(manager).savePlayer(player); verify(integrations.constructed().getFirst()).remove(player, character, false);
        verify(integrations.constructed().getFirst()).integrate(player, character);
        timer.run(); verify(manager, times(1)).savePlayer(player); assertEquals(2, integrations.constructed().size());
    }

    @Test void tickSkipsDeadRespawningUnloadedAndInactivePlayers() {
        var wound = trait("wound", "injury", "1h"); character.getTraits().add(wound);
        doReturn(true).when(player).isDead(); InjuryHealingService.tick(); assertEquals(-1, character.getDurationRemainingMs("wound"));
        doReturn(false).when(player).isDead(); deaths.when(() -> PermadeathService.isAwaitingPermakillRespawn(player)).thenReturn(true);
        InjuryHealingService.tick(); assertEquals(-1, character.getDurationRemainingMs("wound"));
        deaths.when(() -> PermadeathService.isAwaitingPermakillRespawn(player)).thenReturn(false);
        loaded.clear(); InjuryHealingService.tick(); assertEquals(-1, character.getDurationRemainingMs("wound"));
        loaded.put(player, new PlayerData(player)); InjuryHealingService.tick();
        assertTrue(integrations.constructed().isEmpty()); verifyNoInteractions(manager);
    }

    @Test void charactersWithoutDurationTraitsNeedNoRefresh() {
        character.addTrait(trait("permanent", "injury", null)); InjuryHealingService.tick();
        assertTrue(integrations.constructed().isEmpty()); verifyNoInteractions(manager);
    }

    @Test void expiredInjuriesAreRemovedWithTheirStateWhileRemainingTraitsStay() {
        var first = trait("first", "injury", "1h"); var second = trait("second", "injury", "1h");
        var remaining = trait("ongoing", "injury", "1h"); var permanent = trait("permanent", "injury", null);
        for (Trait trait : List.of(first, second, remaining, permanent)) character.addTrait(trait);
        character.setDurationExpiresAtMs("first", 1); character.setDurationExpiresAtMs("second", 1);
        InjuryHealingService.tick(); assertEquals(List.of(remaining, permanent), character.getTraits());
        assertNull(character.getTraitState("first")); assertNull(character.getTraitState("second"));
        assertTrue(message().contains("first")); assertTrue(message().contains("second"));
        verify(manager, times(2)).savePlayer(player); verify(manager, times(2)).reevaluateFreeze(player);
    }

    @Test void refreshUpdatesActiveAndInactiveSheetsAtTheIntegrationBoundary() {
        var wound = trait("wound", "injury", "1h", "attribute-modifiers", List.of("strength.-4"));
        character.addTrait(wound); InjuryHealingService.refreshCharacter(player, character);
        assertEquals(-4, character.getAttributeData().getAmount(new AttributeModifier("strength", 0)));
        verify(integrations.constructed().getFirst()).integrate(player, character);
        var inactive = character(false); inactive.addTrait(wound); InjuryHealingService.refreshCharacter(player, inactive);
        assertEquals(-4, inactive.getAttributeData().getAmount(new AttributeModifier("strength", 0)));
        assertEquals(1, integrations.constructed().size());
    }

    @Test void publicHealingApiIgnoresMissingAccountsAndNonHealingTraits() {
        assertEquals(List.of(), HealingInjuries.list(null)); assertFalse(HealingInjuries.cure(null, "wound"));
        assertEquals(-1, HealingInjuries.extend(null, "wound", 1000));
        loaded.clear(); assertTrue(HealingInjuries.list(player).isEmpty()); loaded.put(player, new PlayerData(player));
        assertTrue(HealingInjuries.list(player).isEmpty()); loaded.put(player, data);
        character.addTrait(trait("permanent", "injury", null)); character.addTrait(trait("timed", "background", "1h"));
        assertTrue(HealingInjuries.list(player).isEmpty());
        assertFalse(HealingInjuries.cure(player, null)); assertFalse(HealingInjuries.cure(player, "missing"));
        assertFalse(HealingInjuries.cure(player, "permanent")); assertFalse(HealingInjuries.cure(player, "timed"));
        assertEquals(-1, HealingInjuries.extend(player, "missing", 1000)); verifyNoInteractions(manager);
    }

    @Test void publicListUsesRemainingDurationAndResolvedVariantNames() {
        var legacy = trait("legacy", "injury", "1h"); character.getTraits().add(legacy);
        var fuel = trait("powered", "injury", "1h", "fuel-template", "oil", "fuel-capacity", 10,
            "powered.name", "Powered injury", "depowered.name", "Empty injury"); character.addTrait(fuel);
        character.setDurationRemainingMs("powered", 60_000);
        var list = HealingInjuries.list(player); assertEquals(2, list.size());
        assertEquals("legacy", list.get(0).traitId()); assertEquals("legacy", list.get(0).displayName());
        assertEquals(3_600_000, list.get(0).remainingMs()); assertEquals("Powered injury", list.get(1).displayName());
        assertTrue(list.get(1).remainingMs() > 59_000 && list.get(1).remainingMs() <= 60_000);
        character.setFuel("powered", 0); assertEquals("Empty injury", HealingInjuries.list(player).get(1).displayName());
    }

    @Test void cureRemovesMatchingInjuryCaseInsensitivelyAndSavesEffects() {
        var wound = trait("wound", "injury", "1h"); character.addTrait(wound);
        assertTrue(HealingInjuries.cure(player, "WOUND")); assertTrue(character.getTraits().isEmpty());
        assertNull(character.getTraitState("wound")); assertTrue(message().contains("wound"));
        verify(manager).savePlayer(player); verify(manager).reevaluateFreeze(player);
        assertFalse(HealingInjuries.cure(player, "wound"));
    }

    @Test void extensionUsesLegacyDurationRejectsNegativeBonusAndSaturatesOverflow() {
        var wound = trait("wound", "injury", "1h"); character.getTraits().add(wound);
        assertEquals(3_660_000, HealingInjuries.extend(player, "WOUND", 60_000));
        assertTrue(character.getDurationRemainingMs("wound") > 3_650_000);
        character.removeTraitState("wound"); assertEquals(3_600_000, HealingInjuries.extend(player, "wound", -1));
        assertEquals(Long.MAX_VALUE, HealingInjuries.extend(player, "wound", Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, character.getTraitState("wound").getExpiresAtMs());
        verify(manager, times(3)).savePlayer(player); assertEquals(3, integrations.constructed().size());
    }

    @Test void healingScalesPositiveAndNegativeAttributesWithoutMutatingTemplatesOrXp() {
        var wound = trait("wound", "injury", "1h", "attribute-modifiers", List.of("strength.-4", "agility.4", "zero.0"),
            "experience-modifiers.mining.modifier", 12);
        character.getTraits().add(wound);
        assertSame(wound.getTraitData().getAttributeData(), TraitEffectResolver.resolveAttributeData(null, wound));
        assertSame(wound.getTraitData().getAttributeData(), TraitEffectResolver.resolveAttributeData(character, wound));
        character.setDurationRemainingMs("wound", 1_800_000);
        var scaled = TraitEffectResolver.resolveAttributeData(character, wound);
        assertEquals(-2, scaled.getAmount(new AttributeModifier("strength", 0)));
        assertEquals(2, scaled.getAmount(new AttributeModifier("agility", 0)));
        assertEquals(-4, wound.getTraitData().getAttributeData().getAmount(new AttributeModifier("strength", 0)));
        assertEquals(12, scaled.getExperienceModifiers().getFirst().getModifier());
        character.setDurationRemainingMs("wound", 0);
        assertTrue(TraitEffectResolver.resolveAttributeData(character, wound).getModifiers().isEmpty());
        character.setDurationRemainingMs("wound", 7_200_000);
        assertSame(wound.getTraitData().getAttributeData(), TraitEffectResolver.resolveAttributeData(character, wound));
    }

    @Test void poweredAndDepoweredVariantsResolveNamesLorePotionsAndAttributes() {
        var powered = trait("arm", "prosthetic", null, "fuel-template", "oil", "block-offhand", true,
            "powered.name", "Powered", "powered.description", List.of("Working"), "powered.attribute-modifiers", List.of("strength.3"),
            "powered.potion-effects", List.of("SPEED(1)"), "depowered.name", " ", "depowered.description", List.of());
        assertFalse(TraitEffectResolver.isDepowered(null, powered)); assertFalse(TraitEffectResolver.isDepowered(character, null));
        assertTrue(TraitEffectResolver.isDepowered(character, powered));
        assertEquals("arm", TraitEffectResolver.resolveDisplayName(character, powered));
        assertEquals(List.of("Description", "§7Cannot use the offhand or two-handed items."), TraitEffectResolver.resolveDescription(character, powered));
        character.setFuel("arm", 5); assertFalse(TraitEffectResolver.isDepowered(character, powered));
        assertEquals("Powered", TraitEffectResolver.resolveDisplayName(character, powered));
        assertEquals("Working", TraitEffectResolver.resolveDescription(character, powered).getFirst());
        assertEquals(1, TraitEffectResolver.resolvePotionEffects(character, powered).getFirst().getAmplifier());
        assertEquals(3, TraitEffectResolver.resolveAttributeData(character, powered).getAmount(new AttributeModifier("strength", 0)));
        assertTrue(TraitEffectResolver.resolvePotionEffects(null, powered).isEmpty());
        var ordinary = trait("ordinary", "background", null, "potion-effects", List.of("SPEED(2)"));
        assertFalse(TraitEffectResolver.isDepowered(character, ordinary));
        assertEquals(2, TraitEffectResolver.resolvePotionEffects(character, ordinary).getFirst().getAmplifier());
        assertTrue(TraitEffectResolver.resolvePotionEffects(character, trait("plain", "background", null)).isEmpty());
        assertEquals("", TraitEffectResolver.resolveDisplayName(character, null));
        assertTrue(TraitEffectResolver.resolveDescription(character, null).isEmpty());
        assertTrue(TraitEffectResolver.resolvePotionEffects(character, null).isEmpty());
        assertFalse(TraitEffectResolver.resolveAttributeData(character, null).hasModifiers());
        TraitEffectResolver.appendBlockOffhandLore(powered, null);
    }
}

abstract class HealingRuntimeFixture {
    @TempDir Path folder;
    ServerMock server; RuntimeTestState state; RPCharacters plugin; PlayerMock player;
    RPCharacter character; PlayerData data; PlayerManager manager; Race race;
    final Map<Player, PlayerData> loaded = new HashMap<>(); final List<AutoCloseable> boundaries = new ArrayList<>();
    MockedConstruction<Integrator> integrations; MockedStatic<PermadeathService> deaths;
    Runnable timer; long delay, period;

    @BeforeEach void setupHealing() {
        server = MockBukkit.mock(); state = new RuntimeTestState(RPCharacters.class, InjuryPoolLoader.class, CreationManager.class,
            net.Indyuce.mmoitems.MMOItems.class, io.lumine.mythic.lib.MythicLib.class);
        Cache.attributes = new ArrayList<>(); Cache.professions = new ArrayList<>(); Cache.backgroundTraitTypes = new ArrayList<>();
        CreationManager.activeCreators.clear();
        plugin = mock(RPCharacters.class); when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        RPCharacters.plugin = plugin; manager = mock(PlayerManager.class);
        boundary(RPCharacters.class).when(RPCharacters::getPlayerManager).thenReturn(manager);
        boundary(PlayerManager.class).when(() -> PlayerManager.get(any(Player.class))).thenAnswer(call -> loaded.get(call.getArgument(0)));
        boundary(Database.class); boundary(AttributePointService.class); deaths = boundary(PermadeathService.class);
        integrations = mockConstruction(Integrator.class); boundaries.add(integrations);
        player = spy(new PlayerMock(server, "Patient")); server.addPlayer(player);
        var config = new YamlConfiguration(); config.set("name", "Human"); race = new Race("human", config);
        character = character(true); data = new PlayerData(player); data.addCharacter(character); loaded.put(player, data);
        var scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong())).thenAnswer(call -> {
            timer = call.getArgument(1); delay = call.getArgument(2); period = call.getArgument(3); return mock(BukkitTask.class);
        });
        var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS); boundaries.add(bukkit); bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
    }

    <T> MockedStatic<T> boundary(Class<T> type) { var mock = mockStatic(type); boundaries.add(mock); return mock; }
    RPCharacter character(boolean active) { return new RPCharacter(player, UUID.randomUUID().toString(), "Patient", active,
        Status.ALIVE, race, new ArrayList<>(), "HUMAN"); }
    Trait trait(String id, String key, String duration, Object... entries) {
        var config = new YamlConfiguration(); config.set("name", id); config.set("key", key); config.set("description", List.of("Description"));
        if (duration != null) config.set("duration", duration);
        for (int i = 0; i < entries.length; i += 2) config.set((String) entries[i], entries[i + 1]);
        return new Trait(id, config);
    }
    String message() { return Objects.requireNonNull(player.nextMessage()); }
    @AfterEach void cleanupHealing() throws Exception {
        for (int i = boundaries.size() - 1; i >= 0; i--) boundaries.get(i).close(); state.close(); MockBukkit.unmock();
    }
}
