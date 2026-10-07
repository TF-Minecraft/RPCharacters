package net.tfminecraft.rpcharacters.pvp;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import net.tfminecraft.tlibs.armour.ArmorType;

/** Open {@code /pvp start} situations. The command layer owns timers and titles. */
public final class PvpSituations {

	private static final CopyOnWriteArrayList<PvpSituation> open = new CopyOnWriteArrayList<>();

	private PvpSituations() {
	}

	public static void track(PvpSituation situation) {
		if (situation != null) {
			open.add(situation);
		}
	}

	public static void untrack(PvpSituation situation) {
		if (situation != null) {
			open.remove(situation);
		}
	}

	public static PvpSituation openFor(UUID issuerId) {
		if (issuerId == null) {
			return null;
		}
		PvpSituation found = null;
		for (PvpSituation situation : open) {
			if (situation.isOpen() && issuerId.equals(situation.issuerId())) {
				found = situation;
			}
		}
		return found;
	}

	public static void markDeath(UUID playerId) {
		for (PvpSituation situation : open) {
			situation.markDeath(playerId);
		}
	}

	/**
	 * From {@code /pvp start} until the fight closes, nobody in it who is still alive
	 * may put armour on. Helmets are free for those who came in wearing body armour.
	 */
	public static boolean locksArmour(UUID playerId, ArmorType type) {
		if (playerId == null) {
			return false;
		}
		for (PvpSituation situation : open) {
			if (situation.isOpen() && situation.includes(playerId) && !situation.hasDied(playerId)
					&& (type != ArmorType.HELMET || !situation.wasArmoured(playerId))) {
				return true;
			}
		}
		return false;
	}

	/** Another fight still covers this player, so ending one must not clear their session. */
	public static boolean remainsActiveElsewhere(UUID playerId, PvpSituation except) {
		for (PvpSituation situation : open) {
			if (situation == except || !situation.isOpen() || !situation.hasStarted()) {
				continue;
			}
			if (situation.includes(playerId) && !situation.hasDied(playerId)) {
				return true;
			}
		}
		return false;
	}

	static void clear() {
		open.clear();
	}
}
