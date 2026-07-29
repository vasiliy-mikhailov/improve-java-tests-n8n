package tech.mikhailov.ijt;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Human-equivalent timesheet: how long would a mid-level developer have taken
/// to deliver the same test work? Deterministic, itemized, auditable rates.
///
///   analysis   30 min base to open & understand the module, +1 min per 20 source
///              lines (capped at 90 min)
///   writing    12 min per test case that ended up committed
///   mutation   10 min per mutant killed (finding the weakness + crafting the
///              distinguishing assertion — the human equivalent of mutation analysis)
///   verify     15 min base (run suite/coverage, self-review) + 5 min per
///              improvement round (each round = one measure-adjust cycle)
public final class Timesheet {

    private Timesheet() {}

    /// The rates themselves, exposed so a caller — and the tests — can state an expected
    /// estimate in terms of the rates rather than re-hardcoding the numbers.
    public record Rates(int analysisBaseMin, int analysisLinesPerMin, int analysisCapMin,
                        int perTestCaseMin, int perMutantKilledMin,
                        int verifyBaseMin, int perRoundMin) {}

    public static final Rates RATES = new Rates(30, 20, 90, 12, 10, 15, 5);

    /// What an estimate was computed from, echoed back with the result for auditability.
    ///
    /// Every field is boxed because the JS destructured them out of an options object and any
    /// of them may be left out; the compact constructor below substitutes the same defaults
    /// the JS parameter list did. Note that the echoed inputs report the DEFAULTED values,
    /// not the caller's omissions — `rounds` absent is recorded as 1, exactly as in the JS.
    ///
    /// `rounds` defaults to 1 and the others to 0: a file measured once has had one
    /// verification cycle, but nothing else about it is assumed.
    public record Inputs(Integer sourceLines, Integer testCases, Integer addedTestLines,
                         Integer mutantsKilled, Integer rounds) {
        public Inputs {
            if (sourceLines == null) sourceLines = 0;
            if (testCases == null) testCases = 0;
            if (addedTestLines == null) addedTestLines = 0;
            if (mutantsKilled == null) mutantsKilled = 0;
            if (rounds == null) rounds = 1;
        }

        /// The all-defaults inputs — the JS `estimate({})`.
        public static Inputs empty() { return new Inputs(null, null, null, null, null); }
    }

    /// One itemized estimate: the four buckets, their sum, and the sum in hours.
    ///
    /// `addedTestLines` is carried in `inputs` only — it is recorded for the PR body, not
    /// charged for; the rate card bills test cases, not lines.
    public record Estimate(int analysisMin, int testsMin, int mutationMin, int verifyMin,
                           int totalMin, double hours, Inputs inputs) {}

    public static Estimate estimate(Inputs in) {
        // a caller with nothing to state gets the defaults rather than an exception; the JS
        // threw here, and the one call site wraps this in a try/catch that drops the timesheet
        Inputs i = in == null ? Inputs.empty() : in;
        // the division is deliberately floating: 10 source lines is half a minute of reading,
        // and integer division would round every file under 20 lines down to the bare base
        double analysisMin = Math.min(RATES.analysisCapMin(),
                RATES.analysisBaseMin() + i.sourceLines().doubleValue() / RATES.analysisLinesPerMin());
        int testsMin = i.testCases() * RATES.perTestCaseMin();
        int mutationMin = i.mutantsKilled() * RATES.perMutantKilledMin();
        // even a file that needed no adjusting was still run and read once
        int verifyMin = RATES.verifyBaseMin() + Math.max(1, i.rounds()) * RATES.perRoundMin();
        int totalMin = (int) Math.round(analysisMin + testsMin + mutationMin + verifyMin);
        return new Estimate((int) Math.round(analysisMin), testsMin, mutationMin, verifyMin,
                totalMin,
                // 60.0, not 60: integer division would report every estimate under an hour as 0
                Util.round2(totalMin / 60.0),
                i);
    }

    /// Committed test cases / added lines counted from a unified diff.
    public record DiffStats(int testCases, int addedTestLines) {}

    /// A Java test case is an ADDED annotation — `@Test`, `@ParameterizedTest`,
    /// `@RepeatedTest`, `@TestFactory` (JUnit 4/5 and TestNG all spell the plain case
    /// `@Test`). Counting the JS way (`it(` / `test(`) reported zero for every Java file,
    /// which zeroed the "test writing" line of every timesheet.
    private static final Pattern ADDED_TEST_CASE = Pattern.compile(
            "^\\+\\s*@(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b",
            Pattern.MULTILINE);

    /// An added line is `+` followed by something that is not another `+`, which is what keeps
    /// the `+++ b/path` file header out of the count. (`[^+]` matches a newline too, so a bare
    /// `+` — an added blank line — still counts, exactly as in the JS.)
    private static final Pattern ADDED_LINE = Pattern.compile("^\\+[^+]", Pattern.MULTILINE);

    /// Count committed test cases / added lines from a unified diff.
    public static DiffStats diffStats(String diff) {
        return new DiffStats(count(ADDED_TEST_CASE, diff), count(ADDED_LINE, diff));
    }

    /// The JS `(diff.match(re) || []).length` — no matches is zero, not a failure. A null diff
    /// is read as no diff for the same reason: an unobtainable diff must cost the report a
    /// line, not throw.
    private static int count(Pattern re, String diff) {
        if (diff == null || diff.isEmpty()) return 0;
        Matcher m = re.matcher(diff);
        int n = 0;
        while (m.find()) n += 1;
        return n;
    }
}
