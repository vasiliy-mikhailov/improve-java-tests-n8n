package tech.mikhailov.ijt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static tech.mikhailov.ijt.Select.Unit;

/// The ranking preferred units that could not possibly pay.
///
/// Measured on a full JSON-java run (run-1785350515436). Of the first eight units it chose,
/// six could never have produced a kill:
///
///     public Cookie() { }            -> UNMEASURED
///     public CookieList() { }        -> UNMEASURED
///     public Property() { }          -> UNMEASURED
///     public void flush() { }        -> UNMEASURED   (StringBuilderWriter)
///     JSONTokener#setJsonParserConfiguration -> UNMEASURED
///     JSONObject#isRecordType        -> 3 misses, then RE-PICKED at rank 1
///
/// An empty body has no mutable code, so PIT emits zero mutants and reports "no tests to run".
/// Each one still costs a baseline coverage run, a PIT run, a model call and a verification.
///
/// WHY THEY WENT FIRST is the part that matters: `weakness()` is `mac ?? coverage/2`, so a unit
/// with no measurable mutation score has no MAC and sorts as the weakest thing in the repo —
/// picked at rank 1, over and over. The ranking actively preferred exactly the units that could
/// not yield anything, and the miss record never fed back into it.
///
/// Not a port defect; the Node backend ranked identically. Previously visible as the
/// already-perfect waste (Statistics#combine, DataLoaderRegistry#combine) and the synthetic
/// lambda$ units — the same missing predicate each time.
class SelectEligibilityTest {

    private static Unit unit(String path, Double mac, Double coverage) {
        return new Unit(path, path.contains("::") ? path.split("::")[1] : "m",
                mac, coverage, "public", 10, 10, 0, null);
    }

    // ── a unit with no mutation surface is not work ───────────────────────
    @Test
    void aMethodWithNoMutantsIsIneligible() {
        // PIT emits nothing for an empty body, so there is no kill to be had — ever, by anyone
        assertFalse(Select.eligibleUnit(unit("a/Cookie.java::<init>", null, 100.0), 0, false));
    }

    @Test
    void aMethodWithMutantsIsEligible() {
        // the negative half: "always false" would pass every test above
        assertTrue(Select.eligibleUnit(unit("a/XML.java::parse", 40.0, 80.0), 12, false));
    }

    @Test
    void anAlreadyPerfectUnitIsIneligible() {
        // Statistics#combine and DataLoaderRegistry#combine each burned a PIT run to discover
        // there was nothing left: 0 survivors, mutation already 100
        assertFalse(Select.eligibleUnit(unit("a/S.java::combine", 100.0, 100.0), 0, false));
    }

    @Test
    void aSyntheticMethodIsIneligible() {
        // lambda$dispatchQueueBatch$3 and lambda$invokeLoader$7 each consumed a full unit.
        // They are compiler-generated: no test can name them, and the prompt cannot show the
        // model a method that is absent from the source.
        assertFalse(Select.eligibleUnit(unit("a/H.java::lambda$dispatchQueueBatch$3", 0.0, 100.0), 5, false));
        assertFalse(Select.eligibleUnit(unit("a/H.java::access$100", 0.0, 100.0), 5, false));
    }

    @Test
    void aRealMethodWhoseNameMerelyContainsLambdaIsStillEligible() {
        // `lambdaFactory` is somebody's method, not a synthetic one — the marker is the `$`
        assertTrue(Select.eligibleUnit(unit("a/H.java::lambdaFactory", 0.0, 100.0), 5, false));
    }

    @Test
    void aUnitAlreadySettledAsExhaustedIsIneligible() {
        assertFalse(Select.eligibleUnit(unit("a/X.java::m", 20.0, 50.0), 4, true));
    }

    // ── an unmeasurable unit is not a weak one ────────────────────────────
    @Test
    void aUnitWithNoMacSortsLastNotFirst() {
        // THE bug. mac ?? coverage/2 made an unmeasurable unit the weakest thing in the repo,
        // so it was picked at rank 1 repeatedly. Unmeasurable is not weak; it is unknown, and
        // unknown must not outrank a measured gap.
        Unit unmeasurable = new Unit("a/Cookie.java::<init>", "<init>", null, null, "public", 1, 1, 0, null);
        Unit realGap = unit("a/XML.java::parse", 20.0, 40.0);
        List<Unit> ranked = Select.rankUnits(List.of(unmeasurable, realGap)).units();
        assertEquals("a/XML.java::parse", ranked.get(0).path(),
                "a measured 20 MAC must be attacked before something nothing is known about");
    }

    @Test
    void aUnitWithCoverageButNoMacIsStillRankedOnWhatIsKnown() {
        // not the same case: coverage IS a measurement, so it still estimates rather than sinking
        Unit knownCoverage = unit("a/A.java::m", null, 10.0);
        Unit weaker = unit("a/B.java::m", 2.0, 4.0);
        List<Unit> ranked = Select.rankUnits(List.of(knownCoverage, weaker)).units();
        assertEquals("a/B.java::m", ranked.get(0).path());
    }

    // The sixth wasted unit, JSONObject#isRecordType, is NOT fixed here — see the comment in
    // Select.java. Demoting on misses contradicts a deliberate rule with its own test, and the
    // data Unit carries cannot tell "reached but hopeless" from "reached but badly tested".


}
