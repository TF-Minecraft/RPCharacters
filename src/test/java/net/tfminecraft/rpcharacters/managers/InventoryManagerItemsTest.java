package net.tfminecraft.rpcharacters.managers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.logging.Logger;
import net.Indyuce.mmocore.MMOCore;
import net.Indyuce.mmocore.api.player.profess.PlayerClass;
import net.Indyuce.mmocore.manager.ClassManager;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.creation.*;
import net.tfminecraft.rpcharacters.creation.stages.*;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.identity.*;
import net.tfminecraft.rpcharacters.loaders.*;
import net.tfminecraft.rpcharacters.mmocore.MmoCoreClassGuiHelper;
import net.tfminecraft.rpcharacters.objects.*;
import net.tfminecraft.rpcharacters.objects.attributes.*;
import net.tfminecraft.rpcharacters.objects.experience.ExperienceModifier;
import net.tfminecraft.rpcharacters.objects.races.*;
import net.tfminecraft.rpcharacters.objects.trait.*;
import net.tfminecraft.rpcharacters.paidchange.*;
import net.tfminecraft.rpcharacters.permadeath.*;
import net.tfminecraft.rpcharacters.persona.*;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import net.tfminecraft.tlibs.objects.api.subapi.ItemCreator;
import org.bukkit.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.MockedStatic;

class InventoryManagerItemsTest extends InventoryFixture {
    @Test void navigationItemsCarryExpectedNamesActionsAndCharacterIdentity() {
        assertEquals("A name", name(manager.createItemStack(Material.PAPER, "A name")));
        assertEquals("Back", name(manager.getBackButton()));
        assertEquals("char-1", tag(manager.getBackButton("char-1"), "character_id"));
        assertEquals(Material.IRON_AXE, manager.getKillItem().getType());
        assertTrue(lore(manager.getKillItem()).contains("Only staff can reverse this"));
        assertEquals(Material.EMERALD, manager.getSwitchItem().getType());
        assertTrue(lore(manager.getSwitchItem()).contains("cooldown"));
        assertEquals("CONFIRM", name(manager.getConfirmItem()));
        assertEquals("0m", manager.formatTime(0)); assertEquals("1m", manager.formatTime(1));
        assertEquals("1h", manager.formatTime(60)); assertEquals("1d", manager.formatTime(1440));
        assertEquals("2d 3h 4m", manager.formatTime(3064));
    }

    @Test void cancellationExplainsCreationEditAndPendingRefund() {
        assertTrue(lore(manager.createCancelItem(null)).contains("Cancel the edit"));
        assertEquals("Cancel Creation", name(manager.createCancelItem(creation)));
        assertTrue(lore(manager.createCancelItem(creation)).contains("not reversible"));
        when(creation.getPendingPaidChange()).thenReturn(new PendingPaidChange("class", "old class", player.getUniqueId(), DenarWallet.Account.POUCH, new BigDecimal("125"), "{}"));
        String refund = lore(manager.createCancelItem(creation)); assertTrue(refund.contains("Keep your old class")); assertTrue(refund.contains("125"));
        when(creation.getPendingPaidChange()).thenReturn(new PendingPaidChange("class", "old class", player.getUniqueId(), null, BigDecimal.ZERO, "{}"));
        assertFalse(lore(manager.createCancelItem(creation)).contains("back"));
    }

    @Test void descriptionAndCluePreviewPreserveWritingAndMetadata() {
        when(character.getDescription()).thenReturn(List.of("Plain text", "§aColoured", "#123456Hex"));
        String description = lore(manager.getDescriptionItem(character));
        assertTrue(description.contains("Plain text")); assertTrue(description.contains("Coloured")); assertTrue(description.contains("Hex"));
        when(character.getPlayerClues()).thenReturn(List.of());
        var empty = manager.getCluesPreviewItem(character); assertTrue(lore(empty).contains("No clues yet"));
        assertEquals("char-1", tag(empty, "character_id"));
        assertEquals((byte) 1, empty.getItemMeta().getPersistentDataContainer().get(key("open_clues_gui"), PersistentDataType.BYTE));
        when(character.getPlayerClues()).thenReturn(List.of("Scar on hand", "Blue coat"));
        assertTrue(lore(manager.getCluesPreviewItem(character)).contains("Scar on hand"));
    }

