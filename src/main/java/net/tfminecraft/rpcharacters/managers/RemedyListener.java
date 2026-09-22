package net.tfminecraft.rpcharacters.managers;

import java.util.List;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;

import net.tfminecraft.rpcharacters.loaders.InjuryProgressionLoader;
import net.tfminecraft.rpcharacters.loaders.RemedyLoader;
import net.tfminecraft.rpcharacters.loaders.TraitLoader;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RemedyDefinition;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.rpcharacters.utils.TraitChangeService;

public class RemedyListener implements Listener {

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
		List<RemedyDefinition> remedies = RemedyLoader.resolveAll(event.getItem());
		if (remedies.isEmpty()) {
			return;
		}

		Player player = event.getPlayer();
		PlayerData pd = PlayerManager.get(player);
		if (pd == null || !pd.hasActiveCharacter()) {
			event.setCancelled(true);
			RPTexts.send(player, RPTexts.ERROR + "You need an active character to use remedies.");
			return;
		}

		RPCharacter character = pd.getActiveCharacter();
		Trait trait = findCurableTrait(character, remedies);
		if (trait == null) {
			return;
		}

		TraitChangeService.removeTrait(player, character, trait);
		TraitChangeService.sendRemedyCuredMessage(player, trait);
		player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
	}

	private static Trait findCurableTrait(RPCharacter character, List<RemedyDefinition> remedies) {
		for (RemedyDefinition remedy : remedies) {
			for (String traitId : remedy.getTraits()) {
				if (!InjuryProgressionLoader.isHealingTrait(traitId)) {
					continue;
				}
				Trait traitDef = TraitLoader.getByString(traitId);
				if (traitDef == null) {
					continue;
				}
				for (Trait current : character.getTraits()) {
					if (current.getId().equalsIgnoreCase(traitDef.getId()) && current.hasDuration()) {
						return current;
					}
				}
			}
		}
		return null;
	}
}
