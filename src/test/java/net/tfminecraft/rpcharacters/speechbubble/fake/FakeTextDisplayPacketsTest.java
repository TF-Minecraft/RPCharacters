package net.tfminecraft.rpcharacters.speechbubble.fake;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.logging.Logger;
import com.comphenix.protocol.*;
import com.comphenix.protocol.events.*;
import com.comphenix.protocol.reflect.*;
import com.comphenix.protocol.reflect.accessors.*;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.comphenix.protocol.wrappers.*;
import net.tfminecraft.rpcharacters.*;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Vector3f;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.mockbukkit.mockbukkit.MockBukkit;

class FakeTextDisplayPacketsTest {
    RuntimeTestState state; ProtocolManager protocol; FakeTextDisplayPackets packets; Player viewer;
    RPCharacters plugin; Logger logger; Location location;
    final List<AutoCloseable> boundaries = new ArrayList<>();
    final List<PacketState> created = new ArrayList<>(), sent = new ArrayList<>();
    final Map<PacketContainer, PacketState> states = new IdentityHashMap<>();
    int doubleFields = 6, byteFields = 3, vectorFields = 2, floatFields = 2;
    boolean missingStructure;

    @BeforeEach void setup() throws Exception {
        var server = MockBukkit.mock(); state = new RuntimeTestState(RPCharacters.class);
        viewer = server.addPlayer("Viewer"); location = new Location(viewer.getWorld(), 1.25, 64.5, -3.75, 90, -45);
        plugin = mock(RPCharacters.class); logger = mock(Logger.class); when(plugin.getLogger()).thenReturn(logger); RPCharacters.plugin = plugin;
        bootstrapProtocolWrappers();
        protocol = mock(ProtocolManager.class); packets = new FakeTextDisplayPackets(protocol);
        when(protocol.createPacket(any(PacketType.class))).thenAnswer(call -> packet(call.getArgument(0)).packet);
        when(protocol.createPacket(any(PacketType.class), eq(true))).thenAnswer(call -> packet(call.getArgument(0)).packet);
        doAnswer(call -> { sent.add(states.get(call.getArgument(1))); return null; }).when(protocol).sendServerPacket(eq(viewer), any(PacketContainer.class));
    }

    /** NMS discovery and wrappers are external boundaries; the plugin's field/metadata construction runs normally. */
    void bootstrapProtocolWrappers() throws Exception {
        var handle = new NmsHandle();
        var methodAccessor = mock(MethodAccessor.class); when(methodAccessor.invoke(any(), any(Object[].class))).thenReturn(handle);
        var fieldAccessor = mock(FieldAccessor.class); when(fieldAccessor.get(any())).thenReturn(handle);
        when(fieldAccessor.getField()).thenReturn(NmsHandle.class.getField("CODEC"));
        var constructorAccessor = mock(ConstructorAccessor.class); when(constructorAccessor.invoke(any(Object[].class))).thenReturn(handle);
        var accessors = mockStatic(Accessors.class, call -> {
            Class<?> result = call.getMethod().getReturnType();
            if (result == MethodAccessor.class) return methodAccessor;
            if (result == FieldAccessor.class) return fieldAccessor;
            if (result == FieldAccessor[].class) return new FieldAccessor[]{fieldAccessor, fieldAccessor};
            if (result == ConstructorAccessor.class) return constructorAccessor;
            return Answers.RETURNS_DEFAULTS.answer(call);
        }); boundaries.add(accessors);
        var reflection = mockStatic(MinecraftReflection.class, call -> {
            if (call.getMethod().getReturnType() == Class.class) return NmsHandle.class;
            if (call.getMethod().getReturnType() == Optional.class) return Optional.of(NmsHandle.class);
            return Answers.RETURNS_DEFAULTS.answer(call);
        }); boundaries.add(reflection);
        var fuzzy = mock(FuzzyReflection.class, call -> {
            if (call.getMethod().getReturnType() == Field.class) return NmsHandle.class.getField("CODEC");
            if (call.getMethod().getReturnType() == Method.class) return NmsHandle.class.getMethod("convert", Object.class);
            return Answers.RETURNS_DEFAULTS.answer(call);
        });
        var fuzzies = mockStatic(FuzzyReflection.class, call -> call.getMethod().getReturnType() == FuzzyReflection.class ? fuzzy : Answers.RETURNS_DEFAULTS.answer(call));
        boundaries.add(fuzzies);
        var chats = mockStatic(WrappedChatComponent.class); boundaries.add(chats);
        chats.when(() -> WrappedChatComponent.fromText(anyString())).thenAnswer(call -> {
            var chat = mock(WrappedChatComponent.class); when(chat.getHandle()).thenReturn(new TextHandle(call.getArgument(0))); return chat;
        });
        var registry = mockStatic(WrappedDataWatcher.Registry.class); boundaries.add(registry);
        registry.when(() -> WrappedDataWatcher.Registry.get(any(java.lang.reflect.Type.class), eq(false))).thenAnswer(call -> {
            var serializer = mock(WrappedDataWatcher.Serializer.class); when(serializer.getGenericType()).thenReturn(call.getArgument(0)); return serializer;
        });
        var chatSerializer = mock(WrappedDataWatcher.Serializer.class); when(chatSerializer.getGenericType()).thenReturn(TextHandle.class);
        registry.when(() -> WrappedDataWatcher.Registry.getChatComponentSerializer(false)).thenReturn(chatSerializer);
        boundaries.add(mockConstruction(WrappedDataValue.class, (value, context) -> {
            when(value.getIndex()).thenReturn((int) context.arguments().get(0));
            when(value.getSerializer()).thenReturn((WrappedDataWatcher.Serializer) context.arguments().get(1));
            when(value.getValue()).thenReturn(context.arguments().get(2));
        }));
    }