    @Test void traitOverviewHidesBackgroundAndShowsDurationsRiskAndProfessionEffects() {
        var background = trait("history", "background"); var injury = trait("broken_arm", "injury"); var ordinary = trait("strong", "physical");
        when(injury.hasDuration()).thenReturn(true); when(injury.getDurationMs()).thenReturn(7_200_000L);
        when(character.getDurationRemainingMs("broken_arm")).thenReturn(-1L, 3_600_000L);
        when(character.getTraits()).thenReturn(List.of(background, injury, ordinary));
        when(character.getAttributeData()).thenReturn(data(new int[] {}, new int[] {10, 0, -5}));
        var result = manager.getTraitsItem(character); String text = lore(result);
        assertFalse(text.contains("history")); assertTrue(text.contains("broken_arm")); assertTrue(text.contains("strong"));
        assertTrue(text.contains("10%")); assertTrue(text.contains("0%")); assertTrue(text.contains("-5%")); assertTrue(text.contains("Risk boundary"));
        assertEquals("char-1", tag(result, "character_id"));
        when(character.getStatus()).thenReturn(Status.DEAD);
        assertFalse(lore(manager.getTraitsItem(character)).contains("Risk boundary"));
    }

    @Test void traitDetailsShowResolvedEffectsFuelDurationAndNoEffectFallback() {
        var trait = trait("powered_arm", "prosthetic"); var data = data(new int[] {2, -1, 0}, new int[] {20, -5, 0});
        when(trait.getTraitData().getAttributeData()).thenReturn(data);
        when(trait.hasDuration()).thenReturn(true); when(character.getDurationRemainingMs("powered_arm")).thenReturn(90_000L);
        when(trait.hasFuelTemplate()).thenReturn(true); when(trait.getFuelCapacity()).thenReturn(100D); when(character.getFuel("powered_arm")).thenReturn(40D);
        when(trait.getTraitData().hasPotionEffects()).thenReturn(true); when(trait.getTraitData().getPotionEffects()).thenReturn(List.of(new PotionData("speed(1)")));
        var result = manager.getTraitInfoItem(trait, character); String text = lore(result);
        assertTrue(text.contains("Time remaining")); assertTrue(text.contains("Fuel")); assertTrue(text.contains("+2")); assertTrue(text.contains("-1"));
        assertTrue(text.contains("+20%")); assertTrue(text.contains("-5%")); assertTrue(text.contains("0%")); assertTrue(text.contains("Speed: 2"));
        assertEquals("char-1", tag(result, "character_id"));
        assertTrue(lore(manager.getTraitInfoItem(trait("empty", "physical"), character)).contains("No direct effects"));
    }

    @Test void characterCardsExposeMetadataOnlyInDetailAndHighlightActiveSelection() {
        when(character.getAlias()).thenReturn("Alias"); when(character.getSlug()).thenReturn("aria-1"); when(character.isHidden()).thenReturn(true);
        player.addAttachment(plugin, Cache.personaCharacterHiddenPermission, true);
        cooldowns.when(() -> PermissionGroupService.hasCharacterSwitchCooldown(player, data)).thenReturn(true);
        cooldowns.when(() -> PermissionGroupService.getRemainingCooldownMinutes(player, data)).thenReturn(61);
        var detail = manager.getCharacterItem(character, false); String text = lore(detail);
        assertTrue(text.contains("Display: Alias")); assertTrue(text.contains("Name: Aria")); assertTrue(text.contains("Id: aria-1")); assertTrue(text.contains("Hidden from TAB/list"));
        assertTrue(text.contains("Description:")); assertTrue(text.contains("1h 1m")); assertEquals("char-1", tag(detail, "character_id")); assertEquals(player.getName(), tag(detail, "owner"));
        when(character.isActive()).thenReturn(true);
        var active = manager.getCharacterItem(character, true, true);
        assertTrue(active.getItemMeta().hasEnchants()); assertTrue(active.getItemMeta().hasItemFlag(ItemFlag.HIDE_ENCHANTS));
        assertTrue(lore(active).contains("Over slot limit")); assertFalse(lore(active).contains("Gender:")); assertFalse(lore(active).contains("Description:"));
    }

