package net.tfminecraft.rpcharacters.progress;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import io.lumine.mythic.lib.player.resource.ResourceUpdateReason;
import net.Indyuce.mmocore.MMOCore;
import net.Indyuce.mmocore.api.event.PlayerLevelChangeEvent;
import net.Indyuce.mmocore.api.player.profess.*;
import net.Indyuce.mmocore.api.player.attribute.PlayerAttribute;
import net.Indyuce.mmocore.skill.*;
import net.Indyuce.mmocore.skill.binding.SkillSlot;
import net.Indyuce.mmocore.skilltree.SkillTreeNode;
import net.Indyuce.mmocore.skilltree.tree.SkillTree;
import net.tfminecraft.rpcharacters.mmocore.MmoProgressCodec;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MmoProgressCodecTest extends ProgressFixture {
    @Test void restoredAccessoryModifiersAreResolvedBeforeResourceCapsAndValues() {
        var saved=MmoProgressCodec.fresh(); saved.set("mana",150d);
        var resources=MmoProgressCodec.resources(saved);
        var equippedBonus=new java.util.concurrent.atomic.AtomicReference<Double>(0d);
        var maximum=new java.util.concurrent.atomic.AtomicReference<Double>(20d);
        var mana=new java.util.concurrent.atomic.AtomicReference<Double>(20d);
        doAnswer(c->{equippedBonus.set(300d);return null;}).when(items).resolveInventory();
        var stats=mmo.getStats();
        doAnswer(c->{maximum.set(20d+equippedBonus.get());return null;}).when(stats).updateStats();
        doAnswer(c->{mana.set(Math.min(maximum.get(),c.getArgument(0)));return true;})
                .when(mmo).setMana(anyDouble(),eq(ResourceUpdateReason.CHOOSE_CLASS));
        resources.apply(mmo);
        assertEquals(320d,maximum.get()); assertEquals(150d,mana.get());
        var ordered=inOrder(items,stats,mmo);
        ordered.verify(items).resolveInventory(); ordered.verify(stats).updateStats();
        ordered.verify(mmo).setMana(150d,ResourceUpdateReason.CHOOSE_CLASS);
        doAnswer(c->{equippedBonus.set(0d);return null;}).when(items).resolveInventory();
        resources.apply(mmo);
        assertEquals(20d,maximum.get()); assertEquals(20d,mana.get(),"Removing a ring must remove the outgoing cap too");
    }

    @Test void savedResourcesSurviveEarlyClampingAndCheckpointReplacementButRespectRestoredCaps() {
        var saved=MmoProgressCodec.fresh(); saved.set("mana",150d); saved.set("stamina",140d); saved.set("stellium",130d);
        var resources=MmoProgressCodec.resources(saved);
        var cap=new java.util.concurrent.atomic.AtomicReference<Double>(100d);
        var mana=new java.util.concurrent.atomic.AtomicReference<Double>();
        var stamina=new java.util.concurrent.atomic.AtomicReference<Double>();
        var stellium=new java.util.concurrent.atomic.AtomicReference<Double>();
        doAnswer(c->{mana.set(Math.min(cap.get(),c.getArgument(0)));return true;}).when(mmo).setMana(anyDouble(),eq(ResourceUpdateReason.CHOOSE_CLASS));
        doAnswer(c->{stamina.set(Math.min(cap.get(),c.getArgument(0)));return true;}).when(mmo).setStamina(anyDouble(),eq(ResourceUpdateReason.CHOOSE_CLASS));
        doAnswer(c->{stellium.set(Math.min(cap.get(),c.getArgument(0)));return true;}).when(mmo).setStellium(anyDouble(),eq(ResourceUpdateReason.CHOOSE_CLASS));
        MmoProgressCodec.apply(mmo,saved); assertEquals(100d,mana.get()); assertEquals(100d,stamina.get()); assertEquals(100d,stellium.get());
        saved.set("mana",20d); saved.set("stamina",20d); saved.set("stellium",20d);
        var stats=mmo.getStats(); doAnswer(c->{cap.set(200d);return null;}).when(stats).updateStats();
        resources.apply(mmo); assertEquals(150d,mana.get()); assertEquals(140d,stamina.get()); assertEquals(130d,stellium.get());
        doAnswer(c->{cap.set(125d);return null;}).when(stats).updateStats();
        resources.apply(mmo); assertEquals(125d,mana.get()); assertEquals(125d,stamina.get()); assertEquals(125d,stellium.get());
        for (String key:List.of("mana","stamina","stellium")) {
            for (double invalid:List.of(-1d,Double.NaN,Double.POSITIVE_INFINITY)) {
                var malformed=MmoProgressCodec.fresh(); malformed.set(key,invalid);
                clearInvocations(mmo); assertThrows(IllegalArgumentException.class,()->MmoProgressCodec.resources(malformed)); verifyNoInteractions(mmo);
            }
        }
    }
    @Test void completeMmoStateRoundTripsIncludingDottedIdsSlotsClaimsAndResources() throws Exception {
        when(mmo.getSkillPoints()).thenReturn(13); when(mmo.getAttributePoints()).thenReturn(9);
        when(mmo.getSkillReallocationPoints()).thenReturn(4); when(mmo.getAttributeReallocationPoints()).thenReturn(6);
        when(mmo.getSkillTreeReallocationPoints()).thenReturn(7); when(mmo.getClassPoints()).thenReturn(8);
        when(mmo.getMana()).thenReturn(23d); when(mmo.getStamina()).thenReturn(24d); when(mmo.getStellium()).thenReturn(25d);
        when(mmo.mapSkillLevels()).thenReturn(Map.of("fire.ball",3)); when(mmo.mapSkillTreePoints()).thenReturn(Map.of("tree.fire",5));
        when(mmo.getNodeLevels()).thenReturn(Map.of("tree.fire.node",2)); when(mmo.getItemClaims()).thenReturn(Map.of("claim.item",4));
        when(mmo.getWaypoints()).thenReturn(new HashSet<>(Set.of("spawn","town"))); when(mmo.getUnlockedItems()).thenReturn(Set.of("weapon.fire"));
        when(mmo.mapBoundSkills()).thenReturn(Map.of(1,"fire.ball",2,"missing",3,"no.slot"));
        var skill=mock(ClassSkill.class); when(warrior.getSkill("fire.ball")).thenReturn(skill); when(warrior.getSkill("no.slot")).thenReturn(skill); when(warrior.getSkillSlot(1)).thenReturn(mock(SkillSlot.class));
        var node=mock(SkillTreeNode.class); when(node.getFullId()).thenReturn("tree.fire.node"); var absent=mock(SkillTreeNode.class); when(absent.getFullId()).thenReturn("absent"); when(MMOCore.plugin.skillTreeManager.getAllNodes()).thenReturn(List.of(node,absent));
        var tree=mock(SkillTree.class); when(warrior.getSkillTrees()).thenReturn(List.of(tree)); var attribute=mock(PlayerAttribute.class); when(MMOCore.plugin.attributeManager.getAll()).thenReturn(List.of(attribute));
        var saved=new SavedClassInformation(MMOCore.plugin.playerDataManager.getDefaultData()); saved.registerSkillLevel("fire.ball",2);
        when(mmo.getSavedClasses()).thenReturn(Set.of("warrior")); when(mmo.getClassInfo("warrior")).thenReturn(saved);
        var encoded=MmoProgressCodec.capture(mmo); String before=encoded.saveToString();
        var reloaded=new YamlConfiguration(); reloaded.loadFromString(before); MmoProgressCodec.validate(reloaded); clearInvocations(mmo); MmoProgressCodec.apply(mmo,reloaded);
        var ordered=inOrder(mmo); ordered.verify(mmo).getProfess(); ordered.verify(mmo).resetSkillTrees(); ordered.verify(mmo).resetSkills(); ordered.verify(mmo).resetTimesClaimed(); ordered.verify(mmo).getSavedClasses(); ordered.verify(mmo).unloadClassInfo("warrior"); ordered.verify(mmo).setClass(warrior);
        verify(warrior).resetAdvancement(mmo,false); verify(profession).resetAdvancement(mmo,false); verify(attribute).resetAdvancement(mmo,false);
        verify(mmo).setLevel(8,PlayerLevelChangeEvent.Reason.CHOOSE_PROFILE); verify(mmo).setExperience(30); verify(mmo).setClassPoints(8);
        verify(mmo).setSkillPoints(13); verify(mmo).setAttributePoints(9); verify(mmo).setSkillReallocationPoints(4); verify(mmo).setAttributeReallocationPoints(6); verify(mmo).setSkillTreeReallocationPoints(7);
        verify(mmo).setSkillLevel("fire.ball",3); verify(mmo).setSkillTreePoints("tree.fire",5); verify(mmo).setClaims("claim.item",4); verify(mmo).setNodeLevel(node,2); verify(mmo,never()).setNodeLevel(eq(absent),anyInt()); verify(tree).resolveStates(mmo);
        verify(mmo).setUnlockedItems(Set.of("weapon.fire")); verify(mmo).bindSkill(1,skill); verify(mmo,never()).bindSkill(eq(3),any());
        verify(skills).setLevel(profession,5,PlayerLevelChangeEvent.Reason.CHOOSE_PROFILE); verify(skills).setExperience(profession,12);
        verify(mmo).setMana(23,ResourceUpdateReason.CHOOSE_CLASS); verify(mmo).setStamina(24,ResourceUpdateReason.CHOOSE_CLASS); verify(mmo).setStellium(25,ResourceUpdateReason.CHOOSE_CLASS);
        var slot=ArgumentCaptor.forClass(SavedClassInformation.class); verify(mmo).applyClassInfo(eq(warrior),slot.capture()); assertEquals(2,slot.getValue().mapSkillLevels().get("fire.ball"));
        assertEquals(before,encoded.saveToString(),"Applying a copy cannot mutate another character's baseline");
    }
    @Test void freshDefaultsClearAllOutgoingCharacterStateAndRestoreIndependentResources() {
        var fresh=MmoProgressCodec.fresh(); assertEquals(1,fresh.getInt("level")); assertEquals(20,fresh.getDouble("mana")); assertEquals(List.of(),fresh.getList("claims"));
        when(mmo.getSavedClasses()).thenReturn(Set.of("old")); when(mmo.getWaypoints()).thenReturn(new HashSet<>(Set.of("foreign")));
        MmoProgressCodec.apply(mmo,fresh); verify(mmo).unloadClassInfo("old"); verify(mmo).resetTimesClaimed(); verify(mmo).resetSkills(); verify(mmo).resetSkillTrees(); verify(mmo).setUnlockedItems(Set.of()); assertTrue(mmo.getWaypoints().isEmpty());
        verify(skills).setLevel(profession,1,PlayerLevelChangeEvent.Reason.CHOOSE_PROFILE); verify(skills).setExperience(profession,0); verify(mmo).setMana(20,ResourceUpdateReason.CHOOSE_CLASS);
    }
    @Test void invalidNumbersStructureAndUnknownClassesNeverMutateMmo() throws Exception {
        var good=MmoProgressCodec.fresh();
        assertThrows(IllegalArgumentException.class,()->MmoProgressCodec.apply(mmo,null));
        for (String key:List.of("format","class","class-points","level","experience","skill-points","skills","bound-skills","unlocked-items","professions","claims","class-info","mana")) {
            var copy=copy(good); copy.set(key,null); clearInvocations(mmo); assertThrows(IllegalArgumentException.class,()->MmoProgressCodec.apply(mmo,copy),key); verifyNoInteractions(mmo);
        }
        for (Object invalid:List.of(-1,1.5,Double.NaN,Double.POSITIVE_INFINITY,((long)Integer.MAX_VALUE)+1)) { var copy=copy(good); copy.set("skill-points",invalid); assertThrows(IllegalArgumentException.class,()->MmoProgressCodec.apply(mmo,copy)); }
        var copy=copy(good); copy.set("format",99); assertThrows(IllegalArgumentException.class,()->MmoProgressCodec.validate(copy)); copy.set("format",1); copy.set("level",0); assertThrows(IllegalArgumentException.class,()->MmoProgressCodec.validate(copy));
        copy.set("level",1); copy.set("class"," "); assertThrows(IllegalArgumentException.class,()->MmoProgressCodec.validate(copy)); copy.set("class",5); assertThrows(IllegalArgumentException.class,()->MmoProgressCodec.validate(copy));
        copy.set("class","missing"); clearInvocations(mmo); assertThrows(IllegalArgumentException.class,()->MmoProgressCodec.apply(mmo,copy)); verifyNoInteractions(mmo);
        for (Object bad:List.of(List.of("bad"),List.of(Map.of("id","x","value",1),Map.of("id","x","value",2)),List.of(Map.of("value",2)))) { var invalid=copy(good); invalid.set("claims",bad); assertThrows(IllegalArgumentException.class,()->MmoProgressCodec.apply(mmo,invalid)); }
        var slot=copy(good).getValues(false); slot.put("health",20);slot.put("attributes",List.of());slot.put("node-claims",List.of());slot.put("class","missing"); var unknownSlot=copy(good);unknownSlot.set("class-info",List.of(slot));clearInvocations(mmo);assertThrows(IllegalArgumentException.class,()->MmoProgressCodec.apply(mmo,unknownSlot));verifyNoInteractions(mmo);
    }
    @Test void nullCounterEntriesAreOmittedAndStoredClassResourcesRemainAvailable() {
        Map<String,Integer> nullable=new HashMap<>(); nullable.put("removed",null); nullable.put("kept",2); when(mmo.mapSkillLevels()).thenReturn(nullable);
        assertEquals(List.of(Map.of("id","kept","value",2)),MmoProgressCodec.capture(mmo).getList("skills"));
    }
    private static YamlConfiguration copy(YamlConfiguration input) throws Exception {var result=new YamlConfiguration();result.loadFromString(input.saveToString());return result;}
}
