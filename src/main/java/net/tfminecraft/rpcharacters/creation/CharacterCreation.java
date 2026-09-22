package net.tfminecraft.rpcharacters.creation;



import java.time.Instant;

import java.util.ArrayList;

import java.util.List;



import org.bukkit.entity.Player;



import net.Indyuce.mmocore.api.player.profess.PlayerClass;

import net.tfminecraft.rpcharacters.mmocore.ClassService;

import net.tfminecraft.rpcharacters.creation.stages.ClueStage;

import net.tfminecraft.rpcharacters.creation.stages.InfoStage;

import net.tfminecraft.rpcharacters.creation.stages.QuestionStage;

import net.tfminecraft.rpcharacters.creation.stages.AttributesStage;

import net.tfminecraft.rpcharacters.creation.stages.SelectionStage;

import net.tfminecraft.rpcharacters.creation.stages.SetterStage;

import net.tfminecraft.rpcharacters.creation.stages.SummaryStage;

import net.tfminecraft.rpcharacters.RPCharacters;

import net.tfminecraft.rpcharacters.Cache;

import net.tfminecraft.rpcharacters.loaders.StageLoader;

import net.tfminecraft.rpcharacters.managers.CreationManager;

import net.tfminecraft.rpcharacters.managers.InventoryManager;

import net.tfminecraft.rpcharacters.managers.PlayerManager;

import net.tfminecraft.rpcharacters.objects.PlayerData;

import net.tfminecraft.rpcharacters.objects.RPCharacter;

import net.tfminecraft.rpcharacters.objects.attributes.AttributeData;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.objects.trait.TraitEffectResolver;

import net.tfminecraft.rpcharacters.enums.CharacterSessionMode;

import net.tfminecraft.rpcharacters.utils.ProstheticTraitRules;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.rpcharacters.persona.CharacterSlotService;



public class CharacterCreation {

	private RPCharacter character;

	private PlayerClass oldclass;

	private Player p;

	

	private boolean canNext;

	

	private List<Stage> stages = new ArrayList<>();

	

	private int currentStage;

	

	private AttributeData tempData;
	private final java.util.Map<String, AttributeData> attributeStageContributions = new java.util.HashMap<>();



	private boolean cancelled = false;



	private boolean editingFromSummary = false;

	private Stage editStage = null;

	private final String summaryStageId = "creation_summary_stage";

	private CharacterSessionMode sessionMode = CharacterSessionMode.CREATING;

	

	public void setCanNext(boolean b) {

		this.canNext = b;

	}

	

	public boolean canNext() {

		return canNext;

	}



	public CharacterSessionMode getSessionMode() {

		return sessionMode;

	}



	public boolean isEditing() {

		return sessionMode == CharacterSessionMode.EDITING;

	}



	public boolean isPreview() {

		return sessionMode == CharacterSessionMode.PREVIEW;

	}

	

	public Stage getCurrentStage() {

		int i = currentStage - 1;

		if (i < 0) {

			i = 0;

		}

		if (i >= stages.size()) {

			i = stages.size() - 1;

		}

		return stages.get(i);

	}



	public Stage getActiveStage() {

		if (editingFromSummary && editStage != null) {

			return editStage;

		}

		return getCurrentStage();

	}



	public Stage getEditStage() {

		return editStage;

	}



	public boolean isEditingFromSummary() {

		return editingFromSummary;

	}



	public String getSummaryStageId() {

		return summaryStageId;

	}

	

	public CharacterCreation(Player p) {

		this.p = p;

		this.sessionMode = CharacterSessionMode.CREATING;

		oldclass = net.Indyuce.mmocore.api.player.PlayerData.get(p).getProfess();

		stages = StageLoader.getNew();

		character = new RPCharacter(p);

		currentStage = 0;

		tempData = new AttributeData();

		runStage();

	}



	public static CharacterCreation forEdit(Player p, RPCharacter character) {

		CharacterCreation cc = new CharacterCreation();

		cc.p = p;

		cc.character = character;

		cc.sessionMode = CharacterSessionMode.EDITING;

		cc.stages = StageLoader.getNew();

		cc.seedEditPreview();

		return cc;

	}