    @Test void slotCardsAndSkullsExplainAvailabilityAndOwnership() {
        when(data.getCharacters(Status.DEAD)).thenReturn(List.of(character));
        assertEquals("Dead Characters: 1", name(manager.getDeadCharactersItem(data))); assertEquals(player.getName(), tag(manager.getDeadCharactersItem(data), "owner"));
        cooldowns.when(() -> PermissionGroupService.hasCharacterSwitchCooldown(player, data)).thenReturn(true);
        cooldowns.when(() -> PermissionGroupService.getRemainingCooldownMinutes(player, data)).thenReturn(30);
        assertTrue(lore(manager.getEmptyCharacterItem(data)).contains("30m"));
        assertTrue(lore(manager.getLockedCharacterItem(player, 2)).contains("Unlock slot 2"));
        assertEquals(Material.TOTEM_OF_UNDYING, manager.getReviveItem(true, 1, 2).getType()); assertTrue(lore(manager.getReviveItem(false, 2, 2)).contains("no free"));
        when(data.isEighteen()).thenReturn(true); assertTrue(lore(manager.getPlayerHead(player)).contains("18+"));
        when(data.isEighteen()).thenReturn(false); assertTrue(lore(manager.getPlayerHead(player)).contains("below 18"));
        players.when(() -> PlayerManager.get(player)).thenReturn(null); assertFalse(lore(manager.getPlayerHead(player)).contains("Member for"));
        var unavailable = mock(ItemStack.class); rpc.when(() -> RPCharacters.getSkull(player)).thenReturn(unavailable);
        assertSame(unavailable, manager.getPlayerHead(player));
    }

    @Test void attributeCardsReflectAvailablePointsRankAndRefunds() {
        var stage = attributeStage(); when(stage.getRemaining()).thenReturn(100);
        assertEquals(64, manager.getAttributePointsHeader(stage).getAmount());
        when(stage.getRemaining()).thenReturn(0); assertEquals(1, manager.getAttributePointsHeader(stage).getAmount());
        assertEquals(Material.LIME_DYE, manager.getAttributeConfirmItem(stage).getType());
        when(stage.getRemaining()).thenReturn(3); assertTrue(lore(manager.getAttributeConfirmItem(stage)).contains("3 left"));
        when(stage.getRank("strength")).thenReturn(0); assertTrue(lore(manager.getAttributeMinusItem("strength", stage)).contains("Already at 0"));
        when(stage.getRank("strength")).thenReturn(2); assertTrue(lore(manager.getAttributeMinusItem("strength", stage)).contains("Refund 2"));
        var minus = manager.getAttributeMinusItem("strength", stage); assertEquals("minus", tag(minus, "attr_action")); assertEquals("strength", tag(minus, "attr_id"));
        assertTrue(lore(manager.getAttributePlusItem("strength", stage)).contains("Not enough points"));
        when(stage.getRemaining()).thenReturn(12); assertEquals(Material.LIME_CONCRETE, manager.getAttributePlusItem("strength", stage).getType());
        when(stage.getRank("strength")).thenReturn(4); assertTrue(lore(manager.getAttributePlusItem("strength", stage)).contains("Max rank"));
        assertFalse(lore(manager.getAttributeStatItem("strength", stage)).contains("Next costs"));
        when(stage.getRank("strength")).thenReturn(1); var stat = manager.getAttributeStatItem("strength", stage);
        assertTrue(lore(stat).contains("Next costs 2")); assertEquals("strength", tag(stat, "attr_id"));
        assertArrayEquals(new int[] {-1,-1,-1}, InventoryManager.attributeSheetSlots(null, "strength"));
        assertArrayEquals(new int[] {11,20,29}, InventoryManager.attributeSheetSlots(stage, "strength"));
        assertArrayEquals(new int[] {11,20,29}, InventoryManager.attributeSheetSlots(0));
        assertArrayEquals(new int[] {-1,-1,-1}, InventoryManager.attributeSheetSlots(-1)); assertArrayEquals(new int[] {-1,-1,-1}, InventoryManager.attributeSheetSlots(6));
    }

