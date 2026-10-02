package net.tfminecraft.rpcharacters.professions;

import java.util.List;
import java.util.Locale;

import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.inventory.ItemStack;

import io.lumine.mythic.lib.api.item.NBTItem;
import net.Indyuce.mmoitems.ItemStats;
import net.Indyuce.mmoitems.MMOItems;
import net.Indyuce.mmoitems.api.crafting.condition.Condition;
import net.Indyuce.mmoitems.api.crafting.condition.PermissionCondition;
import net.Indyuce.mmoitems.api.crafting.recipe.Recipe;
import net.Indyuce.mmoitems.api.event.PlayerUseCraftingStationEvent;
import net.Indyuce.mmoitems.api.event.PlayerUseCraftingStationEvent.StationAction;
import net.Indyuce.mmoitems.api.item.mmoitem.LiveMMOItem;
import net.Indyuce.mmoitems.api.item.mmoitem.MMOItem;
import net.Indyuce.mmoitems.stat.data.DoubleData;
import net.Indyuce.mmoitems.stat.data.EnchantListData;
import net.Indyuce.mmoitems.stat.type.StatHistory;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.utils.RPTexts;

public class ProfessionEffectService implements Listener {

	private static List<ProfessionUpgradeDefinition> activeUpgrades(Player player) {
		PlayerData pd = PlayerManager.get(player);
		if (pd == null) {
			return List.of();
		}
		RPCharacter character = pd.getActiveCharacter();
		if (character == null) {
			return List.of();
		}
		return character.resolveProfessionUpgrades();
	}

	@EventHandler
	public void stationEnchantTypeEvent(PlayerUseCraftingStationEvent event) {
		if (event.getInteraction() != StationAction.CRAFTING_QUEUE) {
			return;
		}
		ItemStack result = event.getResult();
		for (ProfessionUpgradeDefinition upgrade : activeUpgrades(event.getPlayer())) {
			if (!"station_enchant".equalsIgnoreCase(upgrade.getType())) {
				continue;
			}
			NBTItem nbt = NBTItem.get(result);
			if (!nbt.hasType()) {
				continue;
			}
			MMOItem item = new LiveMMOItem(nbt);
			EnchantListData enchants = (EnchantListData) item.getData(ItemStats.ENCHANTS);
			if (enchants == null) {
				enchants = new EnchantListData();
			}
			boolean changed = false;
			for (String unlock : upgrade.getUnlocks()) {
				String[] parts = unlock.split("\\.", 3);
				if (parts.length != 3 || !matchesItemGroup(parts[0], nbt.getType())) {
					continue;
				}
				try {
					NamespacedKey key = NamespacedKey.fromString(parts[1].toLowerCase(Locale.ROOT));
					Enchantment enchantment = key == null ? null : io.papermc.paper.registry.RegistryAccess.registryAccess()
							.getRegistry(io.papermc.paper.registry.RegistryKey.ENCHANTMENT).get(key);
					int added = Integer.parseInt(parts[2]);
					if (enchantment == null || added <= 0) {
						continue;
					}
					int level = (int) Math.min(Integer.MAX_VALUE, (long) enchants.getLevel(enchantment) + added);
					enchants.addEnchant(enchantment, level);
					changed = true;
				} catch (IllegalArgumentException invalidUnlock) {
					// A malformed configured perk must not discard a completed craft.
				}
			}
			if (changed) {
				item.setData(ItemStats.ENCHANTS, enchants);
				StatHistory history = item.getStatHistory(ItemStats.ENCHANTS);
				history.registerExternalData(enchants);
				item.setStatHistory(ItemStats.ENCHANTS, history);
				updateResult(result, item.newBuilder().build());
			}
		}
	}

