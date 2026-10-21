package net.tfminecraft.rpcharacters.progress;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.rpcharacters.mmocore.MmoProgressCodec;

/**
 * One character's progress: its point pools, its own class level (MMOCore caps the level at the class
 * being played) and its MMOCore copy. The MMOCore copy is replaced, never edited, so clones may share it.
 */
public final class CharacterProgress implements ProgressPools {
	public static final String BASELINE = "baseline";
	public static final String FRESH = "fresh";

	private final UUID ownerId;
	private final String characterId;
	private final String source;
	private long revision;
	private int skillPointsTotal;
	private int rewardSkillPoints;
	private int attributePointsTotal;
	private int classLevel = 1;
	private double classExperience;
	private double carriedExperience;
	private int itemSkillPoints;
	private int itemAttributePoints;
	private final Map<String, Integer> professionPoints = new TreeMap<>();
	private YamlConfiguration mmo;
	private String savedContent;

	CharacterProgress(UUID ownerId, String characterId, String source, YamlConfiguration mmo) {
		this.ownerId = ownerId;
		this.characterId = characterId;
		this.source = source;
		this.mmo = mmo;
	}

	public UUID getOwnerId() {
		return ownerId;
	}

	/** Null for the account baseline. */
	public String getCharacterId() {
		return characterId;
	}

	/** {@link #BASELINE} when copied from the account at cutover, {@link #FRESH} for later characters. */
	public String getSource() {
		return source;
	}

	/** The saved revision this copy was read as or last written as; 0 before the first write. */
	public long getRevision() {
		return revision;
	}

	void setRevision(long revision) {
		this.revision = revision;
	}

	/** The saved content of {@link #getRevision()}, so an unchanged checkpoint can skip its write. */
	String savedContent() {
		return savedContent;
	}

	void setSavedContent(String savedContent) {
		this.savedContent = savedContent;
	}

	YamlConfiguration mmo() {
		return mmo;
	}

	void setMmo(YamlConfiguration mmo) {
		this.mmo = mmo;
	}

	@Override
	public String holder() {
		return "character";
	}

	@Override
	public int getSkillPointsTotal() {
		return skillPointsTotal;
	}

	@Override
	public void setSkillPointsTotal(int total) {
		skillPointsTotal = Math.max(0, total);
	}

	@Override
	public void addSkillPoints(int amount) {
		if (amount > 0) {
			setSkillPointsTotal((int) Math.min(Integer.MAX_VALUE, (long) skillPointsTotal + amount));
		}
	}

	@Override
	public int getRewardSkillPoints() {
		return rewardSkillPoints;
	}

	@Override
	public void setRewardSkillPoints(int credited) {
		rewardSkillPoints = Math.max(0, credited);
	}

	@Override
	public int getAttributePointsTotal() {
		return attributePointsTotal;
	}

	@Override
	public void setAttributePointsTotal(int total) {
		attributePointsTotal = Math.max(0, total);
	}

	@Override
	public void addAttributePoints(int amount) {
		if (amount > 0) {
			setAttributePointsTotal((int) Math.min(Integer.MAX_VALUE, (long) attributePointsTotal + amount));
		}
	}

	@Override
	public int getProfessionPoints(String professionId) {
		return professionId == null ? 0 : professionPoints.getOrDefault(professionId.toLowerCase(Locale.ROOT), 0);
	}

	public void setProfessionPoints(String professionId, int amount) {
		if (professionId != null) {
			professionPoints.put(professionId.toLowerCase(Locale.ROOT), Math.max(0, amount));
		}
	}

	@Override
	public void addProfessionPoints(String professionId, int amount) {
		if (amount > 0) {
			setProfessionPoints(professionId,
					(int) Math.min(Integer.MAX_VALUE, (long) getProfessionPoints(professionId) + amount));
		}
	}

	public void clearProfessionPoints() {
		professionPoints.clear();
	}

	public Map<String, Integer> getProfessionPointsMap() {
		return Collections.unmodifiableMap(professionPoints);
	}

	public int getClassLevel() {
		return classLevel;
	}

	public double getClassExperience() {
		return classExperience;
	}

	public void setClassProgress(int level, double experience) {
		if (!Double.isFinite(experience)) {
			throw new IllegalArgumentException("Class experience must be finite");
		}
		classLevel = Math.max(1, level);
		classExperience = Math.max(0.0, experience);
	}

	/** Class EXP carried over from the account's other characters that the class could not hold yet. */
	public double getCarriedExperience() {
		return carriedExperience;
	}

	public void setCarriedExperience(double experience) {
		if (!Double.isFinite(experience)) {
			throw new IllegalArgumentException("Carried experience must be finite");
		}
		carriedExperience = Math.max(0.0, experience);
	}

	/** How many of the account's item skill points this character's skill total already holds. */
	public int getItemSkillPoints() {
		return itemSkillPoints;
	}

