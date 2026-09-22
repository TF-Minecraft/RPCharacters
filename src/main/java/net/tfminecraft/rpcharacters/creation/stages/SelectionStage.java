package net.tfminecraft.rpcharacters.creation.stages;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.creation.CharacterCreation;
import net.tfminecraft.rpcharacters.creation.Stage;
import net.tfminecraft.rpcharacters.loaders.RaceLoader;
import net.tfminecraft.rpcharacters.loaders.TraitLoader;
import net.tfminecraft.rpcharacters.managers.InventoryManager;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.SelectableItem;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.races.Race;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.utils.ProstheticTraitRules;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.rpcharacters.lifecycle.CharacterLifecycle;
import net.tfminecraft.rpcharacters.mmocore.MmoCoreClassGuiHelper;

public class SelectionStage extends Stage{
	private String target;
	private int minSelect;
	private int maxSelect;
	private int selections;
	private int points;
	private int initialPoints;
	private boolean hasPoints;
	private int size;
	private boolean active;
	private String key;
	
	private String filter;
	
	private List<SelectableItem> options = new ArrayList<>();
	private List<SelectableItem> selected = new ArrayList<>();
	private List<Integer> slots = new ArrayList<>();
	
	public SelectionStage(Stage s, ConfigurationSection config) {
		copyBaseFields(s);
		this.key = config.getString("key");
		this.filter = config.getString("filter");
		this.active = false;
		this.target = config.getString("target");
		this.maxSelect = config.getInt("max-select");
		this.minSelect = config.getInt("min-select");
		this.slots = config.getIntegerList("slots");
		this.selections = 0;
		if(config.contains("points")) {
			this.points = config.getInt("points");
			this.initialPoints = this.points;
			this.hasPoints = true;
		} else {
			this.points = 0;
			this.initialPoints = 0;
		}
		if(config.contains("gui-size")) {
			this.size = config.getInt("gui-size");
		} else {
			this.size = 27;
		}
		if(this.target.equalsIgnoreCase("race")) {
			for(Race r : RaceLoader.get()) {
				if(r.getRaceData().getKey().equalsIgnoreCase(key)) {
					options.add(new SelectableItem(r));
				}
			}
		} else if(this.target.equalsIgnoreCase("trait")) {
			for(Trait t : TraitLoader.get()) {
				if(!t.getTraitData().getKey().equalsIgnoreCase(key)) {
					continue;
				}
				if(isPermanentOnlyFilter() && t.getTraitData().hasDuration()) {
					continue;
				}
				options.add(new SelectableItem(t));
			}
		} else if(this.target.equalsIgnoreCase("class")) {
			Map<String, Integer> classSlots = parseClassSlots(config);
			MmoCoreClassGuiHelper.ClassGuiData classData =
				MmoCoreClassGuiHelper.buildClassOptions(this.size, classSlots);
			options.addAll(classData.getOptions());
			this.slots = classData.getSlots();
		}
	}

	private static Map<String, Integer> parseClassSlots(ConfigurationSection config) {
		Map<String, Integer> out = new LinkedHashMap<>();
		ConfigurationSection section = config.getConfigurationSection("class-slots");
		if (section == null) {
			return out;
		}
		for (String rawKey : section.getKeys(false)) {
			if (rawKey == null || rawKey.isBlank()) {
				continue;
			}
			out.put(rawKey.trim().toLowerCase(Locale.ROOT), section.getInt(rawKey));
		}
		return out;
	}
	public SelectionStage(SelectionStage another) {
		copyBaseFields(another);
		this.active = false;
		this.target = another.getTarget();
		this.options = another.getNewOptions();
		this.minSelect = another.getMinSelections();
		this.maxSelect = another.getMaxSelections();
		this.slots = another.getSlots();
		this.selections = 0;
		this.points = another.getPoints();
		this.initialPoints = another.getInitialPoints();
		this.hasPoints = another.hasPoints();
		this.size = another.getSize();
		this.key = another.getKey();
		this.filter = another.getFilter();
	}
	
	private boolean isPermanentOnlyFilter() {
		return filter != null && filter.equalsIgnoreCase("permanent-only");
	}
	
	public String getFilter() {
		return filter;
	}
	
	public List<SelectableItem> getSelection(){
		return selected;
	}
	public void select(SelectableItem i) {
		spendPoints(i.getCost());
		increase();
		selected.add(i);
	}
	public void unSelect(SelectableItem i) {
		addPoints(i.getCost());
		decrease();
		selected.remove(i);
	}
	public String getKey() {
		return key;
	}
	public int getSize() {
		return size;
	}
	public boolean hasPoints() {
		return hasPoints;
	}
	public int getPoints() {
		return points;
	}
	public void spendPoints(int p) {
		this.points = this.points-p;
	}
	public void addPoints(int p) {
		this.points = this.points+p;
	}
	public void increase() {
		selections++;
	}
	public void decrease() {
		selections--;
	}
	public int getSelections() {
		return selections;
	}
	public boolean isActive() {
		return active;
	}
	public List<Integer> getSlots() {
		return slots;
	}
	public int getMinSelections() {
		return minSelect;
	}
	public int getMaxSelections() {
		return maxSelect;
	}
	public String getTarget() {
		return target;
	}
	public List<SelectableItem> getNewOptions() {
		List<SelectableItem> list = new ArrayList<>();
		for(SelectableItem i : options) {
			list.add(new SelectableItem(i));
		}
		return list;
	}
	public List<SelectableItem> getOptions() {
		return options;
	}
	
