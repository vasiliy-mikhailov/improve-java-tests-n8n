package tech.mikhailov.ijt;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// A new run must not open with the previous run's log.
///
/// `POST /api/run/start` resets everything a run accumulates — files, decisions, prs,
/// mutatorStats, pickFailures, the reachability memo — and did not reset `events`. The event
/// list is a ring buffer that persists in state.json across restarts, so the first thing a new
/// run's log contains is the tail of the last one.
///
/// Observed live: a run against stleary/JSON-java opened with entries naming
/// `DelegatingDataLoader::clear` and `Assertions::nonNull` — units of java-dataloader, from a
/// different run, hours earlier, against a different repository. Every unit actually measured
/// was org/json.
///
/// This is not cosmetic. The dashboard's event log is what a person reads to judge what a run
/// is doing, and it is the same family of defect this project keeps hitting: the work was
/// real, the record describing it was not. A reader cannot tell which lines belong to the run
/// they are watching.
///
/// Inherited from the Node original rather than introduced by the port — sidecar/server.js
/// clears the same six things and likewise leaves events alone. Fixed here because this is now
/// the backend that runs.
class RunStartEventsTest {

    private static void startRun(String repoUrl) {
        try {
            Server.routes().get("POST /api/run/start")
                    .handle(Server.Query.EMPTY, Map.of("force", true, "repoUrl", repoUrl));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void aNewRunDoesNotInheritThePreviousRunsEvents() {
        startRun("https://github.com/acme/first");
        State.event("improving_mutation", "DelegatingDataLoader#clear(): 3 surviving line(s)");
        State.event("improving_mac", "round 1 of Assertions.java::nonNull: mut 0→100 — PROGRESS");
        assertTrue(State.STATE.events.size() >= 2, "fixture must actually log something");

        startRun("https://github.com/acme/second");

        boolean carried = State.STATE.events.stream()
                .anyMatch(e -> String.valueOf(e.msg()).contains("DelegatingDataLoader")
                            || String.valueOf(e.msg()).contains("Assertions.java"));
        assertFalse(carried, "the new run's log still contains the previous run's units:\n  "
                + State.STATE.events.stream().map(e -> e.stage() + ": " + e.msg()).limit(10).toList());
    }

    @Test
    void theNewRunsOwnStartingEventSurvivesTheReset() {
        // Clearing must happen BEFORE the run announces itself, or the reset eats the one line
        // that says which repo and which run this is — and an empty log is its own confusion.
        startRun("https://github.com/acme/first");
        State.event("improving_mutation", "noise from the previous run");

        startRun("https://github.com/acme/second");

        assertTrue(State.STATE.events.stream()
                        .anyMatch(e -> String.valueOf(e.msg()).contains("acme/second")),
                "the run's own starting event must be in its log: "
                        + State.STATE.events.stream().map(e -> String.valueOf(e.msg())).toList());
    }

    @Test
    void theEventSequenceKeepsRisingAcrossRuns() {
        // GET /api/events?after=<seq> pages on seq, and the dashboard polls it. Resetting the
        // counter with the log would make a new run's early events look older than the ones a
        // client already holds, and they would never be delivered.
        startRun("https://github.com/acme/first");
        State.event("x", "one");
        long before = State.STATE.seq;

        startRun("https://github.com/acme/second");
        State.event("x", "two");

        assertTrue(State.STATE.seq > before,
                "seq went backwards across a run boundary: " + before + " → " + State.STATE.seq);
    }
}
