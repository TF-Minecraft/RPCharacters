package net.tfminecraft.rpcharacters.creation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.bukkit.entity.Player;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.calendar.AgeCalculator;
import net.tfminecraft.rpcharacters.classpick.ClassPickService;
import net.tfminecraft.rpcharacters.creation.stages.AttributesStage;
import net.tfminecraft.rpcharacters.creation.stages.ClueStage;
import net.tfminecraft.rpcharacters.creation.stages.InfoStage;
import net.tfminecraft.rpcharacters.creation.stages.QuestionStage;
import net.tfminecraft.rpcharacters.creation.stages.SelectionStage;
import net.tfminecraft.rpcharacters.creation.stages.SetterStage;
import net.tfminecraft.rpcharacters.creation.stages.SummaryStage;
import net.tfminecraft.rpcharacters.loaders.RaceLoader;
import net.tfminecraft.rpcharacters.managers.CreationManager;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.SelectableItem;
import net.tfminecraft.rpcharacters.objects.trait.TraitEffectResolver;
import net.tfminecraft.rpcharacters.loaders.TraitLoader;
import net.tfminecraft.rpcharacters.playerlist.GuiText;
import net.tfminecraft.rpcharacters.utils.ClueFormatter;
import net.tfminecraft.rpcharacters.utils.RPTexts;

/** One connection's creation screens; the existing stages own their validation and saves. */
public final class CreationWizard {

	private static final int WIDTH = 360;
	private static final int PAGE_SIZE = 8;
	private static final ClickCallback.Options CALLBACKS = ClickCallback.Options.builder()
			.uses(1).lifetime(Duration.ofHours(1)).build();
	private final CharacterCreation creation;
	private final Player player;
	private final Map<Stage, Map<String, String>> fields = new HashMap<>();
	private final Map<Stage, Integer> pages = new HashMap<>();
	private final Set<Stage> completedBefore = new HashSet<>();
	private final Set<Stage> confirmed = new HashSet<>();
	private Stage displayed;
	private SelectableItem focused;
	private String error;
	private int revision;

	CreationWizard(CharacterCreation creation) {
		this.creation = creation;
		this.player = creation.getPlayer();
		var account = PlayerManager.get(player);
		for (Stage stage : creation.getStages()) {
			if (!stage.shouldRepeat() && account.hasCompletedStage(stage)) completedBefore.add(stage);
		}
	}

	public boolean isAvailable(Stage stage) {
		return stage.runsInGame() && !completedBefore.contains(stage)
				&& (!stage.hasDependency() || stage.getDependency().check(creation.getCharacter()))
				&& stage.passesAccountAgeGate(PlayerManager.get(player));
	}

	public void show(Stage stage) {
		if (displayed != stage) focused = null;
		if (stage instanceof QuestionStage question && question.getQuestions().isEmpty()) question.pick();
		displayed = stage;
		error = null;
		render();
	}

	public void resume() {
		render();
	}

	public void error(String message) {
		error = ClueFormatter.stripColor(message);
		render();
	}

	/** Keeps legacy callers' messages, while presenting creation errors in the form. */
	public static boolean reportError(CharacterCreation creation, String message) {
		if (creation == null || creation.getWizard() == null) return false;
		creation.getWizard().error(message);
		return true;
	}

	public static void sendError(Player player, CharacterCreation creation, String message) {
		if (!reportError(creation, message)) RPTexts.send(player, message);
	}

	public void advance(Stage stage) {
		confirmed.add(stage);
		pruneUnavailableTraits();
		if (creation.isEditingFromSummary()) creation.returnToSummary();
		else creation.runStage();
	}

