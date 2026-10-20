package net.tfminecraft.rpcharacters.progress;

import net.tfminecraft.rpcharacters.objects.PlayerData;

/**
 * Skill and attribute points from items such as tomes belong to the account. Every character holds all of
 * them on top of the points it earned itself: the live character as they are granted, the others at their
 * next activation, new characters included. Each character counts how many it holds, so none arrive twice.
 * While progress is shared the account total already reaches every character, so items add to it as before.
 */
public final class ItemPoints {
	private ItemPoints() {}

	public static void grantSkillPoints(PlayerData account, ProgressPools pools, int amount) {
		if (pools instanceof CharacterProgress progress) {
			account.addItemSkillPoints(amount);
			catchUp(account, progress);
		} else {
			pools.addSkillPoints(amount);
		}
	}

	public static void grantAttributePoints(PlayerData account, ProgressPools pools, int amount) {
		if (pools instanceof CharacterProgress progress) {
			account.addItemAttributePoints(amount);
			catchUp(account, progress);
		} else {
			pools.addAttributePoints(amount);
		}
	}

	/** @return whether the character received any points */
	static boolean catchUp(PlayerData account, CharacterProgress progress) {
		int skill = account.getItemSkillPoints() - progress.getItemSkillPoints();
		if (skill > 0) {
			progress.addSkillPoints(skill);
			progress.setItemSkillPoints(account.getItemSkillPoints());
		}
		int attribute = account.getItemAttributePoints() - progress.getItemAttributePoints();
		if (attribute > 0) {
			progress.addAttributePoints(attribute);
			progress.setItemAttributePoints(account.getItemAttributePoints());
		}
		return skill > 0 || attribute > 0;
	}
}