	public static CharacterCreation forStagePreview(Player p, String stageId) {

		if (p == null || stageId == null || stageId.isBlank()) {

			return null;

		}

		Stage template = StageLoader.getById(stageId.trim());

		if (template == null) {

			RPTexts.send(p, RPTexts.ERROR + "Unknown stage id.");

			return null;

		}

		Stage fresh = Stage.another(template);

		if (fresh == null) {

			RPTexts.send(p, RPTexts.ERROR + "Could not preview that stage.");

			return null;

		}

		CharacterCreation cc = new CharacterCreation();

		cc.p = p;

		cc.sessionMode = CharacterSessionMode.PREVIEW;

		cc.character = new RPCharacter(p);

		cc.stages = new ArrayList<>();

		cc.stages.add(fresh);

		cc.currentStage = 0;

		cc.tempData = new AttributeData();

		cc.editingFromSummary = true;

		cc.editStage = fresh;

		CreationManager.activeCreators.put(p, cc);

		if (fresh instanceof SelectionStage selection) {

			selection.hydrateFromCharacter(cc.character);

			selection.execute(p, cc);

		} else if (fresh instanceof AttributesStage attributes) {

			attributes.hydrateFromCharacter(cc.character);

			attributes.execute(p, cc);

		} else if (fresh instanceof SetterStage setter) {

			setter.execute(p, cc);

		} else if (fresh instanceof InfoStage info) {

			info.execute(p, cc);

			RPTexts.send(p, RPTexts.COMMAND + "/rpcharacter next");

		} else if (fresh instanceof ClueStage clue) {

			clue.execute(p, cc);

		} else if (fresh instanceof QuestionStage question) {

			question.execute(p, cc);

		} else if (fresh instanceof SummaryStage summary) {

			summary.execute(p, cc);

		} else {

			CreationManager.activeCreators.remove(p);

			RPTexts.send(p, RPTexts.ERROR + "That stage type cannot be previewed.");

			return null;

		}

		RPTexts.send(p, RPTexts.MUTED + "Stage preview: " + RPTexts.WARN + stageId.trim()

				+ RPTexts.MUTED + ". Confirm or " + RPTexts.COMMAND + "/rpcharacter cancel"

				+ RPTexts.MUTED + " to exit.");

		return cc;

	}



	private CharacterCreation() {}

	

	public AttributeData getTempData() {

		return tempData;

	}

	private void seedEditPreview() {
		if (character != null && character.getRace() != null) {
			character.update();
		}
		tempData = new AttributeData(character != null ? character.getAttributeData() : null);
		if (stages == null) {
			return;
		}
		for (Stage stage : stages) {
			if (!(stage instanceof AttributesStage attributes)) {
				continue;
			}
			String key = attributes.getKey();
			if (key == null || key.isBlank()) {
				continue;
			}
			AttributeData slice = new AttributeData();
			slice.clearAll();
			if (character != null && character.getTraits() != null) {
				for (Trait trait : character.getTraits()) {
					if (trait.getTraitData() == null || trait.getTraitData().getKey() == null) {
						continue;
					}
					if (!trait.getTraitData().getKey().equalsIgnoreCase(key)) {
						continue;
					}
					slice.mergeFrom(TraitEffectResolver.resolveAttributeData(character, trait));
				}
			}
			attributeStageContributions.put(key.toLowerCase(java.util.Locale.ROOT), slice);
		}
	}

	public void setAttributeStageContribution(String key, AttributeData data) {
		// Replace only bonuses previously included in this session's preview totals.
		AttributeData previous = attributeStageContributions.put(key.toLowerCase(java.util.Locale.ROOT), data);
		if (previous != null) {
			tempData.mergeFromReverse(previous);
		}
		tempData.mergeFrom(data);
	}

	

	public RPCharacter getCharacter() {

		return character;

	}



	public void openSummary() {

		if (isPreview()) {

			endPreview();

			return;

		}

		SummaryStage summary = findSummaryStage();

		if (summary == null) {

			if (isEditing()) {

				closeEditSession();

			} else {

				finish();

			}

			return;

		}

		InventoryManager inv = new InventoryManager();

		inv.creationSummaryView(p, this, summary);

	}



	private SummaryStage findSummaryStage() {

		for (Stage stage : stages) {

			if (stage instanceof SummaryStage summary) {

				return summary;

			}

		}

		Stage template = StageLoader.getById(summaryStageId);

		if (template instanceof SummaryStage summary) {

			return summary;

		}

		return SummaryEditSupport.getSummaryStage();

	}



