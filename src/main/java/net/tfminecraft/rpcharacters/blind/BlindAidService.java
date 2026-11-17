package net.tfminecraft.rpcharacters.blind;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * Help for blind characters, who keep the Blindness fog: walking faster with a cane while
 * holding sprint, outlines of creatures they can hear, a cane tap that outlines walls, drops,
 * doors and hazards nearby, and warnings before a drop or a hazard.
 */
public final class BlindAidService implements Listener {

	static final NamespacedKey STRIDE_KEY = new NamespacedKey("rpcharacters", "blind_stride");

	private final Plugin plugin;
	private final Supplier<BlindAidSettings> settings;
	private final Predicate<Player> blind;
	private final BiPredicate<ItemStack, List<String>> cane;
	private final GlowPackets glow;
	private final Function<World, Surroundings.Probe> probes;
	private final LongSupplier clock;
	private final Supplier<? extends Collection<? extends Player>> online;
	private final Function<UUID, Entity> entities;
	private final Map<UUID, Viewer> viewers = new HashMap<>();
	private Map<UUID, Location> lastSeen = new HashMap<>();
	private BukkitTask task;

	static final class Viewer {
		Location last;
		Set<UUID> glowing = new HashSet<>();
		long tapReadyAt;
		long warnReadyAt;
		List<Entity> markers = new ArrayList<>();
	}

	/** {@code glow} is null when ProtocolLib is missing; creatures then get no outline. */
	public BlindAidService(Plugin plugin, Supplier<BlindAidSettings> settings, Predicate<Player> blind,
			BiPredicate<ItemStack, List<String>> cane, GlowPackets glow, Function<World, Surroundings.Probe> probes,
			LongSupplier clock, Supplier<? extends Collection<? extends Player>> online, Function<UUID, Entity> entities) {
		this.plugin = plugin;
		this.settings = settings;
		this.blind = blind;
		this.cane = cane;
		this.glow = glow;
		this.probes = probes;
		this.clock = clock;
		this.online = online;
		this.entities = entities;
	}

