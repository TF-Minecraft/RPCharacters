package net.tfminecraft.rpcharacters.blind;

import java.util.List;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;

/**
 * Makes an entity glow for one viewer only, by sending that viewer the entity's shared
 * flags with the glowing bit set. Glow outlines show through the Blindness fog.
 */
public class GlowPackets {

	static final byte ON_FIRE = 0x01;
	static final byte SNEAKING = 0x02;
	static final byte SPRINTING = 0x08;
	static final byte SWIMMING = 0x10;
	static final byte INVISIBLE = 0x20;
	static final byte GLOWING = 0x40;
	static final byte GLIDING = (byte) 0x80;

	private final ProtocolManager protocol;

	public GlowPackets(ProtocolManager protocol) {
		this.protocol = protocol;
	}

	/** Shows {@code target} to {@code viewer} glowing ({@code glow}) or as it really is. */
	public void send(Player viewer, Entity target, boolean glow) {
		byte flags = flags(target);
		if (glow) flags |= GLOWING;
		PacketContainer packet = protocol.createPacket(PacketType.Play.Server.ENTITY_METADATA);
		packet.getIntegers().write(0, target.getEntityId());
		packet.getDataValueCollectionModifier().write(0, List.of(
				new WrappedDataValue(0, WrappedDataWatcher.Registry.get((java.lang.reflect.Type) Byte.class, false), flags)));
		protocol.sendServerPacket(viewer, packet);
	}

	/** The entity's real shared flags (metadata index 0). */
	static byte flags(Entity e) {
		int flags = 0;
		if (e.getFireTicks() > 0 || e.isVisualFire()) flags |= ON_FIRE;
		if (e.isSneaking()) flags |= SNEAKING;
		if (e instanceof Player p && p.isSprinting()) flags |= SPRINTING;
		if (e instanceof LivingEntity l && l.isSwimming()) flags |= SWIMMING;
		if (e.isInvisible()) flags |= INVISIBLE;
		if (e.isGlowing()) flags |= GLOWING;
		if (e instanceof LivingEntity l && l.isGliding()) flags |= GLIDING;
		return (byte) flags;
	}
}
