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
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.identity.DisplayIdentityService;
import net.tfminecraft.rpcharacters.identity.NameColour;
import net.tfminecraft.rpcharacters.persona.NameColourParser;
import net.tfminecraft.rpcharacters.persona.PermissionGroupService;
import net.tfminecraft.rpcharacters.objects.attributes.AttributeData;
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
import net.tfminecraft.rpcharacters.utils.PlaytimeGate;

/** One connection's creation screens; the existing stages own their validation and saves. */
public final class CreationWizard {

	private static final int WIDTH = 360;
	private static final int PAGE_SIZE = 8;
	private static final ClickCallback.Options CALLBACKS = ClickCallback.Options.builder()
			.uses(ClickCallback.UNLIMITED_USES).lifetime(Duration.ofDays(7)).build();
	private final CharacterCreation creation;
	private final Player player;
	private final Map<Stage, Map<String, String>> fields = new HashMap<>();
	private final Map<Stage, Integer> pages = new HashMap<>();
	private final Set<Stage> completedBefore = new HashSet<>();
	private final Set<Stage> confirmed = new HashSet<>();
	private Stage displayed;
	private String error;
	private String colourStyle;
	private String coloursBefore;
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
		if (stage instanceof QuestionStage question && question.getQuestions().isEmpty()) question.pick();
		displayed = stage;
		colourStyle = null;
		error = null;
		render();
	}

	public void resume() {
		render();
	}

	public void error(String message) {
		String plain = ClueFormatter.stripColor(message);
		if (java.util.Objects.equals(error, plain)) return;
		error = plain;
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
		if (stage instanceof SetterStage setter) {
			if ("name".equalsIgnoreCase(setter.getTarget())) creation.getCharacter().setNameColour(NameColour.of(colours()));
			if ("description".equalsIgnoreCase(setter.getTarget())) creation.getCharacter().setGender(remembered("gender", "").trim());
		}
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
		if (colourStyle != null) { renderColours(); return; }
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
			selection(body, inputs, actions, selection, seen);
		} else if (displayed instanceof AttributesStage attributes) {
			attributes(body, inputs, actions, attributes, seen);
		} else if (displayed instanceof ClueStage) {
			clues(body, inputs);
		} else if (displayed instanceof QuestionStage question) {
			if (question.getCurrentQuestion() < question.getQuestions().size()) {
				body.add(message(question.getQuestions().get(question.getCurrentQuestion()).getQuestion(), NamedTextColor.WHITE));
				inputs.add(input("answer", "Your answer", remembered("answer", ""), 256, false));
			}
		}
		body.add(message(error == null ? " " : error, "All points assigned".equals(error) ? NamedTextColor.GREEN : NamedTextColor.RED));
		boolean review = displayed == null || displayed instanceof SummaryStage;
		actions.add(button(creation.isEditingFromSummary() ? "Back to review" : "Back", seen, response -> {
			remember(response);
			creation.goBack();
		}));
		actions.add(button(review ? "Create character" : creation.isEditingFromSummary() ? "Save and review" : "Next", seen,
				response -> { remember(response); submit(); }));
		player.showDialog(Dialog.create(builder -> builder.empty()
				.base(DialogBase.builder(Component.text(review ? "Review your character" : title(displayed), NamedTextColor.GOLD))
						.canCloseWithEscape(false).pause(false)
						.afterAction(DialogBase.DialogAfterAction.NONE)
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
			inputs.add(choice("value", "Your answer", remembered("value", ""), List.of("", "Yes", "No"), List.of("Choose…", "Yes", "No")));
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
		if (description) body.add(message("Appearance and manner · " + Cache.characterDescriptionMinLength + "–" + max + " characters", NamedTextColor.GRAY));
		if (target.equals("age") && character.getRace() != null) {
			int minimum = Cache.calendarAgeMinimum;
			int maximum = Math.max(minimum + 1, character.getRace().getAgeMax());
			float age = parseNumber(remembered("value", initial), minimum);
			inputs.add(DialogInput.numberRange("value", WIDTH, Component.text("Age"), "%s: %s years", minimum, maximum, Math.clamp(age, minimum, maximum), 1f));
		} else inputs.add(input("value", title(stage), remembered("value", initial), Math.max(1, max), description));
		if (description) inputs.add(input("gender", "Gender (optional)", remembered("gender", character.getGender()), 24, false));
		if (target.equals("name")) nameColours(body, actions, seen);
	}

	private void selection(List<DialogBody> body, List<DialogInput> inputs, List<ActionButton> actions, SelectionStage stage, int seen) {
		if (stage.getMaxSelections() > 1) body.add(message(stage.getMinSelections() == stage.getMaxSelections()
				? "Choose " + stage.getMaxSelections() + " · " + picked(stage).size() + " selected" : "Choose up to " + stage.getMaxSelections(), NamedTextColor.GRAY));
		if (stage.hasPoints()) body.add(message(stage.getInitialPoints() + " points", NamedTextColor.GOLD));
		List<SelectableItem> options = stage.getOptions();
		int page = pages.getOrDefault(stage, 0);
		for (int i = page * PAGE_SIZE; i < Math.min((page + 1) * PAGE_SIZE, options.size()); i++) {
			SelectableItem item = options.get(i);
			boolean selected = picked(stage).contains(item.getId());
			String disabled = disabledReason(stage, item);
			Component label = Component.text(selected ? "☑ " : disabled == null ? "☐ " : "☒ ", selected ? NamedTextColor.AQUA : NamedTextColor.DARK_GRAY)
					.append(disabled == null ? optionLabel(stage, item) : Component.text(plain(item.getName()), NamedTextColor.DARK_GRAY));
			label = label.hoverEvent(component(disabled == null ? details(item) : disabled + "\n\n" + details(item)));
			if (disabled == null) label = link(label, seen, response -> togglePick(stage, item));
			body.add(DialogBody.plainMessage(label, WIDTH));
		}
		if (options.size() > PAGE_SIZE) {
			body.add(message("Choices page " + (page + 1) + " of " + ((options.size() + PAGE_SIZE - 1) / PAGE_SIZE), NamedTextColor.GRAY));
			body.add(pageLinks("choices", stage, page, options.size(), seen));
		}
	}

	private Component optionLabel(SelectionStage stage, SelectableItem item) {
		return component(item.getName()).colorIfAbsent(NamedTextColor.WHITE)
				.append(Component.text(stage.hasPoints() ? " · " + item.getCost() + " points" : "", NamedTextColor.GOLD))
				.hoverEvent(component(details(item)));
	}

	private Set<String> picked(SelectionStage stage) {
		Set<String> result = new HashSet<>();
		for (SelectableItem item : stage.getOptions()) if (Boolean.parseBoolean(remembered("option_" + item.getId(), Boolean.toString(item.isSelected())))) result.add(item.getId());
		return result;
	}

	private void togglePick(SelectionStage stage, SelectableItem item) {
		Set<String> selected = picked(stage);
		if (stage.getMaxSelections() == 1 && stage.getMinSelections() > 0 && selected.contains(item.getId())) return;
		String disabled = disabledReason(stage, item);
		if (disabled != null) { error(disabled); return; }
		Map<String, String> values = fields.computeIfAbsent(displayed, ignored -> new HashMap<>());
		if (stage.getMaxSelections() == 1) for (SelectableItem option : stage.getOptions()) values.put("option_" + option.getId(), "false");
		values.put("option_" + item.getId(), Boolean.toString(!selected.contains(item.getId())));
		error = null;
		render();
	}

	private String disabledReason(SelectionStage stage, SelectableItem item) {
		Set<String> selected = picked(stage);
		if (selected.contains(item.getId())) return null;
		if ("trait".equalsIgnoreCase(item.getType())) {
			var trait = TraitLoader.getByString(item.getId());
			if (trait == null) return "Disabled because this trait is unavailable.";
		}
		SelectionStage candidate = new SelectionStage(stage);
		if (stage.getMaxSelections() > 1) for (SelectableItem option : candidate.getOptions()) if (selected.contains(option.getId())) {
			candidate.select(option);
			option.setSelected(true);
		}
		SelectableItem copy = candidate.getOptions().stream().filter(option -> option.getId().equals(item.getId())).findFirst().orElseThrow();
		AttributeData before = new AttributeData(creation.getTempData());
		String denial;
		try { denial = CreationManager.toggleSelection(player, candidate, creation, copy); }
		finally { creation.getTempData().clearAll(); creation.getTempData().mergeFrom(before); }
		if (denial == null) return null;
		String reason = plain(denial);
		if (reason.contains("incompatible")) {
			Set<String> conflicts = new HashSet<>(stage.getMaxSelections() == 1 ? Set.of() : selected);
			creation.getCharacter().getTraits().stream().filter(trait -> !"trait".equalsIgnoreCase(stage.getTarget())
					|| !trait.getTraitData().getKey().equalsIgnoreCase(stage.getKey())).forEach(trait -> conflicts.add(trait.getId()));
			List<String> names = conflicts.stream().filter(id -> {
				var trait = TraitLoader.getByString(id);
				return item.isExclusive(id) || trait != null && trait.getTraitData().isExclusive(item.getId());
			}).map(this::optionName).sorted().toList();
			return "Disabled because it conflicts with " + String.join(", ", names) + ".";
		}
		if (reason.equals("Lacking requirements")) return "Disabled because it requires "
				+ ("all".equalsIgnoreCase(item.getDependency().getMode()) ? "" : "one of ")
				+ String.join(", ", item.getDependency().getDependencies().stream().map(this::optionName).toList()) + ".";
		if (reason.equals("Cannot afford this trait")) return "Disabled because it costs " + item.getCost() + " points; " + candidate.getPoints() + " remain.";
		if (reason.equals("Cannot make any more selections")) return "Disabled because all " + stage.getMaxSelections() + " picks are filled. Untick a choice first.";
		return "Disabled because " + reason;
	}

	private DialogBody pageLinks(String kind, Stage stage, int page, int total, int seen) {
		var line = Component.text();
		if (page > 0) line.append(link(Component.text("Previous " + kind, NamedTextColor.AQUA), seen, response -> changePage(stage, page - 1)));
		if (page > 0 && (page + 1) * PAGE_SIZE < total) line.append(Component.text("   ·   ", NamedTextColor.DARK_GRAY));
		if ((page + 1) * PAGE_SIZE < total) line.append(link(Component.text("More " + kind, NamedTextColor.AQUA), seen, response -> changePage(stage, page + 1)));
		return DialogBody.plainMessage(line.build(), WIDTH);
	}

	private void changePage(Stage stage, int page) {
		pages.put(stage, page);
		render();
	}

	private void attributes(List<DialogBody> body, List<DialogInput> inputs, List<ActionButton> actions, AttributesStage stage, int seen) {
		body.add(message(stage.getPool() + " points to spend", NamedTextColor.GOLD));
		for (String attr : stage.getAttributes()) {
			String initial = remembered("rank_" + attr, Integer.toString(stage.getRank(attr)));
			List<SingleOptionDialogInput.OptionEntry> options = new ArrayList<>();
			for (int rank = 0; rank <= stage.getMaxRank() && rankCost(rank) <= stage.getPool(); rank++) options.add(SingleOptionDialogInput.OptionEntry.create(Integer.toString(rank),
					Component.text("Rank " + rank, NamedTextColor.WHITE).append(Component.text(" · " + rankCost(rank) + " points", NamedTextColor.GOLD)), initial.equals(Integer.toString(rank))));
			inputs.add(DialogInput.singleOption("rank_" + attr, WIDTH, options, Component.text(pretty(attr), NamedTextColor.AQUA), true));
		}
		actions.add(button("Check points", seen, response -> { remember(response); String invalid = applyAttributes(stage, false); error(invalid == null ? "All points assigned" : invalid); }));
		actions.add(button("Balanced", seen, response -> { remember(response); balance(stage); }));
	}

	private void clues(List<DialogBody> body, List<DialogInput> inputs) {
		RPCharacter character = creation.getCharacter();
		List<String> existing = character.getPlayerClues();
		int count = Math.max(character.getCluesNeeded(), existing.size());
		body.add(message(count + " clues · " + Cache.clueMinLength + "–" + Cache.clueMaxLength + " characters each", NamedTextColor.GRAY));
		for (int i = 0; i < count; i++) inputs.add(input("clue_" + i, "Clue " + (i + 1),
				remembered("clue_" + i, i < existing.size() ? plain(existing.get(i)) : ""), Math.max(1, Cache.clueMaxLength), false));
	}

	private int colourCap() {
		return Math.max(0, Math.min(8, PermissionGroupService.getNameColourStops(player)));
	}

	private void nameColours(List<DialogBody> body, List<ActionButton> actions, int seen) {
		body.add(namePreview());
		if (colourCap() == 0) return;
		actions.add(button("Preview name", seen, this::preview));
		actions.add(button("Edit colours", seen, response -> {
			remember(response);
			coloursBefore = remembered("colours", savedColours());
			colourStyle = "choose";
			error = null;
			render();
		}));
	}

	private DialogBody namePreview() {
		String name = remembered("value", creation.getCharacter().getName());
		return message(name.isBlank() ? "Name preview" : DisplayIdentityService.colourPlain(name, NameColour.of(colours())), NamedTextColor.WHITE);
	}

	private void preview(DialogResponseView response) {
		Map<String, String> before = new HashMap<>(fields.getOrDefault(displayed, Map.of()));
		remember(response);
		String invalid = appearanceError();
		if (invalid != null) error(invalid);
		else if (!before.equals(fields.get(displayed))) { error = null; render(); }
	}

	private void renderColours() {
		int seen = ++revision;
		List<DialogBody> body = new ArrayList<>(List.of(namePreview()));
		List<DialogInput> inputs = new ArrayList<>();
		List<ActionButton> actions = new ArrayList<>();
		ActionButton back = button("Back to name", seen, response -> {
			fields.get(displayed).put("colours", coloursBefore);
			colourStyle = null;
			error = null;
			render();
		});
		boolean choosing = colourStyle.equals("choose");
		if (choosing) {
			actions.add(button("Default colour", seen, response -> {
				fields.get(displayed).put("colours", "");
				colourStyle = null;
				error = null;
				render();
			}));
			if (colourCap() > 0) {
				actions.add(button("Solid colour", seen, response -> chooseColourStyle("solid")));
				if (colourCap() > 1) actions.add(button("Gradient", seen, response -> chooseColourStyle("gradient")));
				actions.add(button("Custom hex", seen, response -> chooseColourStyle("custom")));
			}
		} else {
			List<String> colours = rawColours();
			if (colourStyle.equals("custom")) {
				body.add(message("Up to " + colourCap() + " hex colours, separated by spaces", NamedTextColor.GRAY));
				inputs.add(input("colours", "Hex colours", remembered("colours", ""), 8 * 8, false));
			} else {
				inputs.add(colourPicker("colour_0", colourStyle.equals("solid") ? "Colour" : "Start colour", colours.isEmpty() ? "#ffaa00" : colours.getFirst()));
				if (colourStyle.equals("gradient")) inputs.add(colourPicker("colour_1", "End colour", colours.isEmpty() ? "#ffffff" : colours.getLast()));
			}
			actions.add(button("Preview", seen, this::preview));
			actions.add(button("Change style", seen, response -> { remember(response); colourStyle = "choose"; error = null; render(); }));
			actions.add(back);
			actions.add(button("Use colours", seen, response -> {
				remember(response);
				String invalid = appearanceError();
				if (invalid != null) { error(invalid); return; }
				colourStyle = null;
				error = null;
				render();
			}));
		}
		if (error != null) body.add(message(error, NamedTextColor.RED));
		player.showDialog(Dialog.create(builder -> builder.empty()
				.base(DialogBase.builder(Component.text("Name colour", NamedTextColor.GOLD)).canCloseWithEscape(false).pause(false)
						.afterAction(DialogBase.DialogAfterAction.NONE).body(body).inputs(inputs).build())
				.type(DialogType.multiAction(actions, choosing ? back : null, 2))));
	}

	private void chooseColourStyle(String style) {
		List<String> current = rawColours();
		String first = current.isEmpty() ? "#ffaa00" : NameColourParser.parse(current.getFirst()).orElse("#ffaa00");
		if (style.equals("solid")) fields.get(displayed).put("colours", first);
		else if (style.equals("gradient")) fields.get(displayed).put("colours", first + " "
				+ (current.size() > 1 ? NameColourParser.parse(current.get(1)).orElse("#ffffff") : "#ffffff"));
		colourStyle = style;
		error = null;
		render();
	}

	private DialogInput colourPicker(String key, String label, String initial) {
		List<NamedTextColor> palette = List.of(NamedTextColor.BLACK, NamedTextColor.DARK_BLUE, NamedTextColor.DARK_GREEN, NamedTextColor.DARK_AQUA,
				NamedTextColor.DARK_RED, NamedTextColor.DARK_PURPLE, NamedTextColor.GOLD, NamedTextColor.GRAY, NamedTextColor.DARK_GRAY,
				NamedTextColor.BLUE, NamedTextColor.GREEN, NamedTextColor.AQUA, NamedTextColor.RED, NamedTextColor.LIGHT_PURPLE, NamedTextColor.YELLOW, NamedTextColor.WHITE);
		List<SingleOptionDialogInput.OptionEntry> options = new ArrayList<>();
		for (NamedTextColor colour : palette) options.add(SingleOptionDialogInput.OptionEntry.create(colour.asHexString().toLowerCase(Locale.ROOT),
				Component.text(pretty(colour.toString()), colour), initial.equalsIgnoreCase(colour.asHexString())));
		if (options.stream().noneMatch(SingleOptionDialogInput.OptionEntry::initial)) options.add(SingleOptionDialogInput.OptionEntry.create(initial, Component.text(initial), true));
		return DialogInput.singleOption(key, 280, options, Component.text(label), true);
	}

	private String savedColours() {
		NameColour saved = creation.getCharacter().getNameColour();
		return saved == null ? "" : String.join(" ", saved.getHexCodes());
	}

	private List<String> rawColours() {
		String custom = remembered("colours", savedColours()).trim();
		return custom.isEmpty() ? List.of() : List.of(custom.split("\\s+"));
	}

	private List<String> colours() {
		return rawColours().stream().map(NameColourParser::parse).flatMap(java.util.Optional::stream).toList();
	}

	private String appearanceError() {
		if (displayed instanceof SetterStage setter && "name".equalsIgnoreCase(setter.getTarget())) {
			List<String> raw = rawColours();
			if (raw.stream().anyMatch(value -> NameColourParser.parse(value).isEmpty())) return "Use six-digit hex colours in the custom colour field.";
			if (!raw.isEmpty()) return PermissionGroupService.validateNameColourHexes(player, raw, false).orElse(null);
		}
		if (displayed instanceof SetterStage setter && "description".equalsIgnoreCase(setter.getTarget())) {
			String gender = remembered("gender", "").trim();
			if (gender.length() > 24 || !gender.matches("[\\p{L}\\p{Nd} ._'\\-]*")) return "Gender may contain letters, numbers, spaces and - _ . ', up to 24 characters.";
		}
		return null;
	}

	private void review(List<DialogBody> body, List<ActionButton> actions, List<Stage> available, int seen) {
		List<Stage> entries = available.stream().filter(stage -> !(stage instanceof InfoStage) && !(stage instanceof SummaryStage)).toList();
		int page = Math.min(pages.getOrDefault(displayed, 0), Math.max(0, (entries.size() - 1) / PAGE_SIZE));
		for (Stage stage : entries.subList(Math.min(page * PAGE_SIZE, entries.size()), Math.min((page + 1) * PAGE_SIZE, entries.size()))) {
			String value = reviewValue(stage);
			body.add(DialogBody.plainMessage(Component.text(title(stage) + ": ", NamedTextColor.GRAY)
					.append(component(value).colorIfAbsent(NamedTextColor.WHITE)).append(Component.text("  "))
					.append(link(Component.text("[Edit]", NamedTextColor.AQUA).hoverEvent(Component.text("Edit " + title(stage))), seen,
							response -> creation.jumpToStageForEdit(stage.getId()))), WIDTH));
		}
		if (entries.size() > PAGE_SIZE) {
			body.add(message("Review page " + (page + 1) + " of " + ((entries.size() + PAGE_SIZE - 1) / PAGE_SIZE), NamedTextColor.GRAY));
			body.add(pageLinks("entries", displayed, page, entries.size(), seen));
		}
	}

	private String reviewValue(Stage stage) {
		RPCharacter character = creation.getCharacter();
		if (!confirmed.contains(stage)) return "Not confirmed";
		if (stage instanceof SetterStage setter) return switch (setter.getTarget().toLowerCase(Locale.ROOT)) {
			case "name" -> DisplayIdentityService.colourPlain(character.getName(), character.getNameColour());
			case "description" -> character.getPersonaDescription() + "\nGender: " + (character.getGender() == null || character.getGender().isBlank() ? "Unspecified" : character.getGender());
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
		String invalid = appearanceError();
		if (invalid != null) { error(invalid); return; }
		if (displayed == null || displayed instanceof SummaryStage) creation.finish();
		else if (displayed instanceof SetterStage setter) setter.finish(remembered("value", ""), player, creation);
		else if (displayed instanceof SelectionStage selection) {
			String selectionError = applySelection(selection);
			if (selectionError != null) error(selectionError);
			else selection.confirm(player, creation);
		} else if (displayed instanceof AttributesStage attributes) {
			String attributesError = applyAttributes(attributes, true);
			if (attributesError != null) error(attributesError);
			else attributes.confirm(player, creation);
		}
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

	/** Validate the whole form with existing rules before replacing any draft choices. */
	private String applySelection(SelectionStage stage) {
		SelectionStage candidate = new SelectionStage(stage);
		List<SelectableItem> pending = new ArrayList<>();
		Set<String> picked = picked(stage);
		for (int i = 0; i < candidate.getOptions().size(); i++) {
			SelectableItem item = candidate.getOptions().get(i);
			if (picked.contains(item.getId())) pending.add(item);
		}
		pending.sort(java.util.Comparator.comparingInt(SelectableItem::getCost));
		AttributeData before = new AttributeData(creation.getTempData());
		String failure = null;
		try {
			while (!pending.isEmpty()) {
				boolean progress = false;
				for (SelectableItem item : new ArrayList<>(pending)) {
					String invalid = CreationManager.toggleSelection(player, candidate, creation, item);
					if (invalid == null) { pending.remove(item); progress = true; }
					else failure = invalid;
				}
				if (!progress) return failure;
			}
		} finally {
			creation.getTempData().clearAll();
			creation.getTempData().mergeFrom(before);
		}
		for (SelectableItem item : new ArrayList<>(stage.getSelection())) {
			stage.unSelect(item);
			item.setSelected(false);
			creation.getTempData().mergeFromReverse(item.getAttributeData());
		}
		for (SelectableItem selected : candidate.getSelection()) {
			SelectableItem item = stage.getOptions().stream().filter(option -> option.getId().equals(selected.getId())).findFirst().orElseThrow();
			stage.select(item);
			item.click(creation);
		}
		return null;
	}

	private String applyAttributes(AttributesStage stage, boolean commit) {
		Map<String, Integer> ranks = new HashMap<>();
		long spent = 0;
		for (String attr : stage.getAttributes()) {
			float value = parseNumber(remembered("rank_" + attr, "0"), Float.NaN);
			if (!Float.isFinite(value) || value != Math.floor(value) || value < 0 || value > stage.getMaxRank()) return "Choose a whole rank from 0 to " + stage.getMaxRank() + ".";
			int rank = (int) value;
			ranks.put(attr, rank);
			spent += rankCost(rank);
		}
		if (spent != stage.getPool()) return spent > stage.getPool() ? (spent - stage.getPool()) + " points over budget" : (stage.getPool() - spent) + " points remaining";
		if (!commit) return null;
		for (String attr : stage.getAttributes()) while (stage.getRank(attr) > 0) stage.tryDecrease(attr);
		for (String attr : stage.getAttributes()) while (stage.getRank(attr) < ranks.get(attr)) stage.tryIncrease(attr);
		return null;
	}

	private void balance(AttributesStage stage) {
		AttributesStage candidate = new AttributesStage(stage);
		while (candidate.getRemaining() > 0) {
			boolean bought = false;
			for (String attr : candidate.getAttributes().stream().sorted(java.util.Comparator.comparingInt(candidate::getRank)).toList()) {
				if (candidate.tryIncrease(attr)) { bought = true; break; }
			}
			if (!bought) { error("This point budget cannot fit the rank limits."); return; }
		}
		Map<String, String> values = fields.computeIfAbsent(displayed, ignored -> new HashMap<>());
		for (String attr : candidate.getAttributes()) values.put("rank_" + attr, Integer.toString(candidate.getRank(attr)));
		error = null;
		render();
	}

	private static long rankCost(int rank) { return (1L << rank) - 1; }
	private static float parseNumber(String text, float fallback) {
		try { float value = Float.parseFloat(text); return Float.isFinite(value) ? value : fallback; }
		catch (NumberFormatException ignored) { return fallback; }
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
		if (invalid == null && character.getNameColour() != null) invalid = PermissionGroupService.validateNameColourHexes(player, character.getNameColour().getHexCodes(), false).orElse(null);
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
		if (colourStyle != null) {
			if (colourStyle.equals("custom")) values.put("colours", text(response, "colours"));
			else if (colourStyle.equals("solid")) values.put("colours", text(response, "colour_0"));
			else if (colourStyle.equals("gradient")) values.put("colours", text(response, "colour_0") + " " + text(response, "colour_1"));
			return;
		}
		if (displayed instanceof SetterStage setter) {
			if ("age".equalsIgnoreCase(setter.getTarget()) && creation.getCharacter().getRace() != null) {
				Float age = response.getFloat("value");
				values.put("value", age != null && Float.isFinite(age) && age == Math.floor(age) ? Integer.toString(age.intValue()) : "");
			} else values.put("value", text(response, "value"));
			if ("description".equalsIgnoreCase(setter.getTarget())) values.put("gender", text(response, "gender"));
		} else if (displayed instanceof AttributesStage attributes) {
			for (String attr : attributes.getAttributes()) values.put("rank_" + attr, text(response, "rank_" + attr));
		} else if (displayed instanceof QuestionStage) values.put("answer", text(response, "answer"));
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
						.canCloseWithEscape(false).pause(false).afterAction(DialogBase.DialogAfterAction.NONE)
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
			callback.accept(response);
		}, CALLBACKS);
	}

	private Component link(Component label, int seen, Consumer<DialogResponseView> callback) {
		var click = (DialogAction.CustomClickAction) action(seen, callback);
		return label.clickEvent(ClickEvent.custom(click.id(), click.additions()));
	}

	private static DialogInput input(String key, String label, String value, int max, boolean multiline) {
		return DialogInput.text(key, WIDTH, Component.text(label), true, value, max,
				multiline ? TextDialogInput.MultilineOptions.create(null, 60) : null);
	}

	private static DialogBody message(String text, NamedTextColor colour) {
		return DialogBody.plainMessage(component(text).colorIfAbsent(colour), WIDTH);
	}

	private static DialogInput choice(String key, String label, String initial, List<String> values, List<String> labels) {
		List<SingleOptionDialogInput.OptionEntry> options = new ArrayList<>();
		for (int i = 0; i < values.size(); i++) options.add(SingleOptionDialogInput.OptionEntry.create(values.get(i), Component.text(labels.get(i)), values.get(i).equals(initial)));
		return DialogInput.singleOption(key, WIDTH, options, Component.text(label), true);
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
