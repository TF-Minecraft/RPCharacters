package net.tfminecraft.rpcharacters.classpick;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.creation.Stage;
import net.tfminecraft.rpcharacters.creation.StageRevisions;
import net.tfminecraft.rpcharacters.loaders.StageLoader;
import net.tfminecraft.rpcharacters.managers.CreationManager;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.utils.AgeFormatter;
import net.tfminecraft.rpcharacters.utils.RPTexts;

/**
 * {@code /rpcharacter admin resetclasses confirm} gives every character a fresh class lock window,
 * like raising the class stage's revision in stages.yml: class and subclass picks are free again
 * for the stage's lock-time, paid change prices start over, and the free first subclass returns.
 * The resets are counted in data/class-resets.yml and added to the class stage's revision on load.
 * Online characters start their window now, offline ones when they next join.
 */
public final class ClassPickReset {

	public static final String SUBCOMMAND = "resetclasses";
	static final String FILE = "data/class-resets.yml";

	private static final Logger LOG = Logger.getLogger("RPCharacters");

	private static int resets;

	private ClassPickReset() {}

	public static boolean canUse(CommandSender sender) {
		return sender.hasPermission(ClassPickService.settings().resetPermission());
	}

	/** Reads the reset count kept next to stages.yml and adds it to the class stage's revision. */
	public static void load(File dataFolder, Collection<? extends Stage> stages) {
		resets = Math.max(0, YamlConfiguration.loadConfiguration(new File(dataFolder, FILE)).getInt("resets", 0));
		for (Stage stage : stages) {
			if (CreationManager.isClassStage(stage)) {
				stage.setRevision(stage.getRevision() + resets);
			}
		}
	}

	public static boolean handle(CommandSender sender, String[] args, int index) {
		if (!canUse(sender)) {
			RPTexts.send(sender, RPTexts.ERROR + "You do not have permission to use this command.");
			return true;
		}
		Stage stage = ClassPickService.classStage();
		if (stage == null) {
			RPTexts.send(sender, RPTexts.ERROR + "There is no class stage in stages.yml to reset.");
			return true;
		}
		String window = stage.getLockTimeMs() < 0 ? "always"
				: "for " + AgeFormatter.formatCountdown(stage.getLockTimeMs());
		if (args.length <= index || !args[index].equalsIgnoreCase("confirm")) {
			RPTexts.send(sender, RPTexts.WARN + "This resets class picks for every character. Class and subclass"
					+ " changes become free " + window + ", paid change prices start over, and the first subclass"
					+ " is free again. Offline characters get this when they next join.");
			RPTexts.send(sender, RPTexts.WARN + "Run " + RPTexts.COMMAND + "/rpcharacter admin " + SUBCOMMAND
					+ " confirm" + RPTexts.WARN + " to do it.");
			return true;
		}
		if (!save(resets + 1)) {
			RPTexts.send(sender, RPTexts.ERROR + "Could not save " + FILE + ". Nothing was reset; see the console.");
			return true;
		}
		resets++;
		for (Stage loaded : StageLoader.oList) {
			if (CreationManager.isClassStage(loaded)) {
				loaded.setRevision(loaded.getRevision() + 1);
			}
		}
		int online = refreshOnline();
		LOG.info("[RPCharacters] " + sender.getName() + " reset class picks for every character (reset " + resets
				+ ", " + online + " online characters refreshed).");
		RPTexts.send(sender, RPTexts.SUCCESS + "Class picks reset. " + online + " online characters can change"
				+ " class and subclass for free " + window + ". Offline characters get this when they next join.");
		return true;
	}

	private static boolean save(int count) {
		File file = new File(RPCharacters.plugin.getDataFolder(), FILE);
		YamlConfiguration config = new YamlConfiguration();
		config.options().setHeader(List.of(
				"Written by /rpcharacter admin " + SUBCOMMAND + ". Each reset is added to the class stage's revision."));
		config.set("resets", count);
		try {
			config.save(file);
			return true;
		} catch (IOException e) {
			LOG.log(Level.WARNING, "[RPCharacters] Could not save " + file, e);
			return false;
		}
	}

	private static int refreshOnline() {
		int refreshed = 0;
		for (Player player : Bukkit.getOnlinePlayers()) {
			PlayerData pd = PlayerManager.get(player);
			if (pd == null) {
				continue;
			}
			boolean changed = false;
			for (RPCharacter character : pd.getCharacters()) {
				if (StageRevisions.refresh(character, StageLoader.oList)) {
					changed = true;
					refreshed++;
				}
			}
			if (changed) {
				RPCharacters.getPlayerManager().savePlayer(player);
			}
		}
		return refreshed;
	}
}
