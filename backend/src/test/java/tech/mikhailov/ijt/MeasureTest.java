package tech.mikhailov.ijt;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static tech.mikhailov.ijt.Measure.Improvement;
import static tech.mikhailov.ijt.Measure.MEASURE_VERSION;
import static tech.mikhailov.ijt.Measure.Plan;
import static tech.mikhailov.ijt.Measure.Settled;

/// Replaying persisted measurements is only safe while the numbers still mean what they
/// meant when they were written — and twice they have stopped meaning it.
class MeasureTest {

    /// Insertion-ordered, null-tolerant literal for the loose metric bags the ledgers hold.
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /// The ledgers are JSON, so a stored number may arrive as any of Java's boxed kinds.
    private static double num(Object o) {
        return ((Number) o).doubleValue();
    }

    @Test
    void aMeasurementRecordsTheSemanticsThatProducedIt() {
        Map<String, Object> e = Measure.stamp(map("coverageBefore", 80, "mutationBefore", 40, "macBefore", 32));
        assertEquals(MEASURE_VERSION, num(e.get("v")));
        assertEquals(80.0, num(e.get("coverageBefore")));
    }

    @Test
    void aMeasurementTakenUnderOlderSemanticsIsNotRestored() {
        // JSONObject#isRecordStyleAccessor was recorded at mutation 15.79% — a score computed
        // over toString()'s mutants as well. After the scoping fix the same method measures 0%.
        // Replaying the old number would hand the run a baseline it can only "improve" by luck.
        assertFalse(Measure.restorable(
                map("coverageBefore", 74, "mutationBefore", 15.79, "v", MEASURE_VERSION - 1)));
    }

    @Test
    void aMeasurementFromBeforeVersioningExistedIsNotRestored() {
        assertFalse(Measure.restorable(
                map("coverageBefore", 83.33, "mutationBefore", 0, "macBefore", 0, "ts", 1785010802817L)));
    }

    @Test
    void aCurrentMeasurementIsRestored() {
        assertTrue(Measure.restorable(Measure.stamp(map("coverageBefore", 80))));
    }

    @Test
    void nothingAtAllIsNotRestorable() {
        assertFalse(Measure.restorable(null));
        assertFalse(Measure.restorable(Map.of()));
    }

    @Test
    void aUnitKeyIsTheSameKeyHoweverTheMethodNameWasEscaped() {
        // the live ledger holds BOTH of these for one constructor, so its measurement is
        // recorded under one key and looked up under the other
        String escaped = "src/main/java/org/json/Property.java::&lt;init&gt;";
        String plain = "src/main/java/org/json/Property.java::<init>";
        assertEquals(plain, Measure.normalizeUnitKey(escaped));
        assertEquals(plain, Measure.normalizeUnitKey(plain));
        assertEquals(Measure.normalizeUnitKey(plain), Measure.normalizeUnitKey(escaped));
    }

    @Test
    void normalisingAnOrdinaryKeyChangesNothingAndIsIdempotent() {
        String k = "src/main/java/org/json/XML.java::parse";
        assertEquals(k, Measure.normalizeUnitKey(k));
        assertEquals(k, Measure.normalizeUnitKey(Measure.normalizeUnitKey(k)));
        assertEquals("&<>\"", Measure.normalizeUnitKey("&amp;&lt;&gt;&quot;"));
        // Java has no `undefined`; the JS coerces a missing key to '', so a null one does too
        assertEquals("", Measure.normalizeUnitKey(null));
    }

    @Test
    void aFileLevelKeyFromTheOldUnitModelIsNotAUnitAndIsNotRestored() {
        // before the method-by-method model these were the keys; their numbers describe a
        // whole class, not the method the run now works on
        assertFalse(Measure.restorable(map("coverageBefore", 83.33, "v", MEASURE_VERSION),
                "src/main/java/org/json/XML.java"));
        assertTrue(Measure.restorable(map("coverageBefore", 83.33, "v", MEASURE_VERSION),
                "src/main/java/org/json/XML.java::parse"));
    }

    // ── replaying ledgers into a run ──────────────────────────────────────────

    private static final Set<String> UNITS = Set.of(
            "src/main/java/org/json/XML.java::parse",
            "src/main/java/org/json/XML.java::toString",
            "src/main/java/org/json/Property.java::<init>");
    private static final Predicate<String> HAS = UNITS::contains;

