package net.tfminecraft.RPCharacters.Objects;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.RPCharacters.api.CharacterSkull;

import net.Indyuce.mmocore.MMOCore;
import net.Indyuce.mmocore.api.player.profess.PlayerClass;
import net.tfminecraft.RPCharacters.mmocore.AttributePointService;
import net.tfminecraft.RPCharacters.mmocore.ClassService;
import net.tfminecraft.RPCharacters.professions.ProfessionIntegrator;
import net.tfminecraft.RPCharacters.Utils.RPTexts;
import net.tfminecraft.RPCharacters.professions.ProfessionRegistry;
import net.tfminecraft.RPCharacters.professions.ProfessionUpgradeDefinition;
import net.tfminecraft.RPCharacters.Cache;
import net.tfminecraft.RPCharacters.Database.Database;
import net.tfminecraft.RPCharacters.identity.NameColour;
import net.tfminecraft.RPCharacters.Loaders.RaceLoader;
import net.tfminecraft.RPCharacters.Objects.Attributes.AttributeData;
import net.tfminecraft.RPCharacters.Objects.Attributes.AttributeModifier;
import net.tfminecraft.RPCharacters.Objects.Races.Race;
import net.tfminecraft.RPCharacters.Objects.Trait.Trait;
import net.tfminecraft.RPCharacters.Objects.Trait.TraitEffectResolver;
import net.tfminecraft.RPCharacters.Utils.ClueFormatter;
import net.tfminecraft.RPCharacters.enums.ClueAddResult;
import net.tfminecraft.RPCharacters.enums.Status;

public class RPCharacter {
	private String id;
	private String name;
	private Player owner;

	private String mmoClass;
	
	private Boolean active;
	private Status status;
	
	private Race race;
	
	private List<String> desc = new ArrayList<>();
	
	private List<Trait> traits = new ArrayList<Trait>();

	private final Map<String, TraitInstanceState> traitState = new HashMap<>();

	private List<String> playerClues = new ArrayList<>();

	private int createdAtEpochSeconds;
	/** Real time this character has spent online. Not wall-clock age; see getAgeSeconds. */
	private int onlinePlaytimeSeconds;
	private Map<String, Integer> conversationCounts = new HashMap<>();
	private Map<String, Long> conversationLastAtMs = new HashMap<>();

	private String alias;
	private String slug;
	private boolean hidden;
	/** Throwaway character made in-game while dev-characters is on. */
	private boolean dev;
	/** Per grant-kit id. Empty/missing kit = legacy never claim for that kit. */
	private final Map<String, net.tfminecraft.RPCharacters.kit.KitStatus> kitStatuses =
			new HashMap<>();
	private final Map<String, net.tfminecraft.RPCharacters.kit.KitCustomiseData> kitCustomisations =
			new HashMap<>();
	private NameColour nameColour;
	private boolean nameColourStaffOverride;
	private String gender;
	private String personaDescription;
	private String birthday;

	private final Set<String> professionUpgrades = new LinkedHashSet<>();
	private final Map<String, Integer> extraAttributeAllocation = new HashMap<>();

	private String lastLocationWorld;
	private Double lastLocationX;
	private Double lastLocationY;
	private Double lastLocationZ;

	/** Default lethal (vanilla death). Missing JSON key loads as true. */
	private boolean pvpLethal = true;

	public static final int MAX_FOOD_VALUE = 200;
	private static final int MAX_DIET_SCORE = 40;

	private int foodValue = MAX_FOOD_VALUE;
	private int dietScore;
	private int rawDietScore;
	private String lastDietTierId;
	
	private AttributeData attributeData;
	
	public RPCharacter(Player p) {
		owner = p;
		attributeData = new AttributeData();
		status = Status.ALIVE;
		active = false;
		id = UUID.randomUUID().toString();
	}
	public RPCharacter(Player p, String i, String n, Boolean a, Status s, Race r, List<Trait> t, String c) {
		this(p, i, n, a, s, r, t, c, new ArrayList<>());
	}

	public RPCharacter(Player p, String i, String n, Boolean a, Status s, Race r, List<Trait> t, String c, List<String> clues) {
		owner = p;
		attributeData = new AttributeData();
		status = s;
		active = a;
		id = i;
		name = n;
		race = r;
		traits = t;
		mmoClass = c;
		playerClues = new ArrayList<>();
		if (clues != null) {
			for (String clue : clues) {
				playerClues.add(ClueFormatter.format(clue));
			}
		}
		update();
	}
	
