package net.tfminecraft.rpcharacters.will;

import java.util.UUID;

import org.json.simple.JSONObject;

import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.estate.EstateHooks;
import net.tfminecraft.rpcharacters.estate.EstateLink;
import net.tfminecraft.rpcharacters.identity.CharacterDirectory;
import net.tfminecraft.rpcharacters.objects.RPCharacter;

/** Roster row fields for deaths, wills and estates, in the shape ProvinceSystem's contract fixes. */
public final class WillRoster {
    private WillRoster() {}

    @SuppressWarnings("unchecked")
    public static void append(JSONObject row, RPCharacter character) {
        UUID deathId = character.getDeathId();
        if (deathId == null && character.getStatus() == Status.DEAD) {
            deathId = CharacterDirectory.legacyDeathId(UUID.fromString(character.getId()));
        }
        row.put("death_id", deathId == null ? null : deathId.toString());
        row.put("died_at", character.getDiedAt() == null ? null : character.getDiedAt().toEpochMilli());
        CharacterWill will = character.getWill();
        row.put("will_heir_id", will.heir() == null ? null : will.heir().toString());
        row.put("will_heir_name", WillService.heirName(will));
        row.put("will_revision", will.revision());
        EstateHooks estates = EstateLink.current();
        if (estates != null && deathId != null) estates.appendRoster(row, deathId);
    }
}
