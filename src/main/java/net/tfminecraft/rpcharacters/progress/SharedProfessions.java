package net.tfminecraft.rpcharacters.progress;

import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

import net.tfminecraft.rpcharacters.mmocore.MmoProgressCodec;
import net.tfminecraft.rpcharacters.mmocore.MmoProgressCodec.ProfessionLevel;

/**
 * Professions belong to the account. A character plays each profession at the best level any of its
 * account's characters, alive or dead, has reached, and holds at least as many lifetime points for it as
 * any of them. Upgrades stay with the character that bought them, so the points arrive unspent. Levels
 * never go down, so taking the best also settles accounts whose characters levelled apart before this.
 */
final class SharedProfessions {
	private SharedProfessions() {}

	/**
	 * Raises the target's professions and profession points to the best of the account's characters.
	 * The target's MMOCore copy is replaced, never edited, since other copies may share it.
	 * @return whether anything was raised
	 */
	static boolean raise(CharacterProgress target, Collection<CharacterProgress> account) {
		Map<String, ProfessionLevel> levels = new TreeMap<>(MmoProgressCodec.professions(target.mmo()));
		boolean levelled = false;
		boolean pointed = false;
		for (CharacterProgress other : account) {
			if (other == null || other == target) {
				continue;
			}
			for (Map.Entry<String, ProfessionLevel> profession : MmoProgressCodec.professions(other.mmo()).entrySet()) {
				if (profession.getValue().isAbove(levels.get(profession.getKey()))) {
					levels.put(profession.getKey(), profession.getValue());
					levelled = true;
				}
			}
			for (Map.Entry<String, Integer> points : other.getProfessionPointsMap().entrySet()) {
				if (points.getValue() > target.getProfessionPoints(points.getKey())) {
					target.setProfessionPoints(points.getKey(), points.getValue());
					pointed = true;
				}
			}
		}
		if (levelled) {
			target.setMmo(MmoProgressCodec.withProfessions(target.mmo(), levels));
		}
		return levelled || pointed;
	}
}
