package net.tfminecraft.rpcharacters.wipe;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.database.Database;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.ingest.RosterSyncService;
import net.tfminecraft.rpcharacters.mail.MailRecipientDirectory;
import net.tfminecraft.rpcharacters.wardrobe.WardrobeService;

/**
 * Hard-deletes characters from RPCharacters data. Unlike permakill this removes the
 * character record instead of marking it dead.
 */
public final class CharacterWipeService {

	private static final String CHARACTER_DATA_PATH = "plugins/RPCharacters/data/characterdata";

	private static final Database DB = new Database();

	private CharacterWipeService() {}

	public static final class WipeResult {
		public final int playersTouched;
		public final int charactersDeleted;
		public final List<String> deletedIds;

		private WipeResult(int playersTouched, List<String> deletedIds) {
			this.playersTouched = playersTouched;
			this.charactersDeleted = deletedIds.size();
			this.deletedIds = List.copyOf(deletedIds);
		}
	}

	/** Main thread only: activating a replacement syncs MMOCore and fires lifecycle events. */
	public static WipeResult wipeTagged() {
		return wipe(RPCharacter::isDev);
	}

	/** Read-only count of tagged characters on disk, for the wipe confirm prompt. */
	public static int countTagged() {
		int tagged = 0;
		File[] ownerDirs = new File(CHARACTER_DATA_PATH).listFiles();
		if (ownerDirs == null) {
			return 0;
		}
		for (File ownerDir : ownerDirs) {
			if (ownerDir == null || !ownerDir.isDirectory()) {
				continue;
			}
			UUID uuid = parseUuid(ownerDir.getName());
			if (uuid == null) {
				continue;
			}
			PlayerData pd = resolvePlayerData(uuid);
			if (pd == null) {
				continue;
			}
			for (RPCharacter c : pd.getCharacters()) {
				if (c != null && c.getId() != null && c.isDev()) {
					tagged++;
				}
			}
		}
		return tagged;
	}

	private static WipeResult wipe(Predicate<RPCharacter> doomedTest) {
		List<String> deletedIds = new ArrayList<>();
		int playersTouched = 0;
		File[] ownerDirs = new File(CHARACTER_DATA_PATH).listFiles();
		if (ownerDirs == null) {
			return new WipeResult(0, deletedIds);
		}
		for (File ownerDir : ownerDirs) {
			if (ownerDir == null || !ownerDir.isDirectory()) {
				continue;
			}
			UUID uuid = parseUuid(ownerDir.getName());
			if (uuid == null) {
				continue;
			}
			if (wipeOwner(uuid, doomedTest, deletedIds) > 0) {
				playersTouched++;
			}
		}
		return new WipeResult(playersTouched, deletedIds);
	}

	private static PlayerData resolvePlayerData(UUID uuid) {
		PlayerData pd = PlayerManager.get(uuid);
		if (pd != null) {
			return pd;
		}
		pd = DB.loadPlayerData(uuid);
		if (pd == null) {
			return null;
		}
		if (pd.getCharacters().isEmpty()) {
			// No playerdata file, so loadPlayerData returned a blank record without characters.
			DB.loadCharacters(pd);
		}
		return pd;
	}

	private static int wipeOwner(UUID uuid, Predicate<RPCharacter> doomedTest, List<String> deletedIds) {
		PlayerData pd = resolvePlayerData(uuid);
		if (pd == null) {
			return 0;
		}

		List<RPCharacter> doomed = new ArrayList<>();
		for (RPCharacter c : pd.getCharacters()) {
			if (c != null && c.getId() != null && doomedTest.test(c)) {
				doomed.add(c);
			}
		}
		if (doomed.isEmpty()) {
			return 0;
		}

		boolean activeRemoved = false;
		for (RPCharacter c : doomed) {
			if (Boolean.TRUE.equals(c.isActive())) {
				activeRemoved = true;
			}
			pd.getCharacters().remove(c);
			deleteCharacterFile(uuid, c.getId());
			MailRecipientDirectory.remove(c.getId());
			net.tfminecraft.rpcharacters.playtime.CharacterPlaytimeDirectory.remove(uuid, c.getId());
			deletedIds.add(c.getId());
		}

		Player online = Bukkit.getPlayer(uuid);
		boolean replaced = false;
		if (activeRemoved && online != null) {
			// Offline owners are left without an active character: activate() needs a live
			// owner for MMOCore. They hit the no-character freeze and pick from the menu.
			List<RPCharacter> alive = pd.getCharacters(Status.ALIVE);
			if (!alive.isEmpty()) {
				pd.setActiveCharacter(alive.get(0));
				replaced = true;
			}
		}

		DB.savePlayer(pd);

		if (online != null) {
			if (replaced) {
				WardrobeService.refreshActiveAsync(online);
			}
			RPCharacters.getPlayerManager().reevaluateFreeze(online);
			RosterSyncService.pushRosterForPlayer(online);
		}
		return doomed.size();
	}

	private static void deleteCharacterFile(UUID uuid, String characterId) {
		File file = new File(new File(CHARACTER_DATA_PATH, uuid.toString()), characterId + ".json");
		if (file.exists() && !file.delete() && RPCharacters.plugin != null) {
			RPCharacters.plugin.getLogger().warning(
				"[wipe] could not delete character file " + file.getPath()
			);
		}
	}

	private static UUID parseUuid(String raw) {
		try {
			return UUID.fromString(raw);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