    @Test void attributeIconsCloneCustomTemplatesAndFallBackOnMissingOrBrokenIntegrations() {
        var stage = attributeStage(); var template = new ItemStack(Material.ARROW, 2); when(creator.getItemsAdderItem(anyString())).thenReturn(template);
        var item = manager.getAttributePlusItem("strength", stage); assertEquals(Material.ARROW, item.getType()); assertEquals("plus", tag(item, "attr_action"));
        assertFalse(template.getItemMeta().hasDisplayName()); assertNull(tag(template, "attr_action"));
        when(creator.getItemsAdderItem(anyString())).thenReturn(new ItemStack(Material.AIR)); assertEquals(Material.LIME_CONCRETE, manager.getAttributePlusItem("strength", stage).getType());
        when(creator.getItemsAdderItem(anyString())).thenThrow(new IllegalStateException("IA offline")); assertEquals(Material.LIME_CONCRETE, manager.getAttributePlusItem("strength", stage).getType());
        var missingMeta = mock(ItemStack.class); when(missingMeta.getType()).thenReturn(Material.ARROW); when(missingMeta.clone()).thenReturn(missingMeta);
        doReturn(missingMeta).when(creator).getItemsAdderItem(anyString()); assertSame(missingMeta, manager.getAttributePlusItem("strength", stage));
    }

    @Test void modifierPreviewsShowSignedDeltasAndClampNegativeCurrentValues() {
        var incoming = data(new int[] {2,-2,0}, new int[] {5,-5,0}); var current = data(new int[] {-3,8,1}, new int[] {-10,25,1});
        when(creation.getTempData()).thenReturn(current); var lines = new ArrayList<String>(); manager.addModifiers(player, lines, incoming, creation);
        String text = plain(String.join("\n", lines)); assertTrue(text.contains("Attribute0: 0 (+2)")); assertTrue(text.contains("Attribute1: 8 (-2)"));
        assertTrue(text.contains("Profession0: 0% (+5%)")); assertTrue(text.contains("Profession1: 25% (-5%)")); assertEquals(4, lines.size());
        lines.clear(); manager.addModifiers(player, lines, null, creation); when(creation.getTempData()).thenReturn(null); manager.addModifiers(player, lines, incoming, creation); assertTrue(lines.isEmpty());
        when(data.hasActiveCharacter()).thenReturn(false); manager.addModifiers(player, lines, incoming, null); assertTrue(lines.isEmpty());
        when(data.hasActiveCharacter()).thenReturn(true); when(data.getActiveCharacter()).thenReturn(character); when(character.getAttributeData()).thenReturn(current);
        manager.addModifiers(player, lines, incoming, null); assertEquals(4, lines.size());
    }

    @Test void raceAndTraitSelectionsShowDependenciesExclusivesCostsAndFuel() {
        when(race.getRaceData().getAttributeData()).thenReturn(data(new int[] {2}, new int[] {}));
        var selected = option("human", "race", true); var raceItem = manager.getSelectableItem(player, null, selected, creation);
        assertEquals(Material.GREEN_CONCRETE, raceItem.getType()); assertTrue(lore(raceItem).contains("Human lore")); assertTrue(lore(raceItem).contains("(+2)"));
        var trait = trait("artisan", "profession"); var td = trait.getTraitData(); var dep = mock(Dependency.class);
        when(td.hasDependency()).thenReturn(true); when(td.getDependency()).thenReturn(dep); when(dep.getMode()).thenReturn("all"); when(dep.getDependencies()).thenReturn(List.of("human", "educated"));
        when(td.hasExclusives()).thenReturn(true); when(td.getExclusive()).thenReturn(List.of("untrained")); when(td.hasCost()).thenReturn(true); when(td.getCost()).thenReturn(3);
        when(td.getAttributeData()).thenReturn(data(new int[] {1}, new int[] {})); when(trait.hasFuelTemplate()).thenReturn(true);
        var stage = mock(SelectionStage.class); when(stage.hasPoints()).thenReturn(true); when(stage.getPoints()).thenReturn(7);
        String text = lore(manager.getSelectableItem(player, stage, option("artisan", "trait", false), creation));
        assertTrue(text.contains("Requires all")); assertTrue(text.contains("Educated")); assertTrue(text.contains("Untrained")); assertTrue(text.contains("Cost: 3")); assertTrue(text.contains("Unspent Points: 7")); assertTrue(text.contains("arcane fuel"));
        when(dep.getMode()).thenReturn("one-or-more"); when(data.hasActiveCharacter()).thenReturn(true); when(data.getActiveCharacter()).thenReturn(character);
        assertTrue(lore(manager.getSelectableItem(player, null, option("artisan", "trait", false), null)).contains("at least one"));
        when(data.hasActiveCharacter()).thenReturn(false); assertNotNull(manager.getSelectableItem(player, null, option("artisan", "trait", false), null));
    }

