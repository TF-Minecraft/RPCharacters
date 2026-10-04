package net.tfminecraft.rpcharacters.managers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.stream.IntStream;
import net.Indyuce.mmocore.api.player.profess.PlayerClass;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.Permissions;
import net.tfminecraft.rpcharacters.classpick.ClassPickService;
import net.tfminecraft.rpcharacters.mmocore.MmoCoreClassGuiHelper;
import net.tfminecraft.rpcharacters.creation.*;
import net.tfminecraft.rpcharacters.creation.stages.*;
import net.tfminecraft.rpcharacters.enums.*;
import net.tfminecraft.rpcharacters.holder.RPCHolder;
import net.tfminecraft.rpcharacters.loaders.StageLoader;
import net.tfminecraft.rpcharacters.objects.*;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.paidchange.PaidChangeService;
import net.tfminecraft.rpcharacters.persona.*;
import org.bukkit.Material;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

class InventoryManagerViewsTest extends InventoryFixture {
    @Test void characterViewOffersOwnerActionsAndAdminRevivalWithoutLeakingOwnerControls() {
        manager.characterView(player, character); var view=top(); assertFilled(view);
        assertEquals(Material.IRON_AXE,view.getItem(8).getType()); assertEquals(Material.EMERALD,view.getItem(6).getType()); assertEquals(Material.BOOK,view.getItem(16).getType());
        assertSame(player,((RPCHolder)view.getHolder()).getOwner()); assertEquals("char-1",tag(view.getItem(10),"character_id"));
        manager.characterView(other,character); var foreign=other.getOpenInventory().getTopInventory();
        assertEquals(Material.GRAY_STAINED_GLASS_PANE,foreign.getItem(8).getType()); assertEquals(Material.GRAY_STAINED_GLASS_PANE,foreign.getItem(16).getType());
        cooldowns.when(() -> PermissionGroupService.hasCharacterSwitchCooldown(player,data)).thenReturn(true);
        manager.characterView(player,character); assertEquals(Material.GRAY_STAINED_GLASS_PANE,top().getItem(6).getType());
        permissions.when(() -> Permissions.isAdmin(player)).thenReturn(true); manager.characterView(player,character); assertEquals(Material.EMERALD,top().getItem(6).getType());
        when(character.getStatus()).thenReturn(Status.DEAD); manager.characterView(player,character); assertEquals(Material.TOTEM_OF_UNDYING,top().getItem(4).getType());
        slots.when(() -> CharacterSlotService.hasFreeSlot(player,data)).thenReturn(false); manager.characterView(player,character); assertEquals(Material.GRAY_DYE,top().getItem(4).getType());
    }

    @Test void clueViewsPreserveContextIndicesLimitsAndBackIdentity() {
        manager.cluesView(player,character); assertFilled(top()); assertEquals(27,top().getSize());
        assertEquals("char-1",tag(top().getItem(0),"character_id")); assertEquals(0,clueIndex(top().getItem(0))); assertEquals("char-1",tag(top().getItem(26),"character_id"));
        assertTrue(Arrays.stream(top().getContents()).filter(Objects::nonNull).anyMatch(item -> item.getType()==Material.LIME_DYE && lore(item).contains("Extra clues optional")));
        manager.cluesView(player,character,CreationGuiContext.EDIT_SUMMARY,creation);
        var holder=(RPCHolder)top().getHolder(); assertSame(creation,holder.getCreation()); assertEquals(CreationGuiContext.EDIT_SUMMARY,holder.getContext());
        when(character.canAddClue()).thenReturn(false); manager.cluesView(player,character,CreationGuiContext.CREATION_SUMMARY,creation);
        assertTrue(Arrays.stream(top().getContents()).anyMatch(item -> item.getType()==Material.GRAY_DYE));
        manager.cluesView(other,character); assertFalse(Arrays.stream(other.getOpenInventory().getTopInventory().getContents()).anyMatch(item -> item.getType()==Material.GRAY_DYE));
        when(character.getPlayerClues()).thenReturn(IntStream.range(0,60).mapToObj(i -> "Clue "+i).toList());
        manager.cluesView(player,character); assertEquals(54,top().getSize()); assertFilled(top()); assertEquals("Back",name(top().getItem(53)));
    }