    public static final class NmsHandle {
        public static final NmsHandle CODEC = new NmsHandle();
        public static Object convert(Object input) { return input; }
    }
    record TextHandle(String text) {}

    @AfterEach void cleanup() throws Exception {
        for (int i = boundaries.size() - 1; i >= 0; i--) boundaries.get(i).close();
        state.close(); MockBukkit.unmock();
    }

    @SuppressWarnings("unchecked")
    <T> StructureModifier<T> fields(List<T> values) {
        StructureModifier<T> modifier = mock(StructureModifier.class);
        when(modifier.size()).thenReturn(values.size()); when(modifier.getValues()).thenReturn(values);
        when(modifier.write(anyInt(), any())).thenAnswer(call -> { values.set(call.getArgument(0), call.getArgument(1)); return modifier; });
        when(modifier.read(anyInt())).thenAnswer(call -> values.get(call.getArgument(0))); return modifier;
    }
    static <T> List<T> emptyFields(int count) { return new ArrayList<>(Collections.nCopies(count, null)); }
    PacketState packet(PacketType type) {
        var state = new PacketState(type); states.put(state.packet, state); created.add(state); return state;
    }
    final class PacketState {
        final PacketType type; final PacketContainer packet = mock(PacketContainer.class);
        final List<Integer> integers = emptyFields(1); final List<UUID> uuids = emptyFields(1);
        final List<EntityType> entities = emptyFields(1); final List<Double> doubles = emptyFields(doubleFields);
        final List<Byte> bytes = emptyFields(byteFields); final List<Vector> vectors = emptyFields(vectorFields);
        final List<Float> floats = emptyFields(floatFields); final List<List<Integer>> destroyed = emptyFields(1);
        final List<List<WrappedDataValue>> metadata = emptyFields(1);
        PacketState(PacketType type) {
            this.type = type; when(packet.getType()).thenReturn(type);
            if (missingStructure) {
                // ProtocolLib's diagnostic toString resolves the live NMS packet registry.
                var description = mock(PacketType.class);
                when(description.toString()).thenReturn(type.name());
                when(packet.getType()).thenReturn(description);
            }
            doReturn(fields(integers)).when(packet).getIntegers(); doReturn(fields(uuids)).when(packet).getUUIDs();
            doReturn(fields(entities)).when(packet).getEntityTypeModifier(); doReturn(fields(doubles)).when(packet).getDoubles();
            doReturn(fields(bytes)).when(packet).getBytes(); doReturn(fields(destroyed)).when(packet).getIntLists();
            doReturn(fields(metadata)).when(packet).getDataValueCollectionModifier();
            var movement = mock(InternalStructure.class); doReturn(fields(vectors)).when(movement).getVectors(); doReturn(fields(floats)).when(movement).getFloat();
            doReturn(fields(missingStructure ? new ArrayList<>() : new ArrayList<>(List.of(movement)))).when(packet).getStructures();
        }
    }

    @Test void invalidSpawnTeleportAndDestroyInputsProduceNoPackets() {
        packets.spawn(null, -1, UUID.randomUUID(), location, "x", 1, 30);
        packets.spawn(viewer, -1, UUID.randomUUID(), null, "x", 1, 30);
        packets.spawn(viewer, -1, UUID.randomUUID(), new Location(null, 1, 2, 3), "x", 1, 30);
        packets.teleport(null, -1, location); packets.teleport(viewer, -1, null);
        packets.destroy(null, List.of(-1)); packets.destroy(viewer, null); packets.destroy(viewer, List.of());
        verifyNoInteractions(protocol);
    }

