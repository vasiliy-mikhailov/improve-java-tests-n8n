package tech.mikhailov.ijt.orchestrator.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.mikhailov.ijt.State;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The record the PIPELINE writes, not the one the store's tests invented.
///
/// The store had twelve passing tests and had been write-dead in production since the first
/// minute of every run. Every one of them fed it `path == key`, which the pipeline never
/// produces. The real record splits the two:
///
///     key    src/main/java/org/json/CDL.java::<init>     <- the map key, the identity
///     path   src/main/java/org/json/CDL.java             <- the FILE, what a PR is opened on
///     method <init>
///
/// `Unit.patch` read the record's `path` as the unit key and threw
/// `"patch would rename unit ... a unit's key is its identity"` for every unit whose path was a
/// file — which is all 319 of them. The throw escaped `upsertUnit` into `writeSnapshot`'s single
/// try block, so ONE unit killed the entire flush: the remaining units, every PR, both ledgers
/// and the budget, on every flush, for the whole life of the run.
///
/// Measured on run-1785411081611. state.json carried 319 units and 42 PRs; H2 carried 26 rows —
/// exactly the 26 FILES, seeded during discovery before methods existed — and zero PRs. Then a
/// restart restored that picture over the real one, because `load()` believed a non-empty store.
///
/// This is the same defect class as the H2 store itself, `eligibleUnit`, and `Collaborators`:
/// built, tested in isolation, and never confronted with what the real caller sends. So these
/// tests build the record from the pipeline's own shape and assert on the whole snapshot.
class SnapshotShapeTest extends StoreTestSupport {

    private static final String RUN = "run-1";
    private static final String FILE = "src/main/java/org/json/CDL.java";
    private static final String KEY = FILE + "::<init>";

    private Path savedDataDir;

    @BeforeEach
    void redirectTheFileWriter(@TempDir Path tmp) {
        // H2StatePersistence dual-writes through State.FilePersistence, and the default is /data
        savedDataDir = State.DATA_DIR;
        State.DATA_DIR = tmp;
    }

    @AfterEach
    void restoreDataDir() {
        State.DATA_DIR = savedDataDir;
    }