    @Test
    void aSettledUnitIsReplayedSoTheRunDoesNotRedoIt() {
        // The replay ran in /api/repo/prepare, which is BEFORE baseline coverage expands
        // classes into method units — so state.files held FILE keys and every unit-keyed
        // ledger entry missed. An already-improved, already-PR'd unit came back as a fresh
        // candidate and was re-measured, re-improved and re-PR'd.
        Plan plan = Measure.planReplay(
                Map.of("src/main/java/org/json/XML.java::parse", Improvement.of("improved")),
                Map.of(), HAS);
        assertEquals(List.of("src/main/java/org/json/XML.java::parse"),
                plan.settle().stream().map(Settled::key).toList());
    }

    @Test
    void aMeasurementOfAUnitInScopeIsRestoredOneFromOldSemanticsIsNot() {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        m.put("src/main/java/org/json/XML.java::toString", Measure.stamp(map("coverageBefore", 80)));
        m.put("src/main/java/org/json/XML.java::parse", map("coverageBefore", 88, "v", MEASURE_VERSION - 1));
        Plan plan = Measure.planReplay(Map.of(), m, HAS);
        assertEquals(List.of("src/main/java/org/json/XML.java::toString"),
                plan.restore().stream().map(Measure.Restored::key).toList());
        assertEquals(1, plan.stale());
    }

    @Test
    void aFileKeyedEntryFromBeforeTheUnitModelNeverMatchesAUnit() {
        Plan plan = Measure.planReplay(
                Map.of("src/main/java/org/json/XML.java", Improvement.of("improved")),
                Map.of(), HAS);
        assertEquals(List.of(), plan.settle());
        assertEquals(1, plan.unknown());
    }

    @Test
    void anEntryForAUnitOutsideThisRunScopeIsSimplyNotApplicable() {
        Plan plan = Measure.planReplay(
                Map.of("src/main/java/org/json/Gone.java::method", Improvement.of("improved")),
                Map.of(), HAS);
        assertEquals(List.of(), plan.settle());
        assertEquals(1, plan.unknown());
    }

    @Test
    void anEscapedConstructorKeyStillFindsItsUnit() {
        Plan plan = Measure.planReplay(
                Map.of("src/main/java/org/json/Property.java::&lt;init&gt;", Improvement.of("improved")),
                Map.of(), HAS);
        assertEquals(List.of("src/main/java/org/json/Property.java::<init>"),
                plan.settle().stream().map(Settled::key).toList());
    }

    @Test
    void aSettledUnitsStatusIsReplayedButItsStaleMetricsAreNot() {
        // planReplay gated the MEASUREMENT ledger on version and left the IMPROVEMENT ledger
        // ungated — and its records carry the same numbers. isRecordStyleAccessor's 15.79%
        // (three kills that all belonged to toString) was rejected through one door and
        // admitted through the other, into the run's headline mutation score.
        Map<String, Improvement> improved = Map.of("src/main/java/org/json/XML.java::parse",
                Improvement.of("improved", map("mutationBefore", 15.79, "macBefore", 12.6)));
        Plan plan = Measure.planReplay(improved, Map.of(), HAS);
        assertEquals(1, plan.settle().size(), "the unit is still settled — it was really improved");
        assertNull(plan.settle().get(0).metrics(), "but numbers from older semantics are not restored");
    }

    @Test
    void metricsStampedWithTheCurrentSemanticsAreReplayed() {
        Map<String, Improvement> improved = Map.of("src/main/java/org/json/XML.java::parse",
                Improvement.of("improved", Measure.stamp(map("mutationBefore", 70))));
        Plan plan = Measure.planReplay(improved, Map.of(), HAS);
        assertEquals(70.0, num(plan.settle().get(0).metrics().get("mutationBefore")));
    }

    @Test
    void aUnitThatOnceFailedToMeasureIsOfferedAgainNotBlacklistedForEver() {
        Plan plan = Measure.planReplay(
                Map.of("src/main/java/org/json/XML.java::parse", Improvement.of("failed")),
                Map.of(), HAS);
        assertEquals(0, plan.settle().size(), "a failure measured nothing, so it settles nothing");
        assertEquals(1, plan.retryable());
    }

