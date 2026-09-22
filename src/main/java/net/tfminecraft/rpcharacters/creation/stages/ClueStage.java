package net.tfminecraft.rpcharacters.creation.stages;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.creation.CharacterCreation;
import net.tfminecraft.rpcharacters.creation.Stage;
import net.tfminecraft.rpcharacters.managers.CreationManager;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.enums.ClueAddResult;
import net.tfminecraft.rpcharacters.utils.RPTexts;

public class ClueStage extends Stage {

	private String message;
	private boolean submitted;
	private BukkitTask advanceTask;

	public ClueStage(Stage s, ConfigurationSection config) {
		copyBaseFields(s);
		this.message = config.getString("message", "subtitle(§eType clue {current}/{needed} in chat)");
	}

	public ClueStage(ClueStage another) {
		copyBaseFields(another);
		this.message = another.getMessage();
	}

	public String getMessage() {
		return message;
	}

	public void execute(Player p, CharacterCreation cc) {
		if (cc.isCancelled()) return;
		if (advanceTask != null) {
			advanceTask.cancel();
			advanceTask = null;
		}
		submitted = false;
		RPCharacter character = cc.getCharacter();
		runMessage(p, formatMessage(message, character));
	}

	private String formatMessage(String template, RPCharacter character) {
		int current = character.getPlayerClues().size() + 1;
		int needed = character.getCluesNeeded();
		return template
				.replace("{current}", String.valueOf(current))
				.replace("{needed}", String.valueOf(needed));
	}

	public void runMessage(Player p, String message) {
		String type = message.split("\\(")[0];
		String info = RPTexts.formatGui(message.split("\\(")[1].replace(")", ""));
		if (type.equalsIgnoreCase("title")) {
			p.sendTitle(info, " ", 5, 50, 5);
			p.sendMessage(info);
		} else if (type.equalsIgnoreCase("subtitle")) {
			p.sendTitle(" ", info, 5, 50, 5);
			p.sendMessage(info);
		} else if (type.equalsIgnoreCase("chat")) {
			p.sendMessage(info);
		}
	}

	public void finish(String raw, Player p, CharacterCreation cc) {
		if (cc.isCancelled() || cc.getActiveStage() != this) return;
		if (submitted) {
			RPTexts.send(p, RPTexts.MUTED + "All clues saved. Wait for the next prompt.");
			return;
		}
		RPCharacter character = cc.getCharacter();
		ClueAddResult result = character.addPlayerClue(raw);
		if (result != ClueAddResult.SUCCESS) {
			p.sendMessage(character.getClueAddErrorMessage(result));
			runMessage(p, formatMessage(message, character));
			return;
		}

		int count = character.getPlayerClues().size();
		int needed = character.getCluesNeeded();
		RPTexts.title(p, " ", RPTexts.MUTED + "Clue " + RPTexts.WARN + count + RPTexts.MUTED + "/"
				+ RPTexts.WARN + needed + " " + RPTexts.MUTED + "saved", 5, 50, 5);
		RPTexts.send(p, RPTexts.MUTED + "Clue " + RPTexts.WARN + count + RPTexts.MUTED + "/"
				+ RPTexts.WARN + needed + " " + RPTexts.MUTED + "saved.");

		if (!character.hasEnoughClues()) {
			runMessage(p, formatMessage(message, character));
			return;
		}

		submitted = true;
		advanceTask = new BukkitRunnable() {
			@Override
			public void run() {
				if (cc.isCancelled() || cc.getActiveStage() != ClueStage.this
						|| CreationManager.activeCreators.get(p) != cc) return;
				if (autoNext()) {
					cc.runStage();
				} else {
					cc.setCanNext(true);
				}
			}
		}.runTaskLater(RPCharacters.plugin, 60L);
	}
}
