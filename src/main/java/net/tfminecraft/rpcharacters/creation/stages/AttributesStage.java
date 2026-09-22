package net.tfminecraft.rpcharacters.creation.stages;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.creation.CharacterCreation;
import net.tfminecraft.rpcharacters.creation.Stage;
import net.tfminecraft.rpcharacters.loaders.TraitLoader;
import net.tfminecraft.rpcharacters.managers.InventoryManager;
import net.tfminecraft.rpcharacters.objects.attributes.AttributeData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import net.tfminecraft.rpcharacters.mmocore.MmoCoreAttributeHelper;

/**
 * Creation point-buy sheet: spend exactly {@code points} across attributes
 * with cost of the n-th rank = 2^(n-1) (1, 2, 4, 8, …), max rank per attribute from config.
 */
public class AttributesStage extends Stage {

	/** Default center slots for legacy string-list config (vertical up/down = ±9). */
	private static final int[] DEFAULT_CENTER_SLOTS = {20, 21, 22, 23, 24, 25};

	private static final Map<String, String> ATTR_ABBR = Map.of(
		"strength", "str",
		"dexterity", "dex",
		"constitution", "con",
		"intelligence", "int",
		"wisdom", "wis",
		"charisma", "cha"
	);

	private final int pool;
	private final int maxRank;
	private final int size;
	private final String key;
	private final List<String> attributes;
	/** Center inventory slot per attribute id (creation order preserved in {@link #attributes}). */
	private final Map<String, Integer> attributeSlots;

	private final Map<String, Integer> ranks = new LinkedHashMap<>();
	private int remaining;
	private boolean active;

	public AttributesStage(Stage s, ConfigurationSection config) {
		copyBaseFields(s);
		this.key = config.getString("key", "attributes");
		this.pool = config.contains("points") ? config.getInt("points") : 12;
		this.maxRank = config.contains("max-rank") ? config.getInt("max-rank") : 4;
		this.size = config.contains("gui-size") ? config.getInt("gui-size") : 54;
		this.attributes = new ArrayList<>();
		this.attributeSlots = new LinkedHashMap<>();
		parseAttributes(config);
		if (this.attributes.isEmpty()) {
			int idx = 0;
			for (String a : Cache.attributes) {
				if (a == null || a.isBlank()) {
					continue;
				}
				String id = a.trim().toLowerCase(Locale.ROOT);
				if (!acceptAttribute(id)) {
					continue;
				}
				this.attributes.add(id);
				this.attributeSlots.put(id, defaultCenterSlot(idx++));
			}
		}
		resetRanks();
		this.active = false;
	}

	public AttributesStage(AttributesStage another) {
		copyBaseFields(another);
		this.key = another.key;
		this.pool = another.pool;
		this.maxRank = another.maxRank;
		this.size = another.size;
		this.attributes = new ArrayList<>(another.attributes);
		this.attributeSlots = new LinkedHashMap<>(another.attributeSlots);
		resetRanks();
		this.active = false;
	}

	private void parseAttributes(ConfigurationSection config) {
		if (!config.contains("attributes")) {
			return;
		}
		ConfigurationSection map = config.getConfigurationSection("attributes");
		if (map != null) {
			int idx = 0;
			for (String rawKey : map.getKeys(false)) {
				String id = rawKey.trim().toLowerCase(Locale.ROOT);
				if (!acceptAttribute(id)) {
					continue;
				}
				int slot = -1;
				ConfigurationSection entry = map.getConfigurationSection(rawKey);
				if (entry != null && entry.contains("slot")) {
					slot = entry.getInt("slot");
				} else if (map.isInt(rawKey)) {
					slot = map.getInt(rawKey);
				}
				if (slot < 0 || slot >= size) {
					slot = defaultCenterSlot(idx);
					RPCharacters.plugin.getLogger().warning(
						"[attributes] " + id + " missing/invalid slot; using " + slot
					);
				}
				this.attributes.add(id);
				this.attributeSlots.put(id, slot);
				idx++;
			}
			return;
		}
		int idx = 0;
		for (String a : config.getStringList("attributes")) {
			if (a == null || a.isBlank()) {
				continue;
			}
			String id = a.trim().toLowerCase(Locale.ROOT);
			if (!acceptAttribute(id)) {
				continue;
			}
			this.attributes.add(id);
			this.attributeSlots.put(id, defaultCenterSlot(idx++));
		}
	}

	private boolean acceptAttribute(String id) {
		if (id == null || id.isEmpty()) {
			return false;
		}
		if (!MmoCoreAttributeHelper.exists(id)) {
			RPCharacters.plugin.getLogger().warning(
				"[attributes] unknown MMOCore attribute '" + id + "' — skipped"
			);
			return false;
		}
		return true;
	}

	private static int defaultCenterSlot(int index) {
		if (index >= 0 && index < DEFAULT_CENTER_SLOTS.length) {
			return DEFAULT_CENTER_SLOTS[index];
		}
		return 22;
	}

	private void resetRanks() {
		ranks.clear();
		for (String attr : attributes) {
			ranks.put(attr, 0);
		}
		remaining = pool;
	}

	public String getKey() {
		return key;
	}

	public int getPool() {
		return pool;
	}

	public int getMaxRank() {
		return maxRank;
	}

	public int getSize() {
		return size;
	}

	public int getRemaining() {
		return remaining;
	}

