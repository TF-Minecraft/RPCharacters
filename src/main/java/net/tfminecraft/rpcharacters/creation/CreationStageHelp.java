package net.tfminecraft.rpcharacters.creation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.entity.Player;

import net.tfminecraft.tlibs.shaded.lang3.text.WordUtils;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.creation.stages.AttributesStage;
import net.tfminecraft.rpcharacters.creation.stages.ClueStage;
import net.tfminecraft.rpcharacters.creation.stages.InfoStage;
import net.tfminecraft.rpcharacters.creation.stages.QuestionStage;
import net.tfminecraft.rpcharacters.creation.stages.SelectionStage;
import net.tfminecraft.rpcharacters.creation.stages.SetterStage;
import net.tfminecraft.rpcharacters.creation.stages.SummaryStage;
import net.tfminecraft.rpcharacters.creation.stages.WardrobeStage;
import net.tfminecraft.rpcharacters.objects.Question;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.utils.ClueFormatter;
import net.tfminecraft.rpcharacters.utils.RPTexts;

/**
 * On-demand chat recap of the creation stage a player is on, including
 * instructions already shown during the stage and the available controls.
 */
public final class CreationStageHelp {

	private static final String STAGE_ID_SUFFIX = "_stage";

	private CreationStageHelp() {
	}

	/** Sends the recap. Read-only: never re-runs the stage or touches inventories. */
	public static void send(Player p, CharacterCreation cc) {
		Stage stage = cc.getActiveStage();
		if (stage == null) {
			RPTexts.send(p, RPTexts.ERROR + "There is nothing to recap right now.");
			return;
		}
		RPTexts.send(p, RPTexts.separator());
		RPTexts.send(p, RPTexts.MUTED + "Stage: " + RPTexts.WARN + stageName(stage));
		List<String> lines = bodyLines(stage, cc);
		if (lines.isEmpty()) {
			sendBody(p, RPTexts.MUTED + "No extra information for this stage.");
		}
		for (String line : lines) {
			sendBody(p, line);
		}
		for (String hint : hintLines(cc)) {
			sendPlain(p, hint);
		}
		RPTexts.send(p, RPTexts.separator());
	}

	private static List<String> bodyLines(Stage stage, CharacterCreation cc) {
		List<String> lines = new ArrayList<>();
		if (stage instanceof InfoStage info) {
			for (String message : info.getMessages()) {
				String body = messageBody(InfoStage.substitutePlaceholders(message));
				if (!body.isBlank()) {
					lines.add(body);
				}
			}
		} else if (stage instanceof ClueStage clue) {
			lines.addAll(clueLines(clue, cc.getCharacter()));
		} else if (stage instanceof SetterStage setter) {
			String body = messageBody(setter.getMessage());
			if (!body.isBlank()) {
				lines.add(body);
			}
		} else if (stage instanceof QuestionStage question) {
			lines.addAll(questionLines(question));
		} else if (stage instanceof SelectionStage selection) {
			lines.addAll(selectionLines(selection));
		} else if (stage instanceof AttributesStage attributes) {
			lines.add(RPTexts.MUTED + "Spend your points in the menu, then click confirm.");
			lines.add(RPTexts.MUTED + "Points left: " + RPTexts.WARN + attributes.getRemaining()
					+ RPTexts.MUTED + " of " + RPTexts.WARN + attributes.getPool());
			lines.add(RPTexts.MUTED + "Highest rank per attribute: " + RPTexts.WARN + attributes.getMaxRank());
			lines.add(RPTexts.MUTED + "The menu reopens by itself if you close it.");
		} else if (stage instanceof SummaryStage) {
			lines.add(RPTexts.MUTED + "Check every entry in the menu, then confirm to finish.");
			lines.add(RPTexts.MUTED + "Click an entry to redo that stage.");
		} else if (stage instanceof WardrobeStage) {
			lines.add(RPTexts.MUTED + "Skins live on the website. In-game you swap with "
					+ RPTexts.COMMAND + "/rpcharacter wardrobe" + RPTexts.MUTED + ".");
		}
		return lines;
	}

	private static List<String> clueLines(ClueStage clue, RPCharacter character) {
		List<String> lines = new ArrayList<>();
		String template = clue.getMessage();
		if (character == null) {
			lines.add(messageBody(template));
			return lines;
		}
		int current = character.getPlayerClues().size() + 1;
		int needed = character.getCluesNeeded();
		lines.add(messageBody(template
				.replace("{current}", String.valueOf(current))
				.replace("{needed}", String.valueOf(needed))));
		lines.add(RPTexts.MUTED + "Each clue must be " + RPTexts.WARN + Cache.clueMinLength
				+ RPTexts.MUTED + " to " + RPTexts.WARN + Cache.clueMaxLength + RPTexts.MUTED + " characters.");
		return lines;
	}

