package net.tfminecraft.rpcharacters.creation.stages;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.creation.Stage;
import net.tfminecraft.rpcharacters.objects.attributes.*;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AttributesStageTest extends StageFixture {
    @Test void defaultAndCopiedStagesPreserveConfigurationButResetRankState() {
        var stage=new AttributesStage(base(),config());assertEquals("attributes",stage.getKey());assertEquals(12,stage.getPool());assertEquals(4,stage.getMaxRank());assertEquals(54,stage.getSize());assertEquals(List.of("strength","dexterity"),stage.getAttributes());assertEquals(20,stage.getCenterSlot("strength"));assertEquals(21,stage.getCenterSlot("dexterity"));assertFalse(stage.isActive());stage.setActive(true);assertTrue(stage.isActive());assertTrue(stage.tryIncrease("strength"));var copy=new AttributesStage(stage);assertEquals(stage.getAttributes(),copy.getAttributes());assertNotSame(stage.getAttributes(),copy.getAttributes());assertEquals(0,copy.getRank("strength"));assertEquals(12,copy.getRemaining());assertEquals("stage",copy.getId());assertFalse(copy.isActive());assertEquals(1,stage.getRank("strength"));
    }

    @Test void listAndFallbackAttributesNormalizeFilterUnknownsAndBoundSheetSlots() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));var stage=new AttributesStage(base(),config("attributes",Arrays.asList(null," "," INTELLIGENCE ","missing","strength","dexterity","constitution","wisdom","charisma","luck"),"gui-size",54,"points",7,"max-rank",3,"key","custom"));assertEquals(List.of("intelligence","strength","dexterity","constitution","wisdom","charisma","luck"),stage.getAttributes());assertEquals(22,stage.getCenterSlot("luck"));assertEquals("custom",stage.getKey());assertEquals(7,stage.getPool());assertEquals(3,stage.getMaxRank());assertArrayEquals(new int[]{11,20,29},stage.getSheetSlots(" INTELLIGENCE "));assertEquals(-1,stage.getCenterSlot(null));assertArrayEquals(new int[]{-1,-1,-1},stage.getSheetSlots("unknown"));verify(logger).warning(contains("unknown MMOCore attribute 'missing'"));
        Cache.attributes=new ArrayList<>(Arrays.asList(null," ","missing"," STRENGTH "));var fallback=new AttributesStage(base(),config("attributes",List.of()));assertEquals(List.of("strength"),fallback.getAttributes());var tooSmall=new AttributesStage(base(),config("gui-size",9));assertArrayEquals(new int[]{-1,-1,-1},tooSmall.getSheetSlots("strength"));
    }

    @Test void mappedSlotsSupportNestedNumbersAndSafeFallbacks() {
        var stage=new AttributesStage(base(),config("attributes.strength.slot",20,"attributes.dexterity",0,"attributes.constitution",45,"attributes.intelligence",99,"attributes.wisdom","invalid","attributes.charisma.slot",-1,"attributes.missing",12,"attributes. ",10));assertEquals(20,stage.getCenterSlot("strength"));assertArrayEquals(new int[]{-1,0,-1},stage.getSheetSlots("dexterity"));assertArrayEquals(new int[]{-1,45,-1},stage.getSheetSlots("constitution"));assertEquals(23,stage.getCenterSlot("intelligence"));assertEquals(24,stage.getCenterSlot("wisdom"));assertEquals(25,stage.getCenterSlot("charisma"));assertFalse(stage.getAttributes().contains("missing"));assertFalse(stage.getAttributes().contains(""));verify(logger,times(3)).warning(contains("missing/invalid slot"));
    }

    @Test void rankCostsAndAbbreviationsRemainLocaleIndependent() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));assertEquals("int",AttributesStage.abbrevFor(" INTELLIGENCE "));assertEquals("str",AttributesStage.abbrevFor("strength"));assertEquals("dex",AttributesStage.abbrevFor("dexterity"));assertEquals("con",AttributesStage.abbrevFor("constitution"));assertEquals("wis",AttributesStage.abbrevFor("wisdom"));assertEquals("cha",AttributesStage.abbrevFor("charisma"));assertEquals("luc",AttributesStage.abbrevFor("luck"));assertEquals("qi",AttributesStage.abbrevFor("qi"));assertEquals("",AttributesStage.abbrevFor(null));assertEquals("int3",AttributesStage.traitId("intelligence",3));assertEquals(0,AttributesStage.costForRank(-1));assertEquals(0,AttributesStage.costForRank(0));assertEquals(1,AttributesStage.costForRank(1));assertEquals(8,AttributesStage.costForRank(4));
    }

    @Test void purchasesRefundsLimitsAndAffordabilityKeepExactBudget() {
        var stage=new AttributesStage(base(),config("points",4,"max-rank",2));assertFalse(stage.tryIncrease("unknown"));assertFalse(stage.tryDecrease(null));assertFalse(stage.tryDecrease("strength"));assertTrue(stage.tryIncrease(" STRENGTH "));assertEquals(3,stage.getRemaining());assertTrue(stage.tryIncrease("strength"));assertEquals(1,stage.getRemaining());assertFalse(stage.tryIncrease("strength"));assertEquals(2,stage.getRank("strength"));assertEquals(3,stage.spentPoints());assertTrue(stage.tryIncrease("dexterity"));assertFalse(stage.tryIncrease("dexterity"));assertEquals(0,stage.getRemaining());assertTrue(stage.tryDecrease("strength"));assertEquals(2,stage.getRemaining());assertEquals(1,stage.getRank("strength"));assertEquals(2,stage.spentPoints());assertTrue(stage.tryDecrease("strength"));assertFalse(stage.tryDecrease("strength"));assertEquals(3,stage.getRemaining());
    }

    @Test void hydrationCountsContiguousRanksAndClearsPreviousEdits() {
        var stage=new AttributesStage(base(),config("points",7,"max-rank",3));characterTraits.addAll(List.of(trait("str1","attributes",0,false),trait("STR2","attributes",0,false),trait("dex2","attributes",0,false)));stage.hydrateFromCharacter(character);assertEquals(2,stage.getRank("strength"));assertEquals(0,stage.getRank("dexterity"));assertEquals(4,stage.getRemaining());assertEquals(3,stage.spentPoints());assertTrue(stage.tryIncrease("dexterity"));stage.hydrateFromCharacter(character);assertEquals(0,stage.getRank("dexterity"));stage.hydrateFromCharacter(null);assertEquals(0,stage.spentPoints());assertEquals(7,stage.getRemaining());
    }

    @Test void confirmationRequiresEveryPointAndReplacesOnlyAttributeTraits() {
        var stage=new AttributesStage(base(),config("points",3));stage.execute(player,creation);assertTrue(stage.isActive());verify(inventories.constructed().getFirst()).attributesView(player,stage,creation);stage.confirm(player,creation);texts.verify(() -> RPTexts.send(eq(player),contains("Spend all 3")));verify(player,never()).closeInventory();
        Trait old=trait("old","attributes",0,false),unrelated=trait("social","social",0,false),withoutKey=trait("unkeyed",null,0,false),one=trait("str1","attributes",0,false),two=trait("str2","attributes",0,false);AttributeData first=new AttributeData();first.clearAll();first.addModifier(new AttributeModifier("strength",1));when(one.getTraitData().getAttributeData()).thenReturn(first);AttributeData second=new AttributeData();second.clearAll();second.addModifier(new AttributeModifier("strength",1));when(two.getTraitData().getAttributeData()).thenReturn(second);characterTraits.addAll(List.of(old,unrelated,withoutKey));assertTrue(stage.tryIncrease("strength"));assertTrue(stage.tryIncrease("strength"));stage.confirm(player,creation);assertFalse(stage.isActive());assertEquals(List.of(unrelated,withoutKey,one,two),characterTraits);ArgumentCaptor<AttributeData> contribution=ArgumentCaptor.forClass(AttributeData.class);verify(creation).setAttributeStageContribution(eq("attributes"),contribution.capture());assertEquals(2,contribution.getValue().getAmount(new AttributeModifier("strength",0)));assertEquals(1,later.size());runLater();verify(creation).runStage();
    }

    @Test void confirmationRoutesSummaryAndManualCompletionAndSupportsStandaloneView() {
        var stage=new AttributesStage(base(),config("points",0));stage.execute(player,null);assertTrue(stage.isActive());stage.confirm(player,null);assertFalse(stage.isActive());assertTrue(later.isEmpty());stage.setAutoNext(false);stage.confirm(player,creation);runLater();verify(creation).setCanNext(true);when(creation.isEditingFromSummary()).thenReturn(true);stage.confirm(player,creation);runLater();verify(creation).returnToSummary();when(creation.isCancelled()).thenReturn(true);stage.execute(player,creation);assertFalse(stage.isActive());assertEquals(1,inventories.constructed().size());
    }

    @Test void missingConfiguredRankTraitCannotDiscardExistingAttributesOrAdvanceCreation() {
        Trait old=trait("old","attributes",0,false);characterTraits.add(old);var stage=new AttributesStage(base(),config("points",1));assertTrue(stage.tryIncrease("strength"));stage.execute(player,creation);stage.confirm(player,creation);assertEquals(List.of(old),characterTraits,"Validate every replacement trait before removing the character's current attribute contribution");assertTrue(stage.isActive());assertTrue(later.isEmpty());verify(creation,never()).setAttributeStageContribution(any(),any());texts.verify(() -> RPTexts.send(eq(player),contains("Missing attribute trait str1")));
    }

    @Test void overBudgetHydrationMustNotPretendEveryPointWasSpentLegally() {
        characterTraits.addAll(List.of(trait("str1","attributes",0,false),trait("str2","attributes",0,false)));var stage=new AttributesStage(base(),config("points",1));stage.hydrateFromCharacter(character);assertEquals(3,stage.spentPoints());assertEquals(-2,stage.getRemaining(),"Existing overspend must remain visible until refunded");stage.confirm(player,creation);assertTrue(later.isEmpty());assertTrue(stage.tryDecrease("strength"));assertEquals(0,stage.getRemaining());
    }

    @Test void duplicateNormalizedAttributeIdsNeverDoubleCountPurchases() {
        var list=new AttributesStage(base(),config("attributes",List.of("strength"," STRENGTH ")));assertEquals(List.of("strength"),list.getAttributes());assertTrue(list.tryIncrease("strength"));assertEquals(1,list.spentPoints());var map=new AttributesStage(base(),config("attributes.strength",20,"attributes. STRENGTH ",21));assertEquals(List.of("strength"),map.getAttributes());Cache.attributes=new ArrayList<>(List.of("strength"," STRENGTH "));assertEquals(List.of("strength"),new AttributesStage(base(),config()).getAttributes());
    }

    @Test void delayedSummaryCompletionCannotResumeACancelledCreation() {
        var stage=new AttributesStage(base(),config("points",0));when(creation.isEditingFromSummary()).thenReturn(true);stage.confirm(player,creation);when(creation.isCancelled()).thenReturn(true);runLater();verify(creation,never()).returnToSummary();verify(creation,never()).runStage();verify(creation,never()).setCanNext(anyBoolean());
    }

    @Test void severalMaximumRankAttributesKeepOverspendVisibleWithoutIntegerWraparound() {
        for(String attribute:List.of("strength","dexterity","constitution"))for(int rank=1;rank<=31;rank++)characterTraits.add(trait(AttributesStage.traitId(attribute,rank),"attributes",0,false));
        var stage=new AttributesStage(base(),config("attributes",List.of("strength","dexterity","constitution"),"points",Integer.MAX_VALUE,"max-rank",31));stage.hydrateFromCharacter(character);
        assertEquals(31,stage.getRank("strength"));assertEquals(31,stage.getRank("dexterity"));assertEquals(31,stage.getRank("constitution"));assertEquals(Integer.MAX_VALUE,stage.spentPoints(),"The int-facing spent total saturates instead of wrapping negative");assertEquals(Integer.MIN_VALUE,stage.getRemaining(),"The long-backed deficit must survive UI int saturation");
        stage.confirm(player,creation);assertTrue(later.isEmpty());verify(creation,never()).setAttributeStageContribution(any(),any());assertEquals(93,characterTraits.size());
        assertTrue(stage.tryDecrease("strength"));assertEquals(Integer.MIN_VALUE,stage.getRemaining());assertTrue(stage.tryDecrease("dexterity"));assertEquals(-2147483646,stage.getRemaining(),"Refunds operate on the original long deficit, not the saturated display value");
        assertEquals(Integer.MAX_VALUE,AttributesStage.costForRank(32));assertEquals(Integer.MAX_VALUE,AttributesStage.costForRank(Integer.MAX_VALUE));
    }

    @Test void highConfiguredRankCannotOverflowCostIntoAFreePurchase() {
        var stage=new AttributesStage(base(),config("attributes",List.of("strength"),"points",Integer.MAX_VALUE,"max-rank",32));for(int rank=1;rank<=31;rank++)assertTrue(stage.tryIncrease("strength"));assertEquals(0,stage.getRemaining());assertFalse(stage.tryIncrease("strength"),"Rank 32 costs more than any positive int budget, never a negative cost");assertEquals(Integer.MAX_VALUE,stage.spentPoints());
    }
}
