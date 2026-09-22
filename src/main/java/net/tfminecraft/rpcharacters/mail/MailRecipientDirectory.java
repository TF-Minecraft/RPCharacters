package net.tfminecraft.rpcharacters.mail;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.utils.ClueFormatter;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.identity.DisplayIdentityService;
import net.tfminecraft.rpcharacters.identity.NameColour;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.api.ProvinceSystemClient;
import net.tfminecraft.rpcharacters.wardrobe.WardrobeCache;
import net.tfminecraft.rpcharacters.wardrobe.WardrobeSlotData;
import net.tfminecraft.rpcharacters.wardrobe.WardrobeSnapshot;

public final class MailRecipientDirectory {

	private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();

	private MailRecipientDirectory() {}

	public static void scanFromDisk() {
		File root = new File("plugins/RPCharacters/data/characterdata");
		if (!root.exists() || !root.isDirectory()) {
			return;
		}
		File[] ownerDirs = root.listFiles();
		if (ownerDirs == null) {
			return;
		}
		JSONParser parser = new JSONParser();
		for (File ownerDir : ownerDirs) {
			if (!ownerDir.isDirectory()) {
				continue;
			}
			UUID ownerUuid;
			try {
				ownerUuid = UUID.fromString(ownerDir.getName());
			} catch (IllegalArgumentException ignored) {
				continue;
			}
			File[] files = ownerDir.listFiles();
			if (files == null) {
				continue;
			}
			for (File file : files) {
				if (!file.isFile() || !file.getName().endsWith(".json")) {
					continue;
				}
				try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
					Object parsed = parser.parse(reader);
					if (!(parsed instanceof JSONObject json)) {
						continue;
					}
					upsertFromJson(ownerUuid, json);
				} catch (Exception ignored) {
					// skip corrupt character files
				}
			}
		}
	}

	public static void upsert(UUID ownerUuid, RPCharacter character) {
		if (ownerUuid == null || character == null || character.getId() == null) {
			return;
		}
		if (character.getStatus() != Status.ALIVE) {
			ENTRIES.remove(character.getId());
			return;
		}
		Entry existing = ENTRIES.get(character.getId());
		Entry entry = new Entry();
		entry.ownerUuid = ownerUuid;
		entry.characterId = character.getId();
		entry.displayPlain = character.getEffectiveDisplayPlain();
		entry.displayTab = DisplayIdentityService.resolveDisplayTab(character);
		if (character.hasLastLocation()) {
			entry.hasStoredLocation = true;
			entry.world = character.getLastLocationWorld();
			entry.x = character.getLastLocationX();
			entry.y = character.getLastLocationY();
			entry.z = character.getLastLocationZ();
		}
		if (existing != null) {
			entry.baseTextureValue = existing.baseTextureValue;
			entry.baseTextureSignature = existing.baseTextureSignature;
		}
		ENTRIES.put(character.getId(), entry);
	}

	public static void updateWardrobeTexture(
			UUID ownerUuid,
			String characterId,
			String textureValue,
			String textureSignature) {
		if (ownerUuid == null || characterId == null || characterId.isBlank()) {
			return;
		}
		if (textureValue == null || textureValue.isBlank()) {
			return;
		}
		Entry entry = ENTRIES.get(characterId);
		if (entry == null || !ownerUuid.equals(entry.ownerUuid)) {
			entry = new Entry();
			entry.ownerUuid = ownerUuid;
			entry.characterId = characterId;
			ENTRIES.put(characterId, entry);
		}
		entry.baseTextureValue = textureValue;
		entry.baseTextureSignature = textureSignature;
	}

	/**
	 * Pull base wardrobe textures from ProvinceSystem for directory entries that
	 * do not have a cached texture yet (offline characters, non-active alts, etc.).
	 */
	public static void refreshMissingTexturesAsync(Runnable onComplete) {
		if (RPCharacters.plugin == null || !RPCharacters.plugin.isEnabled()) {
			return;
		}
		List<Entry> missing = new ArrayList<>();
		for (Entry entry : ENTRIES.values()) {
			if (entry == null || entry.characterId == null) {
				continue;
			}
			if (entry.baseTextureValue != null && !entry.baseTextureValue.isBlank()) {
				continue;
			}
			missing.add(entry);
		}
		if (missing.isEmpty()) {
			if (onComplete != null) {
				Bukkit.getScheduler().runTask(RPCharacters.plugin, onComplete);
			}
			return;
		}
		Bukkit.getScheduler().runTaskAsynchronously(RPCharacters.plugin, () -> {
			for (Entry entry : missing) {
				ProvinceSystemClient.SimpleResult result = ProvinceSystemClient.fetchWardrobe(
						entry.ownerUuid.toString(),
						entry.characterId);
				if (!result.ok) {
					continue;
				}
				WardrobeSnapshot snapshot = WardrobeSnapshot.parse(result.body);
				cacheBaseTextureFromSnapshot(entry.ownerUuid, snapshot);
			}
			if (onComplete != null) {
				Bukkit.getScheduler().runTask(RPCharacters.plugin, onComplete);
			}
		});
	}

	public static void cacheWardrobeSnapshot(UUID ownerUuid, WardrobeSnapshot snapshot) {
		cacheBaseTextureFromSnapshot(ownerUuid, snapshot);
	}

	private static void cacheBaseTextureFromSnapshot(UUID ownerUuid, WardrobeSnapshot snapshot) {
		if (ownerUuid == null || snapshot == null || snapshot.getCharacterId() == null) {
			return;
		}
		WardrobeSlotData base = snapshot.getSlot(WardrobeSnapshot.SLOT_BASE);
		if (base == null || !base.isFilled()) {
			return;
		}
		String value = base.getTextureValue();
		if (value == null || value.isBlank()) {
			return;
		}
		updateWardrobeTexture(
				ownerUuid,
				snapshot.getCharacterId(),
				value,
				base.getTextureSignature());
	}

	public static void remove(String characterId) {
		if (characterId != null) {
			ENTRIES.remove(characterId);
		}
	}

	public static List<CharacterMailTarget> listMailTargets() {
		Map<String, CharacterMailTarget> targets = new ConcurrentHashMap<>();
		for (Entry entry : ENTRIES.values()) {
			CharacterMailTarget target = toTarget(entry);
			if (target != null) {
				targets.put(entry.characterId, target);
			}
		}
		for (PlayerData pd : PlayerManager.getOnlineData()) {
			if (pd == null) {
				continue;
			}
			for (RPCharacter character : pd.getCharacters(Status.ALIVE)) {
				if (character == null || character.getId() == null) {
					continue;
				}
				if (targets.containsKey(character.getId())) {
					continue;
				}
				Entry live = fromLive(pd.getUniqueId(), character);
				CharacterMailTarget target = toTarget(live);
				if (target != null) {
					targets.put(character.getId(), target);
				}
			}
		}
		return new ArrayList<>(targets.values());
	}

	public static Location getMailTargetLocation(UUID ownerUuid, String characterId) {
		if (ownerUuid == null || characterId == null || characterId.isBlank()) {
			return null;
		}
		PlayerData pd = PlayerManager.get(ownerUuid);
		if (pd != null) {
			RPCharacter active = pd.getActiveCharacter();
			Player owner = resolveOwner(ownerUuid, pd);
			if (active != null && characterId.equals(active.getId()) && owner != null && owner.isOnline()) {
				return owner.getLocation();
			}
			RPCharacter stored = pd.getCharacterById(characterId);
			if (stored != null) {
				return stored.getLastLocation();
			}
		}
		Entry entry = ENTRIES.get(characterId);
		if (entry == null || !ownerUuid.equals(entry.ownerUuid)) {
			return null;
		}
		return toBukkitLocation(entry);
	}

	private static void upsertFromJson(UUID ownerUuid, JSONObject json) {
		Object idRaw = json.get("id");
		if (!(idRaw instanceof String characterId) || characterId.isBlank()) {
			return;
		}
		Status status = Status.ALIVE;
		Object statusRaw = json.get("status");
		if (statusRaw != null) {
			try {
				status = Status.valueOf(statusRaw.toString().toUpperCase());
			} catch (IllegalArgumentException ignored) {
				return;
			}
		}
		if (status != Status.ALIVE) {
			ENTRIES.remove(characterId);
			return;
		}
		String name = json.get("name") instanceof String n ? n : "";
		String alias = json.get("alias") instanceof String a ? a : null;
		String plainSource = alias != null && !alias.isBlank() ? alias : name;
		String displayPlain = ClueFormatter.stripColor(plainSource != null ? plainSource : "");
		NameColour colour = null;
		Object colourRaw = json.get("name-colour");
		if (colourRaw instanceof JSONObject colourJson) {
			colour = NameColour.fromJson(colourJson);
		}
		Entry entry = new Entry();
		entry.ownerUuid = ownerUuid;
		entry.characterId = characterId;
		entry.displayPlain = displayPlain;
		entry.displayTab = DisplayIdentityService.colourPlain(displayPlain, colour);
		Object locRaw = json.get("last-location");
		if (locRaw instanceof JSONObject loc) {
			Object worldRaw = loc.get("world");
			Object xRaw = loc.get("x");
			Object yRaw = loc.get("y");
			Object zRaw = loc.get("z");
			if (worldRaw instanceof String world && !world.isBlank()
					&& xRaw instanceof Number && yRaw instanceof Number && zRaw instanceof Number) {
				entry.hasStoredLocation = true;
				entry.world = world;
				entry.x = ((Number) xRaw).doubleValue();
				entry.y = ((Number) yRaw).doubleValue();
				entry.z = ((Number) zRaw).doubleValue();
			}
		}
		ENTRIES.put(characterId, entry);
	}

	private static Entry fromLive(UUID ownerUuid, RPCharacter character) {
		Entry entry = new Entry();
		entry.ownerUuid = ownerUuid;
		entry.characterId = character.getId();
		entry.displayPlain = character.getEffectiveDisplayPlain();
		entry.displayTab = DisplayIdentityService.resolveDisplayTab(character);
		if (character.hasLastLocation()) {
			entry.hasStoredLocation = true;
			entry.world = character.getLastLocationWorld();
			entry.x = character.getLastLocationX();
			entry.y = character.getLastLocationY();
			entry.z = character.getLastLocationZ();
		}
		return entry;
	}

	private static CharacterMailTarget toTarget(Entry entry) {
		if (entry == null || entry.characterId == null) {
			return null;
		}
		PlayerData pd = PlayerManager.get(entry.ownerUuid);
		Player owner = resolveOwner(entry.ownerUuid, pd);
		boolean liveActive = false;
		RPCharacter liveCharacter = null;
		if (pd != null) {
			liveCharacter = pd.getActiveCharacter();
			liveActive = liveCharacter != null
					&& entry.characterId.equals(liveCharacter.getId())
					&& owner != null
					&& owner.isOnline();
		}
		String worldName = null;
		Location location = null;
		String displayTab = entry.displayTab;
		String displayPlain = entry.displayPlain;
		if (liveActive) {
			location = owner.getLocation();
			worldName = location.getWorld() != null ? location.getWorld().getName() : entry.world;
			if (liveCharacter != null) {
				displayTab = DisplayIdentityService.resolveDisplayTab(liveCharacter);
				displayPlain = liveCharacter.getEffectiveDisplayPlain();
			}
		} else if (entry.hasStoredLocation) {
			worldName = entry.world;
			location = toBukkitLocation(entry);
		}
		String textureValue = entry.baseTextureValue;
		String textureSignature = entry.baseTextureSignature;
		if (textureValue == null || textureValue.isBlank()) {
			WardrobeSnapshot snap = WardrobeCache.get(entry.ownerUuid);
			if (snap != null
					&& entry.characterId.equalsIgnoreCase(snap.getCharacterId())) {
				WardrobeSlotData base = snap.getSlot(WardrobeSnapshot.SLOT_BASE);
				if (base != null && base.isFilled()) {
					textureValue = base.getTextureValue();
					textureSignature = base.getTextureSignature();
				}
			}
		}
		return new CharacterMailTarget(
				entry.ownerUuid,
				entry.characterId,
				DisplayIdentityService.ensureDisplayTabWhite(displayTab),
				displayPlain,
				worldName,
				location,
				textureValue,
				textureSignature);
	}

	private static Player resolveOwner(UUID ownerUuid, PlayerData pd) {
		if (pd != null && pd.getPlayer() != null) {
			return pd.getPlayer();
		}
		return Bukkit.getPlayer(ownerUuid);
	}

	private static Location toBukkitLocation(Entry entry) {
		if (entry == null || !entry.hasStoredLocation || entry.world == null) {
			return null;
		}
		World world = Bukkit.getWorld(entry.world);
		if (world == null) {
			return null;
		}
		return new Location(world, entry.x, entry.y, entry.z);
	}

	private static final class Entry {
		private UUID ownerUuid;
		private String characterId;
		private String displayTab = "";
		private String displayPlain = "";
		private boolean hasStoredLocation;
		private String world;
		private Double x;
		private Double y;
		private Double z;
		private String baseTextureValue;
		private String baseTextureSignature;
	}
}
