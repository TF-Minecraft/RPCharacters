package net.tfminecraft.rpcharacters.ingest;

import java.util.Collection;

/**
 * A roster push replaces the website rows for that player. Skip it when a saved
 * character file did not load, so a join failure cannot delete that website row.
 */
public final class RosterPushPolicy {

	private RosterPushPolicy() {}

	/**
	 * @param characterFileIds saved file ids, or null when the folder could not be listed
	 */
	public static boolean wouldDropSavedCharacters(
			Collection<String> loadedIds,
			Collection<String> characterFileIds) {
		if (characterFileIds == null) {
			return true;
		}
		for (String id : characterFileIds) {
			if (id == null || id.isBlank()) {
				continue;
			}
			if (loadedIds == null || !loadedIds.contains(id)) {
				return true;
			}
		}
		return false;
	}
}
