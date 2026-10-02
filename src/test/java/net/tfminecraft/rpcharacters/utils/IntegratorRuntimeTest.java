package net.tfminecraft.rpcharacters.utils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;

import net.Indyuce.mmocore.api.player.PlayerData;
import net.Indyuce.mmocore.api.player.attribute.PlayerAttributes;
import net.Indyuce.mmocore.api.player.attribute.PlayerAttributes.AttributeInstance;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.attributes.AttributeModifier;

class IntegratorRuntimeTest {
    RuntimeTestState state;
    MockedStatic<PlayerData> players;
    Player player;
    PlayerData mmo;
    PlayerAttributes attributes;
    RPCharacter character;
    final Integrator integrator = new Integrator();
    final Map<String, AttributeInstance> instances = new LinkedHashMap<>();
    @BeforeEach void setup() {
        MockBukkit.mock(); state = new RuntimeTestState(); Cache.attributes = new ArrayList<>(); Cache.professions = new ArrayList<>();
        player = mock(Player.class); when(player.getUniqueId()).thenReturn(UUID.randomUUID()); character = new RPCharacter(player);
        mmo = mock(PlayerData.class); attributes = mock(PlayerAttributes.class); when(mmo.getAttributes()).thenReturn(attributes); when(mmo.isSynchronized()).thenReturn(true);
        when(attributes.getInstance(anyString())).thenAnswer(c -> instances.get(c.<String>getArgument(0).toLowerCase(Locale.ROOT))); when(attributes.getInstances()).thenAnswer(c -> instances.values());
        players = mockStatic(PlayerData.class); players.when(() -> PlayerData.get(player)).thenReturn(mmo); players.when(() -> PlayerData.has(player)).thenReturn(true);
        attribute("strength", 10); attribute("dexterity", 6);
        character.getAttributeData().addModifier(new AttributeModifier("strength", 3)); character.getAttributeData().addModifier(new AttributeModifier("dexterity", -2)); character.getAttributeData().addModifier(new AttributeModifier("removed_attribute", 4));
    }
    @AfterEach void teardown() { players.close(); state.close(); MockBukkit.unmock(); }
    private void attribute(String id, int base) { AttributeInstance a = spy(new PlayerAttributes(mmo).new AttributeInstance(id)); a.setBase(base); clearInvocations(a); instances.put(id, a); }
    private int value(String id) { return instances.get(id).getBase(); }

    @Test void creationLayerAddsKnownModifiersAndSnapshotsRemainIndependent() {
        integrator.integrate(player, character); assertEquals(13, value("strength")); assertEquals(4, value("dexterity"));
        Map<String, Integer> saved = integrator.get(player, character); assertEquals(Map.of("strength", 13, "dexterity", 4), saved); saved.clear(); assertEquals(2, instances.size());
        assertEquals(List.of("strength.3", "dexterity.-2", "removed_attribute.4"), integrator.getRemoveList(player, character)); assertEquals(3, character.getAttributeData().getModifiers().size());
        assertEquals(integrator.getRemoveList(player, character), integrator.getRemoveList(null, character));
    }
    @Test void strippingRemovesOnlyCreationLayerAndClampsBelowZero() {
        instances.get("strength").setBase(2); integrator.stripCreationLayer(player, character); assertEquals(0, value("strength")); assertEquals(8, value("dexterity"));
        instances.get("strength").setBase(10); instances.get("dexterity").setBase(6); integrator.remove(player, character, true); assertEquals(7, value("strength")); assertEquals(8, value("dexterity"));
        instances.get("strength").setBase(10); instances.get("dexterity").setBase(6); integrator.remove(player, character, false); assertEquals(7, value("strength")); assertEquals(8, value("dexterity"));
    }
    @Test void serializedRemovalsPreserveNegativeCreationModifiersAndIgnoreRemovedAttributes() {
        integrator.remove(player, "strength.2"); integrator.remove(player, "dexterity.-3"); integrator.remove(player, "deleted.5"); assertEquals(8, value("strength")); assertEquals(9, value("dexterity"));
    }
    @Test void emptyPendingQueuesDoNotTouchMmoAndValidQueuesApplyInOrder() {
        integrator.applyPendingRemoves(null, List.of("strength.1")); integrator.applyPendingRemoves(player, null); integrator.applyPendingRemoves(player, List.of()); players.verifyNoInteractions();
        integrator.applyPendingRemoves(player, List.of("strength.2", "strength.3", "dexterity.-1")); assertEquals(5, value("strength")); assertEquals(7, value("dexterity"));
    }
    @Test void malformedPendingEntriesDoNotLoseValidRemovalsAfterThem() {
        List<String> pending = Arrays.asList("strength.2", "bad", null, "dexterity.nope", "strength.2147483648", "dexterity.3", "strength.-1");
        assertDoesNotThrow(() -> integrator.applyPendingRemoves(player, pending)); assertEquals(9, value("strength")); assertEquals(3, value("dexterity")); assertEquals(7, pending.size());
    }
    @Test void persistedRemovalCannotMakeTheBaseAttributeNegative() {
        integrator.remove(player, "strength.100"); assertEquals(0, value("strength"));
    }
    @Test void absentExternalPlayerDataLeavesCharacterAndMmoStateUntouched() {
        players.when(() -> PlayerData.get(player)).thenReturn(null);
        assertAll(() -> assertDoesNotThrow(() -> integrator.integrate(player, character)), () -> assertDoesNotThrow(() -> integrator.stripCreationLayer(player, character)), () -> assertDoesNotThrow(() -> integrator.remove(player, "strength.2")), () -> assertEquals(Map.of(), integrator.get(player, character)));
        assertEquals(10, value("strength")); assertEquals(6, value("dexterity"));
    }
    @Test void nullPublicInputsAreSafeAndNeedNoExternalLookup() {
        assertAll(() -> assertDoesNotThrow(() -> integrator.integrate(null, character)), () -> assertDoesNotThrow(() -> integrator.integrate(player, null)), () -> assertDoesNotThrow(() -> integrator.stripCreationLayer(null, character)), () -> assertDoesNotThrow(() -> integrator.stripCreationLayer(player, null)), () -> assertDoesNotThrow(() -> integrator.remove(null, "strength.2")), () -> assertDoesNotThrow(() -> integrator.remove(player, (String) null)), () -> assertEquals(Map.of(), integrator.get(null, character)), () -> assertEquals(List.of(), integrator.getRemoveList(player, null)));
    }
    @Test void partiallyLoadedMmoPlayerIsNotMutatedBeforeSynchronization() {
        when(mmo.isSynchronized()).thenReturn(false);
        integrator.integrate(player, character); integrator.stripCreationLayer(player, character); integrator.remove(player, "strength.2");
        assertEquals(10, value("strength")); assertEquals(6, value("dexterity")); verify(instances.get("strength"), never()).setBase(anyInt()); assertEquals(Map.of(), integrator.get(player, character));
    }
    @Test void unavailableMmoApiIsHandledWithoutThrowingFromPublicActions() {
        players.when(() -> PlayerData.get(player)).thenThrow(new IllegalStateException("MMOCore is unloading"));
        assertAll(() -> assertDoesNotThrow(() -> integrator.integrate(player, character)), () -> assertDoesNotThrow(() -> integrator.stripCreationLayer(player, character)), () -> assertDoesNotThrow(() -> integrator.remove(player, "strength.2")), () -> assertEquals(Map.of(), integrator.get(player, character)));
    }
}
