package net.tfminecraft.rpcharacters.mmocore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import io.lumine.mythic.lib.player.resource.ResourceUpdateReason;

import net.Indyuce.mmocore.MMOCore;
import net.Indyuce.mmocore.api.event.PlayerLevelChangeEvent;
import net.Indyuce.mmocore.api.player.PlayerData;
import net.Indyuce.mmocore.api.player.attribute.PlayerAttribute;
import net.Indyuce.mmocore.api.player.profess.PlayerClass;
import net.Indyuce.mmocore.api.player.profess.SavedClassInformation;
import net.Indyuce.mmocore.experience.Profession;
import net.Indyuce.mmocore.player.ClassDataContainer;
import net.Indyuce.mmocore.player.DefaultPlayerData;
import net.Indyuce.mmocore.skill.ClassSkill;
import net.Indyuce.mmocore.skilltree.SkillTreeNode;
import net.Indyuce.mmocore.skilltree.tree.SkillTree;

/**
 * One character's MMOCore progress: class, level, points, skills, bindings, skill trees, reward claims,
 * unlocked items, saved class slots, professions and waypoints. Attribute bases are left out because
 * RPCharacters rebuilds them from the character; guild, friends and quests stay with MMOCore.
 * Maps are stored as lists of entries, so ids containing dots survive YAML. Main thread only.
 */
public final class MmoProgressCodec {
	public static final int FORMAT = 1;
	private static final PlayerLevelChangeEvent.Reason QUIET = PlayerLevelChangeEvent.Reason.CHOOSE_PROFILE;

	private MmoProgressCodec() {}

