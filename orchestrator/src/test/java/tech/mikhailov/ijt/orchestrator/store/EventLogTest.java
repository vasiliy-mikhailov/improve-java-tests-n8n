package tech.mikhailov.ijt.orchestrator.store;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The event log, and the two rules about it that cost real runs.
///
/// `seq` only ever rises, and clearing the log at run start is a SCOPED delete. They pull in
/// opposite directions — the second removes exactly the rows the naive implementation of the
/// first would count — which is why they are pinned together here.
class EventLogTest extends StoreTestSupport {

    private static final String REPO = "https://github.com/stleary/JSON-java";

    private static List<Long> seqs(List<EventRow> rows) {
        return rows.stream().map(EventRow::getSeq).toList();
    }

    @Test
    void seqStartsAtOneAndRises() {
        store.startRun(newRun("run-1", REPO));
        assertEquals(1, store.appendEvent("run-1", "starting", "one").getSeq());
        assertEquals(2, store.appendEvent("run-1", "cloning", "two").getSeq());
        assertEquals(2, store.lastSeq());
    }

    /// The rule the whole design of {@link StoreRow#getLastSeq()} exists for.
    ///
    /// Run start deletes the outgoing run's rows. A counter seeded from `max(seq)` over the
    /// table — an identity column, a database sequence reset by a truncate, `count() + 1` —
    /// rewinds when they go, and the next run re-issues numbers a connected client already
    /// holds. The client asks for "everything after 2", is handed rows 1 and 2 again, discards
    /// them as old, and its feed silently stops for the rest of the run.
    @Test
    void seqKeepsRisingAcrossARunBoundary() {
        store.startRun(newRun("run-1", REPO));
        store.appendEvent("run-1", "starting", "one");
        store.appendEvent("run-1", "cloning", "two");
        long held = store.lastSeq();          // what a connected dashboard holds

        store.startRun(newRun("run-2", REPO));

        EventRow first = store.appendEvent("run-2", "starting", "three");
        assertAll(
                () -> assertEquals(3, first.getSeq(), "seq restarted across the run boundary"),
                () -> assertTrue(store.eventsAfter("run-1", 0).isEmpty(),
                        "the previous run's log should have been cleared"),
                // and the reconnecting client is served: it asks for everything after 2 and the
                // new run's first line is 3, so it arrives.
                () -> assertEquals(List.of(3L), seqs(store.eventsAfter("run-2", held))));
    }

    /// Clearing the log means deleting THIS RUN's rows. Not the table.
    ///
    /// A blanket `delete from ijt_event` — or a `truncate` — also takes the lines written
    /// outside any run (`purged <repo>`, anything logged before the first `run/start`) and, on
    /// most databases, resets the identity the seq used to come from.
    @Test
    void clearingIsScopedToOneRun() {
        EventRow beforeAnyRun = store.appendEvent(null, "starting", "purged JSON-java");
        store.startRun(newRun("run-1", REPO));
        store.appendEvent("run-1", "cloning", "cloning JSON-java");
        store.appendEvent("run-1", "measuring_baseline", "319 units");

        store.startRun(newRun("run-2", REPO));

        assertAll(
                () -> assertTrue(store.eventsAfter("run-1", 0).isEmpty()),
                () -> assertEquals(List.of(beforeAnyRun.getSeq()), seqs(store.eventsAfter(0)),
                        "the run-less line was collateral damage — the delete was not scoped"),
                () -> assertEquals(3, store.lastSeq(), "the high-water mark followed the rows down"));
    }

    /// A restart re-reads a database holding several runs' rows. The feed must still be one
    /// run's, so the query is scoped by run and not only by seq.
    @Test
    void theFeedIsScopedToARun() {
        store.appendEvent("run-1", "cloning", "run 1 line");
        store.appendEvent("run-2", "cloning", "run 2 line");
        store.appendEvent("run-1", "verifying", "run 1 again");

        assertAll(
                () -> assertEquals(List.of(1L, 3L), seqs(store.eventsAfter("run-1", 0))),
                () -> assertEquals(List.of(2L), seqs(store.eventsAfter("run-2", 0))),
                () -> assertEquals(List.of(1L, 2L, 3L), seqs(store.eventsAfter(0))));
    }

    /// The migration reads an existing state.json, which carries a seq of its own. Numbering
    /// from 1 beside it would re-issue values a client connected across the migration holds.
    @Test
    void bumpSeqRaisesButNeverLowers() {
        store.bumpSeqTo(4210);
        assertEquals(4211, store.appendEvent("run-1", "starting", "first after migration").getSeq());

        store.bumpSeqTo(7);     // an older file, replayed second
        assertEquals(4211, store.lastSeq(), "the mark went backwards");
        assertEquals(4212, store.appendEvent("run-1", "cloning", "next").getSeq());
    }

    /// Tool output reaches the log, and git and gh echo credentials the pipeline passed them.
    /// The dashboard is not where that gets diagnosed.
    @Test
    void messagesAreRedactedAndClipped() {
        EventRow secret = store.appendEvent("run-1", "cloning",
                "fatal: could not read from https://x-access-token:ghp_0123456789abcdefghij@github.com/o/r");
        EventRow long_ = store.appendEvent("run-1", "improving_mutation", "x".repeat(900));

        assertAll(
                () -> assertFalse(secret.getMsg().contains("ghp_0123456789abcdefghij"),
                        "a token reached the event log"),
                () -> assertTrue(secret.getMsg().contains("«redacted"), secret.getMsg()),
                () -> assertEquals(StateRepository.MAX_EVENT_MSG, long_.getMsg().length()));
    }

    /// Everything else about a run resets; the log's numbering does not, and neither does the
    /// row that carries it.
    @Test
    void appendedRowsCarryTheirRunAndStage() {
        store.startRun(newRun("run-1", REPO));
        EventRow row = store.appendEvent("run-1", "measuring_baseline", "ledger: 143 already settled");

        assertAll(
                () -> assertEquals("run-1", row.getRunId()),
                () -> assertEquals("measuring_baseline", row.getStage()),
                () -> assertEquals("ledger: 143 already settled", row.getMsg()),
                () -> assertTrue(row.getTs() > 0));
    }
}
