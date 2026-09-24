package net.tfminecraft.rpcharacters.ingest;

/**
 * A roster push replaces the website rows for that player. Skip it when disk
 * still has character files that did not load, so a join failure cannot delete them.
 */
public final class RosterPushPolicy {

	private RosterPushPolicy() {}

	public static boolean wouldDropSavedCharacters(int loadedCharacters, int characterFilesOnDisk) {
		return characterFilesOnDisk > loadedCharacters;
	}
}