	private void pruneUnavailableTraits() {
		RPCharacter character = creation.getCharacter();
		boolean changed;
		do {
			changed = false;
			for (Stage stage : creation.getStages()) {
				if (!(stage instanceof SelectionStage selection) || !"trait".equalsIgnoreCase(selection.getTarget())
						|| !stage.runsInGame()) continue;
				for (var trait : new ArrayList<>(character.getTraits())) {
					if (trait.getTraitData().getKey().equalsIgnoreCase(selection.getKey())
							&& (!isAvailable(stage) || trait.getTraitData().hasDependency() && !trait.getTraitData().getDependency().check(character))) {
						character.removeTrait(trait);
						confirmed.remove(stage);
						selection.hydrateFromCharacter(character);
						changed = true;
					}
				}
			}
		} while (changed);
	}

	public void close() {
		revision++;
		player.closeDialog();
	}

	private void render() {
		int seen = ++revision;
		List<DialogBody> body = new ArrayList<>();
		List<DialogInput> inputs = new ArrayList<>();
		List<ActionButton> actions = new ArrayList<>();
		List<Stage> available = creation.getStages().stream().filter(this::isAvailable).toList();
		int index = available.indexOf(displayed);
		body.add(message(index < 0 ? "Review your character" : "Step " + (index + 1) + " of " + available.size(), NamedTextColor.GRAY));
		if (displayed == null || displayed instanceof SummaryStage) {
			review(body, actions, available, seen);
		} else if (displayed instanceof InfoStage info) {
			List<String> copy = info.hasWebMessages() && info.runsOnWeb() ? info.getWebMessages() : info.getMessages();
			for (String raw : copy) {
				String line = copyBody(InfoStage.substitutePlaceholders(raw));
				if (!line.contains("/rpcharacter") && !line.isBlank()) body.add(message(line, NamedTextColor.GRAY));
			}
		} else if (displayed instanceof SetterStage setter) {
			setter(body, inputs, actions, setter, seen);
		} else if (displayed instanceof SelectionStage selection) {
			selection(body, actions, selection, seen);
		} else if (displayed instanceof AttributesStage attributes) {
			attributes(body, actions, attributes, seen);
		} else if (displayed instanceof ClueStage) {
			clues(body, inputs);
		} else if (displayed instanceof QuestionStage question) {
			if (question.getCurrentQuestion() < question.getQuestions().size()) {
				body.add(message(question.getQuestions().get(question.getCurrentQuestion()).getQuestion(), NamedTextColor.WHITE));
				inputs.add(input("answer", "Your answer", remembered("answer", ""), 256, false));
			}
		}
		if (error != null) body.add(message(error, NamedTextColor.RED));
		boolean review = displayed == null || displayed instanceof SummaryStage;
		actions.add(button(creation.isEditingFromSummary() ? "Back to review" : "Back", seen, response -> {
			remember(response);
			creation.goBack();
		}));
		if (!(displayed instanceof SetterStage setter && "real_age".equalsIgnoreCase(setter.getTarget()))) {
			actions.add(button(review ? "Create character" : creation.isEditingFromSummary() ? "Save and review" : "Next", seen,
					response -> { remember(response); submit(); }));
		}
		body.add(message("Esc closes this screen. /rpcharacter create resumes your draft.", NamedTextColor.DARK_GRAY));
		player.showDialog(Dialog.create(builder -> builder.empty()
				.base(DialogBase.builder(Component.text(review ? "Review your character" : title(displayed), NamedTextColor.GOLD))
						.canCloseWithEscape(true).pause(false)
						.afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
						.body(body).inputs(inputs).build())
				.type(DialogType.multiAction(actions, button("Cancel creation", seen, response -> {
					remember(response);
					cancelPrompt();
				}), 2))));
	}