    @Test void flatSpawnWritesIdentityPositionRotationAndCompleteMetadata() {
        var uuid = UUID.randomUUID(); packets.spawn(viewer, -12, uuid, location, "Visible text", .6f, 50);
        assertEquals(2, sent.size()); var spawn = sent.getFirst(); assertEquals(PacketType.Play.Server.SPAWN_ENTITY, spawn.type);
        assertEquals(List.of(-12), spawn.integers); assertEquals(List.of(uuid), spawn.uuids); assertEquals(List.of(EntityType.TEXT_DISPLAY), spawn.entities);
        assertEquals(List.of(1.25, 64.5, -3.75, 0.0, 0.0, 0.0), spawn.doubles);
        assertEquals(List.of((byte) 64, (byte) -32, (byte) 64), spawn.bytes);
        var metadata = sent.get(1); assertEquals(PacketType.Play.Server.ENTITY_METADATA, metadata.type); assertEquals(List.of(-12), metadata.integers);
        var values = metadata.metadata.getFirst(); assertEquals(List.of(12, 15, 23, 24, 25, 26, 27), values.stream().map(WrappedDataValue::getIndex).toList());
        assertEquals(new Vector3f(.6f, .6f, .6f), values.get(0).getValue()); assertEquals((byte) 3, values.get(1).getValue());
        assertEquals(new TextHandle("Visible text"), values.get(2).getValue()); assertEquals(50, values.get(3).getValue());
        assertEquals(1073741824, values.get(4).getValue()); assertEquals((byte) -1, values.get(5).getValue()); assertEquals((byte) 1, values.get(6).getValue());
        assertEquals(List.of(Vector3f.class, Byte.class, TextHandle.class, Integer.class, Integer.class, Byte.class, Byte.class),
            values.stream().map(value -> value.getSerializer().getGenericType()).toList());
    }

    @Test void updateTextReplacesMetadataAndNormalizesNullText() {
        packets.updateText(viewer, -3, null, 2f, 120); assertEquals(1, sent.size());
        assertEquals(PacketType.Play.Server.ENTITY_METADATA, sent.getFirst().type);
        assertEquals(new TextHandle(""), sent.getFirst().metadata.getFirst().get(2).getValue());
        assertEquals(new Vector3f(2f, 2f, 2f), sent.getFirst().metadata.getFirst().getFirst().getValue());
        assertEquals(120, sent.getFirst().metadata.getFirst().get(3).getValue());
    }

    @Test void flatTeleportSupportsThreeCoordinatesAndPartialRotationLayouts() {
        doubleFields = 3; byteFields = 1; packets.teleport(viewer, -9, location);
        assertEquals(PacketType.Play.Server.ENTITY_TELEPORT, sent.getFirst().type);
        assertEquals(List.of(-9), sent.getFirst().integers); assertEquals(List.of(1.25, 64.5, -3.75), sent.getFirst().doubles);
        assertEquals(List.of((byte) 64), sent.getFirst().bytes);
        byteFields = 0; packets.teleport(viewer, -9, location); assertTrue(sent.getLast().bytes.isEmpty());
        byteFields = 2; packets.teleport(viewer, -9, location); assertEquals(List.of((byte) 64, (byte) -32), sent.getLast().bytes);
    }

    @Test void nestedTeleportWritesPositionVelocityAndDegreeRotation() {
        doubleFields = 0; packets.teleport(viewer, -7, location);
        var packet = sent.getFirst(); assertEquals(List.of(new Vector(1.25, 64.5, -3.75), new Vector()), packet.vectors);
        assertEquals(List.of(90f, -45f), packet.floats);
        vectorFields = 1; floatFields = 1; packets.teleport(viewer, -7, location);
        assertEquals(List.of(new Vector(1.25, 64.5, -3.75)), sent.getLast().vectors); assertEquals(List.of(90f), sent.getLast().floats);
    }

    @Test void absentPositionStructureFailsBeforeSendingMalformedPacket() {
        doubleFields = 0; missingStructure = true;
        var failure = assertThrows(IllegalStateException.class, () -> packets.teleport(viewer, -7, location));
        assertTrue(failure.getMessage().contains("no writable position structure"));
        assertTrue(failure.getMessage().contains(PacketType.Play.Server.ENTITY_TELEPORT.name())); assertTrue(sent.isEmpty());
    }

    @Test void destroyUsesOnePacketWithEveryRequestedEntityId() {
        packets.destroy(viewer, List.of(-1, -2, -3)); assertEquals(1, sent.size());
        assertEquals(PacketType.Play.Server.ENTITY_DESTROY, sent.getFirst().type);
        assertEquals(List.of(-1, -2, -3), sent.getFirst().destroyed.getFirst());
    }

    @Test void transportFailureLogsViewerAndCauseAndRemainsSafeWithoutPlugin() {
        doThrow(new IllegalStateException("Disconnected")).when(protocol).sendServerPacket(eq(viewer), any(PacketContainer.class));
        assertDoesNotThrow(() -> packets.destroy(viewer, List.of(-1)));
        verify(logger).warning(contains("Viewer: Disconnected")); RPCharacters.plugin = null;
        assertDoesNotThrow(() -> packets.destroy(viewer, List.of(-2))); verify(logger, times(1)).warning(anyString());
    }
}
