package net.tfminecraft.rpcharacters.prosthetics;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.tfminecraft.rpcharacters.loaders.ProstheticLoader;
import net.tfminecraft.rpcharacters.loaders.TraitLoader;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.ProstheticReplacement;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.utils.ProstheticTraitRules;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.rpcharacters.utils.TraitChangeService;

public final class ProstheticInstallListener implements Listener {

	private static final int CONFIRM_SLOT = 11;
	private static final int CANCEL_SLOT = 15;

	private final Map<UUID, PendingSwap> pendingSwaps = new ConcurrentHashMap<>();

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onPlayerInteract(PlayerInteractEvent event) {
		Action action = event.getAction();
		if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
			return;
		}
		if (event.getHand() != EquipmentSlot.HAND) {
			return;
		}

		ItemStack item = event.getItem();
		ProstheticInstallMatch match = ProstheticLoader.resolveForItem(item);
		if (match == null) {
			return;
		}

		event.setCancelled(true);

		Player player = event.getPlayer();
		PlayerData pd = PlayerManager.get(player);
		if (pd == null || !pd.hasActiveCharacter()) {
			RPTexts.send(player, RPTexts.ERROR + "You need an active character to install prosthetics.");
			return;
		}

		RPCharacter character = pd.getActiveCharacter();
		ProstheticReplacement replacement = match.getReplacement();
		boolean hasInjury = ownsTrait(character, replacement.getPermanentInjuryId());
		String ownedId = replacement.ownedProstheticId(character.getTraits());
		ProstheticTraitRules.InstallAction resolved = ProstheticTraitRules.resolveInstall(
				hasInjury, ownedId, match.getTraitId());

		switch (resolved) {
			case INSTALL -> {
				if (TraitChangeService.replaceInjuryWithProsthetic(player, character,
						replacement.getPermanentInjuryId(), match.getTraitId())) {
					consumeHeldItem(player);
					player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
				}
			}
			case ALREADY_OWNED -> RPTexts.send(player, RPTexts.MUTED + "You already have that prosthetic.");
			case REPLACE -> openReplaceConfirm(player, character, ownedId, match);
			case NONE -> RPTexts.send(player, RPTexts.MUTED + "No injury on this character can use that item.");
		}
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onInventoryClick(InventoryClickEvent event) {
		if (!(event.getWhoClicked() instanceof Player player)) {
			return;
		}
		if (!(event.getView().getTopInventory().getHolder() instanceof ProstheticConfirmHolder)) {
			return;
		}
		event.setCancelled(true);
		if (event.getClickedInventory() == null
				|| !event.getClickedInventory().equals(event.getView().getTopInventory())) {
			return;
		}

		if (event.getSlot() == CONFIRM_SLOT) {
			applyPendingSwap(player);
			player.closeInventory();
			return;
		}
		if (event.getSlot() == CANCEL_SLOT) {
			pendingSwaps.remove(player.getUniqueId());
			player.closeInventory();
		}
	}

	@EventHandler
	public void onInventoryDrag(InventoryDragEvent event) {
		if (event.getView().getTopInventory().getHolder() instanceof ProstheticConfirmHolder) {
			event.setCancelled(true);
		}
	}

	@EventHandler
	public void onInventoryClose(InventoryCloseEvent event) {
		if (!(event.getPlayer() instanceof Player player)) {
			return;
		}
		if (event.getInventory().getHolder() instanceof ProstheticConfirmHolder) {
			pendingSwaps.remove(player.getUniqueId());
		}
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		pendingSwaps.remove(event.getPlayer().getUniqueId());
	}

	private void openReplaceConfirm(Player player, RPCharacter character, String fromTraitId,
			ProstheticInstallMatch match) {
		Trait fromTrait = TraitLoader.getByString(fromTraitId);
		Trait toTrait = TraitLoader.getByString(match.getTraitId());
		if (fromTrait == null || toTrait == null) {
			return;
		}

		Inventory inventory = RPCharacters.plugin.getServer().createInventory(
				new ProstheticConfirmHolder(player), 27, RPTexts.formatGui(RPTexts.MUTED + "Replace prosthetic?"));
		inventory.setItem(CONFIRM_SLOT, confirmItem(fromTrait, toTrait));
		inventory.setItem(CANCEL_SLOT, cancelItem());
		ItemStack fill = filler();
		for (int slot = 0; slot < inventory.getSize(); slot++) {
			if (inventory.getItem(slot) == null) {
				inventory.setItem(slot, fill);
			}
		}
		player.openInventory(inventory);
		pendingSwaps.put(player.getUniqueId(), new PendingSwap(
				character.getId(), fromTraitId, match.getTraitId(), match.getItemPath()));
	}

