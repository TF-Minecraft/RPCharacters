package net.tfminecraft.rpcharacters.database;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.*;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.ingest.RosterPushPolicy;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.paidchange.DenarWallet.Account;
import net.tfminecraft.rpcharacters.paidchange.PendingPaidChange;
import net.tfminecraft.rpcharacters.tutorial.TutorialService;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

class CharacterFieldsRuntimeTest {
    RuntimeTestState state;

    @BeforeEach void setup() {
        MockBukkit.mock(); state = new RuntimeTestState(); Cache.attributes = new ArrayList<>(); Cache.professions = new ArrayList<>();
    }
    @AfterEach void cleanup() { state.close(); MockBukkit.unmock(); }
    JSONObject json(Map<?, ?> values) { return new JSONObject(values); }

    @Test void nutritionSupportsRawOnlyLegacyStringsAndPreservesUnrelatedFields() {
        var character = new RPCharacter(null); character.setRawDietScore(12); character.setDietScore(10);
        CharacterNutritionFields.load(null, json(Map.of())); CharacterNutritionFields.load(character, null);
        assertEquals(12, character.getRawDietScore());
        CharacterNutritionFields.load(character, json(Map.of("raw-diet-score", "82")));
        assertEquals(82, character.getRawDietScore()); assertEquals(40, character.getDietScore());
        character.setLastDietTierId("healthy"); var noTier = new LinkedHashMap<String, Object>(); noTier.put("last-diet-tier", null);
        CharacterNutritionFields.load(character, json(noTier)); assertEquals("healthy", character.getLastDietTierId());
        character.setFoodValue(12); var saved = new LinkedHashMap<String, Object>(); saved.put("unrelated", "keep");
        CharacterNutritionFields.save(null, character); CharacterNutritionFields.save(saved, null); assertEquals(Map.of("unrelated", "keep"), saved);
        CharacterNutritionFields.save(saved, character); assertEquals(12, saved.get("food-value"));
        assertEquals(82, saved.get("raw-diet-score")); assertEquals(40, saved.get("diet-score")); assertEquals("healthy", saved.get("last-diet-tier"));
        character.setLastDietTierId(" "); var blank = new HashMap<String, Object>(); CharacterNutritionFields.save(blank, character);
        assertFalse(blank.containsKey("last-diet-tier")); assertEquals("keep", saved.get("unrelated"));
    }

    @Test void invalidNutritionTextFailsWithoutInventingAReplacementValue() {
        var character = new RPCharacter(null); character.setRawDietScore(15); character.setDietScore(15);
        assertThrows(NumberFormatException.class, () -> CharacterNutritionFields.load(character, json(Map.of("raw-diet-score", "bad"))));
        assertEquals(15, character.getRawDietScore()); assertEquals(15, character.getDietScore());
    }

    @Test void oversizedNutritionValuesCannotWrapTheSavedDietNegative() {
        var character = new RPCharacter(null);
        CharacterNutritionFields.load(character, json(Map.of("raw-diet-score", Long.MAX_VALUE, "diet-score", Long.MAX_VALUE)));
        assertEquals(Integer.MAX_VALUE, character.getRawDietScore()); assertEquals(40, character.getDietScore());
    }

    @Test void malformedPendingPaymentsAreDiscardedWithoutLosingValidStageMetadata() {
        var character = new RPCharacter(null); character.setPaidChangeCount("class", 2);
        CharacterStageChangeFields.load(null, json(Map.of())); CharacterStageChangeFields.load(character, null);
        assertEquals(2, character.getPaidChangeCount("class"));
        for (Map<String, Object> pending : List.of(Map.<String, Object>of("amount", "bad"),
            Map.<String, Object>of("amount", "12.34", "payer", "bad-uuid"), Map.<String, Object>of("account", "UNKNOWN"))) {
            CharacterStageChangeFields.load(character, json(Map.of("paid-changes", Map.of("class", 3L, "ignored", "bad"),
                "stage-revisions", Map.of("class", Map.of("revision", 4L), "ignored", "bad"), "paid-change-pending", pending)));
            assertNull(character.getPendingPaidChange()); assertEquals(3, character.getPaidChangeCount("class"));
            assertEquals(4, character.getStageRevision("class")); assertEquals(0, character.getStageRevisionSince("class"));
        }
    }