	public void jumpToStageForEdit(String stageId) {

		if (stageId == null || stageId.isBlank()) {

			return;

		}

		Stage template = StageLoader.getById(stageId);

		if (template == null) {

			RPTexts.send(p, RPTexts.ERROR + "Could not open editor for that choice.");

			return;

		}

		if (!StageEditLock.canEdit(p, template, character)) {

			RPTexts.send(p, RPTexts.ERROR + "That choice is locked and can no longer be edited.");

			return;

		}

		Stage fresh = Stage.another(template);

		if (fresh == null) {

			RPTexts.send(p, RPTexts.ERROR + "Could not open editor for that choice.");

			return;

		}

		editingFromSummary = true;

		editStage = fresh;

		if (fresh instanceof SelectionStage selection) {

			selection.hydrateFromCharacter(character);

			selection.execute(p, this);

		} else if (fresh instanceof AttributesStage attributes) {

			attributes.hydrateFromCharacter(character);

			attributes.execute(p, this);

		} else if (fresh instanceof SetterStage setter) {

			setter.execute(p, this);

		} else if (fresh instanceof InfoStage info) {

			info.execute(p, this);

			RPTexts.send(p, RPTexts.COMMAND + "/rpcharacter next");

		} else {

			editingFromSummary = false;

			editStage = null;

			RPTexts.send(p, RPTexts.ERROR + "That choice cannot be edited from the summary.");

		}

	}



	public void returnToSummary() {

		if (isPreview()) {

			endPreview();

			return;

		}

		editingFromSummary = false;

		editStage = null;

		if (isEditing()) {

			persistEdits();

		}

		openSummary();

	}



	public void persistEdits() {

		ProstheticTraitRules.stripReplacedInjuries(character);

		character.update();

		RPCharacters.getPlayerManager().savePlayer(p);

		RPCharacters.getPlayerManager().reevaluateFreeze(p);

	}

	

	public void runStage() {

		if (isPreview()) {

			endPreview();

			return;

		}

		canNext = false;

		if(cancelled) return;

		if(currentStage >= stages.size()) {

			openSummary();

			return;

		}

		Stage s = stages.get(currentStage);

		if(!s.shouldRepeat()) {

			PlayerData pd = PlayerManager.get(p);

			if(pd.hasCompletedStage(s)) {

				currentStage++;

				runStage();

				return;

			} else {

				pd.addCompletedStage(s);

			}

		}

		if(s.hasDependency()) {

			if(!s.getDependency().check(character)) {

				currentStage++;

				runStage();

				return;

			}

		}

		PlayerData agePd = PlayerManager.get(p);

		if (!s.passesAccountAgeGate(agePd)) {

			currentStage++;

			runStage();

			return;

		}

		if (!s.runsInGame()) {

			currentStage++;

			runStage();

			return;

		}

		if(s instanceof InfoStage) {

			InfoStage info = (InfoStage) s;

			info.execute(p, this);

		} else if(s instanceof QuestionStage) {

			QuestionStage q = (QuestionStage) s;

			q.execute(p, this);

		} else if(s instanceof SetterStage) {

			SetterStage ss = (SetterStage) s;

			ss.execute(p, this);

		} else if(s instanceof SelectionStage) {

			SelectionStage ss = (SelectionStage) s;

			ss.execute(p, this);

		} else if(s instanceof AttributesStage) {

			AttributesStage attrStage = (AttributesStage) s;

			attrStage.execute(p, this);

		} else if(s instanceof ClueStage) {

			ClueStage cs = (ClueStage) s;

			cs.execute(p, this);

		} else if(s instanceof SummaryStage summary) {

			summary.execute(p, this);

		}

		currentStage++;

	}

	

	public void answerQuestion(String a) {

		Stage s = getActiveStage();

		if(s instanceof QuestionStage) {

			QuestionStage q = (QuestionStage) s;

			q.checkAnswer(a, p, this);

		}

	}

	public void finish() {

		if (isPreview()) {

			endPreview();

			return;

		}

		if (isEditing()) {

			closeEditSession();

			return;

		}

		if (!character.hasEnoughClues()) {

			RPTexts.send(p, RPTexts.ERROR + "Missing clues.");

			return;

		}

		if (character.getName() == null || character.getName().isBlank()) {

			RPTexts.send(p, RPTexts.ERROR + "Name not set.");

			return;

		}

		if (!character.hasMMOClass()) {

			RPTexts.send(p, RPTexts.ERROR + "Class not set.");

			return;

		}

		if (character.getRace() == null) {

			RPTexts.send(p, RPTexts.ERROR + "Race not set.");

			return;

		}

		if (character.getBirthday() == null || character.getBirthday().isBlank()) {

			RPTexts.send(p, RPTexts.ERROR + "Age not set.");

			return;

		}

		String description = character.getPersonaDescription();

		if (description == null || description.isBlank()) {

			RPTexts.send(p, RPTexts.ERROR + "Description not set.");

			return;

		}

		PlayerData pd = PlayerManager.get(p);

		if (!CharacterSlotService.hasFreeSlot(p, pd)) {

			RPTexts.send(p, RPTexts.ERROR + "No free character slot. A web create may have taken it.");

			cancel();

			p.closeInventory();

			net.tfminecraft.rpcharacters.ingest.RosterSyncService.pushRosterForPlayer(p);

			return;

		}

		if (character.getCreatedAtEpochSeconds() <= 0) {

			character.setCreatedAtEpochSeconds((int) Instant.now().getEpochSecond());

		}

		ProstheticTraitRules.stripReplacedInjuries(character);

		character.update();

		if (Cache.devCharacters) {

			character.setDev(true);

		}

		pd.addCharacter(character);

		net.tfminecraft.rpcharacters.lifecycle.CharacterLifecycle.fireCreated(p, pd.getUniqueId(), character);

		pd.setActiveCharacter(character);

		net.tfminecraft.rpcharacters.kit.KitService.onCharacterCreated(p, pd, character);

		RPCharacters.getPlayerManager().reevaluateFreeze(p);

		RPCharacters.getPlayerManager().savePlayer(p);

		CreationManager.activeCreators.remove(p);

		net.tfminecraft.rpcharacters.ingest.RosterSyncService.pushRosterForPlayer(p);

		net.tfminecraft.rpcharacters.wardrobe.WardrobeService.refreshActiveAsync(p);

		p.closeInventory();

		RPTexts.title(p, RPTexts.SUCCESS + "Finished!", RPTexts.WARN + "Character " + RPTexts.MUTED + character.getName() + RPTexts.WARN + " created!", 5, 50, 5);

		RPTexts.send(p, RPTexts.MUTED + "Edit later with " + RPTexts.COMMAND + "/rpcharacter edit" + RPTexts.MUTED + ".");

	}



