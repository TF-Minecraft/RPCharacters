package net.tfminecraft.rpcharacters.estate;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.tfminecraft.rpcharacters.estate.EstateRecord.Body;
import net.tfminecraft.rpcharacters.estate.EstateRecord.Decision;
import net.tfminecraft.rpcharacters.estate.EstateRecord.Named;
import net.tfminecraft.rpcharacters.estate.EstateRecord.Part;
import net.tfminecraft.rpcharacters.estate.EstateRecord.Report;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.estate.EstateState;

class EstateBookTest {
    @TempDir Path root;
    private final EstateTestKit.MovingClock clock = new EstateTestKit.MovingClock();
    private final Logger logger = Logger.getLogger("EstateBookTest");

    private static CharacterRef ref() {
        return new CharacterRef(UUID.randomUUID(), UUID.randomUUID());
    }

    /** Every field set, so a round trip proves nothing is dropped. */
    static EstateRecord full() {
        EstateRecord r = new EstateRecord(UUID.randomUUID(), ref());
        Named heir = new Named(ref(), "Kira");
        r.name = "Aria";
        r.diedAt = EstateTestKit.T0;
        r.createdAt = EstateTestKit.T0;
        r.dueAt = EstateTestKit.T0.plus(Duration.ofDays(1));
        r.state = EstateState.DECIDED;
        r.heldLeftMs = 5L;
        r.willHeir = heir.ref().characterId();
        r.willRevision = 4;
        r.wasActive = false;
        r.cause = "STRIKES";
        r.killerAccount = UUID.randomUUID();
        r.killer = ref();
        r.postponedSince = EstateTestKit.T0;
        r.postponeReason = "waiting";
        r.noGuild = true;
        r.decision = new Decision(EstateTestKit.T0, 2, heir, UUID.randomUUID(), UUID.randomUUID(), Part.bank(heir),
                Part.WAITING, Part.FROZEN, Part.character(heir));
        r.participants.put("denareconomy", new Report("DONE", 2, null, Map.of("cents", 5, "postings", 1),
                List.of("estate:x:1"), EstateTestKit.T0));
        r.participants.put("bartershops", new Report("BLOCKED", 1, "intent", Map.of(), List.of(), EstateTestKit.T0));
        r.body = new Body();
        r.body.state = "COLLECTABLE";
        r.body.expectedRev = 7;
        r.body.stacks = new ArrayList<>(List.of("AAEC", "AwQF"));
        r.body.addressedTo = heir;
        r.body.readdressVia = UUID.randomUUID();
        r.body.delivered = 3;
        r.revivedAt = EstateTestKit.T0;
        r.revivedNote = "note";
        r.cancelledAt = EstateTestKit.T0;
        r.revertedAt = EstateTestKit.T0;
        r.updatedAt = EstateTestKit.T0;
        return r;
    }

    @Test
    void everyFieldSurvivesARoundTripAndEditsAreCopies() throws IOException {
        EstateRecord record = full();
        String json = EstateJson.write(record);
        assertEquals(json, EstateJson.write(EstateJson.read(json)));
        EstateRecord copy = EstateBook.edit(record);
        copy.body.stacks.clear();
        assertEquals(2, record.body.stacks.size());
        EstateRecord sparse = new EstateRecord(UUID.randomUUID(), ref());
        sparse.name = "Bran";
        sparse.createdAt = EstateTestKit.T0;
        sparse.dueAt = EstateTestKit.T0;
        sparse.decision = new Decision(EstateTestKit.T0, 0, null, null, null, Part.FROZEN, Part.FROZEN, Part.FROZEN,
                Part.SENDER);
        assertEquals(EstateJson.write(sparse), EstateJson.write(EstateJson.read(EstateJson.write(sparse))));
    }

    @Test
    void malformedRecordsAreRefusedWhole() {
        String good = EstateJson.write(full());
        assertThrows(IOException.class, () -> EstateJson.read(good.replace("\"format\": 1", "\"format\": 2")));
        assertThrows(IOException.class, () -> EstateJson.read("[]"));
        EstateRecord undecided = full();
        undecided.decision = null;
        assertThrows(IOException.class, () -> EstateJson.read(EstateJson.write(undecided)));
        String badMoney = good.replaceFirst("\"kind\": \"ACCOUNT\"", "\"kind\": \"SENDER\"");
        assertThrows(IOException.class, () -> EstateJson.read(badMoney));
    }

    @Test
    void loadIndexesGoodRecordsAndKeepsUnreadableDeathsOutOfReach() throws IOException {
        EstateBook book = new EstateBook(root.resolve("estates"), DurableFiles.DISK, clock, logger);
        book.load();
        assertTrue(book.all().isEmpty());
        EstateRecord later = full();
        later.createdAt = EstateTestKit.T0.plusSeconds(60);
        EstateRecord earlier = full();
        book.save(later);
        book.save(earlier);
        UUID wrongName = UUID.randomUUID();
        Files.writeString(root.resolve("estates/" + wrongName + ".json"), EstateJson.write(full()));
        Files.writeString(root.resolve("estates/notes.json"), "{}");
        Files.writeString(root.resolve("estates/readme.txt"), "ignored");
        book.load();
        assertEquals(List.of(earlier.deathId, later.deathId), book.all().stream().map(r -> r.deathId).toList());
        assertTrue(book.unreadable(wrongName));
        assertFalse(book.unreadable(earlier.deathId));
        assertEquals(clock.instant(), book.get(earlier.deathId).orElseThrow().updatedAt);
    }

    @Test
    void aFailedSaveLeavesTheStoredRecordAndNoTemporaryFile() throws IOException {
        FlakyFiles files = new FlakyFiles();
        EstateBook book = new EstateBook(root.resolve("estates"), files, clock, logger);
        EstateRecord record = full();
        book.save(record);
        EstateRecord next = EstateBook.edit(record);
        next.name = "Changed";
        files.failMoves = true;
        assertThrows(IOException.class, () -> book.save(next));
        assertEquals("Aria", book.get(record.deathId).orElseThrow().name);
        try (var listing = Files.list(root.resolve("estates"))) {
            assertEquals(List.of(record.deathId + ".json"), listing.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void logsAppendBesideTheRecordsAndAFailedAppendIsOnlyLogged() throws IOException {
        FlakyFiles files = new FlakyFiles();
        EstateBook book = new EstateBook(root.resolve("fresh/estates"), files, clock, logger);
        book.log("audit.log", "first");
        book.log("audit.log", "second");
        assertEquals(List.of(clock.instant() + " first", clock.instant() + " second"),
                Files.readAllLines(root.resolve("fresh/estates/audit.log")));
        files.failAppends = true;
        assertDoesNotThrow(() -> book.log("audit.log", "lost"));
    }
}
