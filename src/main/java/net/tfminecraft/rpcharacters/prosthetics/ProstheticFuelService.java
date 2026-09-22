package net.tfminecraft.rpcharacters.prosthetics;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import net.tfminecraft.rpcharacters.loaders.FuelTemplateLoader;
import net.tfminecraft.rpcharacters.loaders.InjuryPoolLoader;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.FuelTemplate;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.utils.Integrator;
import net.tfminecraft.rpcharacters.permadeath.PermadeathService;

public final class ProstheticFuelService {

	private ProstheticFuelService() {
	}

	public static void start() {
		Bukkit.getLogger().info("[RPCharacters] Starting Prosthetic Fuel Service");
		long intervalTicks = InjuryPoolLoader.getHealingTickIntervalTicks();
		new BukkitRunnable() {
			@Override
			public void run() {
				tick();
			}
		}.runTaskTimer(RPCharacters.plugin, intervalTicks, intervalTicks);
	}

	static void tick() {
		long tickMs = InjuryPoolLoader.getHealingTickIntervalMs();
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

			if (processCharacter(player, character, tickMs)) {
				RPCharacters.getPlayerManager().savePlayer(player);
			}
		}
	}

	private static boolean processCharacter(Player player, RPCharacter character, long tickMs) {
		boolean changed = false;
		boolean needsRefresh = false;

		for (Trait trait : character.getTraits()) {
			if (!trait.hasFuelTemplate()) {
				continue;
			}

			FuelTemplate template = FuelTemplateLoader.getByString(trait.getFuelTemplateId());
			if (template == null || template.getBurnIntervalMs() <= 0L) {
				continue;
			}

			String traitId = trait.getId();
			double fuel = character.getFuel(traitId);
			if (fuel < 0D) {
				fuel = trait.getFuelCapacity();
				character.setFuel(traitId, fuel);
			}

			boolean wasPowered = fuel > 0D;
			double burnAmount = template.getBurnRate() * ((double) tickMs / (double) template.getBurnIntervalMs());
			if (burnAmount <= 0D) {
				continue;
			}

			double newFuel = Math.max(0D, fuel - burnAmount);
			if (newFuel == fuel) {
				continue;
			}

			character.setFuel(traitId, newFuel);
			changed = true;
			boolean isPowered = newFuel > 0D;
			if (wasPowered != isPowered) {
				needsRefresh = true;
			}
		}

		if (needsRefresh) {
			refreshCharacter(player, character);
		}

		return changed;
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
