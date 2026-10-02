package net.tfminecraft.rpcharacters.objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.creation.CharacterCreation;
import net.tfminecraft.rpcharacters.creation.Stage;
import net.tfminecraft.rpcharacters.database.Database;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.lifecycle.CharacterLifecycle;
import net.tfminecraft.rpcharacters.loaders.ProstheticLoader;
import net.tfminecraft.rpcharacters.loaders.StageLoader;
import net.tfminecraft.rpcharacters.loaders.TraitLoader;
import net.tfminecraft.rpcharacters.mail.MailRecipientDirectory;
import net.tfminecraft.rpcharacters.mmocore.AttributePointService;
import net.tfminecraft.rpcharacters.mmocore.MmoCorePlayerReady;
import net.tfminecraft.rpcharacters.objects.attributes.AttributeData;
import net.tfminecraft.rpcharacters.objects.attributes.AttributeModifier;
import net.tfminecraft.rpcharacters.objects.experience.ExperienceModifier;
import net.tfminecraft.rpcharacters.objects.races.Race;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.professions.ProfessionIntegrator;

class PlayerDataRuntimeTest {
    ServerMock server;
    RuntimeTestState state;
    Player player;
    final List<AutoCloseable> boundaries = new ArrayList<>();
    @BeforeEach void setup() {
        server = MockBukkit.mock(); state = new RuntimeTestState(TraitLoader.class, StageLoader.class);
        Cache.attributes = new ArrayList<>(); Cache.professions = new ArrayList<>(); Cache.backgroundTraitTypes = new ArrayList<>(); TraitLoader.oList.clear(); StageLoader.oList.clear();
        player = mock(Player.class); when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.getName()).thenReturn("Owner"); when(player.getLocation()).thenReturn(new Location(server.addSimpleWorld("world"), 2, 3, 4));
        boundary(Database.class); boundary(MmoCorePlayerReady.class); boundary(AttributePointService.class); boundary(ProfessionIntegrator.class); boundary(CharacterLifecycle.class); boundary(MailRecipientDirectory.class);
    }
    @AfterEach void teardown() throws Exception { for (var b : boundaries.reversed()) b.close(); state.close(); MockBukkit.unmock(); }
    <T> MockedStatic<T> boundary(Class<T> type) { var b = mockStatic(type); boundaries.add(b); return b; }
    RPCharacter character(String name) { RPCharacter c = new RPCharacter(player); c.setName(name); c.setMMOClass("WARRIOR"); YamlConfiguration race = new YamlConfiguration(); race.set("name", "Human"); race.set("attribute-modifiers", List.of("strength.1")); c.setRace(new Race("human", race)); return c; }
    Trait trait(String id, String key, int amount) { YamlConfiguration config = new YamlConfiguration(); config.set("name", id); config.set("key", key); config.set("attribute-modifiers", List.of("strength." + amount)); return new Trait(id, config); }

    @Test void constructorsAndAccountMetadataRetainValuesAndAllowOnlineRebinding() {
        var online = new PlayerData(player, new ArrayList<>(List.of("intro")), 50L, true, -1); assertSame(player, online.getPlayer()); assertEquals(player.getUniqueId(), online.getUniqueId()); assertTrue(online.isEighteen()); assertEquals(0, online.getAgeSeconds()); assertTrue(online.needsSkillPointsMigration()); assertEquals(50L, online.getLastCharacterSwitchAtMs());
        online.clearCharacterSwitchCooldown(); assertNull(online.getLastCharacterSwitchAtMs()); online.recordCharacterSwitch(); assertTrue(online.getLastCharacterSwitchAtMs() > 0); online.setLastCharacterSwitchAtMs(12L); assertEquals(12L, online.getLastCharacterSwitchAtMs()); online.setEighteen(false); assertFalse(online.isEighteen());
        var offline = new PlayerData(player.getUniqueId(), new ArrayList<>(), null, false, 0, 8); assertNull(offline.getPlayer()); offline.bindPlayer(player); assertSame(player, offline.getPlayer()); assertEquals(8, offline.getAccountSkillPointsTotal()); offline.setCreatedAtEpochSeconds((int) Instant.now().getEpochSecond() - 10); assertTrue(offline.getAgeSeconds() >= 10); offline.setCreatedAtEpochSeconds((int) Instant.now().getEpochSecond() + 100); assertEquals(0, offline.getAgeSeconds());
    }
    @Test void accountPointTotalsRejectNegativeChangesAndSaturateAtMaximum() {
        var data = new PlayerData(player.getUniqueId()); assertTrue(data.needsSkillPointsMigration()); assertTrue(data.needsAttributePointsMigration()); assertEquals(0, data.getAccountSkillPointsTotal()); assertEquals(0, data.getAccountAttributePointsTotal());
        data.addAccountSkillPoints(-1); data.addAccountSkillPoints(0); data.addAccountAttributePoints(-1); data.addAccountAttributePoints(0); assertTrue(data.needsSkillPointsMigration()); assertTrue(data.needsAttributePointsMigration());
        data.setAccountSkillPointsTotal(-1); data.setAccountAttributePointsTotal(-1); data.addAccountSkillPoints(Integer.MAX_VALUE); data.addAccountSkillPoints(2); data.addAccountAttributePoints(Integer.MAX_VALUE); data.addAccountAttributePoints(2); assertEquals(Integer.MAX_VALUE, data.getAccountSkillPointsTotal()); assertEquals(Integer.MAX_VALUE, data.getAccountAttributePointsTotal());
        data.setAccountProfessionPoints(null, 10); data.addAccountProfessionPoints(null, 10); data.addAccountProfessionPoints("mining", -1); assertEquals(0, data.getAccountProfessionPoints(null)); assertFalse(data.isProfessionPointsInitialized()); data.setProfessionPointsInitialized(true); assertTrue(data.isProfessionPointsInitialized());
        Locale.setDefault(Locale.forLanguageTag("tr-TR")); data.setAccountProfessionPoints("MINING", 4); data.addAccountProfessionPoints("mining", 2); assertEquals(6, data.getAccountProfessionPoints("MINING")); data.setAccountProfessionPoints("mining", -1); assertEquals(0, data.getAccountProfessionPoints("mining")); data.addAccountProfessionPoints("mining", Integer.MAX_VALUE); data.addAccountProfessionPoints("MINING", 5); assertEquals(Integer.MAX_VALUE, data.getAccountProfessionPoints("mining")); data.clearAccountProfessionPoints(); assertTrue(data.getAccountProfessionPointsMap().isEmpty());
    }
    @Test void kitAliasTutorialAndPendingRemovalStateHandleEmptyValuesAndDefensiveCopies() {
        PlayerData data = new PlayerData(player); assertNull(data.getLastKitClaimAtMs(null)); assertNull(data.getLastKitClaimAtMs(" ")); data.setLastKitClaimAtMs(null, 1L); data.setLastKitClaimAtMs(" ", 1L); assertTrue(data.getLastKitClaimAtMsMap().isEmpty());
        data.setLastKitClaimAtMs(" SWORD ", 4L); assertEquals(4L, data.getLastKitClaimAtMs("sword")); data.setLastKitClaimAtMs("SWORD", -1L); assertNull(data.getLastKitClaimAtMs("sword")); data.setLastKitGrantAtMs(8L); assertEquals(8L, data.getLastKitGrantAtMs()); data.setLastKitGrantAtMs(null); assertNull(data.getLastKitGrantAtMs());
        data.setTempAlias("Alias"); assertEquals("Alias", data.getTempAlias()); data.clearTempAlias(); assertNull(data.getTempAlias()); data.setTempAlias(null); data.setTempAlias(" "); assertNull(data.getTempAlias());
        data.setTutorialDismissed(null, true); data.setTutorialDismissed(" ", true); data.setTutorialDismissed("INTRO", true); assertTrue(data.hasDismissedTutorial("intro")); assertFalse(data.hasDismissedTutorial(null)); assertThrows(UnsupportedOperationException.class, () -> data.getDismissedTutorials().clear()); data.setTutorialDismissed("intro", false); assertFalse(data.hasDismissedTutorial("INTRO")); data.setTutorialDismissed("intro", true); data.clearDismissedTutorials(); assertTrue(data.getDismissedTutorials().isEmpty());
        List<String> pending = new ArrayList<>(List.of("strength.2")); data.setPendingMmoAttributeRemoves(pending); pending.clear(); assertEquals(List.of("strength.2"), data.takePendingMmoAttributeRemoves()); assertTrue(data.takePendingMmoAttributeRemoves().isEmpty()); data.setPendingMmoAttributeRemoves(null); assertTrue(data.takePendingMmoAttributeRemoves().isEmpty());
        assertTrue(data.needsInvestigationPointsInit()); data.setInvestigationPoints(-1); assertFalse(data.needsInvestigationPointsInit()); assertEquals(0, data.getInvestigationPoints()); data.setLastInvestigationRegenMs(9L); assertEquals(9L, data.getLastInvestigationRegenMs());
    }
    @Test void characterLookupSlugUniquenessAndCompletedStagesPreserveMembership() {
        PlayerData data = new PlayerData(player); assertFalse(data.hasCharacters()); assertFalse(data.hasActiveCharacter()); assertNull(data.getActiveCharacter()); assertNull(data.getCharacterBySlug(null)); assertNull(data.getCharacterBySlug(" ")); assertNull(data.getCharacterById("missing"));
        RPCharacter first = character("Aria"), second = character("Aria"), third = character("Visible"); data.addCharacter(first); data.addCharacter(second); data.addCharacter(third); assertTrue(data.hasCharacters()); assertEquals("aria", first.getSlug()); assertEquals("aria_2", second.getSlug()); assertSame(second, data.getCharacterBySlug("ARIA_2")); assertSame(first, data.getCharacterById(first.getId().toUpperCase(Locale.ROOT))); assertNull(data.getCharacterBySlug("missing"));
        data.assignSlug(null); first.setName(null); data.assignSlug(first); assertEquals("aria", first.getSlug()); first.setName("New Name"); data.assignSlug(first); assertEquals("aria", first.getSlug());
        first.setStatus(Status.DEAD); second.setHidden(true); assertSame(third, data.findFacadeCharacter()); third.setStatus(Status.MISSING); assertNull(data.findFacadeCharacter()); assertEquals(List.of(first), data.getCharacters(Status.DEAD));
        Stage intro = new Stage(); intro.setId("intro"); assertFalse(data.hasCompletedStage(intro)); data.addCompletedStage(intro); data.addCompletedStage(intro); assertTrue(data.hasCompletedStage(intro)); assertEquals(List.of("intro"), data.getCompletedStages());
    }
    @Test void activationUsesBoundOrResolvedPlayerAndRefreshesAnAlreadyActiveSanitizedCharacter() {
        RPCharacter first = character("First"), second = character("Second"); PlayerData data = new PlayerData(player.getUniqueId()); data.addCharacter(first); data.addCharacter(second); data.setActiveCharacter(null); assertFalse(data.hasActiveCharacter());
        try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            UUID id = player.getUniqueId(); bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player); data.setActiveCharacter(first); assertTrue(first.isActive()); assertSame(first, data.getActiveCharacter());
            data.setActiveCharacter(second); assertFalse(first.isActive()); assertTrue(second.isActive()); assertEquals("world", first.getLastLocationWorld()); assertNotNull(data.getLastCharacterSwitchAtMs());
        }
        data.bindPlayer(player); data.setActiveCharacter(second); assertSame(second, data.getActiveCharacter());
        Trait injury = trait("lost_arm", "injury", -1), replacement = trait("metal_arm", "prosthetic", 2); TraitLoader.oList.addAll(List.of(injury, replacement)); second.setTraits(new ArrayList<>(List.of(injury, replacement))); second.update(); assertEquals(2, second.getAttributeData().getAmount(new AttributeModifier("strength", 0)));
        try (var prosthetics = mockStatic(ProstheticLoader.class)) {
            prosthetics.when(() -> ProstheticLoader.getReplacementForProsthetic("metal_arm")).thenReturn(new ProstheticReplacement("lost_arm", Map.of("metal_arm", "v.IRON_INGOT")));
            data.setActiveCharacter(second); assertEquals(List.of(replacement), second.getTraits()); assertEquals(3, second.getAttributeData().getAmount(new AttributeModifier("strength", 0))); assertSame(second, data.getActiveCharacter());
        }
    }
    @Test void attributeDataSeedsAliasesAndCombinesExperienceAndAttributeDeltasWithoutSharing() {
        Cache.attributes = new ArrayList<>(List.of("strength")); Cache.professions = new ArrayList<>(List.of("mining(Miner)", "fishing")); Cache.startingProfessionFactor = 10;
        AttributeData data = new AttributeData(); assertTrue(data.hasModifiers()); assertEquals("Miner", data.getExperienceModifiers().get(0).getAlias()); assertEquals("fishing", data.getExperienceModifiers().get(1).getAlias());
        ExperienceModifier mining = new ExperienceModifier("MINING", "Miner", 5); data.addXPModifier(mining); assertEquals(15, data.getAmount(mining)); assertEquals(0, data.getAmount(new ExperienceModifier("missing", "Missing", 1)));
        data.addModifier(new AttributeModifier("strength", 3)); assertEquals(3, data.getAmount(new AttributeModifier("STRENGTH", 0))); assertEquals(0, data.getAmount(new AttributeModifier("missing", 0)));
        AttributeData delta = new AttributeData(new YamlConfiguration()); delta.addModifier(new AttributeModifier("strength", 2)); delta.addXPModifier(new ExperienceModifier("MINING", "Miner", 4)); data.mergeFrom(delta); assertEquals(5, data.getAmount(new AttributeModifier("strength", 0))); assertEquals(19, data.getAmount(mining)); data.mergeFromReverse(delta); assertEquals(3, data.getAmount(new AttributeModifier("strength", 0))); assertEquals(15, data.getAmount(mining)); assertEquals(4, delta.getAmount(mining));
        AttributeModifier mutable = new AttributeModifier("strength.5"); mutable.remove(2); assertEquals(3, mutable.getAmount()); mutable.remove(10); assertEquals(0, mutable.getAmount()); mutable.add(4); assertEquals(4, mutable.getAmount());
        ExperienceModifier effect = new ExperienceModifier("mining", "Miner", -110); assertEquals(0, effect.getFactor()); effect.modify(135); assertEquals(1.25, effect.getFactor()); data.clearAll(); assertFalse(data.hasModifiers()); data.addXPModifier(effect); assertTrue(data.hasModifiers());
    }
    @Test void copiedAttributeDataDoesNotAddTheDefaultProfessionBonusTwice() {
        Cache.professions = new ArrayList<>(List.of("mining(Miner)")); Cache.startingProfessionFactor = 10; AttributeData original = new AttributeData(); original.addModifier(new AttributeModifier("strength", 3)); AttributeData copy = new AttributeData(original);
        ExperienceModifier mining = new ExperienceModifier("mining", "Miner", 0); assertEquals(10, original.getAmount(mining)); assertEquals(10, copy.getAmount(mining)); assertNotSame(original.getExperienceModifiers().getFirst(), copy.getExperienceModifiers().getFirst());
        copy.addXPModifier(new ExperienceModifier("mining", "Miner", 5)); copy.addModifier(new AttributeModifier("strength", 2)); assertEquals(10, original.getAmount(mining)); assertEquals(15, copy.getAmount(mining)); assertEquals(3, original.getAmount(new AttributeModifier("strength", 0))); assertEquals(5, copy.getAmount(new AttributeModifier("strength", 0)));
    }
    @Test void selectableTraitCopiesDependencyAndTogglesCharacterBonusesWithoutChangingTemplate() {
        YamlConfiguration config = new YamlConfiguration(); config.set("name", "Strong"); config.set("key", "physical"); config.set("cost", 2); config.set("attribute-modifiers", List.of("strength.3")); config.set("mutually-exclusive", List.of("other")); config.set("dependency.type", "trait"); config.set("dependency.mode", "all"); config.set("dependency.depends-on", List.of("history")); Trait strong = new Trait("strong", config); Trait other = trait("other", "physical", 0); TraitLoader.oList.addAll(List.of(strong, other));
        SelectableItem item = new SelectableItem(strong); SelectableItem copy = new SelectableItem(item); assertTrue(copy.hasDependency()); assertSame(item.getDependency(), copy.getDependency()); assertEquals(List.of("other"), copy.getExclusives()); assertTrue(copy.isExclusive(new SelectableItem(other))); assertTrue(copy.isExclusive("other")); assertFalse(copy.isExclusive("missing")); assertFalse(copy.isExclusive(new SelectableItem(trait("unrelated", "physical", 0)))); copy.setDependency(null); assertFalse(copy.hasDependency());
        RPCharacter c = character("Aria"); item.click(c); assertTrue(item.isSelected()); assertEquals(List.of(strong), c.getTraits()); assertEquals(3, c.getAttributeData().getAmount(new AttributeModifier("strength", 0))); item.click(c); assertFalse(item.isSelected()); assertTrue(c.getTraits().isEmpty()); assertEquals(0, c.getAttributeData().getAmount(new AttributeModifier("strength", 0))); assertEquals(3, item.getAttributeData().getAmount(new AttributeModifier("strength", 0)));
        CharacterCreation creation = CharacterCreation.forEdit(player, c); copy.click(creation); assertTrue(copy.isSelected()); copy.click(creation); assertFalse(copy.isSelected());
    }
    @Test void selectableExclusionMatchesIdsConsistentlyAcrossBothPublicOverloads() {
        YamlConfiguration config = new YamlConfiguration(); config.set("name", "Exclusive"); config.set("key", "physical"); config.set("mutually-exclusive", List.of("OTHER")); SelectableItem item = new SelectableItem(new Trait("exclusive", config)); SelectableItem other = new SelectableItem(trait("other", "physical", 0));
        assertTrue(item.isExclusive(other)); assertTrue(item.isExclusive("other"));
    }
}
