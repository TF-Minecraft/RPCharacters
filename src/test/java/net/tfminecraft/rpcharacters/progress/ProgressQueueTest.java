package net.tfminecraft.rpcharacters.progress;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Checkpoints written on the progress writer: order, newest text, reads, failures and shutdown. */
class ProgressQueueTest extends ProgressFixture {
    private final List<ProgressStore> opened = new ArrayList<>();
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<String> writers = new CopyOnWriteArrayList<>();

    @AfterEach void closeStores() {
        release.countDown();
        opened.forEach(ProgressStore::close);
    }

    private ProgressStore queued(Executor writer) {
        ProgressStore queued = new ProgressStore(root.resolve("progress"), writer);
        opened.add(queued);
        return queued;
    }

    // Each write records its thread, and the first waits in the writer until released.
    private void hold(ProgressStore held) {
        held.beforeWrite = () -> {
            writers.add(Thread.currentThread().getName());
            entered.countDown();
            await(release);
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    private void releaseSoon() {
        new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            release.countDown();
        }).start();
    }

    private Path previous(Path primary) {
        return primary.resolveSibling(primary.getFileName() + ".prev");
    }

    @Test void checkpointsKeepTheirOrderTheNewestWaitingTextWinsAndReadsWaitForThem() throws Exception {
        var id = character().getId();
        var pool = Executors.newSingleThreadExecutor();
        var held = queued(pool);
        var value = progress(id); held.create(value);
        Path primary = held.characterPath(account.getUniqueId(), id);
        hold(held);

        value.addSkillPoints(1); var first = held.queueReplace(value);
        assertEquals(2, value.getRevision(), "the copy moves on at once");
        await(entered);
        // Revision 2 is being written, so 3 queues behind it; 4 then replaces 3's text while it waits.
        value.addSkillPoints(1); var second = held.queueReplace(value);
        value.addSkillPoints(1); var third = held.queueReplace(value);
        assertSame(second, third); assertNotSame(first, second);
        assertEquals(4, value.getRevision());
        assertTrue(Files.readString(primary).contains("revision: 1"), "nothing is on disk yet");
        assertThrows(IOException.class, () -> held.queueReplace(progress(id)), "a copy behind the queue is refused at once");

        releaseSoon();
        var read = held.read(account.getUniqueId(), id).orElseThrow();
        assertEquals(4, read.getRevision(), "a read waits for the file's queued writes");
        assertEquals(3, read.getSkillPointsTotal());
        assertTrue(first.isDone() && !first.isCompletedExceptionally() && second.isDone());
        assertTrue(Files.readString(previous(primary)).contains("revision: 2"), ".prev is the revision the last write replaced");
        assertEquals(2, writers.size(), "two writes for three checkpoints");
        assertTrue(held.queueReplace(value).isDone(), "unchanged content queues nothing");
        assertEquals(4, value.getRevision());
        pool.shutdown();
    }

    @Test void aWriterThatRefusesTheTaskLeavesNothingQueuedAndTheCopyUnmoved() throws Exception {
        var id = character().getId();
        var refusing = new java.util.concurrent.atomic.AtomicBoolean(true);
        var store = queued(task -> {
            if (refusing.get()) throw new java.util.concurrent.RejectedExecutionException("writer gone");
            task.run();
        });
        var value = progress(id); store.create(value);

        value.addSkillPoints(1);
        assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> store.queueReplace(value));
        assertEquals(1, value.getRevision(), "the copy keeps its revision when nothing will write it");
        assertEquals(1, store.read(account.getUniqueId(), id).orElseThrow().getRevision(), "a read does not wait on it");

