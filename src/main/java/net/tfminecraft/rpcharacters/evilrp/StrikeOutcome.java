package net.tfminecraft.rpcharacters.evilrp;

/** What a character's strike count costs them. Three strikes and the character dies. */
public enum StrikeOutcome {
	HEALING_INJURY,
	PERMANENT_INJURY,
	DEATH;

	public static final int MAX_STRIKES = 3;

	public static StrikeOutcome forStrike(int strikeNumber) {
		if (strikeNumber >= MAX_STRIKES) {
			return DEATH;
		}
		return strikeNumber == 2 ? PERMANENT_INJURY : HEALING_INJURY;
	}
}