	public void closeEditSession() {

		persistEdits();

		CreationManager.activeCreators.remove(p);

		p.closeInventory();

	}



	public boolean isCancelled() {

		return cancelled;

	}



	public void endPreview() {

		if (!isPreview()) {

			return;

		}

		if (CreationManager.activeCreators.get(p) != this) {

			return;

		}

		editingFromSummary = false;

		editStage = null;

		CreationManager.activeCreators.remove(p);

		p.closeInventory();

		RPTexts.send(p, RPTexts.SUCCESS + "Stage preview ended.");

	}



	public void goBack() {

		if (isPreview()) {

			endPreview();

			return;

		}

		if (editingFromSummary) {

			returnToSummary();

			return;

		}

		if (isEditing()) {

			openSummary();

			return;

		}

		int activeIdx = getActiveStageIndex();

		int startIdx = activeIdx - 1;

		int targetIdx = findPreviousInGameStageIndex(startIdx);

		if (targetIdx < 0) {

			RPTexts.send(p, RPTexts.ERROR + "There is no previous stage.");

			return;

		}

		reopenStageAt(targetIdx);

	}



	private int getActiveStageIndex() {

		int idx = currentStage - 1;

		if (idx < 0) {

			idx = 0;

		}

		if (idx >= stages.size()) {

			idx = stages.size() - 1;

		}

		return idx;

	}



	private int findPreviousInGameStageIndex(int fromIndex) {

		for (int i = fromIndex; i >= 0; i--) {

			Stage stage = stages.get(i);

			if (stage instanceof SummaryStage) {

				continue;

			}

			if (stage.runsInGame()) {

				return i;

			}

		}

		return -1;

	}



	private void reopenStageAt(int index) {

		if (index < 0 || index >= stages.size()) {

			return;

		}

		canNext = false;

		p.closeInventory();

		currentStage = index + 1;

		Stage s = stages.get(index);

		if (s instanceof InfoStage info) {

			info.execute(p, this);

		} else if (s instanceof QuestionStage question) {

			question.execute(p, this);

		} else if (s instanceof SetterStage setter) {

			setter.execute(p, this);

		} else if (s instanceof SelectionStage selection) {

			selection.hydrateFromCharacter(character);

			selection.execute(p, this);

		} else if (s instanceof AttributesStage attributes) {

			attributes.hydrateFromCharacter(character);

			attributes.execute(p, this);

		} else if (s instanceof ClueStage clue) {

			clue.execute(p, this);

		} else if (s instanceof SummaryStage summary) {

			summary.execute(p, this);

		}

	}



	public void cancel() {

		if (isPreview()) {

			endPreview();

			return;

		}

		if (isEditing()) {

			CreationManager.activeCreators.remove(p);

			p.closeInventory();

			return;

		}

		CreationManager.activeCreators.remove(p);

		Stage s = getCurrentStage();

		cancelled = true;

		if(oldclass != null) {

			ClassService.applyClass(p, oldclass.getId());

			RPTexts.send(p, RPTexts.ERROR + "Your class was set back to " + oldclass.getName());

		}

		RPTexts.title(p, RPTexts.ERROR + "Cancelled!", RPTexts.WARN + "Character creation cancelled", 5, 50, 5);

		s.cancel();

	}

}


