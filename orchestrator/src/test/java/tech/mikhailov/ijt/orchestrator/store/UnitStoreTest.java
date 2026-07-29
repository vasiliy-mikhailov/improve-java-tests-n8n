package tech.mikhailov.ijt.orchestrator.store;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// A unit's record: what it stores, and what it refuses to store.
///
/// Most of these are the same rule seen from different angles — ABSENT IS NOT ZERO. It decides
/// which unit gets picked, what the headline averages mean, and whether a unit that has already
/// been exhausted is offered up again.
class UnitStoreTest extends StoreTestSupport {

    private static final String RUN = "run-1";
    private static final String KEY = "src/main/java/org/json/XML.java::toJSONObject";

    @Test
    void aFreshUnitIsACandidateWithNoMeasurements() {
        store.upsertUnit(RUN, KEY, null);
        reload();

        Unit unit = store.unit(RUN, KEY).orElseThrow();
        assertAll(
                () -> assertEquals("candidate", unit.getStatus()),
                () -> assertEquals(0, unit.getAttempts(), "attempts is a count and starts at 0"),
                () -> assertNull(unit.getCoverage()),
                () -> assertNull(unit.getMutation()),
                () -> assertNull(unit.getMac()),
                () -> assertNull(unit.getMacBefore()),
                () -> assertNull(unit.getSpentSec(), "no time spent is not zero time spent"),
                () -> assertTrue(unit.getUpdatedAt() > 0));
    }

    /// A unit whose mutation score could not be measured stores NULL.
    ///
    /// Zero is a measurement; absent is not. A failed PIT run recorded as 0% reads as a unit
    /// with no tests at all: it sorts to the top of the candidate list, is picked over units
    /// that would actually pay, and every later number is measured against a floor that was
    /// never true. Anything that is not a number — a null, a NaN that came back from JSON as a
    /// string, an error object — lands as NULL rather than as a confident 0.
    @Test
    void unmeasuredIsNullNeverZero() {
        store.upsertUnit(RUN, KEY, patch(
                "coverage", null,
                "mutation", "n/a",
                // what a failed measurement actually looks like coming back through JSON
                "mac", Map.of("error", "PIT exited 1")));
        reload();

        Unit unit = store.unit(RUN, KEY).orElseThrow();
        assertAll(
                () -> assertNull(unit.getCoverage()),
                () -> assertNull(unit.getMutation(), "a non-numeric measurement became a number"),
                () -> assertNull(unit.getMac()));
    }

    /// The other half of the same rule: a REAL zero is a real measurement and must survive.
    /// 0% mutation is common and it is the reason a unit is worth picking.
    @Test
    void aMeasuredZeroSurvives() {
        store.upsertUnit(RUN, KEY, patch("coverage", 0, "mutation", 0.0, "mac", 0));
        reload();

        Unit unit = store.unit(RUN, KEY).orElseThrow();
        assertAll(
                // an Integer 0 out of a JSON round-trip is still a measurement of zero
                () -> assertEquals(0.0, unit.getCoverage(), 1e-9),
                () -> assertEquals(0.0, unit.getMutation(), 1e-9),
                () -> assertEquals(0.0, unit.getMac(), 1e-9));
    }

    /// `lastSurvived` carries the distinction in list form: null means nothing has ever been
    /// measured, `[]` means measured and nothing survived. `Select.exhausted` gives opposite
    /// verdicts for the two, and a converter that folded one into the other would either settle
    /// units with work left in them or keep re-picking finished ones.
    @Test
    void neverMeasuredAndMeasuredEmptyStayDifferent() {
        store.upsertUnit(RUN, KEY, null);
        store.upsertUnit(RUN, "a.java::b", patch("lastSurvived", List.of()));
        store.upsertUnit(RUN, "c.java::d", patch("lastSurvived",
                List.of(Map.of("mutator", "NEGATE_CONDITIONALS", "line", 42))));
        reload();

        assertAll(
                () -> assertNull(store.unit(RUN, KEY).orElseThrow().getLastSurvived()),
                () -> assertEquals(List.of(), store.unit(RUN, "a.java::b").orElseThrow().getLastSurvived()),
                () -> assertEquals(1, store.unit(RUN, "c.java::d").orElseThrow().getLastSurvived().size()));
    }

