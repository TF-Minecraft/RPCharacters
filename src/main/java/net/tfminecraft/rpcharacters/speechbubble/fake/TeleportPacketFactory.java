package net.tfminecraft.rpcharacters.speechbubble.fake;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.Collections;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Server;

/**
 * Builds 1.21.2+ entity teleport packets by calling the server's packet record constructors directly.
 *
 * <p>ProtocolLib's {@code createPacket(ENTITY_TELEPORT, true)} makes a blank packet by decoding a zeroed
 * buffer, fills in defaults, then writes every field through reflection. That was most of the cost of
 * speech bubbles following their speakers. This looks the constructors up once and fills in the same
 * values ProtocolLib wrote: an absolute position (no relative flags), zero velocity, the location's yaw
 * and pitch, and not on ground.
 *
 * <p>Detection only accepts the exact record shape
 * {@code ClientboundTeleportEntityPacket(int, PositionMoveRotation(Vec3, Vec3, float, float), Set, boolean)}.
 * Anything else returns {@code null}, and callers keep using ProtocolLib's builder.
 */
final class TeleportPacketFactory {

	static final String PACKET_CLASS = "net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket";

	private static final Set<Object> NO_RELATIVE_FLAGS = Collections.emptySet();

	private final MethodHandle vector;
	private final MethodHandle change;
	private final MethodHandle packet;
	private final Object zeroVector;

	private TeleportPacketFactory(MethodHandle vector, MethodHandle change, MethodHandle packet, Object zeroVector) {
		this.vector = vector;
		this.change = change;
		this.packet = packet;
		this.zeroVector = zeroVector;
	}

	/** Finds the running server's teleport packet record, or returns {@code null} when it has another shape. */
	static TeleportPacketFactory detect() {
		Server server = Bukkit.getServer();
		return server == null ? null : detect(server.getClass().getClassLoader(), PACKET_CLASS);
	}

	static TeleportPacketFactory detect(ClassLoader loader, String packetClassName) {
		try {
			return forPacketClass(Class.forName(packetClassName, false, loader));
		} catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
			return null;
		}
	}

	static TeleportPacketFactory forPacketClass(Class<?> packetClass) {
		RecordComponent[] packetParts = packetClass.getRecordComponents();
		if (packetParts == null || packetParts.length != 4 || packetParts[0].getType() != int.class
				|| packetParts[2].getType() != Set.class || packetParts[3].getType() != boolean.class) {
			return null;
		}
		Class<?> changeClass = packetParts[1].getType();
		RecordComponent[] changeParts = changeClass.getRecordComponents();
		if (changeParts == null || changeParts.length != 4 || changeParts[0].getType() != changeParts[1].getType()
				|| changeParts[2].getType() != float.class || changeParts[3].getType() != float.class) {
			return null;
		}
		Class<?> vectorClass = changeParts[0].getType();
		try {
			MethodHandles.Lookup lookup = MethodHandles.lookup();
			Constructor<?> vectorConstructor = vectorClass.getConstructor(double.class, double.class, double.class);
			MethodHandle vector = lookup.unreflectConstructor(vectorConstructor)
					.asType(MethodType.methodType(Object.class, double.class, double.class, double.class));
			MethodHandle change = lookup
					.unreflectConstructor(changeClass.getConstructor(vectorClass, vectorClass, float.class, float.class))
					.asType(MethodType.methodType(Object.class, Object.class, Object.class, float.class, float.class));
			MethodHandle packet = lookup
					.unreflectConstructor(packetClass.getConstructor(int.class, changeClass, Set.class, boolean.class))
					.asType(MethodType.methodType(Object.class, int.class, Object.class, Set.class, boolean.class));
			return new TeleportPacketFactory(vector, change, packet, vectorConstructor.newInstance(0.0, 0.0, 0.0));
		} catch (ReflectiveOperationException | RuntimeException ex) {
			return null;
		}
	}

	/** Returns a new NMS teleport packet; the records are immutable, so one is needed per send. */
	Object create(int entityId, double x, double y, double z, float yaw, float pitch) {
		try {
			Object position = (Object) vector.invokeExact(x, y, z);
			Object movement = (Object) change.invokeExact(position, zeroVector, yaw, pitch);
			return (Object) packet.invokeExact(entityId, movement, (Set<?>) NO_RELATIVE_FLAGS, false);
		} catch (Throwable ex) {
			throw new IllegalStateException("Cannot build a teleport packet: " + ex, ex);
		}
	}
}