	public void setItemSkillPoints(int points) {
		itemSkillPoints = Math.max(0, points);
	}

	/** How many of the account's item attribute points this character's attribute total already holds. */
	public int getItemAttributePoints() {
		return itemAttributePoints;
	}

	public void setItemAttributePoints(int points) {
		itemAttributePoints = Math.max(0, points);
	}

	/** An unsaved copy of these pools and MMOCore data for another character. */
	CharacterProgress copyFor(String characterId, String source) {
		CharacterProgress copy = new CharacterProgress(ownerId, characterId, source, mmo);
		copy.skillPointsTotal = skillPointsTotal;
		copy.rewardSkillPoints = rewardSkillPoints;
		copy.attributePointsTotal = attributePointsTotal;
		copy.classLevel = classLevel;
		copy.classExperience = classExperience;
		copy.carriedExperience = carriedExperience;
		copy.itemSkillPoints = itemSkillPoints;
		copy.itemAttributePoints = itemAttributePoints;
		copy.professionPoints.putAll(professionPoints);
		return copy;
	}

	void writeTo(ConfigurationSection out) {
		ConfigurationSection pools = out.createSection("pools");
		pools.set("skill-points", skillPointsTotal);
		pools.set("reward-skill-points", rewardSkillPoints);
		pools.set("attribute-points", attributePointsTotal);
		pools.set("class-level", classLevel);
		pools.set("class-experience", classExperience);
		pools.set("carried-experience", carriedExperience);
		pools.set("item-skill-points", itemSkillPoints);
		pools.set("item-attribute-points", itemAttributePoints);
		List<Map<String, Object>> professions = new ArrayList<>();
		professionPoints.forEach((id, value) -> {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("id", id);
			entry.put("value", value);
			professions.add(entry);
		});
		pools.set("profession-points", professions);
		ConfigurationSection copy = out.createSection("mmo");
		mmo.getValues(false).forEach(copy::set);
	}

	static CharacterProgress readFrom(ConfigurationSection in, UUID ownerId, String characterId, String source)
			throws IOException {
		ConfigurationSection pools = in.getConfigurationSection("pools");
		ConfigurationSection mmoSection = in.getConfigurationSection("mmo");
		if (pools == null || mmoSection == null) {
			throw new IOException("Progress is missing its pools or MMOCore data");
		}
		YamlConfiguration mmo = new YamlConfiguration();
		mmoSection.getValues(false).forEach(mmo::set);
		try {
			MmoProgressCodec.validate(mmo);
		} catch (IllegalArgumentException malformed) {
			throw new IOException(malformed.getMessage(), malformed);
		}
		CharacterProgress progress = new CharacterProgress(ownerId, characterId, source, mmo);
		progress.setSkillPointsTotal(integer(pools, "skill-points", 0));
		progress.setRewardSkillPoints(integer(pools, "reward-skill-points", 0));
		progress.setAttributePointsTotal(integer(pools, "attribute-points", 0));
		progress.setClassProgress(integer(pools, "class-level", 1), number(pools, "class-experience").doubleValue());
		// Files written before carried EXP existed have none.
		if (pools.contains("carried-experience")) {
			progress.setCarriedExperience(number(pools, "carried-experience").doubleValue());
		}
		// Files written before item points were shared hold none.
		if (pools.contains("item-skill-points")) {
			progress.setItemSkillPoints(integer(pools, "item-skill-points", 0));
		}
		if (pools.contains("item-attribute-points")) {
			progress.setItemAttributePoints(integer(pools, "item-attribute-points", 0));
		}
		if (!(pools.get("profession-points") instanceof List<?> professions)) {
			throw new IOException("Progress is missing profession-points");
		}
		for (Object raw : professions) {
			if (!(raw instanceof Map<?, ?> entry) || !(entry.get("id") instanceof String id) || id.isBlank()
					|| !(entry.get("value") instanceof Number value) || !validInteger(value, 0)
					|| progress.professionPoints.containsKey(id.toLowerCase(Locale.ROOT))) {
				throw new IOException("Progress has a malformed profession-points entry");
			}
			progress.setProfessionPoints(id, value.intValue());
		}
		return progress;
	}

	static Number number(ConfigurationSection section, String key) throws IOException {
		if (!(section.get(key) instanceof Number value) || !Double.isFinite(value.doubleValue())
				|| value.doubleValue() < 0) {
			throw new IOException("Progress is missing " + key);
		}
		return value;
	}

	static int integer(ConfigurationSection section, String key, int minimum) throws IOException {
		Number value = number(section, key);
		if (!validInteger(value, minimum)) {
			throw new IOException("Progress has an invalid " + key);
		}
		return value.intValue();
	}

	private static boolean validInteger(Number value, int minimum) {
		double decimal = value.doubleValue();
		return Double.isFinite(decimal) && decimal >= minimum && decimal <= Integer.MAX_VALUE
				&& decimal == Math.rint(decimal);
	}
}