    @Test void addAndLimitControlsMustNotOverwriteTheNinthClue() {
        when(character.getPlayerClues()).thenReturn(IntStream.range(0,9).mapToObj(i -> "Distinct clue "+i).toList());
        for(boolean canAdd:List.of(true,false)) {
            when(character.canAddClue()).thenReturn(canAdd); manager.cluesView(player,character);
            Set<Integer> shown=new HashSet<>(); for(ItemStack item:top().getContents()) {Integer index=clueIndex(item); if(index!=null) shown.add(index);}
            assertEquals(new HashSet<>(IntStream.range(0,9).boxed().toList()),shown,"Controls must not obscure a saved clue");
        }
    }

    @Test void clueRowsReserveEnoughRoomForControlsAtEveryRowBoundary() {
        for(int count:List.of(18,27,36,44)) {
            when(character.getPlayerClues()).thenReturn(IntStream.range(0,count).mapToObj(i -> "Clue "+i).toList());
            manager.cluesView(player,character);
            Set<Integer> shown=new HashSet<>(); for(ItemStack item:top().getContents()) {Integer index=clueIndex(item); if(index!=null) shown.add(index);}
            assertEquals(new HashSet<>(IntStream.range(0,count).boxed().toList()),shown,"Every clue fitting a six-row GUI must remain reachable; count="+count);
        }
    }

    @Test void traitAndDeadViewsFilterBackgroundAndRespectInventoryCapacity() {
        var hidden=trait("hidden_background","background"); var visible=trait("visible","physical");
        when(character.getTraits()).thenReturn(List.of(hidden,visible)); manager.traitsView(player,character); assertFilled(top());
        assertEquals("visible",name(top().getItem(0))); assertEquals("char-1",tag(top().getItem(26),"character_id"));
        var many=new ArrayList<Trait>(); many.add(hidden); for(int i=0;i<60;i++) many.add(trait("trait"+i,"physical"));
        when(character.getTraits()).thenReturn(many); manager.traitsView(player,character); assertEquals(54,top().getSize()); assertEquals("trait52",name(top().getItem(52))); assertEquals("Back",name(top().getItem(53)));
        when(data.getCharacters(Status.DEAD)).thenReturn(List.of(character)); manager.deadView(player,player); assertFilled(top()); assertEquals("char-1",tag(top().getItem(0),"character_id")); assertEquals("Back",name(top().getItem(26)));
        when(data.getCharacters(Status.DEAD)).thenReturn(List.of()); manager.deadView(player,player); assertEquals(Material.GRAY_STAINED_GLASS_PANE,top().getItem(0).getType());
    }

    @Test void profileDistinguishesActiveEmptyLockedAndOverLimitSlots() {
        manager.profileView(player,player); assertFilled(top()); assertEquals(Material.PLAYER_HEAD,top().getItem(0).getType());
        assertEquals("char-1",tag(top().getItem(10),"character_id")); assertEquals("Empty Slot",name(top().getItem(11))); assertEquals("LOCKED",name(top().getItem(12))); assertEquals(Material.SKELETON_SKULL,top().getItem(26).getType());
        slots.when(() -> CharacterSlotService.shouldShowLockedSlot(2,2)).thenReturn(false); manager.profileView(player,player); assertEquals(Material.GRAY_STAINED_GLASS_PANE,top().getItem(12).getType());
        when(data.getCharacters(Status.ALIVE)).thenReturn(List.of(character,character,character)); slots.when(() -> CharacterSlotService.getMaxAliveCharacters(player)).thenReturn(1);
        manager.profileView(player,player); assertTrue(lore(top().getItem(10)).contains("Over slot limit")); assertTrue(lore(top().getItem(12)).contains("Over slot limit"));
    }