	private void setter(List<DialogBody> body, List<DialogInput> inputs, List<ActionButton> actions, SetterStage stage, int seen) {
		String target = stage.getTarget().toLowerCase(Locale.ROOT);
		RPCharacter character = creation.getCharacter();
		if (target.equals("real_age")) {
			body.add(message("Are you 18 or older in real life?", NamedTextColor.WHITE));
			body.add(message("This controls age-restricted RP. Lying results in a permanent ban with no appeal.", NamedTextColor.GRAY));
			for (String answer : List.of("Yes", "No")) actions.add(button(answer, seen, response -> stage.finish(answer, player, creation)));
			return;
		}
		boolean description = target.equals("description");
		String initial = switch (target) {
			case "name" -> character.getName();
			case "age" -> character.getBirthday() == null ? "" : AgeCalculator.formatAge(character.getBirthday());
			case "description" -> character.getPersonaDescription();
			default -> "";
		};
		int max = description ? Cache.characterDescriptionMaxLength : target.equals("name") ? Cache.personaDisplayNameMaxLength : 64;
		String hint = description ? "Describe how they look, sound and come across. " + Cache.characterDescriptionMinLength + "–" + max + " characters."
				: target.equals("age") && character.getRace() != null ? "Whole years, from " + Cache.calendarAgeMinimum + " to " + character.getRace().getAgeMax() + " for your race."
				: "Choose the name other players will know your character by.";
		body.add(message(hint, NamedTextColor.GRAY));
		inputs.add(input("value", title(stage), remembered("value", initial), Math.max(1, max), description));
	}

	private void selection(List<DialogBody> body, List<ActionButton> actions, SelectionStage stage, int seen) {
		body.add(message("Choose " + stage.getMinSelections() + "–" + stage.getMaxSelections() + ". Click a choice to read its details.", NamedTextColor.GRAY));
		if (stage.hasPoints()) body.add(message("Points remaining: " + stage.getPoints() + " / " + stage.getInitialPoints(), NamedTextColor.GOLD));
		if (focused != null) body.add(message(details(focused), NamedTextColor.WHITE));
		List<SelectableItem> options = stage.getOptions();
		int page = pages.getOrDefault(stage, 0);
		for (SelectableItem item : options.subList(Math.min(page * PAGE_SIZE, options.size()), Math.min((page + 1) * PAGE_SIZE, options.size()))) {
			String label = (item.isSelected() ? "✓ " : "") + plain(item.getName())
					+ (stage.hasPoints() ? " (" + item.getCost() + ")" : "");
			actions.add(ActionButton.builder(Component.text(label, item.isSelected() ? NamedTextColor.GREEN : NamedTextColor.WHITE))
					.width(174).tooltip(component(details(item)))
					.action(action(seen, response -> {
						focused = item;
						error = CreationManager.toggleSelection(player, stage, creation, item);
						render();
					})).build());
		}
		if (options.size() > PAGE_SIZE) {
			body.add(message("Choices page " + (page + 1) + " of " + ((options.size() + PAGE_SIZE - 1) / PAGE_SIZE), NamedTextColor.GRAY));
			actions.add(button("Previous choices", seen, response -> { pages.put(stage, Math.max(0, page - 1)); render(); }));
			actions.add(button("More choices", seen, response -> { pages.put(stage, Math.min((options.size() - 1) / PAGE_SIZE, page + 1)); render(); }));
		}
	}

	private void attributes(List<DialogBody> body, List<ActionButton> actions, AttributesStage stage, int seen) {
		body.add(message("Spend all " + stage.getPool() + " points. Each new rank costs 1, 2, 4, 8… points.", NamedTextColor.GRAY));
		body.add(message("Points remaining: " + stage.getRemaining(), NamedTextColor.GOLD));
		for (String attr : stage.getAttributes()) {
			body.add(message(pretty(attr) + ": " + stage.getRank(attr) + " / " + stage.getMaxRank(), NamedTextColor.WHITE));
			actions.add(button("− " + pretty(attr), seen, response -> { stage.tryDecrease(attr); render(); }));
			actions.add(button("+ " + pretty(attr), seen, response -> { stage.tryIncrease(attr); render(); }));
		}
	}

