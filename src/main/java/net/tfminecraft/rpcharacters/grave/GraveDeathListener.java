package net.tfminecraft.rpcharacters.grave;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;

public final class GraveDeathListener implements Listener {

	private static final String PROTECT_PERMISSION = "rpchar.grave.protect";

	private final Map<UUID, Map<Integer, ItemStack>> excludedStash = new ConcurrentHashMap<>();
	private final Map<UUID, List<String>> placedNotice = new ConcurrentHashMap<>();

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onPlayerDeath(PlayerDeathEvent event) {
		if (!GraveLoader.isEnabled()) {
			return;
		}
		if (event.getKeepInventory()) {
			return;
		}
		Player victim = event.getEntity();
		PlayerInventory inventory = victim.getInventory();
		ItemStack[] storage = cloneArray(inventory.getStorageContents());
		ItemStack[] armor = cloneArray(inventory.getArmorContents());
		ItemStack offhand = cloneItem(inventory.getItemInOffHand());
		Map<Integer, ItemStack> stash = new HashMap<>();
		offhand = stripKept(storage, armor, offhand, stash);
		if (!hasStoreableItems(storage, armor, offhand) && event.getDroppedExp() <= 0) {
			return;
		}

		Block chestBlock = GraveManager.get().findChestBlock(victim, victim.getLocation());
		if (chestBlock == null) {
			return;
		}

		Player killerPlayer = victim.getKiller();
		UUID killer = killerPlayer != null && !killerPlayer.getUniqueId().equals(victim.getUniqueId())
				? killerPlayer.getUniqueId()
				: null;
		boolean protect = GraveLoader.isProtectByDefault() || victim.hasPermission(PROTECT_PERMISSION);
		int experience = event.getDroppedExp();
		String killerDisplay = GraveKillerDisplay.build(victim, killerPlayer);

		Grave grave = GraveManager.get().spawn(victim, chestBlock, killer, killerDisplay, experience, protect,
				storage, armor, offhand, List.of());
		if (grave == null) {
			return;
		}

		if (!stash.isEmpty()) {
			excludedStash.put(victim.getUniqueId(), stash);
		}
		sendPlaced(victim, chestBlock);
		event.setDroppedExp(0);
		event.getDrops().clear();
	}

	@EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
	public void onPlayerRespawn(PlayerRespawnEvent event) {
		Player player = event.getPlayer();
		Map<Integer, ItemStack> stashed = excludedStash.remove(player.getUniqueId());
		if (stashed != null && !stashed.isEmpty()) {
			PlayerInventory inventory = player.getInventory();
			for (Map.Entry<Integer, ItemStack> entry : stashed.entrySet()) {
				inventory.setItem(entry.getKey(), entry.getValue());
			}
		}
		List<String> notices = placedNotice.remove(player.getUniqueId());
		if (notices != null) {
			for (String notice : notices) {
				if (notice != null && !notice.isBlank()) {
					player.sendMessage(notice);
				}
			}
		}
	}

	private static ItemStack stripKept(ItemStack[] storage, ItemStack[] armor, ItemStack offhand,
			Map<Integer, ItemStack> stash) {
		if (storage != null) {
			for (int i = 0; i < storage.length; i++) {
				if (GraveLoader.keepOutOfGrave(i, storage[i])) {
					stash.put(i, storage[i].clone());
					storage[i] = null;
				}
			}
		}
		if (armor != null) {
			for (int i = 0; i < armor.length; i++) {
				int slot = Grave.STORAGE_SLOTS + i;
				if (GraveLoader.keepOutOfGrave(slot, armor[i])) {
					stash.put(slot, armor[i].clone());
					armor[i] = null;
				}
			}
		}
		int offhandSlot = Grave.STORAGE_SLOTS + Grave.ARMOR_SLOTS;
		if (GraveLoader.keepOutOfGrave(offhandSlot, offhand)) {
			stash.put(offhandSlot, offhand.clone());
			return null;
		}
		return offhand;
	}

	private static boolean hasStoreableItems(ItemStack[] storage, ItemStack[] armor, ItemStack offhand) {
		if (storage != null) {
			for (ItemStack item : storage) {
				if (!Grave.isBlank(item)) {
					return true;
				}
			}
		}
		if (armor != null) {
			for (ItemStack item : armor) {
				if (!Grave.isBlank(item)) {
					return true;
				}
			}
		}
		return !Grave.isBlank(offhand);
	}

	private static ItemStack[] cloneArray(ItemStack[] source) {
		if (source == null) {
			return null;
		}
		ItemStack[] copy = new ItemStack[source.length];
		for (int i = 0; i < source.length; i++) {
			copy[i] = cloneItem(source[i]);
		}
		return copy;
	}

	private static ItemStack cloneItem(ItemStack item) {
		return item != null ? item.clone() : null;
	}

	private void sendPlaced(Player player, Block chest) {
		if (player == null || chest == null) {
			return;
		}
		List<String> notices = new ArrayList<>();
		String template = GraveLoader.getMessagePlaced();
		if (template != null && !template.isBlank()) {
			String world = chest.getWorld() != null ? chest.getWorld().getName() : "unknown";
			String text = template
					.replace("{x}", Integer.toString(chest.getX()))
					.replace("{y}", Integer.toString(chest.getY()))
					.replace("{z}", Integer.toString(chest.getZ()))
					.replace("{world}", world);
			notices.add(StringFormatter.formatHex(text.replace('&', '\u00A7')));
		}
		String hint = GraveLoader.getMessageUnlockHint();
		if (hint != null && !hint.isBlank()) {
			notices.add(StringFormatter.formatHex(hint.replace('&', '\u00A7')));
		}
		if (!notices.isEmpty()) {
			placedNotice.put(player.getUniqueId(), notices);
		}
	}
}
