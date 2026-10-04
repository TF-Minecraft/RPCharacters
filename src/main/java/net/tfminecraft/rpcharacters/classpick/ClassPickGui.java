package net.tfminecraft.rpcharacters.classpick;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.Indyuce.mmocore.api.player.profess.PlayerClass;
import net.Indyuce.mmocore.api.player.profess.Subclass;
import net.tfminecraft.rpcharacters.classpick.ClassPickService.Option;
import net.tfminecraft.rpcharacters.classpick.ClassPickService.Result;
import net.tfminecraft.rpcharacters.classpick.ClassPickService.Status;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.mmocore.MmoCoreClassGuiHelper;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.utils.RPTexts;

/**
 * The class picker: one column per base class (up to 7), with up to 3 of its subclasses below it.
 * The first click on a class marks it, the second click picks it.
 */
public final class ClassPickGui {
	public static final String TITLE = "Class Selection";
	static final int SIZE = 54;
	static final int INFO_SLOT = 4;
	static final int CLOSE_SLOT = 49;
	static final int COLUMNS = 7;
	static final int SUBCLASS_ROWS = 3;

	private ClassPickGui() {}

	public static final class Holder implements InventoryHolder {
		private final Map<Integer, String> classBySlot = new HashMap<>();
		private final Set<Integer> choosable = new HashSet<>();
		private String pending;
		private Inventory inventory;

		@Override
		public Inventory getInventory() {
			return inventory;
		}

		String getPending() {
			return pending;
		}
	}

	public static void open(Player player) {
		Status blocked = ClassPickService.blocker(player);
		if (blocked != null) {
			RPTexts.send(player, ClassPickService.message(new Result(blocked, null, BigDecimal.ZERO, null)));
			return;
		}
		Holder holder = new Holder();
		holder.inventory = Bukkit.createInventory(holder, SIZE, RPTexts.formatGui(RPTexts.MUTED + TITLE));
		render(holder, player);
		player.openInventory(holder.inventory);
	}

	static void render(Holder holder, Player player) {
		RPCharacter character = PlayerManager.get(player).getActiveCharacter();
		int level = net.Indyuce.mmocore.api.player.PlayerData.get(player).getLevel();
		Inventory inventory = holder.inventory;
		inventory.clear();
		holder.classBySlot.clear();
		holder.choosable.clear();
		List<PlayerClass> bases = ClassPickService.baseClasses();
		for (int column = 0; column < Math.min(bases.size(), COLUMNS); column++) {
			PlayerClass base = bases.get(column);
			place(holder, 10 + column, new Option(base, null, 0), character, level);
			List<Subclass> subclasses = base.getSubclasses();
			for (int row = 0; row < Math.min(subclasses.size(), SUBCLASS_ROWS); row++) {
				Subclass subclass = subclasses.get(row);
				place(holder, 19 + 9 * row + column, new Option(subclass.getProfess(), base, subclass.getLevel()),
						character, level);
			}
		}
		inventory.setItem(INFO_SLOT, infoItem(character, level));
		inventory.setItem(CLOSE_SLOT, simpleItem(Material.BARRIER, RPTexts.ERROR + "Close", List.of()));
		for (int slot = 0; slot < SIZE; slot++) {
			if (inventory.getItem(slot) == null) {
				inventory.setItem(slot, simpleItem(Material.GRAY_STAINED_GLASS_PANE, RPTexts.MUTED + " ", List.of()));
			}
		}
	}

	private static void place(Holder holder, int slot, Option option, RPCharacter character, int level) {
		String classId = option.playerClass().getId();
		boolean current = classId.equalsIgnoreCase(character.getMMOClass());
		boolean unlocked = level >= option.requiredLevel();
		holder.classBySlot.put(slot, classId);
		if (!current && unlocked) {
			holder.choosable.add(slot);
		}
		List<String> lore = new ArrayList<>();
		if (option.isSubclass()) {
			lore.add(RPTexts.MUTED + "Subclass of " + ClassPickService.className(option.base()));
		}
		lore.addAll(MmoCoreClassGuiHelper.buildClassLore(option.playerClass()));
		lore.add(RPTexts.spacer());
		boolean pending = classId.equals(holder.pending);
		if (current) {
			lore.add(RPTexts.SUCCESS + "Your current class");
		} else if (!unlocked) {
			lore.add(RPTexts.ERROR + "Unlocks at level " + option.requiredLevel());
		} else {
			lore.add(RPTexts.MUTED + "Cost: " + RPTexts.WARN
					+ ClassPickService.priceText(ClassPickService.price(character, option)));
			lore.add(pending ? RPTexts.WARN + "Click again to confirm" : RPTexts.MUTED + "Click to choose");
		}
		ItemStack icon = option.playerClass().getIcon();
		ItemStack item = icon == null || icon.getType() == Material.AIR ? new ItemStack(Material.PAPER) : icon.clone();
		decorate(item, ClassPickService.className(option.playerClass()), lore, pending || current);
		holder.inventory.setItem(slot, item);
	}

	private static ItemStack infoItem(RPCharacter character, int level) {
		PlayerClass current = character.hasMMOClass()
				? net.Indyuce.mmocore.MMOCore.plugin.classManager.get(character.getMMOClass())
				: null;
		List<String> lore = new ArrayList<>();
		lore.add(RPTexts.MUTED + "Class: " + (current == null ? RPTexts.WARN + "None" : ClassPickService.className(current)));
		lore.add(RPTexts.MUTED + "Level: " + RPTexts.WARN + level);
		lore.addAll(ClassPickService.pricingLore(character, RPTexts.spacer()));
		return simpleItem(Material.BOOK, RPTexts.WARN + "Your Class", lore);
	}

	private static ItemStack simpleItem(Material material, String name, List<String> lore) {
		ItemStack item = new ItemStack(material);
		decorate(item, name, lore, false);
		return item;
	}

	@SuppressWarnings("deprecation")
	private static void decorate(ItemStack item, String name, List<String> lore, boolean glint) {
		ItemMeta meta = item.getItemMeta();
		meta.setDisplayName(RPTexts.formatGui(RPTexts.RESET + name));
		List<String> formatted = new ArrayList<>();
		for (String line : lore) {
			formatted.add(RPTexts.formatGui(RPTexts.RESET + line));
		}
		meta.setLore(formatted);
		if (glint) {
			meta.setEnchantmentGlintOverride(true);
		}
		item.setItemMeta(meta);
	}

	public static void click(Player player, Holder holder, int slot) {
		if (slot == CLOSE_SLOT) {
			player.closeInventory();
			return;
		}
		String classId = holder.classBySlot.get(slot);
		if (classId == null) {
			return;
		}
		if (holder.choosable.contains(slot) && !classId.equals(holder.pending)) {
			holder.pending = classId;
			render(holder, player);
			player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1f, 1f);
			return;
		}
		holder.pending = null;
		Result result = ClassPickService.choose(player, classId);
		RPTexts.send(player, ClassPickService.message(result));
		if (result.status() == Status.CHOSEN) {
			player.closeInventory();
			player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
			return;
		}
		player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
		if (ClassPickService.blocker(player) != null) {
			player.closeInventory();
		} else {
			render(holder, player);
		}
	}
}