    @Test void selectionViewsRenderLabelsOptionsAndRefreshSelectionState() {
        var stage=mock(SelectionStage.class); when(stage.getSize()).thenReturn(27); when(stage.getSlots()).thenReturn(List.of(10,11,12));
        var option=option("human","race",false); when(stage.getOptions()).thenReturn(List.of(option)); when(stage.getKey()).thenReturn("race");
        manager.selectionView(player,stage,creation); assertEquals(Material.RED_CONCRETE,top().getItem(10).getType()); assertNull(top().getItem(11)); assertEquals("CONFIRM",name(top().getItem(26))); assertEquals("Cancel Creation",name(top().getItem(18)));
        assertSame(stage,((RPCHolder)top().getHolder()).getStage()); when(option.isSelected()).thenReturn(true); manager.selectionUpdate(top(),player,stage,creation); assertEquals(Material.GREEN_CONCRETE,top().getItem(10).getType());
        when(stage.getKey()).thenReturn(" "); when(stage.getTarget()).thenReturn("race"); manager.selectionView(player,stage,null); assertTrue(plain(player.getOpenInventory().getTitle()).contains("Race Selection")); assertNull(top().getItem(18));
        when(stage.getKey()).thenReturn(null); when(stage.getTarget()).thenReturn(null); manager.selectionView(player,stage,null); assertTrue(plain(player.getOpenInventory().getTitle()).contains("Selection Selection"));
    }

    @Test void attributeViewsClearStaleItemsAndPlaceControlsInConfiguredSlots() {
        var stage=attributeStage(); when(stage.getAttributes()).thenReturn(List.of("strength","unmapped")); when(stage.getSheetSlots("unmapped")).thenReturn(new int[]{-1,-1,-1});
        manager.attributesView(player,stage,creation); assertEquals("plus",tag(top().getItem(11),"attr_action")); assertEquals("strength",tag(top().getItem(20),"attr_id")); assertEquals("minus",tag(top().getItem(29),"attr_action"));
        assertEquals(Material.EXPERIENCE_BOTTLE,top().getItem(4).getType()); assertEquals("Cancel Creation",name(top().getItem(45)));
        top().setItem(0,new ItemStack(Material.DIAMOND)); when(stage.getRemaining()).thenReturn(0); manager.attributesUpdate(top(),player,stage,null);
        assertNull(top().getItem(0)); assertEquals("Cancel",name(top().getItem(45))); assertEquals(Material.LIME_DYE,top().getItem(53).getType());
    }

    @Test void confirmViewHasOnlyTwoActionsAndFillsUnusedSlots() {
        manager.confirmView(player); assertFilled(top()); assertEquals(Material.GREEN_CONCRETE,top().getItem(11).getType()); assertEquals("Confirm",name(top().getItem(11))); assertEquals(Material.RED_CONCRETE,top().getItem(15).getType()); assertEquals("Cancel",name(top().getItem(15)));
    }

