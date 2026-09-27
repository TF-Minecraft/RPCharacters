package net.tfminecraft.rpcharacters.injuries;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import net.tfminecraft.rpcharacters.loaders.InjuryPoolLoader;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.utils.Integrator;
import net.tfminecraft.rpcharacters.utils.TraitChangeService;
import net.tfminecraft.rpcharacters.permadeath.PermadeathService;

public final class InjuryHealingService {

	private InjuryHealingService() {
	}

	public static void start() {
		Bukkit.getLogger().info("[RPCharacters] Starting Injury Healing Service");
		long intervalTicks = InjuryPoolLoader.getHealingTickIntervalTicks();
		new BukkitRunnable() {
			@Override
			public void run() {
				tick();
			}
		}.runTaskTimer(RPCharacters.plugin, intervalTicks, intervalTicks);
	}

	static void tick() {
		for (Player player : Bukkit.getOnlinePlayers()) {
			if (player.isDead() || PermadeathService.isAwaitingPermakillRespawn(player)) {
				continue;
			}

			PlayerData pd = PlayerManager.get(player);
			if (pd == null || !pd.hasActiveCharacter()) {
				continue;
			}

			RPCharacter character = pd.getActiveCharacter();
			if (!character.isActive()) {
				continue;
			}

			processCharacter(player, character);
		}
	}

	/**
	 * Durations count down in real time, including while offline, so the tick only removes injuries whose time
	 * has run out and refreshes the rest, whose effects fade as they heal.
	 */
	private static void processCharacter(Player player, RPCharacter character) {
		List<Trait> healingTraits = new ArrayList<>();
		for (Trait trait : character.getTraits()) {
			if (trait.hasDuration()) {
				healingTraits.add(trait);
			}
		}
		if (healingTraits.isEmpty()) {
			return;
		}

		boolean initialized = false;
		List<Trait> toRemove = new ArrayList<>();
		for (Trait trait : healingTraits) {
			String traitId = trait.getId();
			long remaining = character.getDurationRemainingMs(traitId);
			if (remaining < 0L) {
				character.setDurationRemainingMs(traitId, trait.getDurationMs());
				initialized = true;
				continue;
			}
			if (remaining == 0L) {
				toRemove.add(trait);
			}
		}

		if (toRemove.isEmpty()) {
			refreshCharacter(player, character);
			if (initialized) {
				RPCharacters.getPlayerManager().savePlayer(player);
			}
			return;
		}

		for (Trait trait : toRemove) {
			TraitChangeService.removeTrait(player, character, trait);
			TraitChangeService.sendLostMessage(player, trait);
		}
	}

	public static void refreshCharacter(Player player, RPCharacter character) {
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