	public void update() {
		if(active) {
			syncMMOClass(false);
		}
		attributeData = new AttributeData();
		attributeData.mergeFrom(race.getRaceData().getAttributeData());
		desc = new ArrayList<>();
		for(Trait t : traits) {
			if(Cache.backgroundTraitTypes.contains(t.getTraitData().getKey())) {
				java.util.List<String> loreLines = new java.util.ArrayList<>();
				for(String s : t.getDesc()) {
					if (s != null && !s.isBlank()) {
						loreLines.add(s);
					}
				}
				if (!loreLines.isEmpty()) {
					// if (!desc.isEmpty()) {
					// 	desc.add(" ");
					// }
					desc.addAll(loreLines);
				}
			}
			attributeData.mergeFrom(TraitEffectResolver.resolveAttributeData(this, t));
		}
	}
	
	public void stampLastLocation(Location location) {
		if (location == null || location.getWorld() == null) {
			return;
		}
		lastLocationWorld = location.getWorld().getName();
		lastLocationX = location.getX();
		lastLocationY = location.getY();
		lastLocationZ = location.getZ();
	}

	public void setLastLocation(String world, Double x, Double y, Double z) {
		if (world == null || world.isBlank() || x == null || y == null || z == null) {
			clearLastLocation();
			return;
		}
		lastLocationWorld = world;
		lastLocationX = x;
		lastLocationY = y;
		lastLocationZ = z;
	}

	public void clearLastLocation() {
		lastLocationWorld = null;
		lastLocationX = null;
		lastLocationY = null;
		lastLocationZ = null;
	}

	public boolean hasLastLocation() {
		return lastLocationWorld != null && !lastLocationWorld.isBlank()
				&& lastLocationX != null && lastLocationY != null && lastLocationZ != null;
	}

	public boolean isPvpLethal() {
		return pvpLethal;
	}

	public void setPvpLethal(boolean pvpLethal) {
		this.pvpLethal = pvpLethal;
	}

	public int getFoodValue() {
		return foodValue;
	}

	public void setFoodValue(int foodValue) {
		this.foodValue = Math.max(0, Math.min(foodValue, MAX_FOOD_VALUE));
	}

	public int getDietScore() {
		return dietScore;
	}

	public void setDietScore(int dietScore) {
		this.dietScore = Math.max(0, Math.min(dietScore, MAX_DIET_SCORE));
	}

	public int getRawDietScore() {
		return rawDietScore;
	}

	public void setRawDietScore(int rawDietScore) {
		this.rawDietScore = Math.max(0, rawDietScore);
	}

	public String getLastDietTierId() {
		return lastDietTierId;
	}

	public void setLastDietTierId(String lastDietTierId) {
		this.lastDietTierId = lastDietTierId;
	}

	public String getLastLocationWorld() {
		return lastLocationWorld;
	}

	public Double getLastLocationX() {
		return lastLocationX;
	}

	public Double getLastLocationY() {
		return lastLocationY;
	}

	public Double getLastLocationZ() {
		return lastLocationZ;
	}

	/** Bukkit location if the stored world is loaded; otherwise null. */
	public Location getLastLocation() {
		if (!hasLastLocation()) {
			return null;
		}
		World world = Bukkit.getWorld(lastLocationWorld);
		if (world == null) {
			return null;
		}
		return new Location(world, lastLocationX, lastLocationY, lastLocationZ);
	}

	public Player getOwner() {
		return owner;
	}

	public void setOwner(Player owner) {
		this.owner = owner;
	}
	public List<String> getDescription(){
		return desc;
	}
	public Boolean isActive() {
		return active;
	}
	public boolean hasMMOClass() {
		return mmoClass != null;
	}
	public void setMMOClass(String s) {
		mmoClass = s.toUpperCase();
	}
	public String getMMOClass() {
		return mmoClass;
	}
	public void activate() {
		syncMMOClass(true);
		Database.log(owner, "Activated the character "+name);
		active = true;
		AttributePointService.syncOnActivate(this);
		ProfessionIntegrator.apply(owner, this);
	}
	public void deactivate() {
		if(mmoClass == null) mmoClass = net.Indyuce.mmocore.api.player.PlayerData.get(owner).getProfess().getId();
		Database.log(owner, "Deactivated the character "+name);
		active = false;
		ProfessionIntegrator.remove(owner, this);
		AttributePointService.syncOnDeactivate(this);
	}

