package net.tfminecraft.rpcharacters.professions;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Material;
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

	// Preserve mutations of the caller-owned ItemStack and its aliases.
	@SuppressWarnings("deprecation")
	@EventHandler
	public void stationEnchantTypeEvent(PlayerUseCraftingStationEvent event) {
		Player player = event.getPlayer();
		if (!event.getInteraction().equals(StationAction.CRAFTING_QUEUE)) {
			return;
		}
		ItemStack item = event.getResult();
		boolean hasChanged = false;
		for (ProfessionUpgradeDefinition upgrade : activeUpgrades(player)) {
			if (!"station_enchant".equalsIgnoreCase(upgrade.getType())) {
				continue;
			}
			if (!NBTItem.get(item).hasType()) {
				continue;
			}
			MMOItem mmoitem = new LiveMMOItem(NBTItem.get(item));
			EnchantListData enchants = (EnchantListData) mmoitem.getData(ItemStats.ENCHANTS);
			for (String unlock : upgrade.getUnlocks()) {
				for (ProfessionItemType type : Cache.professionItemTypes) {
					if (!type.getId().equalsIgnoreCase(unlock.split("\\.")[0])) {
						continue;
					}
					for (String mmoType : type.getMmoItemTypes()) {
						if (!mmoType.equalsIgnoreCase(NBTItem.get(item).getType().toString())) {
							continue;
						}
						String enchType = unlock.split("\\.")[1];
						int enchLevel = Integer.parseInt(unlock.split("\\.")[2]);
						enchLevel = enchLevel + enchants.getLevel(io.papermc.paper.registry.RegistryAccess.registryAccess().getRegistry(io.papermc.paper.registry.RegistryKey.ENCHANTMENT).get(NamespacedKey.minecraft(enchType)));
						enchants.addEnchant(io.papermc.paper.registry.RegistryAccess.registryAccess().getRegistry(io.papermc.paper.registry.RegistryKey.ENCHANTMENT).get(NamespacedKey.minecraft(enchType)), enchLevel);
						hasChanged = true;
					}
				}
			}
			if (hasChanged) {
				mmoitem.setData(ItemStats.ENCHANTS, enchants);
				StatHistory history = mmoitem.getStatHistory(ItemStats.ENCHANTS);
				history.registerExternalData(enchants);
				mmoitem.setStatHistory(ItemStats.ENCHANTS, history);
				event.getResult().setType(Material.AIR);
				item = mmoitem.newBuilder().build();
			}
		}
		if (hasChanged) {
			item.setAmount(event.getResult().getAmount());
			player.getInventory().addItem(item);
			event.getResult().setType(Material.AIR);
		}
	}

	// This path mutates the existing ItemStack; replacing it would change aliases held by callers.
	@SuppressWarnings("deprecation")
	@EventHandler
	public void stationAddedStats(PlayerUseCraftingStationEvent event) {
		Player player = event.getPlayer();
		if (!event.getInteraction().equals(StationAction.CRAFTING_QUEUE)) {
			return;
		}
		ItemStack item = event.getResult();
		boolean hasChanged = false;
		DoubleData oldStat = null;
		String statName = null;
		for (ProfessionUpgradeDefinition upgrade : activeUpgrades(player)) {
			if (!"add_stats".equalsIgnoreCase(upgrade.getType())) {
				continue;
			}
			if (!NBTItem.get(item).hasType()) {
				continue;
			}
			MMOItem mmoitem = new LiveMMOItem(NBTItem.get(item));
			for (String stat : upgrade.getUnlocks()) {
				for (ProfessionItemType type : Cache.professionItemTypes) {
					if (!type.getId().equalsIgnoreCase(stat.split("\\.")[0])) {
						continue;
					}
					for (String mmoType : type.getMmoItemTypes()) {
						if (!mmoType.equalsIgnoreCase(NBTItem.get(item).getType().toString())) {
							continue;
						}
						statName = stat.split("\\.")[1];
						oldStat = (DoubleData) mmoitem.getData(MMOItems.plugin.getStats().get(stat.split("\\.")[1].toUpperCase()));
						if (oldStat == null) {
							oldStat = new DoubleData(0.0);
						}
						oldStat.setValue(oldStat.getValue() + Double.parseDouble(stat.split("\\.")[2].replace(",", ".")));
						hasChanged = true;
					}
				}
			}
			if (hasChanged && statName != null) {
				mmoitem.replaceData(MMOItems.plugin.getStats().get(statName.toUpperCase()), oldStat);
				StatHistory hist = mmoitem.computeStatHistory(MMOItems.plugin.getStats().get(statName.toUpperCase()));
				if (hist != null) {
					DoubleData original = (DoubleData) hist.getOriginalData();
					original.setValue(oldStat.getValue());
					mmoitem.setStatHistory(MMOItems.plugin.getStats().get(statName.toUpperCase()), hist);
				}
				event.getResult().setType(Material.AIR);
				item = mmoitem.newBuilder().build();
			}
		}
		if (hasChanged) {
			event.getResult().setType(Material.AIR);
			item.setAmount(event.getResult().getAmount());
			if (player.getInventory().firstEmpty() == -1) {
				player.getWorld().dropItem(player.getLocation(), item);
			} else {
				player.getInventory().addItem(item);
			}
		}
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
			RPTexts.send(player, RPTexts.ERROR + "You are not allowed to breed this animal");
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void awardBreedingExperience(EntityBreedEvent event) {
		if (event.isCancelled() || !(event.getBreeder() instanceof Player player)
				|| Cache.professionBreedingExperience == null) {
			return;
		}
		String entityType = event.getEntityType().name().toLowerCase(Locale.ROOT);
		Cache.professionBreedingExperience.award(player, hasBreedingUnlock(player, entityType) ? entityType : "generic");
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
