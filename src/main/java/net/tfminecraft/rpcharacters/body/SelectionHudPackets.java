package net.tfminecraft.rpcharacters.body;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.comphenix.protocol.wrappers.WrappedChatComponent;

/** Isolates ProtocolLib and the checked 1.21.10 ability-packet constructor from body persistence. */
final class SelectionHudPackets implements SelectionPresentation.Transport {
    private final ProtocolManager manager;
    private final Method abilities;
    private final Constructor<?> abilityPacket;
    private final PacketAdapter listener;

    SelectionHudPackets(Plugin plugin, SelectionPresentation presentation) throws ReflectiveOperationException {
        if (!"1.21.10".equals(Bukkit.getMinecraftVersion())) throw new IllegalStateException("Selection HUD adapter requires Minecraft 1.21.10");
        manager = ProtocolLibrary.getProtocolManager();
        if (manager == null) throw new IllegalStateException("ProtocolLib manager is unavailable");
        abilities = MinecraftReflection.getEntityPlayerClass().getMethod("getAbilities");
        abilityPacket = MinecraftReflection.getMinecraftClass("network.protocol.game.ClientboundPlayerAbilitiesPacket")
                .getConstructor(abilities.getReturnType());
        listener = new PacketAdapter(plugin, ListenerPriority.HIGHEST, PacketType.Play.Server.SYSTEM_CHAT,
                PacketType.Play.Server.SET_ACTION_BAR_TEXT, PacketType.Play.Server.SET_TITLE_TEXT,
                PacketType.Play.Server.SET_SUBTITLE_TEXT, PacketType.Play.Server.SET_TITLES_ANIMATION,
                PacketType.Play.Client.ABILITIES, PacketType.Play.Client.PONG) {
            @Override public void onPacketReceiving(PacketEvent event) {
                if (event.isCancelled()) return;
                if (event.getPacketType() == PacketType.Play.Client.ABILITIES) {
                    if (presentation.blockFlight(event.getPlayer())) event.setCancelled(true);
                } else if (presentation.acknowledge(event.getPlayer(), event.getPacket().getIntegers().read(0))) {
                    event.setCancelled(true);
                }
            }
            @Override public void onPacketSending(PacketEvent event) {
                if (event.isCancelled()) return;
                Player player = event.getPlayer();
                if (!presentation.holds(player)) return;
                PacketContainer packet = event.getPacket();
                if (event.getPacketType() != PacketType.Play.Server.SYSTEM_CHAT || Boolean.TRUE.equals(packet.getBooleans().readSafely(0))) {
                    if (presentation.transientPacket(player)) event.setCancelled(true);
                } else {
                    WrappedChatComponent content = packet.getChatComponents().readSafely(0);
                    if (presentation.chat(player, content == null ? null : content.getJson())) event.setCancelled(true);
                }
            }
        };
        manager.addPacketListener(listener);
    }

    @Override public void mode(Player player, boolean hidden) {
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            // Vanilla reads its actual ability fields and internal speeds. Bukkit speeds have a different scale.
            PacketContainer liveAbilities = PacketContainer.fromPacket(abilityPacket.newInstance(abilities.invoke(handle)));
            PacketContainer mode = manager.createPacket(PacketType.Play.Server.GAME_STATE_CHANGE);
            mode.getGameStateIDs().write(0, 3);
            mode.getFloat().write(0, hidden ? 3.0f : player.getGameMode().getValue() * 1.0f);
            PacketContainer bundle = manager.createPacket(PacketType.Play.Server.BUNDLE);
            bundle.getPacketBundles().write(0, List.of(mode, liveAbilities));
            manager.sendServerPacket(player, bundle, false);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not copy the player's authoritative abilities", error);
        }
    }

    @Override public void clear(Player player) {
        PacketContainer title = manager.createPacket(PacketType.Play.Server.CLEAR_TITLES);
        title.getBooleans().write(0, true);
        manager.sendServerPacket(player, title, false);
        PacketContainer action = manager.createPacket(PacketType.Play.Server.SET_ACTION_BAR_TEXT);
        action.getChatComponents().write(0, WrappedChatComponent.fromText(""));
        manager.sendServerPacket(player, action, false);
    }

    @Override public void fence(Player player, int id) {
        // A pong follows all earlier client flight requests on the same connection.
        PacketContainer ping = manager.createPacket(PacketType.Play.Server.PING);
        ping.getIntegers().write(0, id);
        manager.sendServerPacket(player, ping, false);
    }

    @Override public void chat(Player player, String json) {
        PacketContainer packet = manager.createPacket(PacketType.Play.Server.SYSTEM_CHAT);
        packet.getChatComponents().write(0, WrappedChatComponent.fromJson(json));
        packet.getBooleans().write(0, false);
        manager.sendServerPacket(player, packet, false);
    }

    @Override public void close() { manager.removePacketListener(listener); }
}