	private void syncMMOClass(boolean notifyOnChange) {
		if (mmoClass == null) {
			return;
		}
		PlayerClass playerClass = MMOCore.plugin.classManager.get(mmoClass);
		if (playerClass == null) {
			return;
		}
		boolean alreadyOnClass = ClassService.isOnClass(owner, mmoClass);
		ClassService.applyClass(owner, mmoClass);
		if (notifyOnChange && !alreadyOnClass) {
			RPTexts.send(owner, RPTexts.WARN + "Your class was changed to " + playerClass.getName());
		}
	}
	public Status getStatus() {
		return status;
	}
	public void setStatus(Status status) {
		Database.log(owner, "Set the status of the character "+name+" to "+status.toString());
		this.status = status;
	}
	public void setRace(Race race) {
		this.race = race;
	}
	public void setTraits(List<Trait> traits) {
		this.traits = traits;
	}
	public void setAttributeData(AttributeData attributeData) {
		this.attributeData = attributeData;
	}
	public Race getRace() {
		return race;
	}
	public void removeTrait(Trait t) {
		for(int i = 0; i<traits.size(); i++) {
			Trait trait = traits.get(i);
			if(trait.equals(t)) {
				if(active) Database.log(owner, "-"+t.getId()+" ("+name+")");
				traits.remove(i);
				removeTraitState(t.getId());
				return;
			}
		}
	}
	public void addTrait(Trait t) {
		if(active) Database.log(owner, "+"+t.getId()+" ("+name+")");
		this.traits.add(t);
		initializeTraitState(t);
	}
	public List<Trait> getTraits() {
		return traits;
	}

	public TraitInstanceState getTraitState(String traitId) {
		if (traitId == null) {
			return null;
		}
		return traitState.get(normalizeTraitStateKey(traitId));
	}

	public long getDurationRemainingMs(String traitId) {
		TraitInstanceState state = getTraitState(traitId);
		return state != null && state.hasDuration() ? state.getDurationRemainingMs() : -1L;
	}

	public void setDurationRemainingMs(String traitId, long durationRemainingMs) {
		if (traitId == null) {
			return;
		}
		traitState.computeIfAbsent(normalizeTraitStateKey(traitId), ignored -> new TraitInstanceState())
				.setDurationRemainingMs(Math.max(0L, durationRemainingMs));
	}

	public double getFuel(String traitId) {
		TraitInstanceState state = getTraitState(traitId);
		return state != null && state.hasFuel() ? state.getFuel() : -1D;
	}

	public void setFuel(String traitId, double fuel) {
		if (traitId == null) {
			return;
		}
		traitState.computeIfAbsent(normalizeTraitStateKey(traitId), ignored -> new TraitInstanceState())
				.setFuel(Math.max(0D, fuel));
	}

	public void removeTraitState(String traitId) {
		if (traitId == null) {
			return;
		}
		traitState.remove(normalizeTraitStateKey(traitId));
	}

	public Map<String, TraitInstanceState> getTraitStateMap() {
		return Collections.unmodifiableMap(traitState);
	}

	public void initializeTraitState(Trait trait) {
		if (trait == null) {
			return;
		}
		String traitId = trait.getId();
		if (trait.hasDuration()) {
			setDurationRemainingMs(traitId, trait.getDurationMs());
		}
		if (trait.hasFuelTemplate() && trait.getFuelCapacity() > 0D) {
			setFuel(traitId, trait.getFuelCapacity());
		}
	}

	public void ensureTraitStateDefaults() {
		for (Trait trait : traits) {
			if (trait == null || trait.getId() == null) {
				continue;
			}
			String traitId = trait.getId();
			if (trait.hasDuration() && getDurationRemainingMs(traitId) < 0L) {
				setDurationRemainingMs(traitId, trait.getDurationMs());
			}
			if (trait.hasFuelTemplate() && trait.getFuelCapacity() > 0D && getFuel(traitId) < 0D) {
				setFuel(traitId, trait.getFuelCapacity());
			}
		}
		clearOrphanTraitState();
	}

	public void clearOrphanTraitState() {
		Set<String> owned = new LinkedHashSet<>();
		for (Trait trait : traits) {
			if (trait != null && trait.getId() != null) {
				owned.add(normalizeTraitStateKey(trait.getId()));
			}
		}
		traitState.keySet().removeIf(key -> !owned.contains(key));
	}