	public boolean isActive() {
		return active;
	}

	public void setActive(boolean active) {
		this.active = active;
	}

	public List<String> getAttributes() {
		return attributes;
	}

	/** Center slot for attribute, or -1 if unknown. */
	public int getCenterSlot(String attr) {
		Integer slot = attributeSlots.get(normalize(attr));
		return slot == null ? -1 : slot;
	}

	/**
	 * Vertical sheet slots: [up, center, down] using center ± 9.
	 * Returns {-1,-1,-1} if center is unset or out of bounds.
	 */
	public int[] getSheetSlots(String attr) {
		int center = getCenterSlot(attr);
		if (center < 0 || center >= size) {
			return new int[] {-1, -1, -1};
		}
		int up = center - 9;
		int down = center + 9;
		if (up < 0 || down >= size) {
			return new int[] {-1, center, -1};
		}
		return new int[] {up, center, down};
	}

	public int getRank(String attr) {
		return ranks.getOrDefault(normalize(attr), 0);
	}

	public static String abbrevFor(String attr) {
		String n = normalize(attr);
		return ATTR_ABBR.getOrDefault(n, n.length() >= 3 ? n.substring(0, 3) : n);
	}

	public static String traitId(String attr, int rank) {
		return abbrevFor(attr) + rank;
	}

	/** Cost to purchase the n-th rank (1-based): 1, 2, 4, 8, … */
	public static int costForRank(int rank) {
		if (rank < 1) {
			return 0;
		}
		return 1 << (rank - 1);
	}

	public int spentPoints() {
		int spent = 0;
		for (String attr : attributes) {
			int r = getRank(attr);
			for (int n = 1; n <= r; n++) {
				spent += costForRank(n);
			}
		}
		return spent;
	}

	private static String normalize(String attr) {
		return attr == null ? "" : attr.trim().toLowerCase(Locale.ROOT);
	}

	public boolean tryIncrease(String attr) {
		String n = normalize(attr);
		if (!ranks.containsKey(n)) {
			return false;
		}
		int rank = ranks.get(n);
		if (rank >= maxRank) {
			return false;
		}
		int cost = costForRank(rank + 1);
		if (remaining < cost) {
			return false;
		}
		ranks.put(n, rank + 1);
		remaining -= cost;
		return true;
	}

	public boolean tryDecrease(String attr) {
		String n = normalize(attr);
		if (!ranks.containsKey(n)) {
			return false;
		}
		int rank = ranks.get(n);
		if (rank <= 0) {
			return false;
		}
		int refund = costForRank(rank);
		ranks.put(n, rank - 1);
		remaining += refund;
		return true;
	}

	public void hydrateFromCharacter(RPCharacter character) {
		resetRanks();
		if (character == null) {
			return;
		}
		for (String attr : attributes) {
			int rank = 0;
			for (int n = 1; n <= maxRank; n++) {
				if (hasTraitId(character, traitId(attr, n))) {
					rank = n;
				} else {
					break;
				}
			}
			ranks.put(attr, Math.min(rank, maxRank));
		}
		remaining = pool - spentPoints();
		if (remaining < 0) {
			remaining = 0;
		}
	}

	private static boolean hasTraitId(RPCharacter character, String id) {
		for (Trait t : character.getTraits()) {
			if (t.getId().equalsIgnoreCase(id)) {
				return true;
			}
		}
		return false;
	}

	public void confirm(Player p, CharacterCreation cc) {
		if (remaining != 0) {
			RPTexts.send(p, RPTexts.ERROR + "Spend all " + pool + " attribute points ("
				+ remaining + " left).");
			return;
		}
		active = false;
		p.closeInventory();
		if (cc == null) {
			return;
		}
		List<Trait> toRemove = new ArrayList<>();
		for (Trait trait : cc.getCharacter().getTraits()) {
			if (trait.getTraitData().getKey() != null
				&& trait.getTraitData().getKey().equalsIgnoreCase(key)) {
				toRemove.add(trait);
			}
		}
		for (Trait trait : toRemove) {
			cc.getCharacter().removeTrait(trait);
		}
		AttributeData contribution = new AttributeData();
		contribution.clearAll();
		for (String attr : attributes) {
			int rank = getRank(attr);
			for (int n = 1; n <= rank; n++) {
				Trait t = TraitLoader.getByString(traitId(attr, n));
				if (t == null) {
					RPTexts.send(p, RPTexts.ERROR + "Missing attribute trait "
						+ traitId(attr, n) + " — check attributes-traits.yml");
					continue;
				}
				cc.getCharacter().addTrait(t);
				contribution.mergeFrom(t.getTraitData().getAttributeData());
			}
		}
		cc.setAttributeStageContribution(key, contribution);
		RPTexts.send(p, RPTexts.SUCCESS + "Attributes set.");
		new BukkitRunnable() {
			@Override
			public void run() {
				if (cc.isEditingFromSummary()) {
					cc.returnToSummary();
				} else if (autoNext()) {
					cc.runStage();
				} else {
					cc.setCanNext(true);
				}
			}
		}.runTaskLater(RPCharacters.plugin, 2L);
	}

	public void execute(Player p, CharacterCreation cc) {
		if (cc != null && cc.isCancelled()) {
			return;
		}
		active = true;
		InventoryManager inv = new InventoryManager();
		inv.attributesView(p, this, cc);
	}
}
