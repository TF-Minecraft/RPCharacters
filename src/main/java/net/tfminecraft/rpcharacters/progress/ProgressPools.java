package net.tfminecraft.rpcharacters.progress;

/**
 * Lifetime point pools: skill points with the exp-table reward watermark, attribute points and
 * profession points. The account holds them while progress is shared; each character holds its own
 * once progress is per character.
 */
public interface ProgressPools {
	/** "account" or "character", for staff messages. */
	String holder();

	int getSkillPointsTotal();

	void setSkillPointsTotal(int total);

	void addSkillPoints(int amount);

	/** Skill points from claimed exp-table rewards already added to the skill total. */
	int getRewardSkillPoints();

	void setRewardSkillPoints(int credited);

	int getAttributePointsTotal();

	void setAttributePointsTotal(int total);

	void addAttributePoints(int amount);

	int getProfessionPoints(String professionId);

	void addProfessionPoints(String professionId, int amount);
}
