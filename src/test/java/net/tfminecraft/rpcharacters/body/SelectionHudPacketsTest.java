package net.tfminecraft.rpcharacters.body;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.FuzzyReflection;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.reflect.accessors.Accessors;
import com.comphenix.protocol.reflect.accessors.ConstructorAccessor;
import com.comphenix.protocol.reflect.accessors.FieldAccessor;
import com.comphenix.protocol.reflect.accessors.MethodAccessor;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.comphenix.protocol.wrappers.WrappedChatComponent;

class SelectionHudPacketsTest {
    public interface HandlePlayer extends Player { FakeServerPlayer getHandle(); }
    public static class FakeAbilities {
        boolean flying = true, mayFly = true, invulnerable = false;
        float flyingSpeed = .1375f, walkingSpeed = .0875f;
    }
    public static class FakeServerPlayer {
        final FakeAbilities abilities = new FakeAbilities();
        public FakeAbilities getAbilities() { return abilities; }
    }
    public static class FakeAbilityPacket {
        final FakeAbilities abilities;
        public FakeAbilityPacket(FakeAbilities abilities) { this.abilities = abilities; }
    }
    public static class ComponentHandle {
        public static final Object CODEC = new Object();
        public static Object convert(Object value) { return value; }
    }

    final List<AutoCloseable> boundaries = new ArrayList<>();
    final List<PacketContainer> sent = new ArrayList<>();
    final Map<PacketContainer, PacketState> packets = new HashMap<>();
    MockedStatic<MinecraftReflection> reflection;
    MockedStatic<ProtocolLibrary> protocol;
    ProtocolManager manager;
    PluginManager pluginManager;
    Plugin plugin;
    HandlePlayer player;
    FakeServerPlayer handle;
    SelectionPresentation presentation;
    SelectionHudPackets adapter;
    PacketAdapter listener;
    FakeAbilityPacket copied;
    String version = "1.21.10";