    @Test void creationSummaryRendersAllEntryTypesAndPreservesClassTemplate() throws Exception {
        var classes=installMmo(); var warrior=mock(PlayerClass.class); var template=new ItemStack(Material.DIAMOND_SWORD); when(warrior.getIcon()).thenReturn(template); when(warrior.getName()).thenReturn("Knight"); when(classes.get("warrior")).thenReturn(warrior);
        var attributeStage=attributeStage(); StageLoader.oList.add(mock(Stage.class)); StageLoader.oList.add(attributeStage); Cache.attributes=Arrays.asList(null," ","strength");
        var rank1=trait(AttributesStage.traitId("strength",1),"attributes"); var rank2=trait(AttributesStage.traitId("strength",2),"attributes"); var profession=trait("artisan","profession");
        when(character.getTraits()).thenReturn(List.of(rank1,rank2,profession)); when(character.getPersonaDescription()).thenReturn("A traveller with a detailed description that spans several short lines for the summary.");
        var summary=summary("name","name-stage","class","class-stage","race","race-stage","age","age-stage","description","description-stage","clues","clues","attributes","attribute-stage","profession","profession-stage","skip","clues");
        manager.creationSummaryView(player,creation,summary); assertFilled(top());
        assertEquals("edit:name-stage",tag(top().getItem(10),"summary_action")); assertEquals(Material.DIAMOND_SWORD,top().getItem(11).getType()); assertTrue(lore(top().getItem(11)).contains("Knight")); assertFalse(template.getItemMeta().hasDisplayName());
        assertTrue(lore(top().getItem(12)).contains("Human")); assertTrue(lore(top().getItem(13)).contains("Birthday:")); assertTrue(lore(top().getItem(14)).contains("several short lines"));
        assertEquals("clues",tag(top().getItem(15),"summary_action")); assertFalse(lore(top().getItem(15)).contains("Click to change")); assertTrue(lore(top().getItem(16)).contains("Strength +2")); assertTrue(lore(top().getItem(19)).contains("artisan"));
        assertEquals(Material.GRAY_STAINED_GLASS_PANE,top().getItem(20).getType()); assertEquals(Material.LIME_CONCRETE,top().getItem(53).getType()); assertEquals("confirm",tag(top().getItem(53),"summary_action")); assertEquals("cancel",tag(top().getItem(45),"summary_action"));
    }

    @Test void summaryDefaultsCoverMissingSelectionsAndEveryRequiredConfirmationField() {
        var empty=summary();
        when(creation.getCharacter()).thenReturn(null); manager.creationSummaryView(player,creation,empty); assertEquals(Material.RED_CONCRETE,top().getItem(53).getType()); when(creation.getCharacter()).thenReturn(character);
        players.when(() -> PlayerManager.get(player)).thenReturn(null); manager.creationSummaryView(player,creation,empty); assertEquals(Material.RED_CONCRETE,top().getItem(53).getType()); players.when(() -> PlayerManager.get(player)).thenReturn(data);
        slots.when(() -> CharacterSlotService.hasFreeSlot(player,data)).thenReturn(false); manager.creationSummaryView(player,creation,empty); assertEquals(Material.RED_CONCRETE,top().getItem(53).getType()); slots.when(() -> CharacterSlotService.hasFreeSlot(player,data)).thenReturn(true);
        when(character.hasEnoughClues()).thenReturn(false); manager.creationSummaryView(player,creation,empty); assertEquals(Material.RED_CONCRETE,top().getItem(53).getType()); when(character.hasEnoughClues()).thenReturn(true);
        when(character.getName()).thenReturn(" "); manager.creationSummaryView(player,creation,empty); assertEquals(Material.RED_CONCRETE,top().getItem(53).getType()); when(character.getName()).thenReturn("Aria");
        when(character.hasMMOClass()).thenReturn(false); manager.creationSummaryView(player,creation,empty); assertEquals(Material.RED_CONCRETE,top().getItem(53).getType()); when(character.hasMMOClass()).thenReturn(true);
        when(character.getRace()).thenReturn(null); manager.creationSummaryView(player,creation,empty); assertEquals(Material.RED_CONCRETE,top().getItem(53).getType()); when(character.getRace()).thenReturn(race);
        when(character.getBirthday()).thenReturn(""); manager.creationSummaryView(player,creation,empty); assertEquals(Material.RED_CONCRETE,top().getItem(53).getType()); when(character.getBirthday()).thenReturn("1/1/1700");
        when(character.getPersonaDescription()).thenReturn(" "); manager.creationSummaryView(player,creation,empty); assertEquals(Material.RED_CONCRETE,top().getItem(53).getType());
        when(character.getName()).thenReturn(null); when(character.hasMMOClass()).thenReturn(false); when(character.getRace()).thenReturn(null); when(character.getTraits()).thenReturn(null);
        manager.creationSummaryView(player,creation,summary("name","name","class","class","race","race","description","desc","attributes","attrs","profession","profession"));
        assertTrue(lore(top().getItem(10)).contains("Not set")); assertTrue(lore(top().getItem(11)).contains("Not selected")); assertTrue(lore(top().getItem(12)).contains("Not selected")); assertTrue(lore(top().getItem(13)).contains("Not set")); assertTrue(lore(top().getItem(14)).contains("Not selected")); assertTrue(lore(top().getItem(15)).contains("Not selected"));
        Cache.attributes=List.of("strength"); manager.creationSummaryView(player,creation,summary("attributes","attrs")); assertTrue(lore(top().getItem(10)).contains("Not selected"));
    }