	public void start() {
		stop();
		long period = settings.get().checkTicks();
		task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, period, period);
	}

	public void stop() {
		if (task != null) task.cancel();
		task = null;
		for (Player p : online.get()) clear(p);
		viewers.clear();
		lastSeen = new HashMap<>();
	}

	Viewer viewer(Player p) {
		return viewers.get(p.getUniqueId());
	}

	void tick() {
		BlindAidSettings s = settings.get();
		Map<UUID, Location> seen = new HashMap<>();
		for (Player p : online.get()) {
			if (!s.enabled() || !blind.test(p)) {
				clear(p);
				continue;
			}
			Viewer v = viewers.computeIfAbsent(p.getUniqueId(), id -> new Viewer());
			Location here = p.getLocation();
			double walked = v.last != null && v.last.getWorld() == here.getWorld()
					? Math.hypot(here.getX() - v.last.getX(), here.getZ() - v.last.getZ()) : 0;
			v.last = here;
			stride(p, s, p.getCurrentInput());
			if (walked > 0 && striding(p)) {
				p.setExhaustion(p.getExhaustion() + s.strideExhaustionPerBlock() * (float) walked);
			}
			if (s.warnEnabled() && walked > 0.05) warn(p, v, s);
			sense(p, v, s, seen);
		}
		lastSeen = seen;
	}

	// ------------------------------------------------------------------ stride

	void stride(Player p, BlindAidSettings s, Input input) {
		AttributeInstance speed = p.getAttribute(Attribute.MOVEMENT_SPEED);
		if (speed == null) return;
		boolean want = s.strideEnabled() && input.isForward() && input.isSprint() && !input.isSneak()
				&& p.getFoodLevel() >= s.strideMinFood() && !p.isFlying() && !p.isGliding() && !p.isSwimming()
				&& !p.isInsideVehicle() && holdsCane(p, s);
		boolean has = speed.getModifier(STRIDE_KEY) != null;
		if (want && !has) {
			speed.addTransientModifier(new AttributeModifier(STRIDE_KEY, s.strideBonus(),
					AttributeModifier.Operation.MULTIPLY_SCALAR_1));
		} else if (!want && has) {
			speed.removeModifier(STRIDE_KEY);
		}
	}

	static boolean striding(Player p) {
		AttributeInstance speed = p.getAttribute(Attribute.MOVEMENT_SPEED);
		return speed != null && speed.getModifier(STRIDE_KEY) != null;
	}

	private static void endStride(Player p) {
		AttributeInstance speed = p.getAttribute(Attribute.MOVEMENT_SPEED);
		if (speed != null && speed.getModifier(STRIDE_KEY) != null) speed.removeModifier(STRIDE_KEY);
	}

	boolean holdsCane(Player p, BlindAidSettings s) {
		return isCane(p.getInventory().getItemInMainHand(), s) || isCane(p.getInventory().getItemInOffHand(), s);
	}

	private boolean isCane(ItemStack item, BlindAidSettings s) {
		return item != null && !item.getType().isAir() && cane.test(item, s.caneItems());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onInput(PlayerInputEvent e) {
		if (viewers.containsKey(e.getPlayer().getUniqueId())) stride(e.getPlayer(), settings.get(), e.getInput());
	}

	// ------------------------------------------------------------------ sound sense

	private void sense(Player p, Viewer v, BlindAidSettings s, Map<UUID, Location> seen) {
		Set<UUID> keep = new HashSet<>();
		if (s.senseEnabled() && glow != null) {
			double r = s.senseRadius();
			for (Entity e : p.getNearbyEntities(r, r, r)) {
				if (!(e instanceof LivingEntity) || e instanceof ArmorStand) continue;
				Location at = e.getLocation();
				if (at.distanceSquared(p.getLocation()) > r * r) continue;
				seen.put(e.getUniqueId(), at);
				if (heard(p, e, at, s)) {
					keep.add(e.getUniqueId());
					glow.send(p, e, true);
				}
			}
		}
		for (UUID id : v.glowing) {
			if (keep.contains(id)) continue;
			Entity e = entities.apply(id);
			if (e != null) glow.send(p, e, false);
		}
		v.glowing = keep;
	}

	private boolean heard(Player p, Entity e, Location at, BlindAidSettings s) {
		if (e.isSneaking() || e.isInvisible()) return false;
		if (e instanceof Player other && (!p.canSee(other) || other.getGameMode() == GameMode.SPECTATOR)) return false;
		Location before = lastSeen.get(e.getUniqueId());
		if (before == null || before.getWorld() != at.getWorld()
				|| before.distanceSquared(at) < s.senseMinMove() * s.senseMinMove()) {
			return false;
		}
		return !s.senseLineOfSight() || p.hasLineOfSight(e);
	}

	// ------------------------------------------------------------------ cane tap

	@EventHandler(priority = EventPriority.MONITOR)
	public void onInteract(PlayerInteractEvent e) {
		if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
		Player p = e.getPlayer();
		Viewer v = viewers.get(p.getUniqueId());
		BlindAidSettings s = settings.get();
		if (v == null || !s.tapEnabled() || !isCane(e.getItem(), s)) return;
		long now = clock.getAsLong();
		if (now < v.tapReadyAt) return;
		v.tapReadyAt = now + s.tapCooldownMillis();
		tap(p, v, s);
	}

	private void tap(Player p, Viewer v, BlindAidSettings s) {
		removeMarkers(v.markers);
		Location at = p.getLocation();
		World world = at.getWorld();
		List<Entity> markers = new ArrayList<>();
		for (Surroundings.Spot spot : Surroundings.tap(probes.apply(world), at.getBlockX(), at.getBlockY(),
				at.getBlockZ(), s.tapRadius(), s.tapMaxMarkers())) {
			BlockData data = spot.marker() == Surroundings.Marker.DROP ? Material.WHITE_CARPET.createBlockData()
					: world.getBlockAt(spot.x(), spot.y(), spot.z()).getBlockData();
			Color colour = colour(spot.marker());
			BlockDisplay display = world.spawn(new Location(world, spot.x(), spot.y(), spot.z()), BlockDisplay.class, d -> {
				d.setVisibleByDefault(false);
				d.setPersistent(false);
				d.setBlock(data);
				d.setGlowing(true);
				d.setGlowColorOverride(colour);
			});
			p.showEntity(plugin, display);
			markers.add(display);
		}
		v.markers = markers;
		p.playSound(at, s.tapSound(), 1f, 1f);
		Bukkit.getScheduler().runTaskLater(plugin, () -> removeMarkers(markers), s.tapTicks());
	}

	static Color colour(Surroundings.Marker marker) {
		return switch (marker) {
			case HAZARD -> Color.ORANGE;
			case DROP -> Color.RED;
			case DOOR -> Color.YELLOW;
			case WALL -> Color.WHITE;
		};
	}

	private static void removeMarkers(List<Entity> markers) {
		for (Entity marker : markers) marker.remove();
	}

	// ------------------------------------------------------------------ warnings

	private void warn(Player p, Viewer v, BlindAidSettings s) {
		long now = clock.getAsLong();
		if (now < v.warnReadyAt) return;
		Location at = p.getLocation();
		Vector look = at.getDirection().setY(0);
		if (look.lengthSquared() < 1.0E-4) return;
		look.normalize();
		Optional<Surroundings.Marker> ahead = Surroundings.ahead(probes.apply(at.getWorld()), at.getX(), at.getBlockY(),
				at.getZ(), look.getX(), look.getZ(), s.warnLookAhead(), s.warnDropDepth());
		if (ahead.isEmpty()) return;
		p.sendActionBar(ahead.get() == Surroundings.Marker.DROP ? s.warnDropMessage() : s.warnHazardMessage());
		p.playSound(at, s.warnSound(), 1f, 0.6f);
		v.warnReadyAt = now + s.warnCooldownMillis();
	}

	// ------------------------------------------------------------------ clean-up

	@EventHandler
	public void onQuit(PlayerQuitEvent e) {
		clear(e.getPlayer());
	}

	private void clear(Player p) {
		endStride(p);
		Viewer v = viewers.remove(p.getUniqueId());
		if (v == null) return;
		for (UUID id : v.glowing) {
			Entity e = entities.apply(id);
			if (e != null) glow.send(p, e, false);
		}
		removeMarkers(v.markers);
	}
}