	private void clues(List<DialogBody> body, List<DialogInput> inputs) {
		RPCharacter character = creation.getCharacter();
		body.add(message("Give others clues to recognise your character: appearance, scent, habits or home.", NamedTextColor.GRAY));
		body.add(message("Each clue needs " + Cache.clueMinLength + "–" + Cache.clueMaxLength + " characters.", NamedTextColor.GRAY));
		List<String> existing = character.getPlayerClues();
		int count = Math.max(character.getCluesNeeded(), existing.size());
		for (int i = 0; i < count; i++) inputs.add(input("clue_" + i, "Clue " + (i + 1),
				remembered("clue_" + i, i < existing.size() ? plain(existing.get(i)) : ""), Math.max(1, Cache.clueMaxLength), true));
	}

	private void review(List<DialogBody> body, List<ActionButton> actions, List<Stage> available, int seen) {
		body.add(message("Check every choice. Click an entry to edit it, then return here.", NamedTextColor.GRAY));
		List<Stage> entries = available.stream().filter(stage -> !(stage instanceof InfoStage) && !(stage instanceof SummaryStage)).toList();
		int page = Math.min(pages.getOrDefault(displayed, 0), Math.max(0, (entries.size() - 1) / PAGE_SIZE));
		for (Stage stage : entries.subList(Math.min(page * PAGE_SIZE, entries.size()), Math.min((page + 1) * PAGE_SIZE, entries.size()))) {
			String value = reviewValue(stage);
			body.add(message(title(stage) + ": " + value, NamedTextColor.WHITE));
			actions.add(ActionButton.builder(Component.text("Edit " + title(stage))).width(174)
					.tooltip(component(value)).action(action(seen, response -> creation.jumpToStageForEdit(stage.getId()))).build());
		}
		if (entries.size() > PAGE_SIZE) {
			body.add(message("Review page " + (page + 1) + " of " + ((entries.size() + PAGE_SIZE - 1) / PAGE_SIZE), NamedTextColor.GRAY));
			actions.add(button("Previous entries", seen, response -> { pages.put(displayed, Math.max(0, page - 1)); render(); }));
			actions.add(button("More entries", seen, response -> { pages.put(displayed, Math.min((entries.size() - 1) / PAGE_SIZE, page + 1)); render(); }));
		}
	}

	private String reviewValue(Stage stage) {
		RPCharacter character = creation.getCharacter();
		if (!confirmed.contains(stage)) return "Not confirmed";
		if (stage instanceof SetterStage setter) return switch (setter.getTarget().toLowerCase(Locale.ROOT)) {
			case "name" -> character.getName();
			case "description" -> character.getPersonaDescription();
			case "age" -> AgeCalculator.formatAge(character.getBirthday());
			case "real_age" -> PlayerManager.get(player).isEighteen() ? "18+" : "Under 18";
			default -> fields.getOrDefault(stage, Map.of()).getOrDefault("value", "");
		};
		if (stage instanceof SelectionStage selection) {
			if ("race".equalsIgnoreCase(selection.getTarget())) return plain(character.getRace().getName());
			if ("class".equalsIgnoreCase(selection.getTarget())) return selection.getOptions().stream()
					.filter(item -> item.getId().equalsIgnoreCase(character.getMMOClass())).map(item -> plain(item.getName()))
					.findFirst().orElse(character.getMMOClass());
			return character.getTraits().stream().filter(trait -> trait.getTraitData().getKey().equalsIgnoreCase(selection.getKey()))
					.map(trait -> plain(TraitEffectResolver.resolveDisplayName(character, trait))).reduce((a, b) -> a + ", " + b).orElse("None");
		}
		if (stage instanceof AttributesStage attributes) {
			AttributesStage saved = new AttributesStage(attributes);
			saved.hydrateFromCharacter(character);
			return saved.getAttributes().stream().map(attr -> pretty(attr) + " " + saved.getRank(attr))
					.reduce((a, b) -> a + ", " + b).orElse("None");
		}
		if (stage instanceof ClueStage) return String.join("\n", character.getPlayerClues().stream().map(CreationWizard::plain).toList());
		return "Completed";
	}