    @Test
    void whatTheRunsLearnedAboutReachingAUnitSurvivesARestart() {
        // The demotion for "proved unexecutable" needs two observed misses. That evidence lived
        // only in run state, so every restart forgot it and the unit went back to rank 1 to
        // burn the same three rounds again — on a run that restarts whenever anything ships.
        Map<String, Map<String, Object>> m = Map.of("src/main/java/org/json/XML.java::parse",
                Measure.stamp(map("coverageBefore", 0, "everReached", false, "missesEver", 3)));
        Plan plan = Measure.planReplay(Map.of(), m, HAS);
        assertEquals(Boolean.FALSE, plan.restore().get(0).entry().get("everReached"));
        assertEquals(3.0, num(plan.restore().get(0).entry().get("missesEver")));
    }

    @Test
    void whichMutantsWereAlreadyAttackedSurvivesARestart() {
        // Within a run the register is intact — but it lived only in run state, so a restart
        // forgot it and a unit could spend a fresh round on a mutant it had already failed to
        // kill. "Do not try to kill the same mutant twice" has to hold across runs too.
        Map<String, Map<String, Object>> m = Map.of("src/main/java/org/json/XML.java::parse",
                Measure.stamp(map("attemptedMutants", List.of("MathMutator@10", "NullReturnVals@20"))));
        Plan plan = Measure.planReplay(Map.of(), m, HAS);
        assertEquals(List.of("MathMutator@10", "NullReturnVals@20"),
                plan.restore().get(0).entry().get("attemptedMutants"));
    }

    // ── effort survives a replay; measurements are still version-gated ─────────
    // The dashboard read "Human-equivalent work 0" beside "2 file(s) improved", with FTE and
    // ETA blank. The ledger had the numbers - spentSec 1389, a full timesheet - and the replay
    // threw them away, because the version gate added to protect the coverage/mutation
    // headline was applied to the whole metrics object. Those objects are written unstamped
    // (three sites in server.js), so the gate rejected 100% of them, always. Not a filter, a
    // delete.
    //
    // The gate exists because coverage/mutation/MAC semantics changed twice: per-file ->
    // per-method -> method-scoped. Human minutes and machine seconds mean exactly the same
    // thing under all three.
    private static Map<String, Improvement> effortLedger() {
        return Map.of("src/main/java/org/json/XML.java::parse",
                Improvement.of("improved", map(
                        "spentSec", 1389,
                        "timesheet", map("totalMin", 88, "hours", 1.47),
                        "macBefore", 0, "macAfter", 66.67, "mutationAfter", 66.67)));
    }

    @Test
    void anUnstampedLedgerStillYieldsItsEffortAccounting() {
        Plan plan = Measure.planReplay(effortLedger(), Map.of(), HAS);
        assertEquals(1, plan.settle().size());
        assertEquals(1389.0, plan.settle().get(0).effort().spentSec().doubleValue());
        assertEquals(88.0, num(plan.settle().get(0).effort().timesheet().get("totalMin")));
    }

    @Test
    void butNotItsUnstampedCoverageOrMutationNumbers() {
        Plan plan = Measure.planReplay(effortLedger(), Map.of(), HAS);
        assertNull(plan.settle().get(0).metrics(),
                "those carry semantics that have changed twice and must stay gated");
    }

    @Test
    void aStampedLedgerYieldsBoth() {
        Map<String, Improvement> led = Map.of("src/main/java/org/json/XML.java::parse",
                Improvement.of("improved", Measure.stamp(map(
                        "spentSec", 1389,
                        "timesheet", map("totalMin", 88),
                        "macAfter", 66.67))));
        Plan plan = Measure.planReplay(led, Map.of(), HAS);
        assertEquals(66.67, num(plan.settle().get(0).metrics().get("macAfter")));
        assertEquals(1389.0, plan.settle().get(0).effort().spentSec().doubleValue());
    }

    @Test
    void aRecordWithNoMetricsAtAllReportsNoEffortNotAFakeZero() {
        Plan plan = Measure.planReplay(
                Map.of("src/main/java/org/json/XML.java::parse", Improvement.of("improved")),
                Map.of(), HAS);
        Measure.Effort effort = plan.settle().get(0).effort();
        assertTrue(effort.isEmpty());
        assertEquals(Map.of(), effort.toMap());
        // absent, not zero: an unspent second and a second nobody counted are different facts
        assertNull(effort.spentSec());
        assertNull(effort.timesheet());
    }
}