    /// One unit as `State.newFileRecord` plus the measurement phase leaves it.
    private static Map<String, Object> record(String file, String method) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("path", file);                 // the FILE — not the key
        f.put("method", method);
        f.put("key", file + "::" + method);  // the identity
        f.put("fqcn", "org.json.CDL");
        f.put("coverage", 100.0);
        f.put("mutation", null);
        f.put("status", "candidate");
        f.put("attempts", 0);
        return f;
    }

    // ── the record the pipeline actually writes ───────────────────────────

    @Test
    void aUnitWhosePathIsItsFileIsStored() {
        // THE bug, at its smallest. This threw, and the throw took the rest of the flush with it.
        assertNotNull(store.upsertUnit(RUN, KEY, record(FILE, "<init>")));
        reload();
        assertEquals(1, store.units(RUN).size());
    }

    @Test
    void theRecordComesBackWithItsFileAndItsKeyBothIntact() {
        // Not interchangeable: `path` is what a PR is opened against and what the prompt shows
        // the model, `key` is what the ledgers and `state.files` are keyed by. Collapsing them
        // is what put 26 file rows in the table instead of 319 units.
        store.upsertUnit(RUN, KEY, record(FILE, "<init>"));
        reload();

        Map<String, Object> back = store.unit(RUN, KEY).orElseThrow().toMap();
        assertAll(
                () -> assertEquals(FILE, back.get("path"), "path is the file"),
                () -> assertEquals(KEY, back.get("key"), "key is the unit"),
                () -> assertEquals("<init>", back.get("method")));
    }

    @Test
    void twoMethodsOfOneFileAreTwoUnits() {
        // 319 units live in 26 files. Keying on the file is what silently collapsed them, each
        // one overwriting the last, leaving one row per file with whatever landed most recently.
        store.upsertUnit(RUN, FILE + "::<init>", record(FILE, "<init>"));
        store.upsertUnit(RUN, FILE + "::rowToString", record(FILE, "rowToString"));
        reload();

        assertEquals(2, store.units(RUN).size(), "two methods of one file are two units");
    }

    @Test
    void renamingTheKEYIsStillRefused() {
        // The guarantee the old `path` guard was reaching for, moved onto the field that really
        // is the identity. A unit renamed under the ledger leaves its branch and PR pointing at
        // a name that no longer exists.
        store.upsertUnit(RUN, KEY, record(FILE, "<init>"));
        assertTrue(assertThrowsIllegalArgument(() ->
                store.upsertUnit(RUN, KEY, patch("key", FILE + "::rowToString")))
                .getMessage().contains("identity"));
    }

    @Test
    void aPatchMayCorrectTheFileWithoutRenamingTheUnit() {
        // the negative half: `path` is an ordinary column now and must be writable
        store.upsertUnit(RUN, KEY, record(FILE, "<init>"));
        store.upsertUnit(RUN, KEY, patch("path", "src/main/java/org/json/CDL2.java"));
        reload();
        assertEquals("src/main/java/org/json/CDL2.java", store.unit(RUN, KEY).orElseThrow().toMap().get("path"));
    }

    // ── the whole snapshot, not the first row of it ───────────────────────

    @Test
    void everyUnitOfASnapshotIsWritten() {
        H2StatePersistence h2 = new H2StatePersistence(store);
        State.Store s = storeWith(30);

        h2.writeSnapshot(s);
        reload();

        assertEquals(30, store.units(s.run.id).size(), "all of them, not the ones before the first throw");
    }

    @Test
    void thePrsSurviveAUnitThatCannotBeWritten() {
        // The compounding half. PRs, ledgers and the budget are written AFTER the units, so any
        // single bad record silently took all of them with it — which is why the table held 42
        // PRs' worth of run and none of the rows. Nothing downstream of a bad unit may be lost.
        H2StatePersistence h2 = new H2StatePersistence(store);
        State.Store s = storeWith(3);
        Map<String, Object> poison = record(FILE, "boom");
        poison.put("key", "a-different-key-entirely");   // would rename the unit: refused
        s.files.put(FILE + "::boom", poison);

        h2.writeSnapshot(s);
        reload();

        assertAll(
                () -> assertEquals(3, store.units(s.run.id).size(), "the good units still land"),
                () -> assertEquals(1, store.prs(s.run.id).size(), "the PR is downstream of the bad unit"),
                () -> assertEquals(1,
                        store.improvedLedger(tech.mikhailov.ijt.Util.slugify(s.run.config.repoUrl())).size(),
                        "so is the ledger"));
    }

    // ── a snapshot is a statement of fact, not an append ──────────────────

    @Test
    void flushingTwiceDoesNotDuplicateThePrs() {
        // Found in production the moment the units started landing. writeSnapshot builds a fresh
        // PrRow per PR per flush and calls save(), and PrRow's id is an IDENTITY surrogate with
        // no natural key — so every flush INSERTS again. The snapshot is debounced but frequent,
        // and one real PR had become 958 rows within the hour.
        //
        // Invisible until now for the reason everything else here was: the flush always died at
        // the first unit, above the PR loop, so addPr had never run twice.
        H2StatePersistence h2 = new H2StatePersistence(store);
        State.Store s = storeWith(2);

        h2.writeSnapshot(s);
        h2.writeSnapshot(s);
        h2.writeSnapshot(s);
        reload();

        assertEquals(1, store.prs(s.run.id).size(), "one PR is one row, however often it is flushed");
    }

    @Test
    void aPrThatGAINSItsUrlIsUpdatedNotDuplicated() {
        // A PR is prepared locally first and only later gets its url, so the same PR is flushed
        // repeatedly with changing content. That must update the row, not add one.
        H2StatePersistence h2 = new H2StatePersistence(store);
        State.Store s = storeWith(1);
        h2.writeSnapshot(s);
        reload();

        ((Map<String, Object>) s.prs.get(0)).put("url", "https://github.com/o/r/pull/7");
        h2.writeSnapshot(s);
        reload();

        assertEquals(1, store.prs(s.run.id).size());
        assertEquals("https://github.com/o/r/pull/7", store.prsAsMaps(s.run.id).get(0).get("url"));
    }

    @Test
    void twoDifferentPrsAreTwoRows() {
        // the negative half: collapsing everything to one row would pass both tests above
        H2StatePersistence h2 = new H2StatePersistence(store);
        State.Store s = storeWith(1);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("file", "src/main/java/org/json/XML.java");
        second.put("branch", "tests/improve-xml");
        s.prs.add(second);

        h2.writeSnapshot(s);
        h2.writeSnapshot(s);
        reload();

        assertEquals(2, store.prs(s.run.id).size());
    }

    // ── clearLedger has to reach the store ────────────────────────────────

    @Test
    void clearingTheImprovedLedgerRemovesTheROWSToo() {
        // `clearLedger:true` was `state().improvedLedger.remove(slug)` and nothing else. The run
        // that asked for it did start clean — and the rows stayed in H2, where putImproved
        // upserts and nothing ever deleted. So the next restart restored every cleared entry.
        //
        // Measured on a from-scratch run: 320 entries cleared, 23 units converted, restarted for
        // an unrelated deploy, and back came 320 entries with 108 improved — the old ledger with
        // the new run's work merged into it, and not a word logged.
        //
        // StateRepository.clearImproved existed the whole time, correct and called by nothing.
        // Eighth instance of this codebase's signature defect.
        H2StatePersistence h2 = new H2StatePersistence(store);
        State.Store s = storeWith(1);
        String slug = tech.mikhailov.ijt.Util.slugify(s.run.config.repoUrl());
        h2.writeSnapshot(s);
        reload();
        assertEquals(1, store.improvedLedger(slug).size(), "precondition: the ledger has an entry");

        h2.clearImprovedLedger(slug);
        reload();

        assertTrue(store.improvedLedger(slug).isEmpty(),
                "the rows survived the clear, so the next restart resurrects them");
    }

    @Test
    void clearingOneRepoLeavesAnotherRepoAlone() {
        // the ledgers are per-repo and a clear is per-repo; taking the table would lose work on
        // every other repo this deployment has ever improved
        H2StatePersistence h2 = new H2StatePersistence(store);
        store.putImproved("other-repo", "A.java::m", Map.of("state", "improved"));
        State.Store s = storeWith(1);
        String slug = tech.mikhailov.ijt.Util.slugify(s.run.config.repoUrl());
        h2.writeSnapshot(s);
        reload();

        h2.clearImprovedLedger(slug);
        reload();

        assertEquals(1, store.improvedLedger("other-repo").size(), "another repo's ledger is not ours to clear");
    }

    @Test
    void theMEASUREMENTSSurviveAClear() {
        // clearLedger means "redo the work", not "forget what this repo measures". Losing the
        // measureLedger costs ~40 minutes of re-measuring and buys nothing.
        H2StatePersistence h2 = new H2StatePersistence(store);
        State.Store s = storeWith(1);
        String slug = tech.mikhailov.ijt.Util.slugify(s.run.config.repoUrl());
        s.measureLedger.put(slug, new LinkedHashMap<>(Map.of("A.java::m", Map.of("macBefore", 10))));
        h2.writeSnapshot(s);
        reload();

        h2.clearImprovedLedger(slug);
        reload();

        assertEquals(1, store.measureLedger(slug).size(), "the measurements are not the dispositions");
    }

    // ── a thinner store must not win a restore ────────────────────────────

    @Test
    void aStoreHoldingLessThanTheFileRefusesToRestore() {
        // What the loss actually looked like. H2 held 26 rows, state.json held 319 units and 42
        // PRs, and `load()` answered true because the store was not EMPTY — so the caller never
        // read the file, the run came back with a picture of itself from before it had started,
        // and the next flush would have written that over the only complete copy.
        H2StatePersistence h2 = new H2StatePersistence(store);
        State.Store written = storeWith(30);
        h2.writeSnapshot(written);
        reload();

        // the file holds all thirty; the database is made to hold four, as the rename guard
        // effectively did by rejecting every unit after the ones discovery had seeded
        stateFileHolding(30);
        int kept = 0;
        for (Unit u : store.units(written.run.id)) {
            if (++kept > 4) em.getEntityManager().remove(em.getEntityManager().merge(u));
        }
        reload();

        assertFalse(h2.load(new State.Store()),
                "a store thinner than the file is not a restore — the caller must read the file");
    }

    @Test
    void aStoreThatMatchesTheFileStillRestores() {
        // the negative half: "always fall back" would pass the test above and make H2 dead
        H2StatePersistence h2 = new H2StatePersistence(store);
        State.Store written = storeWith(30);
        h2.writeSnapshot(written);
        reload();
        stateFileHolding(30);

        State.Store restored = new State.Store();
        assertTrue(h2.load(restored), "H2 has everything the file has");
        assertEquals(30, restored.files.size());
    }

    /// A state.json on disk carrying `units` entries, and nothing else that matters here.
    ///
    /// Written directly rather than through the dual-write, because `FilePersistence` serialises
    /// the global `State.STATE` and ignores the store it is handed — true of the production
    /// caller, which only ever passes that same global, and not something to reproduce here.
    private static void stateFileHolding(int units) {
        StringBuilder files = new StringBuilder();
        for (int i = 0; i < units; i++) {
            files.append(i == 0 ? "" : ",")
                    .append('"').append(FILE).append("::m").append(i).append("\":{\"path\":\"")
                    .append(FILE).append("\"}");
        }
        try {
            java.nio.file.Files.writeString(State.stateFile(), "{\"files\":{" + files + "}}");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    // ── a restored run is not a running one ───────────────────────────────

    @Test
    void aRunRestoredAsRunningIsMarkedInterrupted() {
        // The process that owned that run is gone — that is what a restore MEANS. `State.load()`
        // has always relabelled it; the H2 path reimplemented the restore and left the migration
        // behind, and the two guards then deadlocked against each other:
        //
        //     POST /webhook/improve-run  -> 409 "a run is already active (stage idle)"
        //     POST /api/run/stop         -> {"ok":true,"stopped":[]}   nothing to stop
        //
        // Unstartable and unstoppable for the 900s the staleness window takes to expire, with no
        // way out but force:true. Server's guard reads exactly this stage name — see
        // "'interrupted' means the sidecar restarted" in POST /api/run/start.
        H2StatePersistence h2 = new H2StatePersistence(store);
        State.Store written = storeWith(2);
        written.run.status = "running";
        h2.writeSnapshot(written);
        reload();

        State.Store restored = new State.Store();
        assertTrue(h2.load(restored));
        assertEquals("interrupted", restored.stage.name(),
                "the run's process is gone, so it cannot be a real concurrency conflict");
    }

    @Test
    void aRunThatFINISHEDIsLeftAlone() {
        // the negative half: relabelling a finished run would invent an interruption that never
        // happened, and the dashboard renders this stage
        H2StatePersistence h2 = new H2StatePersistence(store);
        State.Store written = storeWith(2);
        written.run.status = "done";
        h2.writeSnapshot(written);
        reload();

        State.Store restored = new State.Store();
        assertTrue(h2.load(restored));
        assertEquals("idle", restored.stage.name());
    }

    private static IllegalArgumentException assertThrowsIllegalArgument(Runnable r) {
        return org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, r::run);
    }

    /// A store holding `units` real records, one PR and one ledger entry.
    private static State.Store storeWith(int units) {
        State.Store s = new State.Store();
        s.run = State.freshRun(Map.of("repoUrl", "https://example.invalid/r"));
        for (int i = 0; i < units; i++) {
            String key = FILE + "::m" + i;
            s.files.put(key, record(FILE, "m" + i));
        }
        Map<String, Object> pr = new LinkedHashMap<>();
        pr.put("file", FILE);
        pr.put("branch", "tests/improve-cdl");
        pr.put("title", "tests: CDL");
        s.prs.add(pr);
        // keyed by the SLUG the store derives from repoUrl, not a literal — a fixture that
        // invents its own key writes a ledger no lookup will ever ask for
        s.improvedLedger.put(tech.mikhailov.ijt.Util.slugify(s.run.config.repoUrl()),
                new LinkedHashMap<>(Map.of(KEY, Map.of("state", "improved"))));
        return s;
    }
}
