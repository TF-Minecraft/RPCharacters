package net.tfminecraft.rpcharacters.loaders;

import java.io.File;
import java.io.IOException;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.rpcharacters.utils.DurationParser;
import net.tfminecraft.rpcharacters.clues.discovery.ClueDiscoverySettings;

public final class ClueDiscoveryLoader implements LoaderInterface {

	private static ClueDiscoverySettings settings = new ClueDiscoverySettings();

	public static ClueDiscoverySettings getSettings() {
		return settings;
	}

	@Override
	public void load(File configFile) {
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(configFile);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
		}

		ClueDiscoverySettings loaded = new ClueDiscoverySettings();
		loadInvestigationPoints(config.getConfigurationSection("investigation-points"), loaded);
		loadPassiveDiscovery(config.getConfigurationSection("passive-discovery"), loaded);
		loadActiveDiscovery(config.getConfigurationSection("active-discovery"), loaded);
		loadAttributes(config.getConfigurationSection("attributes"), loaded);
		loadPotency(config.getConfigurationSection("potency"), loaded);
		loadDisturbance(config.getConfigurationSection("disturbance"), loaded);
		loadReadability(config.getConfigurationSection("readability"), loaded);
		loadMessages(config.getConfigurationSection("messages"), loaded);
		settings = loaded;
	}

	private static void loadInvestigationPoints(ConfigurationSection section, ClueDiscoverySettings s) {
		if (section == null) return;
		s.setInvestigationPointsMax(section.getInt("max", s.getInvestigationPointsMax()));
		if (section.contains("regen-cycle")) {
			long cycleMs = DurationParser.parseShortDurationMs(section.getString("regen-cycle"));
			if (cycleMs > 0L) {
				s.setInvestigationRegenCycleMs(cycleMs);
			}
		} else if (section.contains("regen-per-minute")) {
			int perMinute = section.getInt("regen-per-minute");
			if (perMinute > 0) {
				s.setInvestigationRegenCycleMs(60_000L / perMinute);
			}
		}
	}

	private static void loadPassiveDiscovery(ConfigurationSection section, ClueDiscoverySettings s) {
		if (section == null) return;
		s.setPassiveDiscoveryEnabled(section.getBoolean("enabled", s.isPassiveDiscoveryEnabled()));
		s.setPassiveIntervalSeconds(section.getInt("interval-seconds", s.getPassiveIntervalSeconds()));
		s.setPassiveRadius(section.getDouble("radius", s.getPassiveRadius()));
		s.setPassiveBaseChance(section.getDouble("base-chance", s.getPassiveBaseChance()));
		s.setPassiveMinPotency(section.getDouble("min-potency", s.getPassiveMinPotency()));
	}

	private static void loadActiveDiscovery(ConfigurationSection section, ClueDiscoverySettings s) {
		if (section == null) return;
		s.setActiveDiscoveryEnabled(section.getBoolean("enabled", s.isActiveDiscoveryEnabled()));
		s.setActiveCooldownSeconds(section.getInt("cooldown-seconds", s.getActiveCooldownSeconds()));
		s.setActiveInvestigationCost(section.getInt("investigation-cost", s.getActiveInvestigationCost()));
		s.setActiveRadius(section.getDouble("radius", s.getActiveRadius()));
		s.setActiveBaseChance(section.getDouble("base-chance", s.getActiveBaseChance()));
		s.setActiveMinPotency(section.getDouble("min-potency", s.getActiveMinPotency()));
	}

	private static void loadAttributes(ConfigurationSection section, ClueDiscoverySettings s) {
		if (section == null) return;
		s.setWisdomWeight(section.getDouble("wisdom-weight", s.getWisdomWeight()));
		s.setIntelligenceWeight(section.getDouble("intelligence-weight", s.getIntelligenceWeight()));
	}

	private static void loadPotency(ConfigurationSection section, ClueDiscoverySettings s) {
		if (section == null) return;
		s.setPotencyInitial(section.getDouble("initial", s.getPotencyInitial()));
		s.setPotencyDecayPerHour(section.getDouble("decay-per-hour", s.getPotencyDecayPerHour()));
		s.setPotencyMinForDiscovery(section.getDouble("min-for-discovery", s.getPotencyMinForDiscovery()));
		s.setPotencyMinForReadable(section.getDouble("min-for-readable", s.getPotencyMinForReadable()));
		s.setPotencyExpireWhenZero(section.getBoolean("expire-when-zero", s.isPotencyExpireWhenZero()));
	}

	private static void loadDisturbance(ConfigurationSection section, ClueDiscoverySettings s) {
		if (section == null) return;
		ConfigurationSection target = section.getConfigurationSection("target-interact");
		if (target != null) {
			s.setTargetInteractEnabled(target.getBoolean("enabled", s.isTargetInteractEnabled()));
			s.setTargetInteractLossMin(target.getDouble("potency-loss-min", s.getTargetInteractLossMin()));
			s.setTargetInteractLossMax(target.getDouble("potency-loss-max", s.getTargetInteractLossMax()));
			s.setTargetInteractZeroLossChance(target.getDouble("zero-loss-chance", s.getTargetInteractZeroLossChance()));
		}
		ConfigurationSection foot = section.getConfigurationSection("foot-traffic");
		if (foot != null) {
			s.setFootTrafficEnabled(foot.getBoolean("enabled", s.isFootTrafficEnabled()));
			s.setFootTrafficRadius(foot.getDouble("radius", s.getFootTrafficRadius()));
			s.setFootTrafficChancePerCheck(foot.getDouble("chance-per-check", s.getFootTrafficChancePerCheck()));
			s.setFootTrafficMaxEventsPerHour(foot.getInt("max-events-per-clue-per-hour", s.getFootTrafficMaxEventsPerHour()));
			s.setFootTrafficOnlyUndiscovered(foot.getBoolean("only-undiscovered-players", s.isFootTrafficOnlyUndiscovered()));
			s.setFootTrafficLossMin(foot.getDouble("potency-loss-min", s.getFootTrafficLossMin()));
			s.setFootTrafficLossMax(foot.getDouble("potency-loss-max", s.getFootTrafficLossMax()));
		}
	}

	private static void loadReadability(ConfigurationSection section, ClueDiscoverySettings s) {
		if (section == null) return;
		s.setReadabilityFullClarity(section.getDouble("full-clarity", s.getReadabilityFullClarity()));
		s.setReadabilityMinAudible(section.getDouble("min-audible", s.getReadabilityMinAudible()));
		s.setReadabilityTooFaintPlaceholder(section.getString("too-faint-placeholder", s.getReadabilityTooFaintPlaceholder()));
	}

	private static void loadMessages(ConfigurationSection section, ClueDiscoverySettings s) {
		if (section == null) return;
		s.setMessageDiscovered(section.getString("discovered", s.getMessageDiscovered()));
		s.setMessageNoInvestigationPoints(section.getString("no-investigation-points", s.getMessageNoInvestigationPoints()));
		s.setMessageAttributeTooLow(section.getString("attribute-too-low", s.getMessageAttributeTooLow()));
		s.setMessageNoClueNearby(section.getString("no-clue-nearby", s.getMessageNoClueNearby()));
	}
}
