package net.tfminecraft.rpcharacters.mmocore;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import net.Indyuce.mmocore.api.player.attribute.PlayerAttribute;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.utils.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedConstruction;

class AttributePointServiceTest extends MmoServiceFixture {
    MockedConstruction<Integrator> integrators;
    @BeforeEach void setupAttributeService(){integrators=mockConstruction(Integrator.class,(integrator,context)->doAnswer(c->{instances.forEach((id,instance)->{if(!IgnoredAttributes.isIgnored(id))instance.setBase(creationBases.getOrDefault(id,0));});return null;}).when(integrator).integrate(player,character));closeables.add(integrators);}

    @Test void nullableInputsAndMissingAccountNeverTouchMmoData(){
        assertEquals(0,AttributePointService.captureAllocationFromMmo(null,character));assertEquals(0,AttributePointService.captureAllocationFromMmo(player,null));AttributePointService.applyAllocationToMmo(null,character);AttributePointService.applyAllocationToMmo(player,null);AttributePointService.applyFreeAttributePoints(null,character);AttributePointService.applyFreeAttributePoints(player,null);AttributePointService.applyCharacterAttributes(null,character);AttributePointService.applyCharacterAttributes(player,null);AttributePointService.clearMmoAttributeBases(null);AttributePointService.syncOnDeactivate(null);AttributePointService.syncOnActivate(null);var ownerless=mock(RPCharacter.class);AttributePointService.syncOnDeactivate(ownerless);AttributePointService.syncOnActivate(ownerless);AttributePointService.grantAttributePoints(null,1);AttributePointService.grantAttributePoints(player,0);AttributePointService.grantAttributePoints(player,-1);AttributePointService.migrateAttributePointsIfNeeded(null,account);AttributePointService.migrateAttributePointsIfNeeded(player,null);AttributePointService.migrateAttributePointsIfNeeded(player,account);AttributePointService.syncAttributePoints(null);AttributePointService.scheduleSyncAttributePoints(null);AttributePointService.refreshAfterCreationLayerChange(null,character);AttributePointService.refreshAfterCreationLayerChange(player,null);AttributePointService.clampExcessAttributePool(player,null);
        players.when(() -> PlayerManager.get(player)).thenReturn(null);AttributePointService.applyFreeAttributePoints(player,character);AttributePointService.syncOnActivate(character);AttributePointService.grantAttributePoints(player,1);AttributePointService.syncAttributePoints(player);AttributePointService.clampExcessAttributePool(player,character);verify(manager,never()).savePlayer(any());assertTrue(integrators.constructed().isEmpty());assertTrue(later.isEmpty());
    }

    @Test void captureSeparatesCreationBaseExtraAllocationAndIgnoredAttributes(){
        instance("strength",7,3);instance("dexterity",2,4);instance("ignored",99,0);Cache.ignoredAttributes.add("ignored");allocation.set(new HashMap<>(Map.of("stale",8)));assertEquals(4,AttributePointService.captureAllocationFromMmo(player,character));assertEquals(Map.of("strength",4),allocation.get());
    }

    @Test void allocationKeysAreLocaleIndependent(){Locale.setDefault(Locale.forLanguageTag("tr-TR"));instance("INTELLIGENCE",7,3);assertEquals(4,AttributePointService.captureAllocationFromMmo(player,character));assertEquals(Map.of("intelligence",4),allocation.get());}

    @Test void captureCannotWrapCombinedAttributeInvestment(){
        instance("strength",Integer.MAX_VALUE,0);instance("dexterity",Integer.MAX_VALUE,0);assertEquals(Integer.MAX_VALUE,AttributePointService.captureAllocationFromMmo(player,character));assertEquals(Map.of("strength",Integer.MAX_VALUE,"dexterity",Integer.MAX_VALUE),allocation.get());
    }

    @Test void applicationAddsValidExtrasOnceWhenRebuildingAndLeavesIgnoredBasesIntact(){
        var strength=instance("strength",99,3);var ignored=instance("ignored",55,0);Cache.ignoredAttributes.add("ignored");instance("zero",2,0);instance("negative",3,0);instance("null-value",1,0);Map<String,Integer> extra=new HashMap<>();extra.put("strength",4);extra.put("ignored",5);extra.put("missing",2);extra.put("zero",0);extra.put("negative",-1);extra.put("null-value",null);allocation.set(extra);AttributePointService.applyAllocationToMmo(player,character);assertEquals(103,strength.getBase());assertEquals(55,ignored.getBase());
        allocation.set(new HashMap<>(Map.of("strength",4)));accountAttributes.set(10);AttributePointService.applyCharacterAttributes(player,character);assertEquals(7,strength.getBase());assertEquals(55,ignored.getBase());assertEquals(6,attributePointsBalance.get());AttributePointService.applyCharacterAttributes(player,character);assertEquals(7,strength.getBase());verify(integrators.constructed().getFirst()).integrate(player,character);accountAttributes.set(1);AttributePointService.applyFreeAttributePoints(player,character);assertEquals(0,attributePointsBalance.get());
    }