    @Test void editingSummaryMakesLockedEntriesUnclickableUnlessPaidAndExplainsCooldowns() {
        when(creation.isEditing()).thenReturn(true); var nameStage=mock(Stage.class); when(nameStage.getId()).thenReturn("name-stage"); StageLoader.oList.add(nameStage);
        locks.when(() -> StageEditLock.canEdit(player,nameStage,character)).thenReturn(false); var summary=summary("name","name-stage","clues","clues");
        manager.creationSummaryView(player,creation,summary); assertNull(tag(top().getItem(10),"summary_action")); assertTrue(lore(top().getItem(10)).contains("Locked")); assertEquals("Done",name(top().getItem(53)));
        paid.when(() -> PaidChangeService.canPayToOpen(nameStage)).thenReturn(true); paid.when(() -> PaidChangeService.summaryLore(nameStage,character,true)).thenReturn(List.of("Pay 100 denars"));
        manager.creationSummaryView(player,creation,summary); assertEquals("edit:name-stage",tag(top().getItem(10),"summary_action")); assertTrue(lore(top().getItem(10)).contains("Pay 100"));
        locks.when(() -> StageEditLock.canEdit(player,nameStage,character)).thenReturn(true); locks.when(() -> StageEditLock.lockLore(nameStage,character)).thenReturn("Reusable tomorrow");
        manager.creationSummaryView(player,creation,summary); assertTrue(lore(top().getItem(10)).contains("Reusable tomorrow")); assertEquals("clues",tag(top().getItem(11),"summary_action"));
    }

    @Test void summaryCapacityAndMissingClassDefinitionAreHandledWithoutMutatingEntries() throws Exception {
        installMmo(); var entries=new LinkedHashMap<String,String>(); for(int i=0;i<35;i++) entries.put("entry_"+i,"stage-"+i);
        var summary=mock(SummaryStage.class); when(summary.getEntries()).thenReturn(entries); manager.creationSummaryView(player,creation,summary);
        assertEquals("edit:stage-27",tag(top().getItem(43),"summary_action")); assertEquals(35,entries.size());
        manager.creationSummaryView(player,creation,summary("class","class")); assertEquals(Material.NETHERITE_SWORD,top().getItem(10).getType()); assertTrue(lore(top().getItem(10)).contains("warrior"));
    }

