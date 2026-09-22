package net.tfminecraft.rpcharacters.prosthetics;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.rpcharacters.loaders.FuelTemplateLoader;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.FuelTemplate;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.utils.Integrator;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.rpcharacters.utils.TraitStateFormat;

public final class ProstheticRefuelListener implements Listener {

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
		FuelTemplate template = FuelTemplateLoader.resolveForItem(item);
		if (template == null) {
			return;
		}

		event.setCancelled(true);

		Player player = event.getPlayer();
		PlayerData pd = PlayerManager.get(player);
		if (pd == null || !pd.hasActiveCharacter()) {
			RPTexts.send(player, RPTexts.ERROR + "You need an active character to refuel prosthetics.");
			return;
		}

		RPCharacter character = pd.getActiveCharacter();
		List<Trait> fueledProsthetics = findFueledProsthetics(character, template.getId());
		if (fueledProsthetics.isEmpty()) {
			RPTexts.send(player, RPTexts.MUTED + "You do not have a prosthetic that uses that fuel.");
			return;
		}

		boolean needsRefresh = false;
		boolean refueled = false;
		String lastFuelDisplay = null;
		for (Trait trait : fueledProsthetics) {
			String traitId = trait.getId();
			double capacity = trait.getFuelCapacity();
			double current = character.getFuel(traitId);
			if (current < 0D) {
				current = 0D;
			}
			boolean wasPowered = current > 0D;
			double newFuel = Math.min(capacity, current + template.getAmountPerItem());
			lastFuelDisplay = TraitStateFormat.formatFuel(newFuel, capacity);
			if (newFuel == current) {
				continue;
			}
			character.setFuel(traitId, newFuel);
			refueled = true;
			if (!wasPowered && newFuel > 0D) {
				needsRefresh = true;
			}
		}

		if (!refueled) {
			RPTexts.send(player, RPTexts.MUTED + "Your prosthetic fuel is already full.");
			return;
		}

		if (needsRefresh) {
			refreshCharacter(player, character);
		}
		RPCharacters.getPlayerManager().savePlayer(player);

		if (lastFuelDisplay != null) {
			RPTexts.send(player, RPTexts.SUCCESS + "Fuel: " + RPTexts.MUTED + lastFuelDisplay);
		}
		player.playSound(player.getLocation(), Sound.ITEM_BUCKET_FILL, 0.8f, 1.2f);

		if (item.getAmount() <= 1) {
			player.getInventory().setItemInMainHand(null);
		} else {
			item.setAmount(item.getAmount() - 1);
		}
	}

	private static List<Trait> findFueledProsthetics(RPCharacter character, String templateId) {
		List<Trait> matches = new ArrayList<>();
		for (Trait trait : character.getTraits()) {
			if (!trait.hasFuelTemplate()) {
				continue;
			}
			if (trait.getFuelTemplateId().equalsIgnoreCase(templateId)) {
				matches.add(trait);
			}
		}
		return matches;
	}

	private static void refreshCharacter(Player player, RPCharacter character) {
		if (character.isActive()) {
			Integrator integrator = new Integrator();
			integrator.remove(player, character, false);
			character.update();
			integrator.integrate(player, character);
		} else {
			character.update();
		}
	}
}
