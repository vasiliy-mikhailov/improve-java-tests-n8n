package tech.mikhailov.ijt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static tech.mikhailov.ijt.Timesheet.DiffStats;
import static tech.mikhailov.ijt.Timesheet.Estimate;
import static tech.mikhailov.ijt.Timesheet.Inputs;
import static tech.mikhailov.ijt.Timesheet.RATES;

class TimesheetTest {

    @Test
    void estimateItemizesAndSumsTheFourBuckets() {
        Estimate t = Timesheet.estimate(new Inputs(200, 5, null, 4, 2));
        assertEquals(RATES.analysisBaseMin() + 200 / RATES.analysisLinesPerMin(), t.analysisMin()); // 40
        assertEquals(5 * RATES.perTestCaseMin(), t.testsMin());                                     // 60
        assertEquals(4 * RATES.perMutantKilledMin(), t.mutationMin());                              // 40
        assertEquals(RATES.verifyBaseMin() + 2 * RATES.perRoundMin(), t.verifyMin());               // 25
        assertEquals(40 + 60 + 40 + 25, t.totalMin());
        assertEquals(Math.round(165 / 60.0 * 100) / 100.0, t.hours());
    }

    @Test
    void analysisTimeIsCappedForVeryLargeFiles() {
        Estimate t = Timesheet.estimate(new Inputs(100000, 0, null, 0, 1));
        assertEquals(RATES.analysisCapMin(), t.analysisMin());
    }

    @Test
    void estimateKeepsItsInputsForAuditability() {
        Estimate t = Timesheet.estimate(new Inputs(10, 1, 30, 1, 1));
        assertEquals(new Inputs(10, 1, 30, 1, 1), t.inputs());
    }

    @Test
    void estimateTreatsAZeroRoundFileAsOneVerificationCycle() {
        Estimate a = Timesheet.estimate(new Inputs(null, null, null, null, 0));
        Estimate b = Timesheet.estimate(new Inputs(null, null, null, null, 1));
        assertEquals(b.verifyMin(), a.verifyMin());
    }

    @Test
    void diffStatsCountsAddedJUnitTestCasesNotRemovedOrContextLines() {
        String diff = String.join("\n",
                "diff --git a/src/test/java/com/example/core/ValidatorMacCovTest.java b/src/test/java/com/example/core/ValidatorMacCovTest.java",
                "+++ b/src/test/java/com/example/core/ValidatorMacCovTest.java",
                "+    @Test",
                "+    void isValidUsername_nullReturnsFalse() {",
                "+        assertFalse(Validator.isValidUsername(null));",
                "+    }",
                "+    @ParameterizedTest",
                "+    @ValueSource(strings = {\"ab\", \"a\"})",
                "+    void tooShort(String s) { assertFalse(Validator.isValidUsername(s)); }",
                "-    @Test",
                "-    void removed() {}",
                "     @Test");
        DiffStats s = Timesheet.diffStats(diff);
        assertEquals(2, s.testCases(), "@Test and @ParameterizedTest, not the removed or context ones");
        assertEquals(7, s.addedTestLines());
    }

    @Test
    void diffStatsDoesNotCountJsStyleTestCallsInAJavaProject() {
        DiffStats s = Timesheet.diffStats("+  it('does a thing', () => {});");
        assertEquals(0, s.testCases());
    }

    @Test
    void diffStatsOnAnEmptyDiffYieldsZeroes() {
        assertEquals(new DiffStats(0, 0), Timesheet.diffStats(""));
    }

    // ── what the Java translation could silently change, which the JS never had to say ──

    @Test
    void anEstimateUnderAnHourStillReportsItsHours() {
        // totalMin / 60 in ints would report every sub-hour unit as 0.0 h — the timesheet the
        // PR body quotes, and most single-method units land well under an hour
        Estimate t = Timesheet.estimate(Inputs.empty());
        assertEquals(30 + 0 + 0 + 20, t.totalMin());
        assertEquals(0.83, t.hours());
    }

    @Test
    void aShortFileIsStillChargedForThePartMinuteOfReadingIt() {
        // sourceLines / analysisLinesPerMin is a floating division: 10 lines is half a minute,
        // and integer division would charge every file under 20 lines the bare base
        assertEquals(31, Timesheet.estimate(new Inputs(10, 0, null, 0, 1)).analysisMin());
    }

    @Test
    void omittedInputsAreEchoedAsTheDefaultsTheyWereChargedAt() {
        // the JS destructured with defaults, so the audit trail records what the estimate was
        // actually computed from — rounds absent was billed as 1 and is reported as 1
        assertEquals(new Inputs(0, 0, 0, 0, 1), Timesheet.estimate(Inputs.empty()).inputs());
    }

    @Test
    void aDiffThatCouldNotBeObtainedCostsTheReportALineRatherThanThrowing() {
        assertEquals(new DiffStats(0, 0), Timesheet.diffStats(null));
    }
}
