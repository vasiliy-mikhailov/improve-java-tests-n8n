package tech.mikhailov.ijt.orchestrator.store;

import org.junit.jupiter.api.Test;
import tech.mikhailov.ijt.Measure;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The per-repo ledgers: the only rows that outlive a run, and the reason the datasource is a
/// file and not `jdbc:h2:mem`.
class LedgerTest extends StoreTestSupport {

    private static final String SLUG = "github-com-stleary-json-java";
    private static final String OTHER = "github-com-graphql-java-dataloader";
    private static final String KEY = "src/main/java/org/json/XML.java::toJSONObject";

    private static Map<String, Object> improvedRecord() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("macBefore", 12.5);
        metrics.put("macAfter", 48.0);
        metrics.put("spentSec", 1389);
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("state", "improved");
        rec.put("ts", 1_700_000_000_000L);
        rec.put("metrics", metrics);
        rec.put("prUrl", null);                                // local mode: no URL…
        rec.put("patchPath", "/data/prs/xml-tojsonobject.patch");   // …a patch instead
        rec.put("branch", "tests/improve-xml-tojsonobject");
        return rec;
    }

    /// THE LEDGERS SURVIVE `run/start`. Everything else about a run is reset.
    ///
    /// `improvedLedger` is what stopped run 2 redoing run 1's work — without it the pipeline
    /// re-improves files that already have open PRs. `measureLedger` is why a full run starts in
    /// seconds instead of re-measuring 319 units for 40 minutes.
    @Test
    void ledgersSurviveRunStartAndTheRestDoesNot() {
        store.startRun(newRun("run-1", "https://github.com/stleary/JSON-java"));
        store.upsertUnit("run-1", KEY, patch("status", "improved"));
        store.addPr(new PrRow("run-1", KEY, "tests/improve-xml", "Add tests", "body",
                java.util.List.of("tests"), "github", "https://github.com/o/r/pull/7", null, 1L));
        store.putImproved(SLUG, KEY, improvedRecord());
        store.mergeMeasured(SLUG, KEY, patch("coverageBefore", 40.0));
        store.addOverheadSec(SLUG, 600);

        store.startRun(newRun("run-2", "https://github.com/stleary/JSON-java"));
        reload();

        assertAll(
                () -> assertEquals(1, store.improvedLedger(SLUG).size(), "the improvement ledger was reset"),
                () -> assertEquals(1, store.measureLedger(SLUG).size(), "the measurement ledger was reset"),
                () -> assertEquals(600, store.overheadSec(SLUG)),
                // and everything a run owns is gone from the new run's view, without anything
                // having had to delete it: it is scoped by run id
                () -> assertTrue(store.units("run-2").isEmpty()),
                () -> assertTrue(store.prs("run-2").isEmpty()));
    }

    /// The record is stored exactly as its writer wrote it, nulls included. Only the `improved`
    /// record carries `prUrl`, `patchPath` and `branch`, and it carries them even when null —
    /// local mode has no URL, and which key is present is how a caller tells an opened PR from a
    /// prepared one.
    @Test
    void anImprovedRecordIsStoredVerbatim() {
        store.putImproved(SLUG, KEY, improvedRecord());
        reload();

        Map<String, Object> back = store.improved(SLUG, KEY).orElseThrow();
        assertAll(
                () -> assertEquals("improved", back.get("state")),
                () -> assertTrue(back.containsKey("prUrl"), "a null-valued key was dropped"),
                () -> assertNull(back.get("prUrl")),
                () -> assertEquals("/data/prs/xml-tojsonobject.patch", back.get("patchPath")),
                () -> assertEquals(48.0, asMap(back.get("metrics")).get("macAfter")),
                () -> assertEquals(1_700_000_000_000L, ((Number) back.get("ts")).longValue()));
    }

    /// `Server.recordMeasurement`: the numbers arrive in pieces — the coverage route knows one,
    /// the PIT route another, a later attempt knows what it reached — so a write merges over
    /// what is already there rather than replacing it.
    @Test
    void measurementsMergeAndAreStamped() {
        store.mergeMeasured(SLUG, KEY, patch("coverageBefore", 40.0, "macBefore", 12.5));
        store.mergeMeasured(SLUG, KEY, patch("mutationBefore", 0.0, "attemptMac", 48.0));
        reload();

        Map<String, Object> entry = store.measureLedger(SLUG).get(KEY);
        assertAll(
                () -> assertEquals(40.0, entry.get("coverageBefore"), "the first half was blanked"),
                () -> assertEquals(0.0, entry.get("mutationBefore")),
                () -> assertEquals(48.0, entry.get("attemptMac")),
                // stamped at the write, so a later change to what these numbers MEAN invalidates
                // the entry instead of replaying it into a new run under semantics it was never
                // measured under
                () -> assertEquals(Measure.MEASURE_VERSION, ((Number) entry.get("v")).intValue()),
                () -> assertTrue(((Number) entry.get("ts")).longValue() > 0));
    }

    /// `clearLedger:true` on `POST /api/run/start` forgets what previous runs SETTLED, and only
    /// that. The measurements are still true and re-taking them costs 40 minutes.
    @Test
    void clearingTheImprovementLedgerLeavesTheMeasurements() {
        store.putImproved(SLUG, KEY, improvedRecord());
        store.mergeMeasured(SLUG, KEY, patch("coverageBefore", 40.0));

        store.clearImproved(SLUG);
        reload();

        assertAll(
                () -> assertTrue(store.improvedLedger(SLUG).isEmpty()),
                () -> assertEquals(1, store.measureLedger(SLUG).size()));
    }

    /// `POST /api/purge` starts one repo again from nothing — and only that repo. A batch run
    /// holds several repos' ledgers side by side.
    @Test
    void purgeIsScopedToOneRepo() {
        store.putImproved(SLUG, KEY, improvedRecord());
        store.mergeMeasured(SLUG, KEY, patch("coverageBefore", 40.0));
        store.addOverheadSec(SLUG, 600);
        store.putImproved(OTHER, "a.java::b", improvedRecord());

        store.purgeRepo(SLUG);
        reload();

        assertAll(
                () -> assertTrue(store.improvedLedger(SLUG).isEmpty()),
                () -> assertTrue(store.measureLedger(SLUG).isEmpty()),
                () -> assertEquals(0, store.overheadSec(SLUG)),
                () -> assertEquals(1, store.improvedLedger(OTHER).size(), "another repo's ledger went with it"));
    }

    /// A batch is many runs against one repo, and the FTE ratio counts all of their setup time.
    /// Absent reads as 0 here on purpose: this is a total of time SPENT, not a measurement.
    @Test
    void overheadIsCumulativePerRepo() {
        assertEquals(0, store.overheadSec(SLUG));
        store.addOverheadSec(SLUG, 600);
        store.addOverheadSec(SLUG, 420);
        reload();

        assertAll(
                () -> assertEquals(1020, store.overheadSec(SLUG)),
                () -> assertEquals(0, store.overheadSec(OTHER)));
    }

    /// One row per (kind, repo, unit): a second write updates rather than accumulating a second
    /// disposition for the same unit, which the replay would then pick between arbitrarily.
    @Test
    void writingTheSameUnitTwiceUpdatesOneRow() {
        store.putImproved(SLUG, KEY, patch("state", "exhausted", "ts", 1L));
        store.putImproved(SLUG, KEY, patch("state", "improved", "ts", 2L));
        reload();

        Map<String, Map<String, Object>> ledger = store.improvedLedger(SLUG);
        assertAll(
                () -> assertEquals(1, ledger.size()),
                () -> assertEquals("improved", ledger.get(KEY).get("state")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }
}
