package net.tfminecraft.rpcharacters.pvp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event.Result;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.tfminecraft.tlibs.armour.ArmorEquipEvent;
import net.tfminecraft.tlibs.armour.ArmorType;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.identity.MaskService;
import net.tfminecraft.rpcharacters.loaders.PvpLoader;
import net.tfminecraft.rpcharacters.utils.RPTexts;

/**
 * Putting armour on takes time, so it can't be thrown on the moment a fight
 * looks likely. Pieces are fastened one after another while the player is
 * slowed. Taking or dealing damage, sprinting, dying or logging out stops it.
 * Masks, heads and elytras go on at once, and creative or spectator players
 * are never delayed. {@code /pvp start} takes off anything put on recently.
 */
public final class ArmourDonning implements Listener {

	private static final long TICK_PERIOD = 2L;
	private static final int BAR_LENGTH = 20;
	private static final int OFF_HAND_SLOT = 40;
	private static final int STORAGE_SLOTS = 36;
	private static final int CURSOR = -1;
	private static final int NOT_FOUND = -2;

	private static final Map<UUID, Donning> donning = new HashMap<>();
	private static final Map<UUID, EnumMap<ArmorType, Long>> equippedAt = new HashMap<>();
	private static final Set<UUID> completing = new HashSet<>();
	private static BukkitTask ticker;

	private record Piece(ArmorType type, ItemStack item, ArmorEquipEvent.EquipMethod method) {
	}

	private static final class Donning {
		private final Deque<Piece> queue = new ArrayDeque<>();
		private Piece current;
		private long startMs;
		private long endMs;
	}

	public static void start() {
		shutdown();
		ticker = Bukkit.getScheduler().runTaskTimer(RPCharacters.plugin,
				() -> tick(System.currentTimeMillis()), TICK_PERIOD, TICK_PERIOD);
	}

	public static void shutdown() {
		if (ticker != null) {
			ticker.cancel();
			ticker = null;
		}
		for (UUID id : new ArrayList<>(donning.keySet())) {
			Player player = Bukkit.getPlayer(id);
			if (player != null) {
				stop(player, false);
			}
		}
		donning.clear();
	}

	/** Real armour: a helmet, chestplate, leggings or boots that is not a mask. */
	static boolean isArmourPiece(ItemStack item) {
		if (isEmpty(item)) {
			return false;
		}
		String name = item.getType().name();
		boolean armour = name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
				|| name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS");
		return armour && !MaskService.isMaskItem(item);
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onArmorEquip(ArmorEquipEvent event) {
		Player player = event.getPlayer();
		ItemStack piece = event.getNewArmorPiece();
		if (player == null || piece == null || event.getType() == null
				|| completing.contains(player.getUniqueId()) || !delays(player, event.getType(), piece)) {
			return;
		}
		event.setCancelled(true);
		if (event.getMethod() == ArmorEquipEvent.EquipMethod.DISPENSER) {
			return;
		}
		queue(player, event.getType(), piece, event.getMethod(), System.currentTimeMillis());
	}

	/** Remember when each piece went on, for {@link #takeOffRecent}. */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onArmorChanged(ArmorEquipEvent event) {
		Player player = event.getPlayer();
		if (player == null || event.getType() == null) {
			return;
		}
		EnumMap<ArmorType, Long> times = equippedAt.computeIfAbsent(player.getUniqueId(),
				id -> new EnumMap<>(ArmorType.class));
		if (event.getNewArmorPiece() == null) {
			times.remove(event.getType());
		} else {
			times.put(event.getType(), System.currentTimeMillis());
		}
	}

	/**
	 * Right-clicking armour while that slot is full swaps the two pieces without
	 * an {@link ArmorEquipEvent}. Undo the swap a tick later and fasten it instead.
	 */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onRightClickArmour(PlayerInteractEvent event) {
		if (event.useItemInHand() == Result.DENY || event.getHand() == null) {
			return;
		}
		Action action = event.getAction();
		if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
			return;
		}
		ItemStack held = event.getItem();
		ArmorType type = ArmorType.matchType(held);
		if (type == null) {
			return;
		}
		Player player = event.getPlayer();
		ItemStack worn = armour(player.getInventory(), type);
		if (isEmpty(worn) || held.isSimilar(worn)) {
			return;
		}
		int handSlot = event.getHand() == EquipmentSlot.OFF_HAND ? OFF_HAND_SLOT
				: player.getInventory().getHeldItemSlot();
		ItemStack wanted = held.clone();
		ItemStack previous = worn.clone();
		Bukkit.getScheduler().runTask(RPCharacters.plugin, () -> undoSwap(player, type, handSlot, wanted, previous));
	}