    @Test void activationAndDeactivationPreserveAllocationButClearRuntimeBases(){
        var strength=instance("strength",8,3);var ignored=instance("ignored",20,0);Cache.ignoredAttributes.add("ignored");attributePointsBalance.set(2);AttributePointService.syncOnDeactivate(character);assertEquals(Map.of("strength",5),allocation.get());assertEquals(0,strength.getBase());assertEquals(20,ignored.getBase());assertEquals(0,attributePointsBalance.get());verify(integrators.constructed().getFirst()).stripCreationLayer(player,character);verify(manager).savePlayer(player);accountAttributes.set(4);AttributePointService.syncOnActivate(character);assertEquals(Map.of("strength",4),allocation.get());assertEquals(7,strength.getBase());assertEquals(0,attributePointsBalance.get());
    }

    @Test void grantsAndMigrationUseSpentPlusUnspentAndPersistOnlyWhenNeeded(){
        instance("strength",5,2);attributePointsBalance.set(4);when(account.needsAttributePointsMigration()).thenReturn(true);AttributePointService.migrateAttributePointsIfNeeded(player,account);assertEquals(7,accountAttributes.get());assertEquals(Map.of("strength",3),allocation.get());AttributePointService.grantAttributePoints(player,2);assertEquals(9,accountAttributes.get());assertEquals(6,attributePointsBalance.get());verify(manager,times(2)).savePlayer(player);
        when(account.getActiveCharacter()).thenReturn(null);attributePointsBalance.set(5);AttributePointService.migrateAttributePointsIfNeeded(player,account);assertEquals(5,accountAttributes.get());AttributePointService.grantAttributePoints(player,2);assertEquals(7,accountAttributes.get());verify(manager,times(4)).savePlayer(player);AttributePointService.syncAttributePoints(player);
    }

    @Test void migrationCannotWrapLargeAttributePoolsIntoNegativeLifetimePoints(){
        instance("strength",1,0);attributePointsBalance.set(Integer.MAX_VALUE);when(account.needsAttributePointsMigration()).thenReturn(true);AttributePointService.migrateAttributePointsIfNeeded(player,account);assertEquals(Integer.MAX_VALUE,accountAttributes.get(),"Combining spent and unspent points cannot erase the lifetime pool through int overflow");
    }

    @Test void excessiveUnspentAttributePointsAreClampedWithoutOneWritePerPoint(){
        attributePointsBalance.set(Integer.MAX_VALUE);accountAttributes.set(2);var writes=new java.util.concurrent.atomic.AtomicInteger();doAnswer(call->{assertTrue(writes.incrementAndGet()<=1000,"Clamping a large free pool must not block the server with one setter call per point");attributePointsBalance.set(call.getArgument(0));return null;}).when(mmo).setAttributePoints(anyInt());AttributePointService.clampExcessAttributePool(player,character);assertEquals(2,attributePointsBalance.get());assertTrue(writes.get()<10);
    }

    @Test void realAccountAttributeGrantSaturatesRatherThanErasingItsBalance(){
        var realAccount=new net.tfminecraft.rpcharacters.objects.PlayerData(owner);realAccount.setAccountAttributePointsTotal(Integer.MAX_VALUE-1);players.when(() -> PlayerManager.get(player)).thenReturn(realAccount);AttributePointService.grantAttributePoints(player,10);assertEquals(Integer.MAX_VALUE,realAccount.getAccountAttributePointsTotal());realAccount.addAccountAttributePoints(Integer.MAX_VALUE);assertEquals(Integer.MAX_VALUE,realAccount.getAccountAttributePointsTotal());verify(manager).savePlayer(player);
    }

    @Test void realCharacterCombinedAttributeInvestmentNeverWrapsNegative(){
        var realCharacter=new RPCharacter(player);realCharacter.setExtraAttributeAllocation(Map.of("strength",Integer.MAX_VALUE,"dexterity",Integer.MAX_VALUE));assertEquals(Integer.MAX_VALUE,realCharacter.getSpentExtraAttributePoints());assertEquals(Map.of("strength",Integer.MAX_VALUE,"dexterity",Integer.MAX_VALUE),realCharacter.getExtraAttributeAllocation());
    }