	private static List<String> questionLines(QuestionStage question) {
		List<String> lines = new ArrayList<>();
		List<Question> questions = question.getQuestions();
		int index = question.getCurrentQuestion();
		if (index >= 0 && index < questions.size()) {
			lines.add(RPTexts.MUTED + "Question " + RPTexts.WARN + (index + 1) + RPTexts.MUTED + " of "
					+ RPTexts.WARN + questions.size());
			lines.add(questions.get(index).getQuestion());
		}
		lines.add(RPTexts.MUTED + "Type your answer in chat.");
		return lines;
	}

	private static List<String> selectionLines(SelectionStage selection) {
		List<String> lines = new ArrayList<>();
		lines.add(RPTexts.MUTED + "Pick your options in the menu, then click confirm.");
		int min = selection.getMinSelections();
		int max = selection.getMaxSelections();
		if (max > 1) {
			lines.add(RPTexts.MUTED + "You may pick " + RPTexts.WARN + min + RPTexts.MUTED + " to "
					+ RPTexts.WARN + max + RPTexts.MUTED + " of them.");
		} else if (min > 0) {
			lines.add(RPTexts.MUTED + "You must pick " + RPTexts.WARN + min + RPTexts.MUTED + ".");
		}
		if (selection.hasPoints()) {
			lines.add(RPTexts.MUTED + "Points left: " + RPTexts.WARN + selection.getPoints()
					+ RPTexts.MUTED + " of " + RPTexts.WARN + selection.getInitialPoints());
		}
		lines.add(RPTexts.MUTED + "The menu reopens by itself if you close it.");
		return lines;
	}

	private static List<String> hintLines(CharacterCreation cc) {
		List<String> hints = new ArrayList<>();
		if (cc.canNext()) {
			hints.add(RPTexts.MUTED + "Use " + RPTexts.COMMAND + "/rpcharacter next" + RPTexts.MUTED
					+ " to move on.");
		}
		if (cc.isPreview()) {
			hints.add(RPTexts.MUTED + "Use " + RPTexts.COMMAND + "/rpcharacter cancel" + RPTexts.MUTED
					+ " to leave the preview.");
			return hints;
		}
		hints.add(RPTexts.MUTED + "Use " + RPTexts.COMMAND + "/rpcharacter back" + RPTexts.MUTED
				+ " to redo the stage before this one.");
		if (!cc.isEditing()) {
			hints.add(RPTexts.MUTED + "Use " + RPTexts.COMMAND + "/rpcharacter cancel" + RPTexts.MUTED
					+ " to start over.");
		}
		return hints;
	}

	/** Readable stage name: the stage title when it has one, otherwise what it sets, otherwise its id. */
	// Preserve configured stage-name capitalization.
	@SuppressWarnings("deprecation")
	private static String stageName(Stage stage) {
		if (stage instanceof InfoStage info) {
			for (String message : info.getMessages()) {
				if (message == null || !message.toLowerCase(Locale.ROOT).startsWith("title(")) {
					continue;
				}
				String plain = ClueFormatter.stripColor(RPTexts.formatGui(messageBody(message)));
				if (!plain.isBlank()) {
					return plain;
				}
			}
		}
		if (stage instanceof SetterStage setter && setter.getTarget() != null && !setter.getTarget().isBlank()) {
			return WordUtils.capitalize(setter.getTarget().replace('_', ' ').trim());
		}
		return prettyId(stage.getId());
	}

	// Keep the existing display-name capitalization rules used by configuration and item names.
	@SuppressWarnings("deprecation")
	private static String prettyId(String id) {
		if (id == null || id.isBlank()) {
			return "Character creation";
		}
		String cleaned = id.toLowerCase(Locale.ROOT);
		if (cleaned.endsWith(STAGE_ID_SUFFIX)) {
			cleaned = cleaned.substring(0, cleaned.length() - STAGE_ID_SUFFIX.length());
		}
		return WordUtils.capitalize(cleaned.replace('_', ' ').trim());
	}

	/** Pulls the display text out of {@code title(...)}, {@code subtitle(...)} or {@code chat(...)} copy. */
	private static String messageBody(String raw) {
		if (raw == null || raw.isBlank()) {
			return "";
		}
		int open = raw.indexOf('(');
		int close = raw.lastIndexOf(')');
		if (open < 0 || close < open) {
			return raw.trim();
		}
		return raw.substring(open + 1, close).trim();
	}

	private static void sendBody(Player p, String body) {
		p.sendMessage(RPTexts.formatGui(RPTexts.MUTED + "- " + body));
	}

	private static void sendPlain(Player p, String line) {
		p.sendMessage(RPTexts.formatGui(line));
	}
}