	private void submit() {
		if (displayed == null || displayed instanceof SummaryStage) creation.finish();
		else if (displayed instanceof SetterStage setter) setter.finish(remembered("value", ""), player, creation);
		else if (displayed instanceof SelectionStage selection) selection.confirm(player, creation);
		else if (displayed instanceof AttributesStage attributes) attributes.confirm(player, creation);
		else if (displayed instanceof ClueStage) saveClues();
		else if (displayed instanceof QuestionStage question) {
			int index = question.getCurrentQuestion();
			if (index >= question.getQuestions().size()) advance(question);
			else if (question.getQuestions().get(index).isCorrect(remembered("answer", ""))) {
				question.setCurrentQuestion(index + 1);
				if (index + 1 == question.getQuestions().size()) advance(question);
				else { fields.remove(question); render(); }
			} else error("Wrong answer. Try again.");
		} else advance(displayed);
	}

	private void saveClues() {
		RPCharacter character = creation.getCharacter();
		List<String> clues = new ArrayList<>();
		int count = Math.max(character.getCluesNeeded(), character.getPlayerClues().size());
		for (int i = 0; i < count; i++) {
			String clue = remembered("clue_" + i, "");
			if (!clue.isBlank()) clues.add(clue);
		}
		String invalid = CreationRules.cluesError(clues, character.getCluesNeeded());
		if (invalid != null) { error(invalid); return; }
		for (int i = character.getPlayerClues().size() - 1; i >= 0; i--) character.removePlayerClue(i);
		for (String clue : clues) character.addPlayerClue(clue);
		advance(displayed);
	}

	public boolean validateComplete() {
		for (Stage stage : creation.getStages()) {
			if (isAvailable(stage) && !(stage instanceof InfoStage) && !(stage instanceof SummaryStage) && !confirmed.contains(stage)) {
				error("Review " + title(stage) + " before creating your character.");
				return false;
			}
		}
		RPCharacter character = creation.getCharacter();
		String invalid = CreationRules.nameError(character.getName());
		if (invalid == null) invalid = CreationRules.classError(character.getMMOClass());
		if (invalid == null) invalid = character.getRace() == null ? "Pick a race." : CreationRules.birthdayError(character, character.getBirthday());
		if (invalid == null) invalid = CreationRules.descriptionError(character.getPersonaDescription());
		if (invalid == null) invalid = CreationRules.cluesError(character.getPlayerClues(), character.getCluesNeeded());
		if (invalid == null) invalid = CreationRules.traitsErrorInGame(character, PlayerManager.get(player), creation.getStages());
		if (invalid != null) { error(invalid); return false; }
		return true;
	}

	private void remember(DialogResponseView response) {
		if (response == null) return;
		Map<String, String> values = fields.computeIfAbsent(displayed, stage -> new HashMap<>());
		if (displayed instanceof SetterStage) values.put("value", text(response, "value"));
		else if (displayed instanceof QuestionStage) values.put("answer", text(response, "answer"));
		else if (displayed instanceof ClueStage) {
			int count = Math.max(creation.getCharacter().getCluesNeeded(), creation.getCharacter().getPlayerClues().size());
			for (int i = 0; i < count; i++) values.put("clue_" + i, text(response, "clue_" + i));
		}
	}

	private String remembered(String key, String initial) {
		return fields.getOrDefault(displayed, Map.of()).getOrDefault(key, initial == null ? "" : initial);
	}

	private static String text(DialogResponseView response, String key) {
		String text = response.getText(key);
		return text == null ? "" : text;
	}