    @Test void classButtonAndClassSummaryEntryOpenTheClassPicker() throws Exception {
        var classes=installMmo(); var warrior=mock(PlayerClass.class); when(warrior.getName()).thenReturn("Warrior"); when(classes.get("warrior")).thenReturn(warrior);
        classGui.when(() -> MmoCoreClassGuiHelper.formatLine(anyString())).thenAnswer(call -> call.getArgument(0)); when(character.isActive()).thenReturn(true);
        manager.characterView(player,character); var button=top().getItem(InventoryManager.CLASS_PICK_SLOT);
        assertEquals(Material.NETHERITE_SWORD,button.getType()); assertTrue(lore(button).contains("Warrior")); assertTrue(button.getItemMeta().getPersistentDataContainer().has(key(InventoryManager.OPEN_CLASS_PICK_KEY)));
        manager.characterView(other,character); assertEquals(Material.GRAY_STAINED_GLASS_PANE,other.getOpenInventory().getTopInventory().getItem(InventoryManager.CLASS_PICK_SLOT).getType());
        when(character.hasMMOClass()).thenReturn(false); assertTrue(lore(manager.getClassPickItem(character)).contains("Not selected")); when(character.hasMMOClass()).thenReturn(true);
        when(creation.isEditing()).thenReturn(true); var classStage=mock(SelectionStage.class); when(classStage.getId()).thenReturn("class-stage"); when(classStage.getTarget()).thenReturn("class"); StageLoader.oList.add(classStage);
        locks.when(() -> StageEditLock.canEdit(player,classStage,character)).thenReturn(false);
        manager.creationSummaryView(player,creation,summary("class","class-stage"));
        assertEquals("edit:class-stage",tag(top().getItem(10),"summary_action")); assertTrue(lore(top().getItem(10)).contains("class picker")); assertTrue(lore(top().getItem(10)).contains("First subclass"));
        try {
            ClassPickService.configure(new ClassPickService.Settings(false,false,java.math.BigDecimal.ZERO,List.of(),Set.of()));
            manager.characterView(player,character); assertEquals(Material.GRAY_STAINED_GLASS_PANE,top().getItem(InventoryManager.CLASS_PICK_SLOT).getType());
            manager.creationSummaryView(player,creation,summary("class","class-stage")); assertNull(tag(top().getItem(10),"summary_action")); assertTrue(lore(top().getItem(10)).contains("Locked"));
        } finally {
            ClassPickService.configure(ClassPickService.Settings.DEFAULTS);
        }
    }

    @Test void attributeSummaryIdentifiersAreIndependentOfDefaultLocale() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR")); Cache.attributes=List.of("intelligence");
        var rank=trait(AttributesStage.traitId("intelligence",1),"attributes"); when(character.getTraits()).thenReturn(List.of(rank));
        manager.creationSummaryView(player,creation,summary("ATTRIBUTES","attribute-stage"));
        assertTrue(lore(top().getItem(10)).contains("Intelligence +1"),"Uppercase config identifiers must use locale-independent matching");
    }

    @Test void ageSummaryRetainsLockedAndPaidChangeInstructions() {
        when(creation.isEditing()).thenReturn(true); var ageStage=mock(Stage.class); when(ageStage.getId()).thenReturn("age-stage"); StageLoader.oList.add(ageStage);
        locks.when(() -> StageEditLock.canEdit(player,ageStage,character)).thenReturn(false);
        manager.creationSummaryView(player,creation,summary("age","age-stage"));
        assertTrue(lore(top().getItem(10)).contains("Locked")); assertFalse(lore(top().getItem(10)).contains("Click to change")); assertNull(tag(top().getItem(10),"summary_action"));
        paid.when(() -> PaidChangeService.summaryLore(ageStage,character,true)).thenReturn(List.of("Pay 100 denars to change age")); paid.when(() -> PaidChangeService.canPayToOpen(ageStage)).thenReturn(true);
        manager.creationSummaryView(player,creation,summary("age","age-stage"));
        assertTrue(lore(top().getItem(10)).contains("Pay 100 denars")); assertEquals("edit:age-stage",tag(top().getItem(10),"summary_action"));
    }

    private Integer clueIndex(ItemStack item) {return item==null?null:item.getItemMeta().getPersistentDataContainer().get(key("clue_index"),PersistentDataType.INTEGER);}
    private SummaryStage summary(String... entries) {var result=mock(SummaryStage.class); var map=new LinkedHashMap<String,String>(); for(int i=0;i<entries.length;i+=2) map.put(entries[i],entries[i+1]); when(result.getEntries()).thenReturn(map); return result;}
}