	static void undoSwap(Player player, ArmorType type, int handSlot, ItemStack wanted, ItemStack previous) {
		PlayerInventory inventory = player.getInventory();
		if (!player.isOnline() || !wanted.isSimilar(armour(inventory, type))
				|| !previous.isSimilar(inventory.getItem(handSlot))) {
			return;
		}
		boolean locked = PvpSituations.locksArmour(player.getUniqueId());
		if (!locked && !delays(player, type, wanted)) {
			return;
		}
		ItemStack swappedOn = armour(inventory, type);
		setArmour(inventory, type, inventory.getItem(handSlot));
		inventory.setItem(handSlot, swappedOn);
		player.updateInventory();
		if (locked) {
			RPTexts.send(player, PvpLoader.getArmourLocked());
			return;
		}
		queue(player, type, swappedOn, ArmorEquipEvent.EquipMethod.HOTBAR, System.currentTimeMillis());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onDamage(EntityDamageEvent event) {
		if (event.getEntity() instanceof Player victim) {
			stop(victim, true);
		}
		if (!(event instanceof EntityDamageByEntityEvent hit)) {
			return;
		}
		if (hit.getDamager() instanceof Player attacker) {
			stop(attacker, true);
		} else if (hit.getDamager() instanceof Projectile projectile
				&& projectile.getShooter() instanceof Player shooter) {
			stop(shooter, true);
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onSprint(PlayerToggleSprintEvent event) {
		if (event.isSprinting()) {
			stop(event.getPlayer(), true);
		}
	}

	@EventHandler
	public void onDeath(PlayerDeathEvent event) {
		stop(event.getEntity(), false);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		stop(event.getPlayer(), false);
	}

	/**
	 * For {@code /pvp start}: stop fastening, and take off any piece put on within
	 * {@code armour.recent-seconds}. Returns whether anything came off.
	 */
	public static boolean takeOffRecent(Player player, long nowMs) {
		stop(player, true);
		EnumMap<ArmorType, Long> times = equippedAt.get(player.getUniqueId());
		long windowMs = PvpLoader.getRecentArmourMs();
		if (times == null || windowMs <= 0L) {
			return false;
		}
		PlayerInventory inventory = player.getInventory();
		List<String> pieces = new ArrayList<>();
		for (ArmorType type : ArmorType.values()) {
			Long at = times.get(type);
			if (at == null || nowMs - at > windowMs) {
				continue;
			}
			times.remove(type);
			ItemStack worn = armour(inventory, type);
			if (!isArmourPiece(worn)) {
				continue;
			}
			setArmour(inventory, type, null);
			giveBack(player, worn);
			pieces.add(pieceName(type));
		}
		if (pieces.isEmpty()) {
			return false;
		}
		RPTexts.send(player, PvpLoader.getArmourStripped().replace("{pieces}", String.join(", ", pieces)));
		return true;
	}

	private static boolean delays(Player player, ArmorType type, ItemStack piece) {
		GameMode mode = player.getGameMode();
		return mode != GameMode.CREATIVE && mode != GameMode.SPECTATOR
				&& PvpLoader.getDonMs(type) > 0L && isArmourPiece(piece);
	}

	private static void queue(Player player, ArmorType type, ItemStack item, ArmorEquipEvent.EquipMethod method,
			long nowMs) {
		Donning state = donning.computeIfAbsent(player.getUniqueId(), id -> new Donning());
		boolean alreadyQueued = state.queue.stream().anyMatch(piece -> piece.type() == type);
		if (alreadyQueued || state.current != null && state.current.type() == type) {
			return;
		}
		ItemStack snapshot = item.clone();
		snapshot.setAmount(1);
		state.queue.add(new Piece(type, snapshot, method));
		if (state.current == null) {
			next(player, state, nowMs);
		} else {
			showProgress(player, state, nowMs);
		}
	}

	private static void next(Player player, Donning state, long nowMs) {
		Piece piece = state.queue.poll();
		state.current = piece;
		if (piece == null) {
			donning.remove(player.getUniqueId());
			clearSlowness(player);
			return;
		}
		long durationMs = Math.max(1L, PvpLoader.getDonMs(piece.type()));
		state.startMs = nowMs;
		state.endMs = nowMs + durationMs;
		int amplifier = PvpLoader.getDonSlownessAmplifier();
		if (amplifier >= 0) {
			player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int) (durationMs / 50L) + 20,
					amplifier, false, false, true));
		}
		showProgress(player, state, nowMs);
	}

