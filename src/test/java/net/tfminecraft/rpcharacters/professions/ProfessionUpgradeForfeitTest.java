package net.tfminecraft.rpcharacters.professions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.tfminecraft.rpcharacters.objects.RPCharacter;

class ProfessionUpgradeForfeitTest {

    private final ProfessionUpgradeDefinition apprentice = upgrade("smith_1", "Blacksmith", 3);
    private final ProfessionUpgradeDefinition journeyman = upgrade("smith_2", "Blacksmith", 5);

    @BeforeEach
    void registerUpgrades() {
        ProfessionRegistry.setUpgrades(List.of(apprentice, journeyman));
    }

    @AfterEach
    void clearRegistry() {
        ProfessionRegistry.clear();
    }

    @Test
    void aRemovedUpgradeStaysSpentOnItsProfession() {
        RPCharacter character = new RPCharacter(null);
        character.addProfessionUpgrade(apprentice.getId());
        character.addProfessionUpgrade(journeyman.getId());

        character.forfeitProfessionUpgrade(journeyman);

        assertFalse(character.hasProfessionUpgrade(journeyman.getId()));
        assertEquals(8, character.getSpentPointsOnProfession("blacksmith"));
        assertEquals(3, character.getTotalSpentPoints(), "forfeits must not count towards the upgrade cap");
    }

    @Test
    void rebuyingAForfeitedUpgradeCostsItsPointsAgain() {
        RPCharacter character = new RPCharacter(null);
        character.addProfessionUpgrade(apprentice.getId());
        character.forfeitProfessionUpgrade(apprentice);
        character.addProfessionUpgrade(apprentice.getId());

        assertEquals(6, character.getSpentPointsOnProfession("Blacksmith"));
    }

    @Test
    void forfeitingAnUpgradeThatIsNotHeldCostsNothing() {
        RPCharacter character = new RPCharacter(null);

        character.forfeitProfessionUpgrade(apprentice);

        assertEquals(0, character.getSpentPointsOnProfession("blacksmith"));
        assertEquals(Map.of(), character.getForfeitedProfessionPoints());
    }

    @Test
    void anAdminRemovalStillGivesThePointsBack() {
        RPCharacter character = new RPCharacter(null);
        character.addProfessionUpgrade(apprentice.getId());

        character.removeProfessionUpgrade(apprentice.getId());

        assertEquals(0, character.getSpentPointsOnProfession("blacksmith"));
    }

    @Test
    void clearingForfeitsRestoresTheFullRefund() {
        RPCharacter character = new RPCharacter(null);
        character.setForfeitedProfessionPoints(Map.of("Blacksmith", 4, "chef", 0));

        assertEquals(Map.of("blacksmith", 4), character.getForfeitedProfessionPoints());
        character.clearForfeitedProfessionPoints();
        assertEquals(0, character.getSpentPointsOnProfession("blacksmith"));
    }

    private static ProfessionUpgradeDefinition upgrade(String id, String professionId, int cost) {
        return new ProfessionUpgradeDefinition(id, professionId, null, cost, "perk", List.of(), List.of());
    }
}