    @Test void classSelectionsUseClonedClassIconsAndFallbackForUnknownClasses() throws Exception {
        var classes = installMmo(); var chosen = mock(PlayerClass.class); var template = new ItemStack(Material.DIAMOND_SWORD);
        when(classes.get("warrior")).thenReturn(chosen); when(chosen.getIcon()).thenReturn(template);
        classGui.when(() -> MmoCoreClassGuiHelper.buildClassLore(chosen)).thenReturn(List.of("Class skills"));
        var selected = manager.getSelectableItem(player, null, option("warrior", "class", true), creation);
        assertEquals(Material.DIAMOND_SWORD, selected.getType()); assertTrue(lore(selected).contains("Class skills")); assertTrue(lore(selected).contains("Selected")); assertFalse(template.getItemMeta().hasDisplayName());
        when(chosen.getIcon()).thenReturn(new ItemStack(Material.AIR)); assertEquals(Material.RED_CONCRETE, manager.getSelectableItem(player, null, option("warrior", "class", false), null).getType());
        assertEquals(Material.RED_CONCRETE, manager.getSelectableItem(player, null, option("unknown", "class", false), null).getType());
    }

    @Test void traitSelectionRendersAnIncompleteDependencyWithoutGrantingIt() {
        var definition = new org.bukkit.configuration.file.YamlConfiguration();
        definition.set("name", "Artisan");
        definition.set("description", List.of("Careful craftsmanship"));
        definition.set("key", "profession");
        definition.set("dependency.type", "trait");
        definition.set("dependency.depends-on", List.of("educated"));
        Trait artisan = new Trait("artisan", definition);
        TraitLoader.oList.add(artisan);

        var config = new org.bukkit.configuration.file.YamlConfiguration();
        config.set("target", "trait");
        config.set("key", "profession");
        config.set("slots", List.of(10));
        config.set("max-select", 1);
        SelectionStage stage = new SelectionStage(new Stage(), config);

        assertNull(artisan.getTraitData().getDependency().getMode());
        assertFalse(artisan.getTraitData().getDependency().satisfiedBy(Set.of("educated")),
                "An incomplete dependency must remain unsatisfied");
        assertDoesNotThrow(() -> manager.selectionView(player, stage, creation));
        ItemStack rendered = top().getItem(10);
        assertEquals("Artisan", name(rendered));
        assertEquals(Material.RED_CONCRETE, rendered.getType());
        assertTrue(lore(rendered).contains("Careful craftsmanship"));
        assertTrue(lore(rendered).contains("Educated"));
        assertFalse(lore(rendered).contains("Requires all"));
        assertFalse(lore(rendered).contains("Requires at least one"));
        assertEquals("CONFIRM", name(top().getItem(26)));
    }
}

