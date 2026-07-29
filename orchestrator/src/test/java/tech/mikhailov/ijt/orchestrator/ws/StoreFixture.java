package tech.mikhailov.ijt.orchestrator.ws;

import tech.mikhailov.ijt.State;
import tech.mikhailov.ijt.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/// The process-wide store, put back the way a fresh boot finds it.
///
/// `State.STATE` is one static object for the whole JVM — the Node module-level `state` it was
/// ported from — so every test here shares it and none may run in parallel. Each test points
/// `DATA_DIR` at its own temp directory first, or the debounced flush writes a real
/// `/data/state.json` from a unit test.
final class StoreFixture {

    private StoreFixture() {}

    /// Reset, with `dataDir` as the new DATA_DIR.
    ///
    /// The flush comes BEFORE the reset and AFTER the redirect on purpose: it settles whatever
    /// the previous test left owed, into the new temp directory rather than into the previous
    /// one (which JUnit is about to delete) or into `/data`.
    static void reset(Path dataDir) throws IOException {
        State.DATA_DIR = dataDir;
        State.flushNow();
        State.Store s = State.STATE;
        synchronized (s) {
            s.run = null;
            s.stage = new State.Stage("idle", "", Util.nowSec(), null);
            s.runner = null;
            s.files = new LinkedHashMap<>();
            s.decisions = new LinkedHashMap<>();
            s.prs = new ArrayList<>();
            s.events = new ArrayList<>();
            s.seq = 0;
            s.improvedLedger = new LinkedHashMap<>();
            s.overheadLedger = new LinkedHashMap<>();
            s.measureLedger = new LinkedHashMap<>();
            s.currentUnit = null;
            s.llmLog = new ArrayList<>();
            s.pickFailures = null;
            s.mutatorStats = null;
            s.llmBudget = null;
            s.extra().clear();
        }
        Files.deleteIfExists(State.stateFile());
        Files.deleteIfExists(State.eventsFile());
    }

    /// Settle the last owed flush somewhere disposable, so the JVM's shutdown hook does not
    /// write a test's store into a directory that has been deleted — or into `/data`.
    static void drain() throws IOException {
        Path dir = Files.createTempDirectory("ijt-ws-drain-");
        State.DATA_DIR = dir;
        State.flushNow();
        Files.deleteIfExists(dir.resolve("state.json"));
        Files.deleteIfExists(dir);
    }
}