    @Test void syncAndDelayedSyncReconcilePoolsAndReportAllocation(){
        instance("strength",5,2);attributePointsBalance.set(4);accountAttributes.set(0);Cache.attributePointsAdminDebugMessages=true;AttributePointService.syncAttributePoints(player,player);assertEquals(7,accountAttributes.get());assertEquals(4,attributePointsBalance.get());texts.verify(() -> RPTexts.sendPrefixed(eq(player),contains("strength +3")));clearInvocations(manager);AttributePointService.syncAttributePoints(player);verify(manager,never()).savePlayer(player);instances.clear();AttributePointService.syncAttributePoints(player,player);texts.verify(() -> RPTexts.sendPrefixed(eq(player),contains("Allocation: ")),times(2));texts.verify(() -> RPTexts.sendPrefixed(eq(player),contains("none")));Cache.attributePointsAdminDebugMessages=false;AttributePointService.syncAttributePoints(player,player);
        AttributePointService.scheduleSyncAttributePoints(player);AttributePointService.scheduleSyncAttributePoints(player,player);assertEquals(2,later.size());when(player.isOnline()).thenReturn(false);later.removeFirst().run();when(player.isOnline()).thenReturn(true);later.removeFirst().run();assertEquals(accountAttributes.get(),attributePointsBalance.get());
    }

    @Test void clampingRemovesLargestAllocationsWithoutLoweringCreationBases(){
        var strength=instance("strength",5,3);var dexterity=instance("dexterity",1,1);allocation.set(new HashMap<>(Map.of("strength",3,"dexterity",1,"missing",1)));accountAttributes.set(0);attributePointsBalance.set(3);AttributePointService.clampExcessAttributePool(player,character);assertTrue(allocation.get().isEmpty());assertEquals(3,strength.getBase());assertEquals(1,dexterity.getBase());assertEquals(0,attributePointsBalance.get());
        Cache.ignoredAttributes.add("ignored");Map<String,Integer> ignored=new HashMap<>();ignored.put("ignored",3);ignored.put("null",null);ignored.put("zero",0);allocation.set(ignored);AttributePointService.clampExcessAttributePool(player,character);assertEquals(3,allocation.get().get("ignored"));assertEquals(0,attributePointsBalance.get());
    }

    @Test void creationLayerRefreshCapturesExistingInvestmentBeforeRecalculatingFreePoints(){
        var strength=instance("strength",9,4);accountAttributes.set(3);attributePointsBalance.set(6);AttributePointService.refreshAfterCreationLayerChange(player,character);assertEquals(Map.of("strength",3),allocation.get());assertEquals(7,strength.getBase());assertEquals(0,attributePointsBalance.get());AttributePointService.clearMmoAttributeBases(player);assertEquals(0,strength.getBase());
    }

    @Test void attributeDefinitionQueriesResolveNamesNormalizeAndFailSoft(){
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));assertFalse(MmoCoreAttributeHelper.exists(null));assertFalse(MmoCoreAttributeHelper.exists(" "));assertEquals("",MmoCoreAttributeHelper.displayName(null));assertEquals("",MmoCoreAttributeHelper.displayName(" "));when(attributeManager.has("intelligence")).thenReturn(true);assertTrue(MmoCoreAttributeHelper.exists(" INTELLIGENCE "));assertFalse(MmoCoreAttributeHelper.exists("unknown"));var definition=mock(PlayerAttribute.class);when(definition.getName()).thenReturn("§aIntelligence");when(attributeManager.get("intelligence")).thenReturn(definition);assertEquals("§aIntelligence",MmoCoreAttributeHelper.displayName(" INTELLIGENCE "));when(definition.getName()).thenReturn(" ");assertEquals("Intelligence",MmoCoreAttributeHelper.displayName("intelligence"));when(definition.getName()).thenReturn(null);assertEquals("Intelligence",MmoCoreAttributeHelper.displayName("intelligence"));assertEquals("Unknown",MmoCoreAttributeHelper.displayName("unknown"));when(attributeManager.get("broken")).thenThrow(new IllegalStateException("gone"));assertEquals("Broken",MmoCoreAttributeHelper.displayName("broken"));when(attributeManager.has("broken")).thenThrow(new IllegalStateException("gone"));assertFalse(MmoCoreAttributeHelper.exists("broken"));verify(logger).warning(contains("could not query MMOCore"));
    }
}