	static void tick(long nowMs) {
		for (Map.Entry<UUID, Donning> entry : new ArrayList<>(donning.entrySet())) {
			Player player = Bukkit.getPlayer(entry.getKey());
			Donning state = entry.getValue();
			if (player == null || !player.isOnline()) {
				donning.remove(entry.getKey());
			} else if (nowMs >= state.endMs) {
				finish(player, state, nowMs);
			} else {
				showProgress(player, state, nowMs);
			}
		}
	}

	private static void finish(Player player, Donning state, long nowMs) {
		Piece piece = state.current;
		if (PvpSituations.locksArmour(player.getUniqueId())) {
			RPTexts.send(player, PvpLoader.getArmourLocked());
			stop(player, false);
			return;
		}
		if (equip(player, piece)) {
			actionBar(player, PvpLoader.getDonDone().replace("{piece}", pieceName(piece.type())));
			player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 1f, 1f);
		}
		next(player, state, nowMs);
	}

	/** Move the piece from wherever it now is into its slot, through a fresh equip event. */
	private static boolean equip(Player player, Piece piece) {
		PlayerInventory inventory = player.getInventory();
		int slot = find(player, piece.item());
		if (slot == NOT_FOUND) {
			RPTexts.send(player, PvpLoader.getDonMissing().replace("{piece}", pieceName(piece.type())));
			return false;
		}
		ItemStack source = slot == CURSOR ? player.getItemOnCursor() : inventory.getItem(slot);
		ItemStack wearing = source.clone();
		wearing.setAmount(1);
		ItemStack worn = armour(inventory, piece.type());
		ArmorEquipEvent event = new ArmorEquipEvent(player, piece.method(), piece.type(),
				isEmpty(worn) ? null : worn, wearing);
		completing.add(player.getUniqueId());
		try {
			Bukkit.getPluginManager().callEvent(event);
		} finally {
			completing.remove(player.getUniqueId());
		}
		if (event.isCancelled()) {
			RPTexts.send(player, PvpLoader.getDonInterrupted().replace("{piece}", pieceName(piece.type())));
			return false;
		}
		// A listener may have moved or used the piece, so look it up again.
		slot = find(player, piece.item());
		if (slot == NOT_FOUND) {
			RPTexts.send(player, PvpLoader.getDonMissing().replace("{piece}", pieceName(piece.type())));
			return false;
		}
		source = slot == CURSOR ? player.getItemOnCursor() : inventory.getItem(slot);
		worn = armour(inventory, piece.type());
		source.setAmount(source.getAmount() - 1);
		ItemStack rest = source.getAmount() > 0 ? source : null;
		if (slot == CURSOR) {
			player.setItemOnCursor(rest);
		} else {
			inventory.setItem(slot, rest);
		}
		setArmour(inventory, piece.type(), wearing);
		if (!isEmpty(worn)) {
			giveBack(player, worn);
		}
		return true;
	}

	private static int find(Player player, ItemStack item) {
		if (item.isSimilar(player.getItemOnCursor())) {
			return CURSOR;
		}
		PlayerInventory inventory = player.getInventory();
		for (int slot = 0; slot < STORAGE_SLOTS; slot++) {
			if (item.isSimilar(inventory.getItem(slot))) {
				return slot;
			}
		}
		return item.isSimilar(inventory.getItem(OFF_HAND_SLOT)) ? OFF_HAND_SLOT : NOT_FOUND;
	}

	/** Stop fastening. The piece stays where it is. */
	static void stop(Player player, boolean tell) {
		Donning state = donning.remove(player.getUniqueId());
		if (state == null) {
			return;
		}
		clearSlowness(player);
		player.sendActionBar(Component.empty());
		if (tell) {
			RPTexts.send(player, PvpLoader.getDonInterrupted().replace("{piece}", pieceName(state.current.type())));
		}
	}

	/** Remove only the slowness this applied: same strength, no particles. */
	private static void clearSlowness(Player player) {
		PotionEffect effect = player.getPotionEffect(PotionEffectType.SLOWNESS);
		if (effect != null && effect.getAmplifier() == PvpLoader.getDonSlownessAmplifier() && !effect.hasParticles()) {
			player.removePotionEffect(PotionEffectType.SLOWNESS);
		}
	}

	private static void showProgress(Player player, Donning state, long nowMs) {
		long total = state.endMs - state.startMs;
		long left = Math.max(0L, state.endMs - nowMs);
		int filled = (int) ((total - left) * BAR_LENGTH / total);
		String bar = "&a" + "|".repeat(filled) + "&8" + "|".repeat(BAR_LENGTH - filled);
		String queued = state.queue.isEmpty() ? ""
				: PvpLoader.getDonQueued().replace("{count}", String.valueOf(state.queue.size()));
		actionBar(player, PvpLoader.getDonProgress()
				.replace("{piece}", pieceName(state.current.type()))
				.replace("{bar}", bar)
				.replace("{seconds}", String.valueOf((left + 999L) / 1000L))
				.replace("{queued}", queued));
	}

	private static void actionBar(Player player, String raw) {
		player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(RPTexts.formatDisplay(raw)));
	}

	/** Armour doesn't stack, so it goes in the first free storage slot or on the ground. */
	private static void giveBack(Player player, ItemStack item) {
		PlayerInventory inventory = player.getInventory();
		for (int slot = 0; slot < STORAGE_SLOTS; slot++) {
			if (isEmpty(inventory.getItem(slot))) {
				inventory.setItem(slot, item);
				return;
			}
		}
		player.getWorld().dropItemNaturally(player.getLocation(), item);
	}

	private static String pieceName(ArmorType type) {
		return type.name().toLowerCase(Locale.ROOT);
	}

	private static ItemStack armour(PlayerInventory inventory, ArmorType type) {
		return switch (type) {
			case HELMET -> inventory.getHelmet();
			case CHESTPLATE -> inventory.getChestplate();
			case LEGGINGS -> inventory.getLeggings();
			case BOOTS -> inventory.getBoots();
		};
	}

	private static void setArmour(PlayerInventory inventory, ArmorType type, ItemStack item) {
		switch (type) {
			case HELMET -> inventory.setHelmet(item);
			case CHESTPLATE -> inventory.setChestplate(item);
			case LEGGINGS -> inventory.setLeggings(item);
			case BOOTS -> inventory.setBoots(item);
		}
	}

	private static boolean isEmpty(ItemStack item) {
		return item == null || item.getType().isAir() || item.getAmount() <= 0;
	}

	static void clear() {
		donning.clear();
		equippedAt.clear();
		completing.clear();
		ticker = null;
	}
}