	private static String normalizeTraitStateKey(String traitId) {
		return traitId.trim().toLowerCase(Locale.ROOT);
	}

	public AttributeData getAttributeData() {
		return attributeData;
	}
	public String getId() {
		return id;
	}
	public void setId(String id) {
		this.id = id;
	}
	public String getName() {
		return name;
	}
	public void setName(String name) {
		if (name == null || name.isBlank()) {
			this.name = null;
			return;
		}
		this.name = ClueFormatter.stripColor(name).trim();
	}

	public String getSlug() {
		return slug;
	}

	public void setSlug(String slug) {
		this.slug = slug;
	}

	public boolean isHidden() {
		return hidden;
	}

	public void setHidden(boolean hidden) {
		this.hidden = hidden;
	}

	public boolean isDev() {
		return dev;
	}

	public void setDev(boolean dev) {
		this.dev = dev;
	}

	public Map<String, net.tfminecraft.RPCharacters.kit.KitStatus> getKitStatuses() {
		return kitStatuses;
	}

	public net.tfminecraft.RPCharacters.kit.KitStatus getKitStatus(String kitId) {
		if (kitId == null || kitId.isBlank()) {
			return null;
		}
		return kitStatuses.get(kitId.trim().toLowerCase(java.util.Locale.ROOT));
	}

	/** Legacy: starter kit status. */
	public net.tfminecraft.RPCharacters.kit.KitStatus getKitStatus() {
		return getKitStatus(net.tfminecraft.RPCharacters.Loaders.KitLoader.DEFAULT_KIT_ID);
	}

	public void setKitStatus(String kitId, net.tfminecraft.RPCharacters.kit.KitStatus status) {
		if (kitId == null || kitId.isBlank()) {
			return;
		}
		String id = kitId.trim().toLowerCase(java.util.Locale.ROOT);
		if (status == null) {
			kitStatuses.remove(id);
		} else {
			kitStatuses.put(id, status);
		}
	}

	/** Legacy: set starter kit status. */
	public void setKitStatus(net.tfminecraft.RPCharacters.kit.KitStatus kitStatus) {
		setKitStatus(net.tfminecraft.RPCharacters.Loaders.KitLoader.DEFAULT_KIT_ID, kitStatus);
	}

	public Map<String, net.tfminecraft.RPCharacters.kit.KitCustomiseData> getKitCustomisations() {
		return kitCustomisations;
	}

	public void putKitCustomise(net.tfminecraft.RPCharacters.kit.KitCustomiseData data) {
		if (data == null || data.getKitKey() == null || data.getKitKey().isBlank()) {
			return;
		}
		kitCustomisations.put(data.getKitKey().toLowerCase(), data);
	}

	public void removeKitCustomise(String kitKey) {
		if (kitKey == null || kitKey.isBlank()) {
			return;
		}
		kitCustomisations.remove(kitKey.trim().toLowerCase(java.util.Locale.ROOT));
	}

	public String getAlias() {
		return alias;
	}

	public void setAlias(String alias) {
		if (alias == null || alias.isBlank()) {
			this.alias = null;
			return;
		}
		this.alias = ClueFormatter.stripColor(alias);
	}

	public void clearAlias() {
		this.alias = null;
	}

	public NameColour getNameColour() {
		return nameColour;
	}

	public void setNameColour(NameColour nameColour) {
		this.nameColour = nameColour;
	}

	public boolean isNameColourStaffOverride() {
		return nameColourStaffOverride;
	}

	public void setNameColourStaffOverride(boolean nameColourStaffOverride) {
		this.nameColourStaffOverride = nameColourStaffOverride;
	}

	public String getGender() {
		return gender;
	}

	public void setGender(String gender) {
		this.gender = gender;
	}

	public String getPersonaDescription() {
		return personaDescription;
	}

	public void setPersonaDescription(String personaDescription) {
		this.personaDescription = personaDescription;
	}

	public String getBirthday() {
		return birthday;
	}

	public void setBirthday(String birthday) {
		if (birthday == null || birthday.isBlank()) {
			this.birthday = null;
			return;
		}
		this.birthday = birthday.trim();
	}

	public String getEffectiveDisplayPlain() {
		String effective = alias != null && !alias.isBlank() ? alias : name;
		if (effective == null) {
			return "";
		}
		return ClueFormatter.stripColor(effective);
	}