    @BeforeEach void setup() throws Exception {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        manager = mock(ProtocolManager.class);
        protocol = mockStatic(ProtocolLibrary.class); boundaries.add(protocol);
        protocol.when(ProtocolLibrary::getProtocolManager).thenReturn(manager);
        var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS); boundaries.add(bukkit);
        bukkit.when(Bukkit::getMinecraftVersion).thenAnswer(call -> version);
        pluginManager = mock(PluginManager.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
        bootstrapChat();
        reflection.when(MinecraftReflection::getEntityPlayerClass).thenReturn(FakeServerPlayer.class);
        reflection.when(() -> MinecraftReflection.getMinecraftClass("network.protocol.game.ClientboundPlayerAbilitiesPacket"))
                .thenReturn(FakeAbilityPacket.class);
        when(manager.createPacket(any(PacketType.class))).thenAnswer(call -> packet(call.getArgument(0)).packet);
        doAnswer(call -> { sent.add(call.getArgument(1)); return null; }).when(manager)
                .sendServerPacket(any(Player.class), any(PacketContainer.class), eq(false));
        doAnswer(call -> { listener = call.getArgument(0); return null; }).when(manager).addPacketListener(any());
        var containers = mockStatic(PacketContainer.class); boundaries.add(containers);
        containers.when(() -> PacketContainer.fromPacket(any())).thenAnswer(call -> {
            copied = call.getArgument(0);
            return packet(PacketType.Play.Server.ABILITIES).packet;
        });
        player = mock(HandlePlayer.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        handle = new FakeServerPlayer(); when(player.getHandle()).thenReturn(handle);
        presentation = new SelectionPresentation(plugin, null);
        adapter = new SelectionHudPackets(plugin, presentation);
    }

    private void bootstrapChat() throws Exception {
        var accessor = mock(MethodAccessor.class);
        var field = mock(FieldAccessor.class);
        when(accessor.invoke(any(), any(Object[].class))).thenReturn(new ComponentHandle());
        when(field.get(any())).thenReturn(new ComponentHandle());
        when(field.getField()).thenReturn(ComponentHandle.class.getField("CODEC"));
        var constructor = mock(ConstructorAccessor.class);
        when(constructor.invoke(any(Object[].class))).thenReturn(new ComponentHandle());
        boundaries.add(mockStatic(Accessors.class, call -> {
            Class<?> type = call.getMethod().getReturnType();
            if (type == MethodAccessor.class) return accessor;
            if (type == FieldAccessor.class) return field;
            if (type == FieldAccessor[].class) return new FieldAccessor[] {field, field};
            if (type == ConstructorAccessor.class) return constructor;
            return Answers.RETURNS_DEFAULTS.answer(call);
        }));
        reflection = mockStatic(MinecraftReflection.class, call -> {
            if (call.getMethod().getReturnType() == Class.class) return ComponentHandle.class;
            if (call.getMethod().getReturnType() == Optional.class) return Optional.of(ComponentHandle.class);
            return Answers.RETURNS_DEFAULTS.answer(call);
        }); boundaries.add(reflection);
        var fuzzy = mock(FuzzyReflection.class, call -> {
            if (call.getMethod().getReturnType() == Field.class) return ComponentHandle.class.getField("CODEC");
            if (call.getMethod().getReturnType() == Method.class) return ComponentHandle.class.getMethod("convert", Object.class);
            return Answers.RETURNS_DEFAULTS.answer(call);
        });
        boundaries.add(mockStatic(FuzzyReflection.class, call -> call.getMethod().getReturnType() == FuzzyReflection.class
                ? fuzzy : Answers.RETURNS_DEFAULTS.answer(call)));
        var chats = mockStatic(WrappedChatComponent.class); boundaries.add(chats);
        chats.when(() -> WrappedChatComponent.fromText(anyString())).thenAnswer(call -> component("{\"text\":\"\"}"));
        chats.when(() -> WrappedChatComponent.fromJson(anyString())).thenAnswer(call -> component(call.getArgument(0)));
    }

    @AfterEach void cleanup() throws Exception {
        for (AutoCloseable boundary : boundaries.reversed()) boundary.close();
        MockBukkit.unmock();
    }

    private WrappedChatComponent component(String json) {
        WrappedChatComponent component = mock(WrappedChatComponent.class);
        when(component.getJson()).thenReturn(json);
        return component;
    }

    private PacketState packet(PacketType type) {
        PacketState state = new PacketState(type);
        packets.put(state.packet, state);
        return state;
    }

    private final class PacketState {
        final PacketContainer packet = mock(PacketContainer.class);
        final Map<String, Object> values = new HashMap<>();
        PacketState(PacketType type) {
            when(packet.getType()).thenReturn(type);
            when(packet.getGameStateIDs()).thenAnswer(call -> modifier("reason"));
            when(packet.getIntegers()).thenAnswer(call -> modifier("integer"));
            when(packet.getFloat()).thenAnswer(call -> modifier("float"));
            when(packet.getBooleans()).thenAnswer(call -> modifier("boolean"));
            when(packet.getChatComponents()).thenAnswer(call -> modifier("chat"));
            when(packet.getPacketBundles()).thenAnswer(call -> modifier("bundle"));
        }
        @SuppressWarnings("unchecked")
        private <T> StructureModifier<T> modifier(String key) {
            StructureModifier<T> modifier = mock(StructureModifier.class);
            when(modifier.write(anyInt(), any())).thenAnswer(call -> { values.put(key, call.getArgument(1)); return modifier; });
            when(modifier.readSafely(anyInt())).thenAnswer(call -> values.get(key));
            when(modifier.read(anyInt())).thenAnswer(call -> values.get(key));
            return modifier;
        }
    }

    @Test void modeAndAuthoritativeAbilitiesTravelInTheSameBundleWithoutReadingBukkitSpeeds() {
        adapter.mode(player, true);
        assertSame(handle.abilities, copied.abilities);
        assertEquals(.1375f, copied.abilities.flyingSpeed);
        assertEquals(.0875f, copied.abilities.walkingSpeed);
        assertFalse(copied.abilities.invulnerable);
        var bundle = packets.get(sent.getLast());
        assertEquals(PacketType.Play.Server.BUNDLE, bundle.packet.getType());
        @SuppressWarnings("unchecked") var members = (List<PacketContainer>) bundle.values.get("bundle");
        assertEquals(2, members.size());
        assertEquals(3, packets.get(members.getFirst()).values.get("reason"));
        assertEquals(3.0f, packets.get(members.getFirst()).values.get("float"));
        assertEquals(PacketType.Play.Server.ABILITIES, members.getLast().getType());
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        adapter.mode(player, false);
        @SuppressWarnings("unchecked") var restore = (List<PacketContainer>) packets.get(sent.getLast()).values.get("bundle");
        assertEquals(1.0f, packets.get(restore.getFirst()).values.get("float"));
        verify(player, never()).getFlySpeed(); verify(player, never()).getWalkSpeed();
        verify(player, never()).setGameMode(any());
    }

    @Test void clearAndReplayBypassTheListenerAndReplayAlwaysUsesOrdinaryChat() {
        adapter.clear(player);
        assertEquals(PacketType.Play.Server.CLEAR_TITLES, sent.getFirst().getType());
        assertEquals(true, packets.get(sent.getFirst()).values.get("boolean"));
        assertEquals(PacketType.Play.Server.SET_ACTION_BAR_TEXT, sent.get(1).getType());
        adapter.chat(player, "{\"text\":\"retained\"}");
        PacketState replay = packets.get(sent.getLast());
        assertEquals(false, replay.values.get("boolean"));
        assertEquals("{\"text\":\"retained\"}", ((WrappedChatComponent) replay.values.get("chat")).getJson());
        adapter.close(); verify(manager).removePacketListener(listener);
    }

    @Test void anAcknowledgementFenceFollowsRestorationOnTheSameConnection() {
        adapter.fence(player, 1234);
        PacketState ping = packets.get(sent.getLast());
        assertEquals(PacketType.Play.Server.PING, ping.packet.getType());
        assertEquals(1234, ping.values.get("integer"));
    }

    @Test void clientFlightIsBlockedUntilItsOwnRestoreAcknowledgementAndOtherPongsAreUntouched() throws Exception {
        SelectionPresentation active = new SelectionPresentation(plugin, adapter);
        new SelectionHudPackets(plugin, active);
        active.begin(player); active.mask(player);
        PacketEvent flight = event(PacketType.Play.Client.ABILITIES, false, null);
        listener.onPacketReceiving(flight); verify(flight).setCancelled(true);
        PacketEvent cancelled = event(PacketType.Play.Client.ABILITIES, false, null);
        when(cancelled.isCancelled()).thenReturn(true);
        listener.onPacketReceiving(cancelled); verify(cancelled, never()).setCancelled(anyBoolean());
        active.release(player);
        PacketState ping = packets.get(sent.getLast());
        assertEquals(PacketType.Play.Server.PING, ping.packet.getType());
        int id = (int) ping.values.get("integer");
        PacketEvent other = event(PacketType.Play.Client.PONG, false, null);
        packets.get(other.getPacket()).values.put("integer", id + 1);
        listener.onPacketReceiving(other); verify(other, never()).setCancelled(anyBoolean());
        PacketEvent pong = event(PacketType.Play.Client.PONG, false, null);
        packets.get(pong.getPacket()).values.put("integer", id);
        listener.onPacketReceiving(pong); verify(pong).setCancelled(true);
        PacketEvent live = event(PacketType.Play.Client.ABILITIES, false, null);
        listener.onPacketReceiving(live); verify(live, never()).setCancelled(anyBoolean());
    }

    @Test void listenerQueuesOnlyOrdinarySystemChatAndDropsOverlayAndTitleTraffic() {
        SelectionPresentation active = new SelectionPresentation(plugin, adapter);
        // Give the listener the active instance rather than the constructor-only fixture.
        var packetsAdapter = assertDoesNotThrow(() -> new SelectionHudPackets(plugin, active));
        active.begin(player);
        PacketEvent ordinary = event(PacketType.Play.Server.SYSTEM_CHAT, false, component("old"));
        listener.onPacketSending(ordinary); verify(ordinary).setCancelled(true);
        for (PacketType type : List.of(PacketType.Play.Server.SYSTEM_CHAT, PacketType.Play.Server.SET_ACTION_BAR_TEXT,
                PacketType.Play.Server.SET_TITLE_TEXT, PacketType.Play.Server.SET_SUBTITLE_TEXT,
                PacketType.Play.Server.SET_TITLES_ANIMATION)) {
            PacketEvent transientEvent = event(type, true, component("stale bar"));
            listener.onPacketSending(transientEvent); verify(transientEvent).setCancelled(true);
        }
        PacketEvent empty = event(PacketType.Play.Server.SYSTEM_CHAT, false, null);
        listener.onPacketSending(empty); verify(empty, never()).setCancelled(anyBoolean());
        PacketEvent cancelled = event(PacketType.Play.Server.SYSTEM_CHAT, false, component("cancelled"));
        when(cancelled.isCancelled()).thenReturn(true);
        listener.onPacketSending(cancelled); verify(cancelled, never()).getPacket();
        active.release(player);
        assertEquals(1, sent.stream().filter(p -> p.getType() == PacketType.Play.Server.SYSTEM_CHAT).count());
        packetsAdapter.close();
    }

    @Test void listenerLeavesUnknownAndInteractiveConnectionsAlone() throws Exception {
        SelectionPresentation active = new SelectionPresentation(plugin, adapter);
        new SelectionHudPackets(plugin, active);
        PacketEvent unknown = event(PacketType.Play.Server.SYSTEM_CHAT, false, component("normal"));
        listener.onPacketSending(unknown); verify(unknown, never()).setCancelled(anyBoolean());
        active.begin(player); active.interact(player);
        PacketEvent ordinary = event(PacketType.Play.Server.SYSTEM_CHAT, false, component("link code"));
        PacketEvent overlay = event(PacketType.Play.Server.SYSTEM_CHAT, true, component("help"));
        listener.onPacketSending(ordinary); listener.onPacketSending(overlay);
        verify(ordinary, never()).setCancelled(anyBoolean()); verify(overlay, never()).setCancelled(anyBoolean());
        assertFalse(listener.getSendingWhitelist().getTypes().contains(PacketType.Play.Server.CHAT));
    }

    private PacketEvent event(PacketType type, Boolean overlay, WrappedChatComponent content) {
        PacketEvent event = mock(PacketEvent.class);
        PacketState packet = packet(type);
        packet.values.put("boolean", overlay); packet.values.put("chat", content);
        when(event.getPacketType()).thenReturn(type); when(event.getPacket()).thenReturn(packet.packet);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }

    @Test void optionalFactoryFallsBackForAnUnsupportedServerOrUnavailableManagerAndCanInitialize() {
        Plugin configured = mock(Plugin.class);
        when(configured.getLogger()).thenReturn(mock(Logger.class));
        Plugin protocolPlugin = mock(Plugin.class);
        when(protocolPlugin.isEnabled()).thenReturn(true);
        when(pluginManager.getPlugin("ProtocolLib")).thenReturn(protocolPlugin);
        version = "1.21.11";
        assertFalse(SelectionPresentation.create(configured, true).available());
        version = "1.21.10";
        protocol.when(ProtocolLibrary::getProtocolManager).thenReturn(null);
        assertFalse(SelectionPresentation.create(configured, true).available());
        protocol.when(ProtocolLibrary::getProtocolManager).thenReturn(manager);
        reflection.when(MinecraftReflection::getEntityPlayerClass).thenReturn(Object.class);
        assertFalse(SelectionPresentation.create(configured, true).available());
        reflection.when(MinecraftReflection::getEntityPlayerClass).thenThrow(new NoClassDefFoundError("absent"));
        assertFalse(SelectionPresentation.create(configured, true).available());
        reflection.when(MinecraftReflection::getEntityPlayerClass).thenReturn(FakeServerPlayer.class);
        SelectionPresentation initialized = SelectionPresentation.create(configured, true);
        assertTrue(initialized.available());
        initialized.close();
    }

    @Test void reflectiveFailureIsContainedWithoutMutatingThePlayer() {
        when(player.getHandle()).thenThrow(new IllegalStateException("handle unavailable"));
        assertThrows(IllegalStateException.class, () -> adapter.mode(player, true));
        assertTrue(sent.isEmpty());
    }
}