    @Test void paymentAndRevisionStateRoundTripsThroughJsonWithoutLosingDecimalAmounts() throws Exception {
        var character = new RPCharacter(null); var payer = UUID.randomUUID();
        character.setPaidChangeCount("class", 2); character.setStageRevision("class", 3, 1234);
        for (Account account : Arrays.asList(Account.BANK, null)) {
            var pending = new PendingPaidChange("class", "Class", payer, account, new BigDecimal("12.34"), "snapshot");
            character.setPendingPaidChange(pending); var saved = new LinkedHashMap<String, Object>(); CharacterStageChangeFields.save(saved, character);
            var loaded = new RPCharacter(null); CharacterStageChangeFields.load(loaded, (JSONObject) new JSONParser().parse(json(saved).toJSONString()));
            assertEquals(pending, loaded.getPendingPaidChange()); assertEquals(2, loaded.getPaidChangeCount("class"));
            assertEquals(3, loaded.getStageRevision("class")); assertEquals(1234, loaded.getStageRevisionSince("class"));
        }
    }

    @Test void classPickStateRoundTripsAndIsOmittedUntilUsed() {
        var character = new RPCharacter(null); var saved = new HashMap<String, Object>();
        CharacterClassPickFields.save(saved, character); assertFalse(saved.containsKey("class-picks"));
        character.setSubclassPicked(true); character.setPaidClassPicks(2); CharacterClassPickFields.save(saved, character);
        var loaded = new RPCharacter(null); CharacterClassPickFields.load(loaded, json(saved));
        assertTrue(loaded.hasPickedSubclass()); assertEquals(2, loaded.getPaidClassPicks());
        var paidOnly = new RPCharacter(null); paidOnly.setPaidClassPicks(1); var paidSaved = new HashMap<String, Object>();
        CharacterClassPickFields.save(paidSaved, paidOnly); assertEquals(Map.of("subclass-picked", false, "paid", 1), paidSaved.get("class-picks"));
        var untouched = new RPCharacter(null);
        CharacterClassPickFields.load(null, json(saved)); CharacterClassPickFields.load(untouched, null);
        CharacterClassPickFields.load(untouched, json(Map.of("class-picks", "broken")));
        CharacterClassPickFields.load(untouched, json(Map.of("class-picks", Map.of("paid", "many"))));
        assertFalse(untouched.hasPickedSubclass()); assertEquals(0, untouched.getPaidClassPicks());
    }

    @Test void oversizedPaidChangeCountCannotResetThePriceHistory() {
        var character = new RPCharacter(null);
        CharacterStageChangeFields.load(character, json(Map.of("paid-changes", Map.of("class", Long.MAX_VALUE))));
        assertEquals(Integer.MAX_VALUE, character.getPaidChangeCount("class"));
    }

    @Test void oversizedStageRevisionCannotBecomeAnOlderRevision() {
        var character = new RPCharacter(null);
        CharacterStageChangeFields.load(character, json(Map.of("stage-revisions", Map.of("class", Map.of("revision", Long.MAX_VALUE, "since", 1234L)))));
        assertEquals(Integer.MAX_VALUE, character.getStageRevision("class")); assertEquals(1234, character.getStageRevisionSince("class"));
    }

    @Test void tutorialFieldsTolerateMissingRecordsAndRetainLegacyDismissals() {
        var account = new PlayerData(UUID.randomUUID()); account.setTutorialDismissed("existing", true);
        PlayerTutorialFields.load(null, json(Map.of())); PlayerTutorialFields.load(account, null);
        assertTrue(account.hasDismissedTutorial("existing"));
        PlayerTutorialFields.load(account, json(Map.of("dismissed-tutorials", Arrays.asList("EVIL-RP", null, " "),
            "permadeath-tutorial-dismissed", true)));
        assertEquals(Set.of("existing", TutorialService.EVIL_RP, TutorialService.PERMADEATH_ZONE), account.getDismissedTutorials());
        var saved = new HashMap<String, Object>(); PlayerTutorialFields.save(saved, account);
        var reloaded = new PlayerData(UUID.randomUUID()); PlayerTutorialFields.load(reloaded, json(saved));
        assertEquals(account.getDismissedTutorials(), reloaded.getDismissedTutorials());
        var empty = new HashMap<String, Object>(); PlayerTutorialFields.save(empty, new PlayerData(UUID.randomUUID())); assertTrue(empty.isEmpty());
    }

    @Test void rosterSafetyIgnoresBlankFileIdsButStillRequiresEveryActualCharacter() {
        assertFalse(RosterPushPolicy.wouldDropSavedCharacters(Set.of("alice"), Arrays.asList(null, "", " ", "alice")));
        assertFalse(RosterPushPolicy.wouldDropSavedCharacters(null, Arrays.asList(null, " ")));
        assertTrue(RosterPushPolicy.wouldDropSavedCharacters(null, Arrays.asList(null, "alice")));
        assertTrue(RosterPushPolicy.wouldDropSavedCharacters(Set.of("alice"), List.of("alice", "bob")));
    }
}