	public void modify(String type, String value) {
		modify(type, value, true);
	}

	public void modify(String type, String value, boolean affect) {
		if (type.equalsIgnoreCase("name")) {
			if (affect) {
				setName(value);
			}
		} else if (type.equalsIgnoreCase("race")) {
			Race newRace = RaceLoader.getByString(value);
			if (affect && newRace != null) {
				race = newRace;
			}
		}
	}

	/**
	 * Required player-authored clue count for this character.
	 * Uses the highest matching trait override (not additive), capped by max-clues.
	 */
	public int getCluesNeeded() {
		int needed = Cache.defaultCluesRequired;
		if (hasAnyEvilTrait()) {
			needed = Math.max(needed, Cache.evilCluesRequired);
		}
		for (Trait trait : traits) {
			Integer override = Cache.traitClueOverrides.get(trait.getId().toLowerCase());
			if (override != null && override > needed) {
				needed = override;
			}
		}
		return Math.min(needed, Cache.maxClues);
	}

	private boolean hasAnyEvilTrait() {
		for (Trait trait : traits) {
			if (trait.getTraitData() != null && "evil".equalsIgnoreCase(trait.getTraitData().getKey())) {
				return true;
			}
		}
		return false;
	}

	/** Whether this character has enough player clues to satisfy {@link #getCluesNeeded()}. */
	public boolean hasEnoughClues() {
		return playerClues.size() >= getCluesNeeded();
	}

	public List<String> getPlayerClues() {
		return Collections.unmodifiableList(playerClues);
	}

	public boolean canAddClue() {
		return playerClues.size() < Cache.maxClues;
	}

	public ClueAddResult addPlayerClue(String raw) {
		if (!canAddClue()) return ClueAddResult.AT_MAX;

		String plain = ClueFormatter.stripColor(raw);
		if (plain.length() < Cache.clueMinLength) return ClueAddResult.TOO_SHORT;
		if (plain.length() > Cache.clueMaxLength) return ClueAddResult.TOO_LONG;

		String formatted = ClueFormatter.format(raw);
		for (String existing : playerClues) {
			if (ClueFormatter.stripColor(existing).equalsIgnoreCase(plain)) {
				return ClueAddResult.DUPLICATE;
			}
		}

		playerClues.add(formatted);
		Database.log(owner, "Added clue to " + name + ": " + plain);
		return ClueAddResult.SUCCESS;
	}

	public boolean removePlayerClue(int index) {
		if (index < 0 || index >= playerClues.size()) return false;
		String removed = ClueFormatter.stripColor(playerClues.remove(index));
		Database.log(owner, "Removed clue from " + name + ": " + removed);
		return true;
	}

	public String getClueAddErrorMessage(ClueAddResult result) {
		switch (result) {
			case AT_MAX:
				return RPTexts.formatDisplay(RPTexts.ERROR + "You cannot have more than " + Cache.maxClues + " clues.");
			case TOO_SHORT:
			case TOO_LONG:
				return ClueFormatter.lengthRangeMessage();
			case DUPLICATE:
				return RPTexts.formatDisplay(RPTexts.ERROR + "You already have a clue like that.");
			default:
				return null;
		}
	}

	public int getCreatedAtEpochSeconds() {
		return createdAtEpochSeconds;
	}

	public void setCreatedAtEpochSeconds(int createdAtEpochSeconds) {
		this.createdAtEpochSeconds = Math.max(0, createdAtEpochSeconds);
	}

	public int getAgeSeconds() {
		if (createdAtEpochSeconds <= 0) {
			return 0;
		}
		long age = Instant.now().getEpochSecond() - createdAtEpochSeconds;
		return (int) Math.max(0L, age);
	}

	public int getOnlinePlaytimeSeconds() {
		return onlinePlaytimeSeconds;
	}

	public void setOnlinePlaytimeSeconds(int onlinePlaytimeSeconds) {
		this.onlinePlaytimeSeconds = Math.max(0, onlinePlaytimeSeconds);
	}

	public void addOnlinePlaytimeSeconds(int seconds) {
		if (seconds <= 0) {
			return;
		}
		long sum = (long) onlinePlaytimeSeconds + seconds;
		this.onlinePlaytimeSeconds = (int) Math.min(Integer.MAX_VALUE, sum);
	}

	public Map<String, Integer> getConversationCounts() {
		return conversationCounts;
	}

