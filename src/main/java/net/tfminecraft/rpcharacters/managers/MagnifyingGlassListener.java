package net.tfminecraft.rpcharacters.managers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.rpcharacters.loaders.ClueDiscoveryLoader;
import net.tfminecraft.rpcharacters.loaders.MagnifyingGlassLoader;
import net.tfminecraft.rpcharacters.objects.MagnifyingGlassDefinition;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.SpawnedClue;
import net.tfminecraft.rpcharacters.objects.attributes.AttributeModifier;
import net.tfminecraft.rpcharacters.clues.discovery.ClueDiscoveryService;
import net.tfminecraft.rpcharacters.clues.discovery.ClueDiscoverySettings;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.rpcharacters.clues.discovery.InvestigationPointService;

public final class MagnifyingGlassListener implements Listener {

	private final Map<UUID, Long> lastSearchMs = new HashMap<>();

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onInteract(PlayerInteractEvent event) {
		if (event.getHand() != EquipmentSlot.HAND) return;
		Action action = event.getAction();
		if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

		Player player = event.getPlayer();
		ItemStack item = event.getItem();
		MagnifyingGlassDefinition glass = MagnifyingGlassLoader.resolve(item);
		if (glass == null) return;

		event.setCancelled(true);

		ClueDiscoverySettings settings = ClueDiscoveryLoader.getSettings();
		if (!settings.isActiveDiscoveryEnabled()) return;

		PlayerData pd = PlayerManager.get(player);
		if (pd == null || !pd.hasActiveCharacter()) return;
		RPCharacter character = pd.getActiveCharacter();
		if (character == null) return;

		if (!meetsRequirements(character, glass)) {
			RPTexts.send(player, settings.getMessageAttributeTooLow());
			return;
		}

		long now = System.currentTimeMillis();
		Long last = lastSearchMs.get(player.getUniqueId());
		if (last != null && now - last < settings.getActiveCooldownSeconds() * 1000L) {
			return;
		}

		int cost = glass.getInvestigationCost() > 0 ? glass.getInvestigationCost() : settings.getActiveInvestigationCost();
		if (!InvestigationPointService.hasPoints(player, cost)) {
			InvestigationPointService.sendNoPointsMessage(player);
			return;
		}

		double radius = Math.max(settings.getActiveRadius(), glass.getSearchRadius());
		List<SpawnedClue> clues = SpawnedClueManager.get().getCluesNear(player.getLocation(), radius);

		if (!InvestigationPointService.spend(player, cost)) {
			InvestigationPointService.sendNoPointsMessage(player);
			return;
		}

		lastSearchMs.put(player.getUniqueId(), now);

		boolean discovered = false;
		for (SpawnedClue clue : clues) {
			if (ClueDiscoveryService.tryActiveDiscovery(player, character, clue, glass)) {
				discovered = true;
				break;
			}
		}

		if (!discovered) {
			String msg = settings.getMessageNoClueNearby();
			if (msg != null && !msg.isBlank()) {
				RPTexts.send(player, msg);
			}
		}
	}

	private boolean meetsRequirements(RPCharacter character, MagnifyingGlassDefinition glass) {
		for (Map.Entry<String, Integer> entry : glass.getRequires().entrySet()) {
			int required = entry.getValue();
			if (required <= 0) continue;
			int actual = character.getAttributeData().getAmount(new AttributeModifier(entry.getKey(), 0));
			if (actual < required) return false;
		}
		return true;
	}
}
