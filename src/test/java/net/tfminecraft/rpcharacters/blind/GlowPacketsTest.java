package net.tfminecraft.rpcharacters.blind;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.reflect.FuzzyReflection;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.reflect.accessors.Accessors;
import com.comphenix.protocol.reflect.accessors.ConstructorAccessor;
import com.comphenix.protocol.reflect.accessors.FieldAccessor;
import com.comphenix.protocol.reflect.accessors.MethodAccessor;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;

class GlowPacketsTest {
    ProtocolManager protocol; PacketContainer packet; StructureModifier<Integer> ints; StructureModifier<List<WrappedDataValue>> values;
    MockedStatic<WrappedDataWatcher.Registry> registry; MockedConstruction<WrappedDataValue> constructed;
    WrappedDataWatcher.Serializer byteSerializer; final List<List<?>> arguments = new ArrayList<>();

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        MockBukkit.mock();
        bootstrapProtocolWrappers();
        protocol = mock(ProtocolManager.class); packet = mock(PacketContainer.class);
        ints = mock(StructureModifier.class); values = mock(StructureModifier.class);
        when(packet.getIntegers()).thenReturn(ints); when(packet.getDataValueCollectionModifier()).thenReturn(values);
        when(protocol.createPacket(PacketType.Play.Server.ENTITY_METADATA)).thenReturn(packet);
        byteSerializer = mock(WrappedDataWatcher.Serializer.class);
        registry = mockStatic(WrappedDataWatcher.Registry.class);
        registry.when(() -> WrappedDataWatcher.Registry.get((java.lang.reflect.Type) Byte.class, false)).thenReturn(byteSerializer);
        constructed = mockConstruction(WrappedDataValue.class, (value, context) -> arguments.add(context.arguments()));
    }

    @AfterEach void cleanup() throws Exception {
        if (constructed != null) constructed.close();
        if (registry != null) registry.close();
        for (int i = boundaries.size() - 1; i >= 0; i--) boundaries.get(i).close();
        MockBukkit.unmock();
    }

    final List<AutoCloseable> boundaries = new ArrayList<>();

    /** ProtocolLib's NMS discovery is an external boundary (same bootstrap as FakeTextDisplayPacketsTest). */
    void bootstrapProtocolWrappers() {
        var handle = new NmsHandle();
        var methodAccessor = mock(MethodAccessor.class); when(methodAccessor.invoke(any(), any(Object[].class))).thenReturn(handle);
        var fieldAccessor = mock(FieldAccessor.class); when(fieldAccessor.get(any())).thenReturn(handle);
        try { when(fieldAccessor.getField()).thenReturn(NmsHandle.class.getField("CODEC")); } catch (NoSuchFieldException e) { throw new IllegalStateException(e); }
        var constructorAccessor = mock(ConstructorAccessor.class); when(constructorAccessor.invoke(any(Object[].class))).thenReturn(handle);
        boundaries.add(mockStatic(Accessors.class, call -> {
            Class<?> result = call.getMethod().getReturnType();
            if (result == MethodAccessor.class) return methodAccessor;
            if (result == FieldAccessor.class) return fieldAccessor;
            if (result == FieldAccessor[].class) return new FieldAccessor[]{fieldAccessor, fieldAccessor};
            if (result == ConstructorAccessor.class) return constructorAccessor;
            return Answers.RETURNS_DEFAULTS.answer(call);
        }));
        boundaries.add(mockStatic(MinecraftReflection.class, call -> {
            if (call.getMethod().getReturnType() == Class.class) return NmsHandle.class;
            if (call.getMethod().getReturnType() == Optional.class) return Optional.of(NmsHandle.class);
            return Answers.RETURNS_DEFAULTS.answer(call);
        }));
        var fuzzy = mock(FuzzyReflection.class, call -> {
            if (call.getMethod().getReturnType() == Field.class) return NmsHandle.class.getField("CODEC");
            if (call.getMethod().getReturnType() == Method.class) return NmsHandle.class.getMethod("convert", Object.class);
            return Answers.RETURNS_DEFAULTS.answer(call);
        });
        boundaries.add(mockStatic(FuzzyReflection.class, call -> call.getMethod().getReturnType() == FuzzyReflection.class ? fuzzy : Answers.RETURNS_DEFAULTS.answer(call)));
    }

    public static final class NmsHandle {
        public static final NmsHandle CODEC = new NmsHandle();
        public static Object convert(Object input) { return input; }
    }

    @Test void sendsTheRealFlagsWithOrWithoutTheGlowBit() {
        Player viewer = mock(Player.class);
        Entity target = mock(Entity.class); when(target.getEntityId()).thenReturn(42); when(target.isSneaking()).thenReturn(true);
        GlowPackets glow = new GlowPackets(protocol);
        glow.send(viewer, target, true);
        glow.send(viewer, target, false);
        verify(ints, times(2)).write(0, 42);
        verify(values, times(2)).write(eq(0), any());
        verify(protocol, times(2)).sendServerPacket(viewer, packet);
        assertEquals(List.of(0, byteSerializer, (byte) (GlowPackets.SNEAKING | GlowPackets.GLOWING)), arguments.get(0));
        assertEquals(List.of(0, byteSerializer, GlowPackets.SNEAKING), arguments.get(1));
    }

    @Test void flagsMirrorTheEntityState() {
        Entity plain = mock(Entity.class);
        assertEquals(0, GlowPackets.flags(plain));
        Entity visualFire = mock(Entity.class); when(visualFire.isVisualFire()).thenReturn(true);
        assertEquals(GlowPackets.ON_FIRE, GlowPackets.flags(visualFire));
        Player all = mock(Player.class);
        when(all.getFireTicks()).thenReturn(20); when(all.isSneaking()).thenReturn(true); when(all.isSprinting()).thenReturn(true);
        when(all.isSwimming()).thenReturn(true); when(all.isInvisible()).thenReturn(true); when(all.isGlowing()).thenReturn(true);
        when(all.isGliding()).thenReturn(true);
        assertEquals((byte) 0xFB, GlowPackets.flags(all));
        LivingEntity mob = mock(LivingEntity.class); when(mob.isSwimming()).thenReturn(true);
        assertEquals(GlowPackets.SWIMMING, GlowPackets.flags(mob));
    }
}