	public void setConversationCounts(Map<String, Integer> conversationCounts) {
		this.conversationCounts = conversationCounts != null ? new HashMap<>(conversationCounts) : new HashMap<>();
	}

	public Map<String, Long> getConversationLastAtMs() {
		return conversationLastAtMs;
	}

	public void setConversationLastAtMs(Map<String, Long> conversationLastAtMs) {
		this.conversationLastAtMs = conversationLastAtMs != null ? new HashMap<>(conversationLastAtMs) : new HashMap<>();
	}

	public int getConversationCount(String otherCharacterId) {
		if (otherCharacterId == null) {
			return 0;
		}
		return conversationCounts.getOrDefault(otherCharacterId, 0);
	}

	public boolean canCountConversationWith(RPCharacter other, long nowMs) {
		if (other == null) {
			return false;
		}
		Long lastAt = conversationLastAtMs.get(other.getId());
		if (lastAt == null) {
			return true;
		}
		long cooldownMs = Cache.conversationPairCooldownHours * 60L * 60L * 1000L;
		return nowMs - lastAt >= cooldownMs;
	}

	public void recordConversationWith(RPCharacter other, long nowMs) {
		if (other == null) {
			return;
		}
		String otherId = other.getId();
		conversationCounts.put(otherId, getConversationCount(otherId) + 1);
		conversationLastAtMs.put(otherId, nowMs);
	}

	public List<String> getProfessionUpgrades() {
		return Collections.unmodifiableList(new ArrayList<>(professionUpgrades));
	}

	public void setProfessionUpgrades(List<String> upgradeIds) {
		professionUpgrades.clear();
		if (upgradeIds != null) {
			for (String upgradeId : upgradeIds) {
				if (upgradeId != null && !upgradeId.isBlank()) {
					professionUpgrades.add(upgradeId);
				}
			}
		}
	}

	public boolean hasProfessionUpgrade(String upgradeId) {
		return upgradeId != null && professionUpgrades.contains(upgradeId);
	}

	public void addProfessionUpgrade(String upgradeId) {
		if (upgradeId != null && !upgradeId.isBlank()) {
			professionUpgrades.add(upgradeId);
		}
	}

	public void removeProfessionUpgrade(String upgradeId) {
		professionUpgrades.remove(upgradeId);
	}

	public void clearProfessionUpgrades() {
		professionUpgrades.clear();
	}

	public List<ProfessionUpgradeDefinition> resolveProfessionUpgrades() {
		List<ProfessionUpgradeDefinition> resolved = new ArrayList<>();
		for (String upgradeId : professionUpgrades) {
			ProfessionUpgradeDefinition upgrade = ProfessionRegistry.getUpgrade(upgradeId);
			if (upgrade != null) {
				resolved.add(upgrade);
			}
		}
		return resolved;
	}

	public int getSpentPointsOnProfession(String professionId) {
		int spent = 0;
		for (ProfessionUpgradeDefinition upgrade : resolveProfessionUpgrades()) {
			if (upgrade.getProfessionId().equalsIgnoreCase(professionId)) {
				spent += upgrade.getCost();
			}
		}
		return spent;
	}

	public int getTotalSpentPoints() {
		int spent = 0;
		for (ProfessionUpgradeDefinition upgrade : resolveProfessionUpgrades()) {
			spent += upgrade.getCost();
		}
		return spent;
	}

	public Map<String, Integer> getExtraAttributeAllocation() {
		return Collections.unmodifiableMap(extraAttributeAllocation);
	}

	public void setExtraAttributeAllocation(Map<String, Integer> allocation) {
		extraAttributeAllocation.clear();
		if (allocation != null) {
			for (Map.Entry<String, Integer> entry : allocation.entrySet()) {
				if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0) {
					extraAttributeAllocation.put(entry.getKey().toLowerCase(), entry.getValue());
				}
			}
		}
	}

	public int getCreationBaseAmount(String attributeId) {
		if (attributeId == null || attributeData == null) {
			return 0;
		}
		return attributeData.getAmount(new AttributeModifier(attributeId, 0));
	}

	public int getSpentExtraAttributePoints() {
		int spent = 0;
		for (int amount : extraAttributeAllocation.values()) {
			spent += amount;
		}
		return spent;
	}

	/** Player head for this character (wardrobe / owner). See {@link CharacterSkull}. */
	public ItemStack getSkull() {
		return CharacterSkull.of(this);
	}
}
