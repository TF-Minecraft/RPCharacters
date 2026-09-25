package net.tfminecraft.rpcharacters.pvp;

import java.util.Locale;

/** What the killer picks after a /pvp start win. */
public enum StrikeChoice {
	SPARE,
	/** A strike, or a Kill when the next strike would kill. */
	STRIKE,
	/** A healing injury with no strike, offered instead of being forced to Kill. */
	WOUND,
	/** A permanent injury with no strike, offered instead of being forced to Kill. */
	MAIM;

	/** Wound and Maim only appear when the only strike left would kill. */
	public boolean isOffered(boolean strikeKills) {
		return this == SPARE || this == STRIKE || strikeKills;
	}

	public static StrikeChoice fromCommand(String label) {
		return switch (label.toLowerCase(Locale.ROOT)) {
			case "spare" -> SPARE;
			case "strike", "kill" -> STRIKE;
			case "wound" -> WOUND;
			case "maim" -> MAIM;
			default -> null;
		};
	}
}
