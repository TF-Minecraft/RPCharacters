package net.tfminecraft.rpcharacters.estate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.json.simple.JSONObject;

import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.tlibs.character.CharacterRef;

/**
 * What the rest of RPCharacters asks of estates. Its signatures name no TLibs 2.6.0 type, so callers load
 * on an older TLibs, where {@link EstateLink#current()} is null and nothing here runs.
 */
public interface EstateHooks {
    /** Inside the kill, right after the durable death save. Never throws. */
    void died(UUID owner, RPCharacter character, boolean wasActive, String cause, UUID killerAccount, CharacterRef killer);

    /** Why a revive of this death must wait; empty when it may go ahead. */
    List<String> reviveBlockers(UUID deathId);

    /** After the durable ALIVE save: updates the estate and fires the revive event. */
    void revived(CharacterRef character, UUID previousDeathId, boolean forced, String by);

    /** Adds the {@code estate} object for this death to a roster row, when there is a record. */
    void appendRoster(JSONObject row, UUID deathId);

    /** What one participant says a character holds today: counts by kind, and readable labels. */
    record Held(String participant, Map<String, Integer> counts, List<String> labels) {}

    /** For the will screen: what each participant says the character holds today. */
    List<Held> holdings(UUID characterId);

    /** Unregisters services and stops the clocks. */
    void stop();
}