    /// The patch is applied key by key INCLUDING its nulls. `{attemptStartedAt: null}` is how a
    /// caller stops the stopwatch; a store that skipped nulls would leave it running for ever
    /// and charge the unit for every second until the process died.
    @Test
    void nullsInAPatchAreApplied() {
        store.upsertUnit(RUN, KEY, patch("attemptStartedAt", 1_700_000_000L, "startSha", "abc123"));
        store.upsertUnit(RUN, KEY, patch("attemptStartedAt", null));
        reload();

        Map<String, Object> record = store.unit(RUN, KEY).orElseThrow().toMap();
        assertAll(
                () -> assertTrue(record.containsKey("attemptStartedAt"), "the key was dropped, not cleared"),
                () -> assertNull(record.get("attemptStartedAt")),
                () -> assertEquals("abc123", record.get("startSha"), "an unrelated key was lost"));
    }

    /// A patch merges over what is there. It is how the pipeline works: the coverage route knows
    /// one number, the PIT route another, and the round knows what it changed.
    @Test
    void patchesMergeRatherThanReplace() {
        store.upsertUnit(RUN, KEY, patch("coverage", 40.0, "method", "toJSONObject"));
        store.upsertUnit(RUN, KEY, patch("mutation", 12.5));
        reload();

        Unit unit = store.unit(RUN, KEY).orElseThrow();
        assertAll(
                () -> assertEquals(40.0, unit.getCoverage(), 1e-9),
                () -> assertEquals(12.5, unit.getMutation(), 1e-9),
                () -> assertEquals("toJSONObject", unit.getMethod()));
    }

    /// `updatedAt` is written after the patch, so a patch cannot forge it. The dashboard sorts
    /// on it and a stale value would pin a finished unit to the top of the list.
    @Test
    void updatedAtCannotBeForgedByAPatch() {
        Unit unit = store.upsertUnit(RUN, KEY, patch("updatedAt", 1L, "status", "improving"));
        assertTrue(unit.getUpdatedAt() > 1L);
    }

    /// The record's `path` IS the unit key. Letting a patch change it would leave the unit's
    /// ledger entries, its branch and its PR pointing at a name that no longer exists.
    @Test
    void aPatchCannotRenameAUnit() {
        store.upsertUnit(RUN, KEY, null);
        assertThrows(IllegalArgumentException.class,
                () -> store.upsertUnit(RUN, KEY, patch("path", "src/main/java/org/json/JSONML.java::toJSONArray")));
        // the seed's own `path` is the key and must not trip the guard
        assertNotNull(store.upsertUnit(RUN, KEY, patch("path", KEY, "status", "improving")));
    }

    /// The record the pipeline and the dashboard read — `state.files[key]`.
    @Test
    void toMapIsTheRecordTheDashboardReads() {
        store.upsertUnit(RUN, KEY, patch("coverage", 40.0, "status", "improved", "attempts", 2,
                "prUrl", "https://github.com/o/r/pull/7", "everReached", true));
        reload();

        Map<String, Object> record = store.unit(RUN, KEY).orElseThrow().toMap();
        assertAll(
                () -> assertEquals(KEY, record.get("path")),
                () -> assertEquals(40.0, record.get("coverage")),
                // present and null, exactly as the seed record had them: the dashboard reads the
                // presence of a key
                () -> assertTrue(record.containsKey("mutation")),
                () -> assertNull(record.get("mutation")),
                () -> assertEquals("improved", record.get("status")),
                () -> assertEquals(2, record.get("attempts")),
                () -> assertEquals("https://github.com/o/r/pull/7", record.get("prUrl")),
                () -> assertEquals(Boolean.TRUE, record.get("everReached")),
                // never written until something assigns it
                () -> assertFalse(record.containsKey("spentSec")));
    }

    /// Units are scoped by run, which is what makes them reset at run start without anything
    /// having to delete them.
    @Test
    void unitsAreScopedToTheirRun() {
        store.upsertUnit("run-1", KEY, patch("status", "improved"));
        store.upsertUnit("run-2", KEY, patch("status", "candidate"));
        reload();

        assertAll(
                () -> assertEquals(1, store.units("run-1").size()),
                () -> assertEquals("improved", store.unit("run-1", KEY).orElseThrow().getStatus()),
                () -> assertEquals("candidate", store.unit("run-2", KEY).orElseThrow().getStatus()));
    }

    /// `state.files` — key → record, which is the shape the domain code reads.
    @Test
    void unitsAsMapIsKeyedByUnit() {
        store.upsertUnit(RUN, "b.java::x", patch("mac", 10.0));
        store.upsertUnit(RUN, "a.java::y", null);
        reload();

        Map<String, Map<String, Object>> files = store.unitsAsMap(RUN);
        assertAll(
                () -> assertEquals(List.of("a.java::y", "b.java::x"), List.copyOf(files.keySet())),
                () -> assertEquals(10.0, files.get("b.java::x").get("mac")));
    }
}
