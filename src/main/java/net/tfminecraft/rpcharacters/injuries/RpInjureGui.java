package net.tfminecraft.rpcharacters.injuries;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.objects.trait.TraitEffectResolver;
import net.tfminecraft.rpcharacters.utils.RPTexts;

@SuppressWarnings("deprecation")
public final class RpInjureGui {

	private static NamespacedKey traitKey() {
		return new NamespacedKey(RPCharacters.plugin, "rp_injure_trait");
	}

	private static NamespacedKey actionKey() {
		return new NamespacedKey(RPCharacters.plugin, "rp_injure_action");
	}

	private static final int[] PICKER_SLOTS = {10, 11, 12, 13, 14, 15, 16};

	private RpInjureGui() {
	}

	@SuppressWarnings("deprecation")
	public static void openPicker(Player attacker, Player target, RPCharacter character) {
		Holder holder = new Holder(Kind.PICKER, attacker);
		Inventory inventory = Bukkit.createInventory(holder, 27,
				RPTexts.formatGui(RPTexts.MUTED + "Injure " + target.getName()));
		holder.inventory = inventory;

		List<Trait> traits = RpInjureService.listPickerInjuries(character);
		int index = 0;
		for (Trait trait : traits) {
			if (index >= PICKER_SLOTS.length) {
				break;
			}
			boolean owned = RpInjureService.isOwned(character, trait);
			inventory.setItem(PICKER_SLOTS[index++], pickerItem(trait, character, owned));
		}
		fillEmpty(inventory);
		attacker.openInventory(inventory);
	}

	@SuppressWarnings("deprecation")
	public static void openAccept(Player target, Player attacker, Trait trait) {
		Holder holder = new Holder(Kind.ACCEPT, target);
		Inventory inventory = Bukkit.createInventory(holder, 27,
				RPTexts.formatGui(RPTexts.MUTED + "Injury request"));
		holder.inventory = inventory;

		inventory.setItem(13, previewItem(trait, attacker));
		inventory.setItem(11, actionItem(Material.GREEN_CONCRETE, RPTexts.GUI_SUCCESS + "Accept", "accept"));
		inventory.setItem(15, actionItem(Material.RED_CONCRETE, RPTexts.ERROR + "Decline", "decline"));
		fillEmpty(inventory);
		target.openInventory(inventory);
	}

	static String readTraitId(ItemStack item) {
		if (item == null || !item.hasItemMeta()) {
			return null;
		}
		return item.getItemMeta().getPersistentDataContainer().get(traitKey(), PersistentDataType.STRING);
	}

	static String readAction(ItemStack item) {
		if (item == null || !item.hasItemMeta()) {
			return null;
		}
		return item.getItemMeta().getPersistentDataContainer().get(actionKey(), PersistentDataType.STRING);
	}

	private static ItemStack pickerItem(Trait trait, RPCharacter character, boolean owned) {
		Material material = owned ? Material.GRAY_DYE : (trait.hasIcon() ? trait.getIcon() : Material.RED_DYE);
		ItemStack item = new ItemStack(material, 1);
		ItemMeta meta = item.getItemMeta();
		if (meta == null) {
			return item;
		}
		meta.setDisplayName(RPTexts.formatGui(RPTexts.RESET + trait.getName()));
		List<String> lore = new ArrayList<>();
		for (String line : TraitEffectResolver.resolveDescription(character, trait)) {
			lore.add(RPTexts.formatGui(line));
		}
		if (owned) {
			lore.add(RPTexts.spacer());
			lore.add(RPTexts.formatGui(RPTexts.ERROR + "They already have this."));
		} else {
			lore.add(RPTexts.spacer());
			lore.add(RPTexts.formatGui(RPTexts.MUTED + "Click to request this injury."));
			if (trait.hasDuration()) {
				lore.add(RPTexts.formatGui(RPTexts.MUTED + "Healing injury"));
			} else {
				lore.add(RPTexts.formatGui(RPTexts.WARN + "Permanent injury"));
			}
			meta.getPersistentDataContainer().set(traitKey(), PersistentDataType.STRING, trait.getId());
		}
		meta.setLore(lore);
		item.setItemMeta(meta);
		return item;
	}

	private static ItemStack previewItem(Trait trait, Player attacker) {
		Material material = trait.hasIcon() ? trait.getIcon() : Material.RED_DYE;
		ItemStack item = new ItemStack(material, 1);
		ItemMeta meta = item.getItemMeta();
		if (meta == null) {
			return item;
		}
		meta.setDisplayName(RPTexts.formatGui(RPTexts.RESET + trait.getName()));
		List<String> lore = new ArrayList<>();
		lore.add(RPTexts.formatGui(RPTexts.MUTED + attacker.getName() + " wants to apply this."));
		for (String line : trait.getDesc()) {
			lore.add(RPTexts.formatGui(line));
		}
		if (trait.hasDuration()) {
			lore.add(RPTexts.formatGui(RPTexts.MUTED + "Healing injury"));
		} else {
			lore.add(RPTexts.formatGui(RPTexts.WARN + "Permanent injury"));
		}
		meta.setLore(lore);
		item.setItemMeta(meta);
		return item;
	}

	private static ItemStack actionItem(Material material, String name, String action) {
		ItemStack item = new ItemStack(material, 1);
		ItemMeta meta = item.getItemMeta();
		if (meta == null) {
			return item;
		}
		meta.setDisplayName(RPTexts.formatGui(name));
		meta.getPersistentDataContainer().set(actionKey(), PersistentDataType.STRING, action);
		item.setItemMeta(meta);
		return item;
	}

	private static void fillEmpty(Inventory inventory) {
		ItemStack fill = new ItemStack(Material.GRAY_STAINED_GLASS_PANE, 1);
		ItemMeta meta = fill.getItemMeta();
		if (meta != null) {
			meta.setDisplayName(RPTexts.formatGui(RPTexts.MUTED + " "));
			fill.setItemMeta(meta);
		}
		for (int slot = 0; slot < inventory.getSize(); slot++) {
			if (inventory.getItem(slot) == null) {
				inventory.setItem(slot, fill);
			}
		}
	}

	enum Kind {
		PICKER,
		ACCEPT
	}

	public static final class Holder implements InventoryHolder {
		private final Kind kind;
		private final Player owner;
		private Inventory inventory;

		Holder(Kind kind, Player owner) {
			this.kind = kind;
			this.owner = owner;
		}

		public Kind getKind() {
			return kind;
		}

		public Player getOwner() {
			return owner;
		}

		@Override
		public Inventory getInventory() {
			return inventory;
		}
	}
}
