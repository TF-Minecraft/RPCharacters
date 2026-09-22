package net.tfminecraft.rpcharacters.display;

import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;

import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import net.tfminecraft.rpcharacters.RPCharacters;

public final class TextDisplayHelper {

	private TextDisplayHelper() {}

	public static Transformation createScaleTransformation(float scale) {
		return new Transformation(
				new Vector3f(0, 0, 0),
				new AxisAngle4f(0, 0, 0, 1),
				new Vector3f(scale, scale, scale),
				new AxisAngle4f(0, 0, 0, 1));
	}

	public static Location lerpLocation(Location from, Location to, double factor) {
		if (to == null) {
			return null;
		}
		if (from == null || from.getWorld() == null || to.getWorld() == null
				|| !from.getWorld().equals(to.getWorld())) {
			return to.clone();
		}
		double t = Math.max(0.0, Math.min(1.0, factor));
		Location result = from.clone();
		result.setX(from.getX() + (to.getX() - from.getX()) * t);
		result.setY(from.getY() + (to.getY() - from.getY()) * t);
		result.setZ(from.getZ() + (to.getZ() - from.getZ()) * t);
		result.setYaw((float) (from.getYaw() + (to.getYaw() - from.getYaw()) * t));
		result.setPitch((float) (from.getPitch() + (to.getPitch() - from.getPitch()) * t));
		return result;
	}

	public static TextDisplay findDisplay(java.util.UUID entityId) {
		if (entityId == null) {
			return null;
		}
		Entity entity = Bukkit.getEntity(entityId);
		return entity instanceof TextDisplay textDisplay ? textDisplay : null;
	}

	public static void removeDisplay(java.util.UUID entityId) {
		if (entityId == null) {
			return;
		}
		Entity entity = Bukkit.getEntity(entityId);
		if (entity != null && !entity.isDead()) {
			entity.remove();
		}
	}

	public static TextDisplay getOrCreateDisplay(java.util.UUID entityId, World world, Location location,
			Consumer<TextDisplay> spawner) {
		TextDisplay existing = findDisplay(entityId);
		if (existing != null && !existing.isDead()) {
			return existing;
		}
		return world.spawn(location, TextDisplay.class, spawner::accept);
	}

	public static void applyDisplay(TextDisplay display, String text, Transformation transformation,
			boolean persistent) {
		display.setText(text);
		display.setBillboard(Display.Billboard.CENTER);
		display.setSeeThrough(false);
		display.setShadowed(true);
		display.setInvulnerable(true);
		display.setGravity(false);
		display.setPersistent(persistent);
		display.setAlignment(TextDisplay.TextAlignment.CENTER);
		display.setTransformation(transformation);
	}

	public static void setTag(TextDisplay display, NamespacedKey key, String value) {
		display.getPersistentDataContainer().set(key, PersistentDataType.STRING, value);
	}

	public static void setTag(TextDisplay display, NamespacedKey key, int value) {
		display.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, value);
	}

	public static String getStringTag(TextDisplay display, NamespacedKey key) {
		return display.getPersistentDataContainer().get(key, PersistentDataType.STRING);
	}

	public static NamespacedKey key(String name) {
		return new NamespacedKey(RPCharacters.plugin, name);
	}
}
