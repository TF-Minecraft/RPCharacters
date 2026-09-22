package net.tfminecraft.rpcharacters.clues.discovery;

import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.loaders.ClueDiscoveryLoader;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.attributes.AttributeModifier;
import net.tfminecraft.rpcharacters.utils.RPTexts;

public final class InvestigationPointService {

	private InvestigationPointService() {}

	public static void bootstrap(Player player) {
		PlayerData data = PlayerManager.get(player);
		if (data == null) return;
		ClueDiscoverySettings settings = ClueDiscoveryLoader.getSettings();
		if (data.needsInvestigationPointsInit()) {
			data.setInvestigationPoints(settings.getInvestigationPointsMax());
			data.setLastInvestigationRegenMs(System.currentTimeMillis());
		}
		clampToMax(data, settings);
	}

	public static void regen(Player player) {
		PlayerData data = PlayerManager.get(player);
		if (data == null) return;
		bootstrap(player);
		ClueDiscoverySettings settings = ClueDiscoveryLoader.getSettings();
		long cycleMs = settings.getInvestigationRegenCycleMs();
		if (cycleMs <= 0L) return;

		long now = System.currentTimeMillis();
		Long last = data.getLastInvestigationRegenMs();
		if (last == null) {
			data.setLastInvestigationRegenMs(now);
			return;
		}

		long effectiveCycleMs = resolveEffectiveCycleMs(data, settings, cycleMs);
		long elapsedMs = now - last;
		int cycles = (int) (elapsedMs / effectiveCycleMs);
		if (cycles <= 0) return;

		int max = settings.getInvestigationPointsMax();
		int updated = Math.min(max, data.getInvestigationPoints() + cycles);
		data.setInvestigationPoints(updated);
		data.setLastInvestigationRegenMs(last + cycles * effectiveCycleMs);
	}

	public static boolean hasPoints(Player player, int cost) {
		PlayerData data = PlayerManager.get(player);
		if (data == null) return false;
		bootstrap(player);
		return data.getInvestigationPoints() >= cost;
	}

	public static boolean spend(Player player, int cost) {
		if (cost <= 0) return true;
		PlayerData data = PlayerManager.get(player);
		if (data == null) return false;
		bootstrap(player);
		if (data.getInvestigationPoints() < cost) return false;
		data.setInvestigationPoints(data.getInvestigationPoints() - cost);
		return true;
	}

	public static void sendNoPointsMessage(Player player) {
		String msg = ClueDiscoveryLoader.getSettings().getMessageNoInvestigationPoints();
		if (msg != null && !msg.isBlank()) {
			RPTexts.send(player, msg);
		}
	}

	private static void clampToMax(PlayerData data, ClueDiscoverySettings settings) {
		int max = settings.getInvestigationPointsMax();
		if (data.getInvestigationPoints() > max) {
			data.setInvestigationPoints(max);
		}
	}

	private static long resolveEffectiveCycleMs(PlayerData data, ClueDiscoverySettings settings, long baseCycleMs) {
		double multiplier = computeRegenSpeedMultiplier(data.getActiveCharacter(), settings);
		return Math.max(1_000L, (long) (baseCycleMs / multiplier));
	}

	private static double computeRegenSpeedMultiplier(RPCharacter character, ClueDiscoverySettings settings) {
		if (character == null) {
			return 1.0;
		}
		int wisdom = character.getAttributeData().getAmount(new AttributeModifier("wisdom", 0));
		int intelligence = character.getAttributeData().getAmount(new AttributeModifier("intelligence", 0));
		return 1.0
				+ (wisdom * settings.getWisdomWeight())
				+ (intelligence * settings.getIntelligenceWeight());
	}
}