	@EventHandler
	public void stationAddedStats(PlayerUseCraftingStationEvent event) {
		if (event.getInteraction() != StationAction.CRAFTING_QUEUE) {
			return;
		}
		ItemStack result = event.getResult();
		for (ProfessionUpgradeDefinition upgrade : activeUpgrades(event.getPlayer())) {
			if (!"add_stats".equalsIgnoreCase(upgrade.getType())) {
				continue;
			}
			NBTItem nbt = NBTItem.get(result);
			if (!nbt.hasType()) {
				continue;
			}
			MMOItem item = new LiveMMOItem(nbt);
			boolean changed = false;
			for (String unlock : upgrade.getUnlocks()) {
				String[] parts = unlock.split("\\.", 3);
				if (parts.length != 3 || !matchesItemGroup(parts[0], nbt.getType())) {
					continue;
				}
				try {
					var stat = MMOItems.plugin.getStats().get(parts[1].toUpperCase(Locale.ROOT));
					double added = Double.parseDouble(parts[2].replace(',', '.'));
					if (stat == null || !Double.isFinite(added)) {
						continue;
					}
					var previous = item.getData(stat);
					if (previous != null && !(previous instanceof DoubleData)) {
						continue;
					}
					double value = (previous == null ? 0D : ((DoubleData) previous).getValue()) + added;
					if (!Double.isFinite(value)) {
						continue;
					}
					DoubleData updated = new DoubleData(value);
					item.replaceData(stat, updated);
					StatHistory history = item.computeStatHistory(stat);
					if (history != null) {
						if (history.getOriginalData() instanceof DoubleData original) {
							original.setValue(value);
						}
						item.setStatHistory(stat, history);
					}
					changed = true;
				} catch (IllegalArgumentException invalidUnlock) {
					// Ignore malformed configured numbers without losing other valid perks.
				}
			}
			if (changed) {
				updateResult(result, item.newBuilder().build());
			}
		}
	}

	private static boolean matchesItemGroup(String groupId, String mmoType) {
		for (ProfessionItemType type : Cache.professionItemTypes) {
			if (type.getId().equalsIgnoreCase(groupId)) {
				for (String allowed : type.getMmoItemTypes()) {
					if (allowed.equalsIgnoreCase(mmoType)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** Preserve the event's result object and quantity for later listeners and normal queue delivery. */
	private static void updateResult(ItemStack result, ItemStack rebuilt) {
		int amount = result.getAmount();
		result.setType(rebuilt.getType());
		result.setItemMeta(rebuilt.getItemMeta());
		result.setAmount(amount);
	}

	@EventHandler
	public void permissionCheck(PlayerUseCraftingStationEvent event) {
		Player player = event.getPlayer();
		if (!event.getInteraction().equals(StationAction.INTERACT_WITH_RECIPE)) {
			return;
		}
		Recipe recipe = event.getRecipe();
		for (Condition condition : recipe.getConditions()) {
			if (condition instanceof PermissionCondition perm) {
				net.Indyuce.mmoitems.api.player.PlayerData data = net.Indyuce.mmoitems.api.player.PlayerData.get(player.getUniqueId());
				if (!perm.isMet(data)) {
					event.setCancelled(true);
					RPTexts.send(player, RPTexts.ERROR + perm.getDisplay().format(false));
				}
			}
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void breedEvent(EntityBreedEvent event) {
		if (!(event.getBreeder() instanceof Player player)) {
			return;
		}
		String entityType = event.getEntityType().name().toLowerCase(Locale.ROOT);
		if (hasBreedingUnlock(player, entityType)) {
			return;
		}
		if (Cache.professionLockedBreeding.contains(entityType)) {
			event.setCancelled(true);
			if (event.getMother() instanceof Animals mother) {
				mother.setLoveModeTicks(0);
			}
			if (event.getFather() instanceof Animals father) {
				father.setLoveModeTicks(0);
			}
			RPTexts.send(player, RPTexts.ERROR + "You have not yet learned how to breed this animal.");
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void awardBreedingExperience(EntityBreedEvent event) {
		if (event.isCancelled() || !(event.getBreeder() instanceof Player player)
				|| Cache.professionBreedingExperience == null) {
			return;
		}
		String entityType = event.getEntityType().name().toLowerCase(Locale.ROOT);
		boolean unlocked = hasBreedingUnlock(player, entityType);
		if (!unlocked && Cache.professionLockedBreeding.contains(entityType)) {
			return;
		}
		Cache.professionBreedingExperience.award(player, unlocked ? entityType : "generic");
	}

	private static boolean hasBreedingUnlock(Player player, String entityType) {
		for (ProfessionUpgradeDefinition upgrade : activeUpgrades(player)) {
			if (!"breeding".equalsIgnoreCase(upgrade.getType())) {
				continue;
			}
			for (String unlock : upgrade.getUnlocks()) {
				if (unlock.equalsIgnoreCase(entityType)) {
					return true;
				}
			}
		}
		return false;
	}
}
