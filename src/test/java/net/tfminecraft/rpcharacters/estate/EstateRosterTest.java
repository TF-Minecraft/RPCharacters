package net.tfminecraft.rpcharacters.estate;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.json.simple.JSONObject;
import org.junit.jupiter.api.Test;

import net.tfminecraft.tlibs.character.estate.EstateState;

class EstateRosterTest {
    @Test
    void decidedEstatesCarryRecipientsMoneyAndCountsButNoMoneyCountsForTheMemorial() {
        EstateRecord record = EstateBookTest.full();
        JSONObject estate = EstateRoster.estate(record);
        assertEquals(record.deathId.toString(), estate.get("death_id"));
        assertEquals("DECIDED", estate.get("status"));
        assertEquals(record.dueAt.toEpochMilli(), estate.get("due_at"));
        assertEquals(record.updatedAt.toEpochMilli(), estate.get("updated_at"));
        assertEquals(2L, estate.get("revision"));
        assertEquals("Kira", estate.get("heir_name"));
        JSONObject recipients = (JSONObject) estate.get("recipients");
        assertEquals(Map.of("kind", "CHARACTER", "name", "Kira"), recipients.get("money"));
        assertEquals("WAITING", ((JSONObject) recipients.get("property")).get("kind"));
        assertEquals("FROZEN", ((JSONObject) recipients.get("guild_locks")).get("kind"));
        assertEquals("CHARACTER", ((JSONObject) recipients.get("letters")).get("kind"));
        assertEquals(5L, estate.get("money_moved_cents"));
        JSONObject money = (JSONObject) ((JSONObject) estate.get("participants")).get("denareconomy");
        assertEquals(Map.of(), money.get("counts"));
        record.participants.put("thievery", new EstateRecord.Report("DONE", 2, null, Map.of("locks", 297), java.util.List.of(),
                EstateTestKit.T0));
        JSONObject locks = (JSONObject) ((JSONObject) EstateRoster.estate(record).get("participants")).get("thievery");
        assertEquals(Map.of("locks", 297L), locks.get("counts"));
        assertEquals("DONE", money.get("outcome"));
    }

    @Test
    void openEstatesCarryOnlyTheirClockAndAMissingMoneyAnswerLeavesNoTotal() {
        EstateRecord record = EstateBookTest.full();
        record.state = EstateState.OPEN;
        record.decision = null;
        record.updatedAt = null;
        JSONObject open = EstateRoster.estate(record);
        assertEquals(0L, open.get("revision"));
        assertNull(open.get("updated_at"));
        assertFalse(open.containsKey("recipients"));
        EstateRecord noMoney = EstateBookTest.full();
        noMoney.participants.remove("denareconomy");
        assertFalse(EstateRoster.estate(noMoney).containsKey("money_moved_cents"));
        noMoney.participants.put("denareconomy", new EstateRecord.Report("RETRY", 2, "x", Map.of(), java.util.List.of(),
                EstateTestKit.T0));
        assertFalse(EstateRoster.estate(noMoney).containsKey("money_moved_cents"));
    }
}
