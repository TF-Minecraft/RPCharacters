package net.tfminecraft.rpcharacters.plots;

import org.bukkit.entity.Player;

/** For other plugins: SimpleFactions looks this up at runtime, so it needs no newer RPCharacters to build. */
public final class PlotsApi {
    private PlotsApi() {}

    /** True while the player's character is an assassin out on a hunt; they may not join a warband or battle. */
    public static boolean hunting(Player player) {
        return PlotHunt.hunting(player);
    }

    /** The player's active character's Intrigue, rolled now on first use; null without a living active character. */
    public static Integer intrigue(Player player) {
        PlotCharacter character = PlotCharacters.active(player);
        if (character == null) return null;
        try {
            return Intrigue.of(player, character.characterId);
        } catch (java.io.IOException | RuntimeException error) {
            return null;
        }
    }
}
