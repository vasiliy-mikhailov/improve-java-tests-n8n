package tech.mikhailov.ijt.orchestrator.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.mikhailov.ijt.State;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The restore must be COMPLETE, and a new field must not be able to slip out of it.
///
/// `runner` was dropped, and it cost a run. `{tool, wrapper, jdk, testFramework}` is written by
/// setup and is what Pit and Coverage check before they will do anything:
///
///     if (!truthy(tool)) throw new IllegalStateException("build not detected — call /api/repo/prepare first")
///
/// A resumed run skips setup, so a restore that loses `runner` leaves the pipeline believing it
/// has no build. Measured on run-1785444794893, job execution 41: every unit it picked failed PIT
/// instantly and was marked `failed` — nine of them in two seconds, including XML#parse, one of
/// the largest units in the repo — and the run declared itself done with 0 PRs and 70 candidates
/// still on the list. `failed` is a durable disposition: it goes into improvedLedger and stops
/// those units being offered again on later runs.
///
/// The columns were already there. `runner_json` and `decisions_json` have existed on the Run row,
/// with getters and setters, since the migration landed; `toRun` never wrote them and `fromRow`
/// never read them.
///
/// This is the FIFTH time the H2 path has answered the same question as the file path in its own
/// words and dropped something in the retelling — after the units, the PRs, the ledgers, and the
/// interrupted-run relabelling. So the last test here is reflective: it enumerates
/// `State.Store`'s fields and fails on any one that is neither restored nor explicitly declared
/// transient. Adding a field to the Store now forces a decision about it instead of quietly
/// losing it on the next restart.
class RestoreCompletenessTest extends StoreTestSupport {

    private Path savedDataDir;

    @BeforeEach
    void redirectTheFileWriter(@TempDir Path tmp) {
        savedDataDir = State.DATA_DIR;
        State.DATA_DIR = tmp;
    }

    @AfterEach
    void restoreDataDir() {
        State.DATA_DIR = savedDataDir;
    }

    /// Fields the restore deliberately does not carry, each with the reason it is safe to lose.
    ///
    /// Not a convenience list — anything here is a claim that the pipeline works correctly when
    /// the field comes back empty, and the reflective test below is what forces that claim to be
    /// made out loud.
    private static final Map<String, String> DELIBERATELY_TRANSIENT = Map.of(
            "currentUnit", "the unit in flight when the process died; the resumed run picks again "
                    + "and attributing tokens to a half-finished unit would be a lie",
            "llmLog", "a debugging tail of recent model calls, capped and rewritten constantly; "
                    + "it describes the dead process, not the run");

    /// EVERY field populated, so that "empty after the restore" can only mean it was dropped.
    ///
    /// The first version of this left several unset, and they showed up as losses that were
    /// really just an incomplete fixture — the same mistake, one level up, that let the store's
    /// own tests pass for months.
    private static State.Store storeWithEverything() {
        State.Store s = new State.Store();
        s.run = State.freshRun(Map.of("repoUrl", "https://example.invalid/r"));
        s.stage = new State.Stage("improving_mac", "round 2", 1_700_000_000L, null);
        s.runner = new LinkedHashMap<>(Map.of(
                "tool", "maven", "wrapper", "./mvnw", "jdk", "25", "testFramework", "junit4"));
        s.decisions = new LinkedHashMap<>(Map.of("pick_file", Map.of("file", "A.java::m")));
        // THE SLUG THE RESTORE WILL ASK FOR. Both ledgers are keyed by repo, and load() derives
        // the key from this run's own repoUrl — a fixture that invents a different key tests
        // nothing but its own typo.
        String slug = tech.mikhailov.ijt.Util.slugify(s.run.config.repoUrl());
        s.overheadLedger = new LinkedHashMap<>(Map.of(slug, Map.of("cloneSec", 42)));
        s.files.put("A.java::m", new LinkedHashMap<>(Map.of("path", "A.java", "status", "candidate")));
        s.improvedLedger.put(slug,
                new LinkedHashMap<>(Map.of("A.java::m", Map.of("state", "improved"))));
        s.measureLedger.put(slug,
                new LinkedHashMap<>(Map.of("A.java::m", Map.of("macBefore", 10))));
        s.prs.add(new LinkedHashMap<>(Map.of("file", "A.java", "branch", "tests/improve-a")));
        s.seq = 7;
        s.llmBudget = new LinkedHashMap<>(Map.of("spentUsd", 1.5));
        s.mutatorStats = new LinkedHashMap<>(Map.of("ConditionalsBoundaryMutator", 3));
        s.pickFailures = 2;
        return s;
    }