	public static YamlConfiguration capture(PlayerData mmo) {
		Map<String, Object> root = container(mmo, false);
		root.put("format", FORMAT);
		root.put("class", mmo.getProfess().getId());
		root.put("class-points", mmo.getClassPoints());
		root.put("claims", counts(mmo.getItemClaims()));
		root.put("waypoints", new ArrayList<>(new TreeSet<>(mmo.getWaypoints())));
		List<Map<String, Object>> professions = new ArrayList<>();
		for (Profession profession : MMOCore.plugin.professionManager.getAll()) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("id", profession.getId());
			entry.put("level", mmo.getCollectionSkills().getLevel(profession));
			entry.put("experience", mmo.getCollectionSkills().getExperience(profession));
			professions.add(entry);
		}
		root.put("professions", professions);
		List<Map<String, Object>> slots = new ArrayList<>();
		for (String classId : new TreeSet<>(mmo.getSavedClasses())) {
			Map<String, Object> slot = container(mmo.getClassInfo(classId), true);
			slot.put("class", classId);
			slots.add(slot);
		}
		root.put("class-info", slots);
		return yaml(root);
	}

	/** A new character's progress: MMOCore's default data on its default class. */
	public static YamlConfiguration fresh() {
		DefaultPlayerData defaults = MMOCore.plugin.playerDataManager.getDefaultData();
		Map<String, Object> root = container(defaults, false);
		root.put("format", FORMAT);
		root.put("class", MMOCore.plugin.classManager.getDefaultClass().getId());
		root.put("class-points", defaults.getClassPoints());
		root.put("claims", List.of());
		root.put("waypoints", List.of());
		root.put("professions", List.of());
		root.put("class-info", List.of());
		return yaml(root);
	}

	/** Throws {@link IllegalArgumentException} when the section is not a complete progress copy. */
	public static void validate(ConfigurationSection section) {
		decode(section);
	}

	/** A stored profession's level and the experience gained towards the next one. */
	public record ProfessionLevel(int level, double experience) {
		/** A higher level, or the same level with more experience; anything is above a missing profession. */
		public boolean isAbove(ProfessionLevel other) {
			return other == null || level > other.level || level == other.level && experience > other.experience;
		}
	}

	/** The stored profession levels by MMOCore profession id. Professions never saved are absent. */
	public static Map<String, ProfessionLevel> professions(ConfigurationSection section) {
		Map<String, ProfessionLevel> out = new LinkedHashMap<>();
		decode(section).professions.forEach((id, stored) -> out.put(id, new ProfessionLevel((int) stored[0], stored[1])));
		return out;
	}

	/** A copy of the stored progress with these professions instead of its own; the section itself is left as it was. */
	public static YamlConfiguration withProfessions(ConfigurationSection section, Map<String, ProfessionLevel> professions) {
		decode(section);
		YamlConfiguration copy = new YamlConfiguration();
		section.getValues(false).forEach(copy::set);
		List<Map<String, Object>> entries = new ArrayList<>();
		for (Map.Entry<String, ProfessionLevel> profession : new TreeMap<>(professions).entrySet()) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("id", profession.getKey());
			entry.put("level", profession.getValue().level());
			entry.put("experience", profession.getValue().experience());
			entries.add(entry);
		}
		copy.set("professions", entries);
		decode(copy);
		return copy;
	}

	/** Check stored class definitions without applying progress or claiming rewards. */
	public static void validateClasses(ConfigurationSection section) {
		validateClasses(decode(section));
	}

	private static PlayerClass validateClasses(State state) {
		PlayerClass target = MMOCore.plugin.classManager.get(state.classId);
		if (target == null) {
			throw new IllegalArgumentException("Unknown stored MMOCore class " + state.classId);
		}
		for (String classId : state.slots.keySet()) {
			if (MMOCore.plugin.classManager.get(classId) == null) {
				throw new IllegalArgumentException("Unknown stored MMOCore class " + classId);
			}
		}
		return target;
	}

	/** Freeze current resource values before class changes or checkpoints can replace the saved copy. */
	public static Resources resources(ConfigurationSection section) {
		return new Resources(decode(section).current);
	}

	/** Immutable values reapplied after the character's attributes and equipment have restored its caps. */
	public static final class Resources {
		private final double mana, stamina, stellium;

		private Resources(Stored current) {
			mana = current.mana;
			stamina = current.stamina;
			stellium = current.stellium;
		}

		public void apply(PlayerData mmo) {
			// Direct body restores bypass equipment events; rebuild accessory modifiers before resource caps.
			net.Indyuce.mmoitems.api.player.PlayerData.get(mmo.getMMOPlayerData()).resolveInventory();
			mmo.getStats().updateStats();
			mmo.setMana(mana, ResourceUpdateReason.CHOOSE_CLASS);
			mmo.setStamina(stamina, ResourceUpdateReason.CHOOSE_CLASS);
			mmo.setStellium(stellium, ResourceUpdateReason.CHOOSE_CLASS);
		}
	}

	/**
	 * Replaces the player's MMOCore progress with the stored copy. Everything is decoded before MMOCore is
	 * touched; the outgoing class, profession, attribute and skill-tree rewards lose their triggers first,
	 * so nothing applied for one character stays on the next.
	 */
	public static void apply(PlayerData mmo, ConfigurationSection section) {
		State state = decode(section);
		PlayerClass target = validateClasses(state);
		mmo.getProfess().resetAdvancement(mmo, false);
		for (Profession profession : MMOCore.plugin.professionManager.getAll()) {
			profession.resetAdvancement(mmo, false);
		}
		for (PlayerAttribute attribute : MMOCore.plugin.attributeManager.getAll()) {
			attribute.resetAdvancement(mmo, false);
		}
		mmo.resetSkillTrees();
		mmo.resetSkills();
		mmo.resetTimesClaimed();
		for (String classId : new ArrayList<>(mmo.getSavedClasses())) {
			mmo.unloadClassInfo(classId);
		}
		mmo.setClass(target);
		Stored current = state.current;
		mmo.setLevel(current.level, QUIET);
		mmo.setExperience(current.experience);
		mmo.setClassPoints(state.classPoints);
		mmo.setSkillPoints(current.skillPoints);
		mmo.setSkillReallocationPoints(current.skillReallocationPoints);
		mmo.setSkillTreeReallocationPoints(current.skillTreeReallocationPoints);
		mmo.setAttributePoints(current.attributePoints);
		mmo.setAttributeReallocationPoints(current.attributeReallocationPoints);
		current.skills.forEach(mmo::setSkillLevel);
		state.claims.forEach(mmo::setClaims);
		current.skillTreePoints.forEach(mmo::setSkillTreePoints);
		for (SkillTreeNode node : MMOCore.plugin.skillTreeManager.getAllNodes()) {
			Integer level = current.nodeLevels.get(node.getFullId());
			if (level != null) {
				mmo.setNodeLevel(node, level);
			}
		}
		for (SkillTree tree : target.getSkillTrees()) {
			tree.resolveStates(mmo);
		}
		mmo.setUnlockedItems(new HashSet<>(current.unlockedItems));
		for (Map.Entry<Integer, String> bound : current.boundSkills.entrySet()) {
			ClassSkill skill = target.getSkill(bound.getValue());
			if (skill != null && target.getSkillSlot(bound.getKey()) != null) {
				mmo.bindSkill(bound.getKey(), skill);
			}
		}
		mmo.getWaypoints().clear();
		mmo.getWaypoints().addAll(state.waypoints);
		for (Profession profession : MMOCore.plugin.professionManager.getAll()) {
			double[] stored = state.professions.get(profession.getId());
			mmo.getCollectionSkills().setLevel(profession, stored == null ? 1 : (int) stored[0], QUIET);
			mmo.getCollectionSkills().setExperience(profession, stored == null ? 0.0 : stored[1]);
		}
		for (Map.Entry<String, Stored> slot : state.slots.entrySet()) {
			PlayerClass slotClass = MMOCore.plugin.classManager.get(slot.getKey());
			mmo.applyClassInfo(slotClass, new SavedClassInformation(slot.getValue()));
		}
		mmo.applyTemporaryTriggers();
		new Resources(current).apply(mmo);
	}

	private static Map<String, Object> container(ClassDataContainer data, boolean slot) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("level", data.getLevel());
		out.put("experience", data.getExperience());
		out.put("skill-points", data.getSkillPoints());
		out.put("attribute-points", data.getAttributePoints());
		out.put("attribute-realloc-points", data.getAttributeReallocationPoints());
		out.put("skill-reallocation-points", data.getSkillReallocationPoints());
		out.put("skill-tree-reallocation-points", data.getSkillTreeReallocationPoints());
		out.put("skills", counts(data.mapSkillLevels()));
		out.put("skill-tree-points", counts(data.mapSkillTreePoints()));
		out.put("skill-tree-levels", counts(data.getNodeLevels()));
		List<Map<String, Object>> bound = new ArrayList<>();
		for (Map.Entry<Integer, String> entry : new TreeMap<>(data.mapBoundSkills()).entrySet()) {
			Map<String, Object> binding = new LinkedHashMap<>();
			binding.put("slot", entry.getKey());
			binding.put("skill", entry.getValue());
			bound.add(binding);
		}
		out.put("bound-skills", bound);
		out.put("unlocked-items", new ArrayList<>(new TreeSet<>(data.getUnlockedItems())));
		out.put("mana", data.getMana());
		out.put("stamina", data.getStamina());
		out.put("stellium", data.getStellium());
		if (slot) {
			out.put("health", data.getLastHealth());
			out.put("attributes", counts(data.mapAttributeLevels()));
			out.put("node-claims", counts(data.getNodeTimesClaimed()));
		}
		return out;
	}

	private static List<Map<String, Object>> counts(Map<String, Integer> values) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : new TreeMap<>(values).entrySet()) {
			if (entry.getValue() == null) {
				continue;
			}
			Map<String, Object> count = new LinkedHashMap<>();
			count.put("id", entry.getKey());
			count.put("value", entry.getValue());
			out.add(count);
		}
		return out;
	}

	private static YamlConfiguration yaml(Map<String, Object> root) {
		YamlConfiguration yaml = new YamlConfiguration();
		root.forEach(yaml::set);
		return yaml;
	}

	private static State decode(ConfigurationSection section) {
		if (section == null) {
			throw new IllegalArgumentException("Progress has no MMOCore section");
		}
		Map<String, Object> root = section.getValues(false);
		if (integer(root, "format") != FORMAT) {
			throw new IllegalArgumentException("Unsupported MMOCore progress format " + root.get("format"));
		}
		State state = new State();
		state.classId = text(root, "class");
		state.classPoints = integer(root, "class-points");
		state.current = stored(root, false);
		state.claims = readCounts(root, "claims");
		for (Object waypoint : list(root, "waypoints")) {
			state.waypoints.add(String.valueOf(waypoint));
		}
		for (Map<?, ?> entry : entries(root, "professions")) {
			state.professions.put(text(entry, "id"),
					new double[] {integer(entry, "level"), number(entry, "experience").doubleValue()});
		}
		for (Map<?, ?> entry : entries(root, "class-info")) {
			state.slots.put(text(entry, "class"), stored(entry, true));
		}
		return state;
	}

	private static Stored stored(Map<?, ?> map, boolean slot) {
		Stored stored = new Stored();
		stored.level = integer(map, "level");
		if (stored.level < 1) {
			throw new IllegalArgumentException("MMOCore progress level must be positive");
		}
		stored.experience = number(map, "experience").doubleValue();
		stored.skillPoints = integer(map, "skill-points");
		stored.attributePoints = integer(map, "attribute-points");
		stored.attributeReallocationPoints = integer(map, "attribute-realloc-points");
		stored.skillReallocationPoints = integer(map, "skill-reallocation-points");
		stored.skillTreeReallocationPoints = integer(map, "skill-tree-reallocation-points");
		stored.skills = readCounts(map, "skills");
		stored.skillTreePoints = readCounts(map, "skill-tree-points");
		stored.nodeLevels = readCounts(map, "skill-tree-levels");
		for (Map<?, ?> binding : entries(map, "bound-skills")) {
			stored.boundSkills.put(integer(binding, "slot"), text(binding, "skill"));
		}
		for (Object item : list(map, "unlocked-items")) {
			stored.unlockedItems.add(String.valueOf(item));
		}
		stored.mana = number(map, "mana").doubleValue();
		stored.stamina = number(map, "stamina").doubleValue();
		stored.stellium = number(map, "stellium").doubleValue();
		if (slot) {
			stored.health = number(map, "health").doubleValue();
			stored.attributes = readCounts(map, "attributes");
			stored.nodeClaims = readCounts(map, "node-claims");
		}
		return stored;
	}

	private static Map<String, Integer> readCounts(Map<?, ?> map, String key) {
		Map<String, Integer> out = new LinkedHashMap<>();
		for (Map<?, ?> entry : entries(map, key)) {
			if (out.putIfAbsent(text(entry, "id"), integer(entry, "value")) != null) {
				throw new IllegalArgumentException("Duplicate MMOCore progress " + key + " entry");
			}
		}
		return out;
	}

	private static List<Map<?, ?>> entries(Map<?, ?> map, String key) {
		List<Map<?, ?>> out = new ArrayList<>();
		for (Object entry : list(map, key)) {
			if (!(entry instanceof Map<?, ?> values)) {
				throw new IllegalArgumentException("MMOCore progress " + key + " has a malformed entry");
			}
			out.add(values);
		}
		return out;
	}

	private static List<?> list(Map<?, ?> map, String key) {
		if (!(map.get(key) instanceof List<?> values)) {
			throw new IllegalArgumentException("MMOCore progress is missing " + key);
		}
		return values;
	}

	private static Number number(Map<?, ?> map, String key) {
		if (!(map.get(key) instanceof Number value) || !Double.isFinite(value.doubleValue())
				|| value.doubleValue() < 0) {
			throw new IllegalArgumentException("MMOCore progress is missing " + key);
		}
		return value;
	}

	private static int integer(Map<?, ?> map, String key) {
		Number value = number(map, key);
		double decimal = value.doubleValue();
		if (decimal > Integer.MAX_VALUE || decimal != Math.rint(decimal)) {
			throw new IllegalArgumentException("MMOCore progress has an invalid " + key);
		}
		return value.intValue();
	}

	private static String text(Map<?, ?> map, String key) {
		Object value = map.get(key);
		if (!(value instanceof String text) || text.isBlank()) {
			throw new IllegalArgumentException("MMOCore progress is missing " + key);
		}
		return text;
	}

	private static final class State {
		private String classId;
		private int classPoints;
		private Stored current;
		private Map<String, Integer> claims;
		private final Set<String> waypoints = new HashSet<>();
		private final Map<String, double[]> professions = new LinkedHashMap<>();
		private final Map<String, Stored> slots = new LinkedHashMap<>();
	}

	/** Decoded class data; MMOCore copies it into a {@link SavedClassInformation} for class slots. */
	private static final class Stored implements ClassDataContainer {
		private int level;
		private double experience;
		private int skillPoints;
		private int attributePoints;
		private int attributeReallocationPoints;
		private int skillReallocationPoints;
		private int skillTreeReallocationPoints;
		private double health;
		private double mana;
		private double stamina;
		private double stellium;
		private Map<String, Integer> attributes = new LinkedHashMap<>();
		private Map<String, Integer> skills;
		private Map<String, Integer> skillTreePoints;
		private Map<String, Integer> nodeLevels;
		private Map<String, Integer> nodeClaims = new LinkedHashMap<>();
		private final Map<Integer, String> boundSkills = new LinkedHashMap<>();
		private final Set<String> unlockedItems = new HashSet<>();

		@Override public int getLevel() { return level; }
		@Override public double getExperience() { return experience; }
		@Override public int getSkillPoints() { return skillPoints; }
		@Override public int getAttributePoints() { return attributePoints; }
		@Override public int getAttributeReallocationPoints() { return attributeReallocationPoints; }
		@Override public int getSkillReallocationPoints() { return skillReallocationPoints; }
		@Override public int getSkillTreeReallocationPoints() { return skillTreeReallocationPoints; }
		@Override public double getLastHealth() { return health; }
		@Override public double getMana() { return mana; }
		@Override public double getStamina() { return stamina; }
		@Override public double getStellium() { return stellium; }
		@Override public Map<String, Integer> mapAttributeLevels() { return attributes; }
		@Override public Map<String, Integer> mapSkillLevels() { return skills; }
		@Override public Map<String, Integer> mapSkillTreePoints() { return skillTreePoints; }
		@Override public Map<Integer, String> mapBoundSkills() { return boundSkills; }
		@Override public Map<String, Integer> getNodeLevels() { return nodeLevels; }
		@Override public Map<String, Integer> getNodeTimesClaimed() { return nodeClaims; }
		@Override public Set<String> getUnlockedItems() { return unlockedItems; }
	}
}