        refusing.set(false);
        assertFalse(store.queueReplace(value).isCompletedExceptionally(), "the next checkpoint writes normally");
        assertEquals(2, store.read(account.getUniqueId(), id).orElseThrow().getRevision());
        assertEquals(1, store.read(account.getUniqueId(), id).orElseThrow().getSkillPointsTotal());
    }

    @Test void aFailedCheckpointIsReportedAndTheNextWriteOfThatCopyResumesFromTheDisk() throws Exception {
        var id = character().getId();
        var inline = queued(Runnable::run);
        var value = progress(id); inline.create(value);
        Path primary = inline.characterPath(account.getUniqueId(), id); Path previous = previous(primary);
        Files.delete(previous); Files.createDirectory(previous); Files.writeString(previous.resolve("occupied"), "keep");

        value.addSkillPoints(1); var failed = inline.queueReplace(value);
        var error = assertThrows(ExecutionException.class, failed::get);
        assertInstanceOf(IOException.class, error.getCause());
        assertEquals(2, value.getRevision(), "the copy keeps the revision it claimed");
        // Unchanged, but tried again; it fails again, and the disk may still be at revision 1.
        assertTrue(inline.queueReplace(value).isCompletedExceptionally());
        assertEquals(3, value.getRevision());
        Files.delete(previous.resolve("occupied")); Files.delete(previous);

        assertFalse(inline.queueReplace(value).isCompletedExceptionally(), "resumes from revision 1");
        assertEquals(4, value.getRevision());
        assertEquals(4, inline.read(account.getUniqueId(), id).orElseThrow().getRevision());
        assertEquals(1, inline.read(account.getUniqueId(), id).orElseThrow().getSkillPointsTotal());
        assertTrue(inline.queueReplace(value).isDone());
        assertEquals(4, value.getRevision(), "once written, unchanged content is not written again");
    }

    @Test void aTransitionAfterAFailedCheckpointWritesTheLatestWhileStaleCopiesAreStillRefused() throws Exception {
        var id = character().getId();
        var inline = queued(Runnable::run);
        var value = progress(id); inline.create(value);
        var stale = inline.read(account.getUniqueId(), id).orElseThrow();
        Path primary = inline.characterPath(account.getUniqueId(), id); Path previous = previous(primary);
        Files.delete(previous); Files.createDirectory(previous); Files.writeString(previous.resolve("occupied"), "keep");
        value.addSkillPoints(1);
        assertTrue(inline.queueReplace(value).isCompletedExceptionally());
        Files.delete(previous.resolve("occupied")); Files.delete(previous);

        inline.replace(value);
        assertEquals(3, value.getRevision(), "written although unchanged since the failed checkpoint");
        assertEquals(1, inline.read(account.getUniqueId(), id).orElseThrow().getSkillPointsTotal());
        inline.replace(value);
        assertEquals(3, value.getRevision());

        stale.addSkillPoints(5);
        var refused = assertThrows(ExecutionException.class, () -> inline.queueReplace(stale).get());
        assertTrue(refused.getCause().getMessage().contains("is at revision 3, not 1"), refused.getCause().getMessage());
        value.addSkillPoints(1); inline.replace(value);
        assertEquals(4, inline.read(account.getUniqueId(), id).orElseThrow().getRevision());

        var missing = progress(java.util.UUID.randomUUID().toString()); missing.addSkillPoints(1);
        assertTrue(inline.queueReplace(missing).isCompletedExceptionally());
        Files.writeString(primary, Files.readString(primary).replaceAll("(?m)^revision:.*$", "revision: " + Long.MAX_VALUE));
        var max = inline.read(account.getUniqueId(), id).orElseThrow();
        assertTrue(inline.queueReplace(max).isDone(), "nothing to write");
        max.addSkillPoints(1);
        assertThrows(IOException.class, () -> inline.queueReplace(max));
    }

    @Test void writeFailuresMarkTheOldestRevisionTheDiskMayHold() throws Exception {
        var id = character().getId();
        var inline = queued(Runnable::run);
        var value = progress(id); inline.create(value);
        Path previous = previous(inline.characterPath(account.getUniqueId(), id));
        Files.delete(previous); Files.createDirectory(previous); Files.writeString(previous.resolve("occupied"), "keep");
        for (int i = 0; i < 3; i++) {
            value.addSkillPoints(1);
            assertTrue(inline.queueReplace(value).isCompletedExceptionally());
        }
        Files.delete(previous.resolve("occupied")); Files.delete(previous);
        value.addSkillPoints(1);
        inline.queueReplace(value).get();
        assertEquals(5, inline.read(account.getUniqueId(), id).orElseThrow().getRevision());
        assertEquals(4, inline.read(account.getUniqueId(), id).orElseThrow().getSkillPointsTotal());
    }

    @Test void closeWaitsForQueuedCheckpointsAndLaterOnesAreWrittenOnTheCallingThread() throws Exception {
        var id = character().getId();
        var threaded = new ProgressStore(root.resolve("progress"));
        opened.add(threaded);
        var value = progress(id); threaded.create(value);
        hold(threaded);
        value.addSkillPoints(1); var written = threaded.queueReplace(value);
        await(entered);
        Thread closing = new Thread(threaded::close);
        closing.start();
        closing.join(200);
        assertTrue(closing.isAlive(), "close waits for the held write");
        release.countDown();
        closing.join(10_000);
        assertFalse(closing.isAlive());
        assertTrue(written.isDone() && !written.isCompletedExceptionally());
        assertEquals(List.of("RPCharacters progress writer"), writers);

        value.addSkillPoints(1);
        assertTrue(threaded.queueReplace(value).isDone(), "written before it returns");
        assertEquals(3, threaded.read(account.getUniqueId(), id).orElseThrow().getRevision());
        assertEquals(1, writers.size(), "not through the writer");
        assertThrows(IOException.class, () -> threaded.queueReplace(progress(id)));
    }
}