    /// THE one that cost a run.
    @Test
    void theRunnerSurvivesARestart() {
        H2StatePersistence h2 = new H2StatePersistence(store);
        h2.writeSnapshot(storeWithEverything());
        reload();

        State.Store back = new State.Store();
        assertTrue(h2.load(back));
        assertNotNull(back.runner, "no runner means Pit and Coverage both refuse: 'build not detected'");
        assertAll(
                () -> assertEquals("maven", back.runner.get("tool")),
                () -> assertEquals("junit4", back.runner.get("testFramework")),
                () -> assertEquals("25", back.runner.get("jdk")));
    }

    @Test
    void theDecisionsSurviveARestart() {
        H2StatePersistence h2 = new H2StatePersistence(store);
        h2.writeSnapshot(storeWithEverything());
        reload();

        State.Store back = new State.Store();
        assertTrue(h2.load(back));
        assertEquals(1, back.decisions.size(), "the dashboard renders the last rule application per stage");
    }

    @Test
    void theOverheadLedgerSurvivesARestart() {
        // clone and baseline time, per repo. Losing it does not break a run, it silently flatters
        // the FTE ratio by charging none of the setup to it.
        H2StatePersistence h2 = new H2StatePersistence(store);
        h2.writeSnapshot(storeWithEverything());
        reload();

        State.Store back = new State.Store();
        assertTrue(h2.load(back));
        assertEquals(1, back.overheadLedger.size());
    }

    /// Every field of the Store is restored, or declared transient with a reason. No third option.
    @Test
    void noFieldOfTheStoreCanBeSilentlyDropped() {
        H2StatePersistence h2 = new H2StatePersistence(store);
        State.Store written = storeWithEverything();
        h2.writeSnapshot(written);
        // Events have their own write path — `appendEvent`, one row at a time — because that is
        // the only mutation the persistence interface reports individually. A snapshot does not
        // carry them, so this is how one gets into the log.
        State.Run saved = State.STATE.run;
        try {
            State.STATE.run = written.run;
            h2.appendEvent(new State.Event(1, 1_700_000_000L, "starting", "run started"));
        } finally {
            State.STATE.run = saved;
        }
        reload();

        State.Store back = new State.Store();
        assertTrue(h2.load(back), "the store must answer this restore");

        State.Store pristine = new State.Store();
        Set<String> lost = new TreeSet<>();
        for (Field f : State.Store.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (DELIBERATELY_TRANSIENT.containsKey(f.getName())) continue;
            try {
                Object restored = f.get(back);
                Object empty = f.get(pristine);
                // "still exactly what a fresh Store starts with" is the signature of a field the
                // restore never touched. Compared against a pristine Store rather than against
                // null, because the empty value differs per field: null, 0, {} or [].
                if (isEmptyLike(restored, empty)) lost.add(f.getName());
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
        assertEquals(Set.of(), lost,
                "restored as empty, and not declared transient — either carry it through "
                        + "H2StatePersistence or add it to DELIBERATELY_TRANSIENT with the reason "
                        + "the pipeline is correct without it");
    }

    @Test
    void theTransientListIsNotAPlaceToHideAField() {
        // A name that no longer exists on the Store means the list has drifted, and a stale entry
        // is an exemption nobody is checking.
        Set<String> actual = new TreeSet<>();
        for (Field f : State.Store.class.getFields()) actual.add(f.getName());
        for (String declared : DELIBERATELY_TRANSIENT.keySet()) {
            assertTrue(actual.contains(declared),
                    declared + " is declared transient but is not a field of State.Store any more");
        }
    }

    private static boolean isEmptyLike(Object restored, Object pristine) {
        if (restored == null) return true;
        if (restored instanceof Map<?, ?> m) return m.isEmpty();
        if (restored instanceof List<?> l) return l.isEmpty();
        if (restored instanceof Number n) return n.longValue() == 0 && pristine instanceof Number p
                && p.longValue() == 0;
        return false;
    }
}