	public int getInitialPoints() {
		return initialPoints;
	}

	public void hydrateFromCharacter(RPCharacter character) {
		selected.clear();
		selections = 0;
		if (hasPoints) {
			points = initialPoints;
		}
		for (SelectableItem item : options) {
			item.setSelected(false);
		}
		if (character == null) {
			return;
		}
		if (target.equalsIgnoreCase("class") && character.hasMMOClass()) {
			for (SelectableItem item : options) {
				if (item.getId().equalsIgnoreCase(character.getMMOClass())) {
					item.setSelected(true);
					select(item);
					break;
				}
			}
		} else if (target.equalsIgnoreCase("race") && character.getRace() != null) {
			for (SelectableItem item : options) {
				if (item.getId().equalsIgnoreCase(character.getRace().getId())) {
					item.setSelected(true);
					select(item);
					break;
				}
			}
		} else if (target.equalsIgnoreCase("trait") && key != null) {
			for (Trait trait : character.getTraits()) {
				if (!trait.getTraitData().getKey().equalsIgnoreCase(key)) {
					continue;
				}
				for (SelectableItem item : options) {
					if (item.getId().equalsIgnoreCase(trait.getId())) {
						item.setSelected(true);
						select(item);
					}
				}
			}
		}
	}

	public void confirm(Player p, CharacterCreation cc) {
		if(selections < minSelect) {
			RPTexts.send(p, RPTexts.ERROR + "Need at least " + minSelect + ".");
			return;
		}
		if(hasPoints && points < 0) {
			RPTexts.send(p, RPTexts.ERROR + "Cannot afford this trait");
			return;
		}
		active = false;
		p.closeInventory();
		if(cc != null) {
			if (target.equalsIgnoreCase("trait") && key != null) {
				List<Trait> toRemove = new ArrayList<>();
				for (Trait trait : cc.getCharacter().getTraits()) {
					if (trait.getTraitData().getKey().equalsIgnoreCase(key)) {
						toRemove.add(trait);
					}
				}
				for (Trait trait : toRemove) {
					cc.getCharacter().removeTrait(trait);
				}
			}
			for(SelectableItem item : options) {
				if(item.isSelected()) {
					if(item.getType().equalsIgnoreCase("race")) {
						Race r = RaceLoader.getByString(item.getId());
						if (cc.isEditing()) {
							RPCharacter character = cc.getCharacter();
							String oldRaceId = CharacterLifecycle.raceId(character.getRace());
							character.setRace(r);
							CharacterLifecycle.notifyRaceChange(
									p, PlayerManager.get(p).getUniqueId(), character,
									oldRaceId, CharacterLifecycle.raceId(r));
						} else {
							cc.getCharacter().setRace(r);
						}
						RPTexts.send(p, RPTexts.SUCCESS + "Race set to " + r.getName());
					} else if(item.getType().equalsIgnoreCase("trait")) {
						Trait t = TraitLoader.getByString(item.getId());
						cc.getCharacter().addTrait(t);
						RPTexts.send(p, RPTexts.SUCCESS + "Added trait " + t.getName());
					} else if(item.getType().equalsIgnoreCase("class")) {
						if (cc.isEditing()) {
							RPCharacter character = cc.getCharacter();
							String oldClassId = character.getMMOClass();
							character.setMMOClass(item.getId());
							CharacterLifecycle.notifyClassChange(
									p, PlayerManager.get(p).getUniqueId(), character,
									oldClassId, character.getMMOClass());
						} else {
							cc.getCharacter().setMMOClass(item.getId());
						}
						RPTexts.send(p, RPTexts.SUCCESS + "Class set to " + item.getName());
					}
				}
			}
			if (target.equalsIgnoreCase("trait") && key != null
					&& (key.equalsIgnoreCase("injury") || key.equalsIgnoreCase("prosthetic"))) {
				ProstheticTraitRules.stripReplacedInjuries(cc.getCharacter());
			}
			new BukkitRunnable()
			{
				public void run()
				{
					if (cc.isEditingFromSummary()) {
						cc.returnToSummary();
					} else if(autoNext()) {
						cc.runStage();
					} else {
						cc.setCanNext(true);
					}
				}
			}.runTaskLater(RPCharacters.plugin, 2L);
		} else if (target.equalsIgnoreCase("trait") && key != null
				&& (key.equalsIgnoreCase("injury") || key.equalsIgnoreCase("prosthetic"))) {
			PlayerData pd = PlayerManager.get(p);
			if (pd != null && pd.hasActiveCharacter()) {
				RPCharacter character = pd.getActiveCharacter();
				if (ProstheticTraitRules.stripReplacedInjuries(character)) {
					character.update();
					RPCharacters.getPlayerManager().savePlayer(p);
				}
			}
		}
	}

	@Override
	public void update(PlayerData pd) {
		if(!pd.hasActiveCharacter()) return;
		for(Trait t : pd.getActiveCharacter().getTraits()) {
			for(SelectableItem item : options) {
				if(item.getId().equalsIgnoreCase(t.getId())) {
					item.setSelected(true);
					select(item);
				}
			}
		}
	}
	
	public void execute(Player p, CharacterCreation cc) {
		if(cc.isCancelled()) return;
		active = true;
		InventoryManager inv = new InventoryManager();
		inv.selectionView(p, this, cc);
	}
}
