package net.tfminecraft.rpcharacters.speechbubble.fake;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.InternalStructure;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;

import org.joml.Vector3f;

import net.tfminecraft.rpcharacters.RPCharacters;

/**
 * Client-side TextDisplay packets for per-viewer speech bubbles.
 * Metadata indices match Minecraft 1.21.x Text Display entity (Display base + text fields).
 * Scale must use {@link org.joml.Vector3f} with {@code Registry.get(Vector3f.class)} — not Vector3F.
 *
 * <p>On 1.21+, spawn/teleport packets use nested NMS structures instead of flat short/double fields.
 */
public final class FakeTextDisplayPackets {

	private static final int META_SCALE = 12;
	private static final int META_BILLBOARD = 15;
	private static final int META_TEXT = 23;
	private static final int META_LINE_WIDTH = 24;
	private static final int META_BACKGROUND = 25;
	private static final int META_TEXT_OPACITY = 26;
	private static final int META_TEXT_FLAGS = 27;

	private static final byte BILLBOARD_CENTER = 3;
	private static final int BACKGROUND_TRANSPARENT = 1073741824;
	private static final byte TEXT_OPACITY_DEFAULT = -1;
	private static final byte TEXT_FLAG_SHADOW = 0x01;

	private final ProtocolManager protocolManager;

	public FakeTextDisplayPackets(ProtocolManager protocolManager) {
		this.protocolManager = protocolManager;
	}

	public void spawn(Player viewer, int entityId, UUID entityUuid, Location location, String text, float scale,
			int lineWidth) {
		if (viewer == null || location == null || location.getWorld() == null) {
			return;
		}
		send(viewer, createSpawnPacket(entityId, entityUuid, location));
		send(viewer, createMetadataPacket(entityId, text, scale, lineWidth));
	}

	public void updateText(Player viewer, int entityId, String text, float scale, int lineWidth) {
		send(viewer, createMetadataPacket(entityId, text, scale, lineWidth));
	}

	public void teleport(Player viewer, int entityId, Location location) {
		if (viewer == null || location == null) {
			return;
		}
		send(viewer, createTeleportPacket(entityId, location));
	}

	public void destroy(Player viewer, List<Integer> entityIds) {
		if (viewer == null || entityIds == null || entityIds.isEmpty()) {
			return;
		}
		PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
		packet.getIntLists().write(0, entityIds);
		send(viewer, packet);
	}

	private PacketContainer createSpawnPacket(int entityId, UUID entityUuid, Location location) {
		PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.SPAWN_ENTITY, true);
		packet.getIntegers().write(0, entityId);
		packet.getUUIDs().write(0, entityUuid);
		packet.getEntityTypeModifier().write(0, EntityType.TEXT_DISPLAY);
		writeEntityPosition(packet, location);
		return packet;
	}

	private PacketContainer createTeleportPacket(int entityId, Location location) {
		PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_TELEPORT, true);
		packet.getIntegers().write(0, entityId);
		writeEntityPosition(packet, location);
		return packet;
	}

	/**
	 * Writes position/rotation for spawn and teleport packets on both legacy flat and 1.21+ nested layouts.
	 */
	private void writeEntityPosition(PacketContainer packet, Location location) {
		StructureModifier<Double> doubles = packet.getDoubles();
		if (doubles.size() >= 3) {
			doubles.write(0, location.getX());
			doubles.write(1, location.getY());
			doubles.write(2, location.getZ());
			if (doubles.size() >= 6) {
				doubles.write(3, 0.0);
				doubles.write(4, 0.0);
				doubles.write(5, 0.0);
			}
			writeRotationBytes(packet, location);
			return;
		}

		InternalStructure movement = readFirstStructure(packet);
		StructureModifier<Vector> vectors = movement.getVectors();
		if (vectors.size() >= 1) {
			vectors.write(0, new Vector(location.getX(), location.getY(), location.getZ()));
		}
		if (vectors.size() >= 2) {
			vectors.write(1, new Vector(0, 0, 0));
		}

		StructureModifier<Float> floats = movement.getFloat();
		if (floats.size() >= 1) {
			floats.write(0, location.getYaw());
		}
		if (floats.size() >= 2) {
			floats.write(1, location.getPitch());
		}
	}

	private static InternalStructure readFirstStructure(PacketContainer packet) {
		List<InternalStructure> structures = packet.getStructures().getValues();
		if (structures == null || structures.isEmpty()) {
			throw new IllegalStateException("Packet has no writable position structure: " + packet.getType());
		}
		return structures.get(0);
	}

	private static void writeRotationBytes(PacketContainer packet, Location location) {
		StructureModifier<Byte> bytes = packet.getBytes();
		if (bytes.size() >= 1) {
			bytes.write(0, angleToByte(location.getYaw()));
		}
		if (bytes.size() >= 2) {
			bytes.write(1, angleToByte(location.getPitch()));
		}
		if (bytes.size() >= 3) {
			bytes.write(2, angleToByte(location.getYaw()));
		}
	}

	private static byte angleToByte(float angle) {
		return (byte) (int) (angle * 256.0F / 360.0F);
	}

	private PacketContainer createMetadataPacket(int entityId, String text, float scale, int lineWidth) {
		PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_METADATA);
		packet.getIntegers().write(0, entityId);
		packet.getDataValueCollectionModifier().write(0, buildMetadata(text, scale, lineWidth));
		return packet;
	}

	private List<WrappedDataValue> buildMetadata(String text, float scale, int lineWidth) {
		List<WrappedDataValue> values = new ArrayList<>();
		values.add(new WrappedDataValue(META_SCALE, WrappedDataWatcher.Registry.get(Vector3f.class),
				new Vector3f(scale, scale, scale)));
		values.add(new WrappedDataValue(META_BILLBOARD, WrappedDataWatcher.Registry.get(Byte.class), BILLBOARD_CENTER));
		values.add(new WrappedDataValue(META_TEXT, WrappedDataWatcher.Registry.getChatComponentSerializer(false),
				WrappedChatComponent.fromText(text != null ? text : "").getHandle()));
		values.add(new WrappedDataValue(META_LINE_WIDTH, WrappedDataWatcher.Registry.get(Integer.class), lineWidth));
		values.add(new WrappedDataValue(META_BACKGROUND, WrappedDataWatcher.Registry.get(Integer.class),
				BACKGROUND_TRANSPARENT));
		values.add(new WrappedDataValue(META_TEXT_OPACITY, WrappedDataWatcher.Registry.get(Byte.class),
				TEXT_OPACITY_DEFAULT));
		values.add(new WrappedDataValue(META_TEXT_FLAGS, WrappedDataWatcher.Registry.get(Byte.class), TEXT_FLAG_SHADOW));
		return values;
	}

	private void send(Player viewer, PacketContainer packet) {
		try {
			protocolManager.sendServerPacket(viewer, packet);
		} catch (Exception e) {
			if (RPCharacters.plugin != null) {
				RPCharacters.plugin.getLogger().warning(
						"Failed to send fake TextDisplay packet to " + viewer.getName() + ": " + e.getMessage());
			}
		}
	}
}
