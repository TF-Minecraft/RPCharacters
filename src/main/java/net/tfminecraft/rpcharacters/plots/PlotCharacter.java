package net.tfminecraft.rpcharacters.plots;

import java.util.UUID;

/** A roleplay character in a plot: the character, the account that plays it and its name when captured. */
public final class PlotCharacter {
    public String characterId;
    public UUID playerId;
    public String playerName;
    public String name;

    public PlotCharacter() {}

    public PlotCharacter(String characterId, UUID playerId, String playerName, String name) {
        this.characterId = characterId;
        this.playerId = playerId;
        this.playerName = playerName;
        this.name = name;
    }

    public boolean is(String id) { return id != null && id.equals(characterId); }
}