	private void applyPendingSwap(Player player) {
		PendingSwap pending = pendingSwaps.remove(player.getUniqueId());
		if (pending == null) {
			return;
		}

		PlayerData pd = PlayerManager.get(player);
		if (pd == null || !pd.hasActiveCharacter()) {
			RPTexts.send(player, RPTexts.ERROR + "You need an active character to install prosthetics.");
			return;
		}
		RPCharacter character = pd.getActiveCharacter();
		if (!character.getId().equals(pending.characterId())) {
			RPTexts.send(player, RPTexts.ERROR + "That prosthetic swap is no longer valid.");
			return;
		}

		ItemStack item = player.getInventory().getItemInMainHand();
		ProstheticInstallMatch match = ProstheticLoader.resolveForItem(item);
		if (match == null
				|| !match.getTraitId().equalsIgnoreCase(pending.toTraitId())
				|| !match.getItemPath().equalsIgnoreCase(pending.itemPath())) {
			RPTexts.send(player, RPTexts.ERROR + "You need to keep holding the prosthetic item to install it.");
			return;
		}
		if (!ownsTrait(character, pending.fromTraitId())) {
			RPTexts.send(player, RPTexts.ERROR + "That prosthetic swap is no longer valid.");
			return;
		}

		if (TraitChangeService.replaceProsthetic(player, character, pending.fromTraitId(), pending.toTraitId())) {
			consumeHeldItem(player);
			player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
		}
	}

	private static boolean ownsTrait(RPCharacter character, String traitId) {
		if (character == null || traitId == null || traitId.isBlank()) {
			return false;
		}
		for (Trait trait : character.getTraits()) {
			if (trait.getId().equalsIgnoreCase(traitId)) {
				return true;
			}
		}
		return false;
	}

	private static void consumeHeldItem(Player player) {
		ItemStack item = player.getInventory().getItemInMainHand();
		if (item == null || item.getType().isAir()) {
			return;
		}
		if (item.getAmount() <= 1) {
			player.getInventory().setItemInMainHand(null);
		} else {
			item.setAmount(item.getAmount() - 1);
		}
	}

	private static ItemStack confirmItem(Trait fromTrait, Trait toTrait) {
		ItemStack item = new ItemStack(Material.GREEN_CONCRETE, 1);
		ItemMeta meta = item.getItemMeta();
		meta.setDisplayName(RPTexts.formatGui(RPTexts.GUI_SUCCESS + "Confirm"));
		meta.setLore(List.of(
				RPTexts.formatGui(RPTexts.MUTED + "This will remove " + fromTrait.getName()),
				RPTexts.formatGui(RPTexts.MUTED + "and install " + toTrait.getName() + RPTexts.MUTED + "."),
				RPTexts.formatGui(RPTexts.ERROR + "Your current prosthetic is destroyed."),
				RPTexts.formatGui(RPTexts.ERROR + "It is not returned as an item.")));
		item.setItemMeta(meta);
		return item;
	}

	private static ItemStack cancelItem() {
		ItemStack item = new ItemStack(Material.RED_CONCRETE, 1);
		ItemMeta meta = item.getItemMeta();
		meta.setDisplayName(RPTexts.formatGui(RPTexts.ERROR + "Cancel"));
		item.setItemMeta(meta);
		return item;
	}

	private static ItemStack filler() {
		ItemStack fill = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
		ItemMeta meta = fill.getItemMeta();
		meta.setDisplayName(RPTexts.formatGui(RPTexts.MUTED + " "));
		fill.setItemMeta(meta);
		return fill;
	}

	private record PendingSwap(String characterId, String fromTraitId, String toTraitId, String itemPath) {
	}
}