	private void cancelPrompt() {
		int seen = ++revision;
		player.showDialog(Dialog.create(builder -> builder.empty()
				.base(DialogBase.builder(Component.text("Discard this character?", NamedTextColor.GOLD))
						.canCloseWithEscape(true).pause(false).afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
						.body(List.of(message("Your unfinished choices will be lost.", NamedTextColor.GRAY))).build())
				.type(DialogType.multiAction(List.of(button("Keep creating", seen, response -> render()),
						button("Discard draft", seen, response -> creation.cancel())), null, 2))));
	}

	private ActionButton button(String label, int seen, Consumer<DialogResponseView> callback) {
		return ActionButton.builder(Component.text(label)).width(174).action(action(seen, callback)).build();
	}

	private DialogAction action(int seen, Consumer<DialogResponseView> callback) {
		return DialogAction.customClick((response, audience) -> {
			if (audience != player || !player.isOnline() || seen != revision || creation.isCancelled()
					|| CreationManager.activeCreators.get(player) != creation) return;
			revision++;
			callback.accept(response);
		}, CALLBACKS);
	}

	private static DialogInput input(String key, String label, String value, int max, boolean multiline) {
		return DialogInput.text(key, WIDTH, Component.text(label), true, value, max,
				multiline ? TextDialogInput.MultilineOptions.create(null, 60) : null);
	}

	private static DialogBody message(String text, NamedTextColor colour) {
		return DialogBody.plainMessage(component(text).colorIfAbsent(colour), WIDTH);
	}

	private static Component component(String text) {
		return GuiText.component(RPTexts.formatGui(text));
	}

	private static String plain(String text) {
		return ClueFormatter.stripColor(text);
	}

	private static String title(Stage stage) {
		if (stage instanceof SetterStage setter) return pretty(setter.getTarget());
		if (stage instanceof ClueStage) return "Character clues";
		return pretty(stage.getId().replaceAll("^(creation_|character_)", "").replaceAll("_(selection|info|set)?_?stage$", ""));
	}

	private static String pretty(String text) {
		String words = text.replace('_', ' ');
		return Character.toUpperCase(words.charAt(0)) + words.substring(1);
	}

	private static String copyBody(String text) {
		int open = text.indexOf('(');
		return open >= 0 && text.endsWith(")") ? text.substring(open + 1, text.length() - 1) : text;
	}

	private String details(SelectableItem item) {
		List<String> lines = new ArrayList<>();
		lines.add(item.getName());
		if (item.getType().equalsIgnoreCase("race")) {
			var race = RaceLoader.getByString(item.getId());
			if (race != null) { lines.addAll(race.getDesc()); lines.add("Maximum age: " + race.getAgeMax()); }
		} else if (item.getType().equalsIgnoreCase("trait")) {
			lines.addAll(TraitEffectResolver.resolveDescription(creation.getCharacter(), TraitLoader.getByString(item.getId())));
		} else {
			ClassPickService.baseClasses().stream().filter(klass -> klass.getId().equalsIgnoreCase(item.getId()))
					.findFirst().ifPresent(klass -> lines.addAll(klass.getDescription()));
		}
		for (var modifier : item.getAttributeData().getModifiers()) {
			if (modifier.getAmount() != 0) lines.add(pretty(modifier.getType()) + ": "
					+ (modifier.getAmount() > 0 ? "+" : "") + modifier.getAmount());
		}
		if (item.hasDependency()) {
			var dependency = item.getDependency();
			lines.add(("all".equalsIgnoreCase(dependency.getMode()) ? "Requires all: " : "Requires one: ")
					+ String.join(", ", dependency.getDependencies().stream().map(this::optionName).toList()));
		}
		if (item.hasExclusives()) lines.add("Incompatible with: " + String.join(", ", item.getExclusives().stream().map(this::optionName).toList()));
		return String.join("\n", lines);
	}

	private String optionName(String id) {
		var trait = TraitLoader.getByString(id);
		if (trait != null) return plain(TraitEffectResolver.resolveDisplayName(creation.getCharacter(), trait));
		var race = RaceLoader.getByString(id);
		return race == null ? pretty(id) : plain(race.getName());
	}
}