// Shared fixture stays in this test file: the task owns exactly two files.
abstract class InventoryFixture {
    ServerMock server; PlayerMock player, other; RPCharacters plugin; RuntimeTestState state; MMOCore previousMmo;
    InventoryManager manager; RPCharacter character; PlayerData data; Race race; CharacterCreation creation; ItemCreator creator;
    MockedStatic<PlayerManager> players; MockedStatic<RPCharacters> rpc; MockedStatic<CharacterSlotService> slots;
    MockedStatic<PermissionGroupService> cooldowns; MockedStatic<Permissions> permissions; MockedStatic<StageEditLock> locks;
    MockedStatic<PaidChangeService> paid; MockedStatic<MmoCoreClassGuiHelper> classGui;
    final List<AutoCloseable> boundaries = new ArrayList<>();
    <T> MockedStatic<T> boundary(Class<T> type) {var result = mockStatic(type); boundaries.add(result); return result;}
    @BeforeEach void setupInventory() {
        server = MockBukkit.mock(); state = new RuntimeTestState(RPCharacters.class, StageLoader.class, RaceLoader.class, TraitLoader.class); previousMmo = MMOCore.plugin;
        Cache.attributes = new ArrayList<>(); Cache.professions = new ArrayList<>(); Cache.backgroundTraitTypes = new ArrayList<>(List.of("background")); Cache.characterSlots = new ArrayList<>(List.of(10,11,12)); Cache.deadSlot = 26; Cache.maxClues = 60;
        StageLoader.oList = new ArrayList<>(); RaceLoader.oList = new ArrayList<>(); TraitLoader.oList = new ArrayList<>();
        plugin = mock(RPCharacters.class); when(plugin.getName()).thenReturn("RPCharacters"); when(plugin.namespace()).thenReturn("rpcharacters"); when(plugin.getServer()).thenReturn(server); when(plugin.isEnabled()).thenReturn(true); when(plugin.getLogger()).thenReturn(mock(Logger.class)); RPCharacters.plugin = plugin;
        player = server.addPlayer("Owner"); other = server.addPlayer("Viewer"); manager = new InventoryManager();
        race = mock(Race.class); var raceData = mock(RaceData.class); when(race.getId()).thenReturn("human"); when(race.getName()).thenReturn("Human"); when(race.getDesc()).thenReturn(List.of("Human lore")); when(race.getRaceData()).thenReturn(raceData); when(raceData.getAttributeData()).thenReturn(new AttributeData()); RaceLoader.oList.add(race);
        character = mock(RPCharacter.class); when(character.getOwner()).thenReturn(player); when(character.getId()).thenReturn("char-1"); when(character.getName()).thenReturn("Aria"); when(character.getRace()).thenReturn(race); when(character.getStatus()).thenReturn(Status.ALIVE); when(character.getPlayerClues()).thenReturn(List.of("Blue coat")); when(character.getCluesNeeded()).thenReturn(1); when(character.hasEnoughClues()).thenReturn(true); when(character.canAddClue()).thenReturn(true); when(character.hasMMOClass()).thenReturn(true); when(character.getMMOClass()).thenReturn("warrior"); when(character.getBirthday()).thenReturn("1/1/1700"); when(character.getPersonaDescription()).thenReturn("A long character description."); when(character.getDescription()).thenReturn(List.of("Background story")); when(character.getAttributeData()).thenReturn(new AttributeData()); when(character.getTraits()).thenReturn(List.of());
        data = mock(PlayerData.class); when(data.getPlayer()).thenReturn(player); when(data.getCharacters(Status.ALIVE)).thenReturn(List.of(character)); when(data.getCharacters(Status.DEAD)).thenReturn(List.of());
        creation = mock(CharacterCreation.class); when(creation.getCharacter()).thenReturn(character); when(creation.getTempData()).thenReturn(new AttributeData());
        players = boundary(PlayerManager.class); players.when(() -> PlayerManager.get(player)).thenReturn(data);
        rpc = boundary(RPCharacters.class); rpc.when(() -> RPCharacters.getSkull(any())).thenAnswer(call -> new ItemStack(Material.PLAYER_HEAD));
        slots = boundary(CharacterSlotService.class); slots.when(() -> CharacterSlotService.hasFreeSlot(any(), any())).thenReturn(true); slots.when(() -> CharacterSlotService.getMaxAliveCharacters(any())).thenReturn(2); slots.when(CharacterSlotService::getDisplaySlotCount).thenAnswer(call -> Cache.characterSlots.size()); slots.when(() -> CharacterSlotService.shouldShowLockedSlot(anyInt(), anyInt())).thenReturn(true); slots.when(() -> CharacterSlotService.getUnlockRequirementLore(anyInt())).thenAnswer(call -> "Unlock slot " + call.getArgument(0));
        cooldowns = boundary(PermissionGroupService.class); permissions = boundary(Permissions.class); locks = boundary(StageEditLock.class); locks.when(() -> StageEditLock.canEdit(any(), any(), any())).thenReturn(true);
        paid = boundary(PaidChangeService.class); paid.when(() -> PaidChangeService.summaryLore(any(),any(),anyBoolean())).thenReturn(null); paid.when(() -> PaidChangeService.formatDenars(any())).thenAnswer(call -> ((BigDecimal)call.getArgument(0)).stripTrailingZeros().toPlainString());
        var identity = boundary(DisplayIdentityService.class); identity.when(() -> DisplayIdentityService.resolveDisplayTab(any(RPCharacter.class))).thenReturn("Alias");
        var persona = boundary(PersonaService.class); persona.when(() -> PersonaService.resolveGender(any(RPCharacter.class))).thenReturn("Woman"); persona.when(() -> PersonaService.resolveAge(any(RPCharacter.class))).thenReturn("25"); persona.when(() -> PersonaService.resolveBirthday(any(RPCharacter.class))).thenReturn("1/1/1700"); persona.when(() -> PersonaService.resolveDescription(any(RPCharacter.class))).thenReturn("A thoughtful traveller.");
        var death = boundary(PermadeathService.class); var risk = mock(PermadeathRisk.class); when(risk.toLoreLines()).thenReturn(List.of("Risk boundary")); death.when(() -> PermadeathService.computeRisk(any())).thenReturn(risk);
        var tlibs = boundary(TLibs.class); var api = mock(ItemAPI.class); creator = mock(ItemCreator.class); when(api.getCreator()).thenReturn(creator); tlibs.when(TLibs::getItemAPI).thenReturn(api);
        classGui = boundary(MmoCoreClassGuiHelper.class);
    }
    @AfterEach void cleanupInventory() throws Exception {
        for (int i=boundaries.size()-1;i>=0;i--) boundaries.get(i).close(); MMOCore.plugin = previousMmo; MockBukkit.unmock(); state.close();
    }
    ClassManager installMmo() throws Exception {
        MMOCore.plugin = mock(MMOCore.class); var classes = mock(ClassManager.class);
        var field = MMOCore.class.getField("classManager"); field.setAccessible(true); field.set(MMOCore.plugin, classes); return classes;
    }
    Trait trait(String id, String group) {
        var trait = mock(Trait.class); var definition = mock(TraitData.class); when(trait.getId()).thenReturn(id); when(trait.getName()).thenReturn(id); when(trait.getDesc()).thenReturn(List.of(id + " description")); when(trait.getTraitData()).thenReturn(definition); when(definition.getKey()).thenReturn(group); when(definition.getAttributeData()).thenReturn(new AttributeData()); TraitLoader.oList.add(trait); return trait;
    }
    AttributeData data(int[] attrs, int[] xp) {
        var result = new AttributeData(); for(int i=0;i<attrs.length;i++) result.addModifier(new AttributeModifier("attribute"+i,attrs[i])); for(int i=0;i<xp.length;i++) result.addXPModifier(new ExperienceModifier("profession"+i,"Profession"+i,xp[i])); return result;
    }
    SelectableItem option(String id, String type, boolean selected) {var result=mock(SelectableItem.class); when(result.getId()).thenReturn(id); when(result.getType()).thenReturn(type); when(result.getName()).thenReturn(id); when(result.isSelected()).thenReturn(selected); return result;}
    AttributesStage attributeStage() {var result=mock(AttributesStage.class); when(result.getAttributes()).thenReturn(List.of("strength")); when(result.getSize()).thenReturn(54); when(result.getSheetSlots("strength")).thenReturn(new int[]{11,20,29}); when(result.getRemaining()).thenReturn(12); when(result.getPool()).thenReturn(12); when(result.getMaxRank()).thenReturn(4); return result;}
    NamespacedKey key(String name) {return new NamespacedKey(plugin,name);}
    String tag(ItemStack item,String key) {return item.getItemMeta().getPersistentDataContainer().get(key(key), PersistentDataType.STRING);}
    static String plain(String text) {return ChatColor.stripColor(text);}
    static String name(ItemStack item) {return plain(item.getItemMeta().getDisplayName());}
    static String lore(ItemStack item) {var lore=item.getItemMeta().getLore(); return lore==null?"":plain(String.join("\n",lore));}
    Inventory top() {return player.getOpenInventory().getTopInventory();}
    void assertFilled(Inventory inventory) {for(int slot=0;slot<inventory.getSize();slot++) assertNotNull(inventory.getItem(slot),"empty slot "+slot);}
}
