package tech.mikhailov.ijt;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The fast green/red gate that guards every round: the suite either passed or it did not,
/// and PIT refuses to run on red tests.
///
/// Two things are pinned here. First, that a build log is read honestly — `passed` is
/// three-valued, and a log that does not say must never be read as green. Second, that a
/// child process which was killed, crashed or timed out reports RED: it is the one gate the
/// round depends on, and a suite that never finished has proved nothing.
class TestsTest {

    // ── the log reader ────────────────────────────────────────────────────
    // ported from sidecar/test/unit/suiteoutcome.test.js

    private static final String MVN_GREEN = """
            [INFO] -------------------------------------------------------
            [INFO]  T E S T S
            [INFO] -------------------------------------------------------
            [INFO] Running org.json.JSONArrayTest
            [INFO] Tests run: 112, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.9 s
            [INFO] Results:
            [INFO]
            [INFO] Tests run: 1163, Failures: 0, Errors: 0, Skipped: 3
            [INFO]
            [INFO] BUILD SUCCESS
            """;

    private static final String MVN_FAIL = """
            [INFO] Running org.json.JSONObjectMacMutTest
            [ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
            [ERROR] Failures:
            [ERROR]   JSONObjectMacMutTest.testJavaPackageMethodExclusion:14 expected:<false> but was:<true>
            [INFO] Results:
            [ERROR] Tests run: 1164, Failures: 1, Errors: 0, Skipped: 3
            [INFO] BUILD FAILURE
            """;

    private static final String MVN_COMPILE_ERROR = """
            [ERROR] COMPILATION ERROR :
            [ERROR] /src/test/java/org/json/BMacMutTest.java:[12,9] cannot find symbol
            [INFO] BUILD FAILURE
            """;

    private static final String GRADLE_GREEN = """
            > Task :test
            > Task :jacocoTestReport

            BUILD SUCCESSFUL in 42s
            7 actionable tasks: 7 executed
            """;

    private static final String GRADLE_FAIL = """
            > Task :test FAILED

            BMacMutTest > t() FAILED
                org.opentest4j.AssertionFailedError at BMacMutTest.java:14

            FAILURE: Build failed with an exception.
            """;

    @Test
    void aGreenMavenRunIsRecognisedWithoutRunningTheSuiteAgain() {
        // verify ran the full suite, then ran it AGAIN under JaCoCo, then PIT ran it once more:
        // three executions of 1163 tests per round, two of which say the same thing.
        Tests.Suite r = Tests.suiteOutcome(MVN_GREEN);
        assertEquals(Boolean.TRUE, r.passed());
        assertEquals(1163, r.tests());
        assertEquals(0, r.failures());
    }

    @Test
    void aFailingSuiteIsRecognisedEvenWhenTheBuildWasToldToIgnoreFailures() {
        // runCoverage passes -Dmaven.test.failure.ignore=true, so the exit code is always 0 —
        // the counts are the only honest signal
        Tests.Suite r = Tests.suiteOutcome(MVN_FAIL);
        assertEquals(Boolean.FALSE, r.passed());
        assertEquals(1, r.failures());
        assertTrue(r.summary().contains("expected:<false> but was:<true>"), r.summary());
    }

    @Test
    void aCompilationErrorIsAFailureNotAnAbsenceOfTests() {
        Tests.Suite r = Tests.suiteOutcome(MVN_COMPILE_ERROR);
        assertEquals(Boolean.FALSE, r.passed());
        assertTrue(r.summary().contains("cannot find symbol"), r.summary());
    }

    @Test
    void gradleOutputIsUnderstoodToo() {
        assertEquals(Boolean.TRUE, Tests.suiteOutcome(GRADLE_GREEN).passed());
        assertEquals(Boolean.FALSE, Tests.suiteOutcome(GRADLE_FAIL).passed());
    }

    @Test
    void anUnrecognisableLogYieldsNullCannotTellNeverGreen() {
        // the whole point: an unreadable log must send the caller back to a real test run,
        // not let it record a pass nothing established
        assertNull(Tests.suiteOutcome("").passed());
        assertNull(Tests.suiteOutcome("Downloading from central: something.jar\n").passed());
        // `undefined` in the JS. A null log is the same question with no answer in it.
        assertNull(Tests.suiteOutcome(null).passed());
        // whitespace is not an answer either
        assertNull(Tests.suiteOutcome("   \n\t\n").passed());
    }

    @Test
    void aRunThatExecutedNoTestsAtAllCannotBeCalledGreen() {
        String noTests = "[INFO] Results:\n[INFO]\n[INFO] Tests run: 0, Failures: 0, Errors: 0, Skipped: 0\n"
                + "[INFO] BUILD SUCCESS\n";
        Tests.Suite r = Tests.suiteOutcome(noTests);
        assertNull(r.passed(), "nothing ran, so nothing was proven");
        // the counts are still reported: zero tests is a thing the log said, unlike the
        // verdict, which it did not
        assertEquals(0, r.tests());
        assertEquals(0, r.failures());
        assertEquals(0, r.errors());
    }

    @Test
    void errorsCountAsFailures() {
        String errs = "[INFO] Results:\n[ERROR] Tests run: 50, Failures: 0, Errors: 2, Skipped: 0\n"
                + "[INFO] BUILD FAILURE\n";
        Tests.Suite r = Tests.suiteOutcome(errs);
        assertEquals(Boolean.FALSE, r.passed());
        assertEquals(2, r.errors());
    }

    // ── what the Java translation could silently change ───────────────────

    @Test
    void countsThatPassButFollowACompilationErrorAreNotGreen() {
        // A module whose tests all pass while another module failed to compile: the counts
        // alone would say green. This is why the COMPILATION ERROR check sits inside the
        // counts branch and not only after it.
        String log = "[ERROR] COMPILATION ERROR :\n[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0\n";
        assertEquals(Boolean.FALSE, Tests.suiteOutcome(log).passed());
    }

    @Test
    void aGradleFailureIsRecognisedFromEitherOfItsTwoSpellings() {
        // `^FAILURE: Build failed` is anchored to a line start; `Task :test FAILED` is not,
        // because Gradle prefixes it with `> `. One regex, two different anchorings — the JS
        // alternation split the whole literal, and so must the port.
        assertEquals(Boolean.FALSE, Tests.suiteOutcome("FAILURE: Build failed with an exception.\n").passed());
        assertEquals(Boolean.FALSE, Tests.suiteOutcome("> Task :test FAILED\n").passed());
        // ...and BUILD SUCCESSFUL only counts at the start of a line, so a log merely
        // quoting it does not turn a red run green
        assertEquals(Boolean.TRUE, Tests.suiteOutcome("> Task :test\nBUILD SUCCESSFUL in 3s\n").passed());
    }

    @Test
    void theCountsComeFromTheLastAggregateLineNotTheFirstModule() {
        // Maven prints one line per test class and the aggregate last. Reading the first
        // would report a 112-test module as the whole 1163-test suite.
        assertEquals(1163, Tests.suiteOutcome(MVN_GREEN).tests());
        assertEquals(1164, Tests.suiteOutcome(MVN_FAIL).tests());
    }

    @Test
    void aGradleGreenLogCarriesNoCountsAndThatIsNotZero() {
        // Boxed on purpose: `tests: 0` would say the suite ran nothing, which is the one
        // thing this project treats as "nothing was proven".
        Tests.Suite r = Tests.suiteOutcome(GRADLE_GREEN);
        assertEquals(Boolean.TRUE, r.passed());
        assertNull(r.tests());
        assertNull(r.failures());
        assertNull(r.errors());
    }

    // ── the summary handed to the model ───────────────────────────────────

    @Test
    void theSummaryOfAGreenRunIsTheAggregateLine() {
        assertEquals("[INFO] Tests run: 1163, Failures: 0, Errors: 0, Skipped: 3",
                Tests.failureSummary(MVN_GREEN, true));
        // a green build that printed no counts at all still says something
        assertEquals("build succeeded", Tests.failureSummary("BUILD SUCCESSFUL in 42s\n", true));
    }

    @Test
    void theFailureSummaryKeepsTheCompilerAndDropsTheDownloads() {
        String log = """
                [INFO] Downloading from central: https://repo.maven.apache.org/maven2/junit/junit-4.13.jar
                [INFO] Downloaded from central: https://repo.maven.apache.org/maven2/junit/junit-4.13.jar (381 kB)
                [INFO] Compiling 3 source files
                [ERROR] COMPILATION ERROR :
                [ERROR] /src/test/java/org/json/BMacMutTest.java:[12,9] cannot find symbol
                [INFO] BUILD FAILURE
                """;
        String s = Tests.failureSummary(log, false);
        assertTrue(s.contains("cannot find symbol"), s);
        assertFalse(s.contains("Downloading"), s);
        // the `[ERROR] ` prefix is stripped — it is noise repeated on every kept line, and
        // the model is being shown the compiler's words, not Maven's formatting
        assertFalse(s.contains("[ERROR]"), s);
        assertTrue(s.startsWith("COMPILATION ERROR :"), s);
    }

    @Test
    void aFailureWithNothingRecognisableFallsBackToTheTailOfTheLog() {
        // A build can fail without saying any of the words we look for — a plugin that dies,
        // a wrapper that cannot reach its distribution. The end of the log is then the only
        // account there is, and returning nothing at all would leave the repair prompt blank.
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < 100; i++) log.append("Downloading from central: dep-").append(i).append(".jar\n");
        String s = Tests.failureSummary(log.toString(), false);
        // 40 lines, and the trailing empty field of the final newline is one of them —
        // Java's split() drops it without the -1 limit, which would shift the window by one
        assertEquals(40, s.split("\n", -1).length);
        assertTrue(s.contains("dep-99.jar"), s);
        assertFalse(s.contains("dep-59.jar"), s);
    }

    @Test
    void aHugeFailureKeepsTheEndOfTheLogNotTheStart() {
        // Truncating from the front would hand the model 4000 characters of dependency
        // resolution and drop the error it is being asked to fix.
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            log.append("[ERROR] ").append("x".repeat(200)).append(" line").append(i).append('\n');
        }
        String s = Tests.failureSummary(log.toString(), false);
        assertEquals(4000, s.length());
        // the scan stops one line past the 60-line limit, exactly as the JS loop did
        assertTrue(s.endsWith("line60"), s.substring(s.length() - 20));
        assertFalse(s.contains("line61"));
        assertFalse(s.contains("line0"));
    }

    @Test
    void aGreenSummaryIsCappedFromTheFrontWhereItsSentenceIs() {
        // The green case is the opposite of the red one: one line, capped at 500 from the
        // START — `Tests run: 1163, ...` is at the beginning of it.
        String longLine = "[INFO] Tests run: 1163, Failures: 0, Errors: 0" + " ".repeat(50) + "z".repeat(600);
        String s = Tests.failureSummary(longLine, true);
        assertEquals(500, s.length());
        assertTrue(s.startsWith("[INFO] Tests run: 1163"), s);
    }

    // ── the command ───────────────────────────────────────────────────────

    private static final Tests.Build MAVEN = new Tests.Build("maven", "./mvnw", null);
    private static final Tests.Build GRADLE = new Tests.Build("gradle", "./gradlew", List.of("--no-scan"));

    @Test
    void theFullSuiteRunsUnderMavenWithNoFilter() {
        assertEquals(List.of("./mvnw", "-B", "-ntp", "test", "-DfailIfNoTests=false"),
                Tests.argv(MAVEN, null));
    }

    @Test
    void aScopedMavenRunFiltersBySurefireClassName() {
        // Both "no tests is not an error" flags: in a multi-module repo a `-Dtest=X` run
        // matches nothing in most modules, and without them Surefire fails the build for it.
        assertEquals(List.of("./mvnw", "-B", "-ntp", "test", "-DfailIfNoTests=false",
                        "-Dtest=org.json.JSONObjectMacMutTest", "-DfailIfNoTests=false",
                        "-Dsurefire.failIfNoSpecifiedTests=false"),
                Tests.argv(MAVEN, "src/test/java/org/json/JSONObjectMacMutTest.java"));
    }

    @Test
    void gradleRunsWithoutItsDaemonAndCarriesTheSettingsScanArgs() {
        // --no-scan comes from the settings scan and has to precede the task, not follow it
        assertEquals(List.of("./gradlew", "--no-daemon", "--no-scan", "test"), Tests.argv(GRADLE, null));
        assertEquals(List.of("./gradlew", "--no-daemon", "--no-scan", "test", "--tests", "org.json.BMacMutTest"),
                Tests.argv(GRADLE, "src/test/java/org/json/BMacMutTest.java"));
        // a build detected with no scan args is not a build with a null in its command line
        assertEquals(List.of("gradle", "--no-daemon", "test"),
                Tests.argv(new Tests.Build("gradle", "gradle", null), null));
    }

    @Test
    void anEmptyScopeIsTheWholeSuiteNotAFilterMatchingNothing() {
        // `scope ? ... : null` in the JS: '' is falsy there. A `-Dtest=` with nothing after it
        // would run no tests at all and report the round green.
        assertEquals(Tests.argv(MAVEN, null), Tests.argv(MAVEN, ""));
        assertEquals(Tests.argv(GRADLE, null), Tests.argv(GRADLE, ""));
    }

    @Test
    void aRunWithNoDetectedBuildRefusesToGuessACommand() {
        for (Tests.Build b : new Tests.Build[]{null, new Tests.Build(null, "./mvnw", null),
                new Tests.Build("", "./mvnw", null)}) {
            Exception e = assertThrows(IllegalStateException.class, () -> Tests.argv(b, null));
            assertEquals("build not detected", e.getMessage());
        }
    }

    @Test
    void aTestPathBecomesTheFullyQualifiedClassNameTheFiltersTake() {
        assertEquals("org.json.JSONObjectMacMutTest",
                Tests.classNameOf("src/test/java/org/json/JSONObjectMacMutTest.java"));
        // multi-module: the module prefix is not part of the class name
        assertEquals("org.dataloader.DataLoaderTest",
                Tests.classNameOf("core/src/test/java/org/dataloader/DataLoaderTest.java"));
        // already a class name — passed through untouched, simple or qualified
        assertEquals("JSONObjectMacMutTest", Tests.classNameOf("JSONObjectMacMutTest"));
        assertEquals("org.json.JSONObjectMacMutTest", Tests.classNameOf("org.json.JSONObjectMacMutTest"));
        // a .java file that is not under src/test/java: the best we can say is its own name
        assertEquals("BMacMutTest", Tests.classNameOf("some/other/place/BMacMutTest.java"));
        assertEquals("BMacMutTest", Tests.classNameOf("BMacMutTest.java"));
        // the caller filters null before asking
        assertNull(Tests.classNameOf(null));
    }

    // ── the run itself ────────────────────────────────────────────────────

    /// The world, faked: what {@link Tests#runTests} asked for, and what it was told.
    private static final class FakeIo implements Tests.Io {
        private static final Map<String, String> ENV = Map.of("JAVA_HOME", "/usr/lib/jvm/temurin-17");
        private final Tests.Build build;
        private final Tests.Ran answer;
        List<String> argv;
        String cwd;
        long timeoutMs;
        String label;
        Map<String, String> env;
        final List<String> events = new ArrayList<>();

        FakeIo(Tests.Build build, Tests.Ran answer) {
            this.build = build;
            this.answer = answer;
        }

        @Override public String repoDir() { return "/data/repos/stleary-json-java"; }

        @Override public Tests.Build runner() { return build; }

        @Override public Map<String, String> buildEnv() { return ENV; }

        @Override
        public Tests.Ran run(List<String> argv, String cwd, long timeoutMs, String label, Map<String, String> env) {
            this.argv = argv;
            this.cwd = cwd;
            this.timeoutMs = timeoutMs;
            this.label = label;
            this.env = env;
            return answer;
        }

        @Override public void event(String stage, String msg) { events.add(stage + " | " + msg); }
    }

    @Test
    void theChildRunsInTheCloneUnderTheSuiteCeilingAndTheBuildsOwnJdk() {
        FakeIo io = new FakeIo(MAVEN, new Tests.Ran(0, MVN_GREEN, "", false));
        Tests.runTests(io, null);
        assertEquals("/data/repos/stleary-json-java", io.cwd);
        // the number the n8n node's own timeout is derived from — see Timeouts
        assertEquals(Timeouts.SUITE_RUN_MS, io.timeoutMs);
        assertEquals("tests", io.label);
        // PIT's forked minion dies under the wrong JDK, so the build env travels with every
        // command and is not left to whatever the backend happens to run under
        assertEquals(Map.of("JAVA_HOME", "/usr/lib/jvm/temurin-17"), io.env);
        assertEquals(List.of("./mvnw", "-B", "-ntp", "test", "-DfailIfNoTests=false"), io.argv);
    }

    @Test
    void aGreenRunIsReportedGreenAndLogged() {
        FakeIo io = new FakeIo(MAVEN, new Tests.Ran(0, MVN_GREEN, "", false));
        Tests.Result r = Tests.runTests(io, null);
        assertTrue(r.passed());
        assertEquals(0, r.exitCode());
        assertEquals("[INFO] Tests run: 1163, Failures: 0, Errors: 0, Skipped: 3", r.summary());
        assertEquals(List.of("tests | full suite: green"), io.events);
    }

    @Test
    void aRedRunCarriesTheExitCodeAndTheScopeIntoTheEvent() {
        FakeIo io = new FakeIo(MAVEN, new Tests.Ran(1, MVN_FAIL, "", false));
        Tests.Result r = Tests.runTests(io, "src/test/java/org/json/JSONObjectMacMutTest.java");
        assertFalse(r.passed());
        assertEquals(1, r.exitCode());
        assertTrue(r.summary().contains("expected:<false> but was:<true>"), r.summary());
        assertEquals(List.of("tests | src/test/java/org/json/JSONObjectMacMutTest.java: RED (exit 1)"), io.events);
    }

    @Test
    void aKilledChildWithNoExitCodeIsRedNotGreen() {
        // The one gate the round depends on. A process that died by signal has no exit code,
        // and a primitive defaulting to 0 would let a broken suite through; the JS normalised
        // null → 1 inside exec.run, and so does report().
        FakeIo io = new FakeIo(MAVEN, new Tests.Ran(null, "", "Killed", false));
        Tests.Result r = Tests.runTests(io, null);
        assertFalse(r.passed());
        assertEquals(1, r.exitCode());
        assertEquals(List.of("tests | full suite: RED (exit 1)"), io.events);
    }

    @Test
    void aRunThatHitTheCeilingIsRedEvenIfTheCodeSaysZero() {
        // Killing a process group is not instantaneous, and a child that exits in the same
        // instant it is destroyed can still report 0. A suite abandoned at the one-hour
        // ceiling has not passed — it has not finished.
        FakeIo io = new FakeIo(MAVEN, new Tests.Ran(0, MVN_GREEN, "", true));
        assertFalse(Tests.runTests(io, null).passed());
    }

    @Test
    void bothStreamsAreSearchedForTheFailure() {
        // Maven writes its errors to stdout and the JVM writes stack traces to stderr; the
        // summary is built from the two concatenated, as the JS did.
        FakeIo io = new FakeIo(MAVEN, new Tests.Ran(1, "",
                "java.lang.AssertionError: expected:<true> but was:<false>", false));
        assertTrue(Tests.runTests(io, null).summary().contains("expected:<true> but was:<false>"));
        // A child that produced nothing at all — a wrapper that could not be executed — still
        // yields a summary rather than a NullPointerException. JS concatenated two strings
        // that were always strings; here either stream can arrive null.
        FakeIo silent = new FakeIo(MAVEN, new Tests.Ran(127, null, null, false));
        Tests.Result r = Tests.runTests(silent, null);
        assertFalse(r.passed());
        assertEquals(127, r.exitCode());
        assertNotNull(r.summary());
    }

    @Test
    void aRunWithNoDetectedBuildNeverReachesTheChildProcess() {
        FakeIo io = new FakeIo(null, new Tests.Ran(0, MVN_GREEN, "", false));
        assertThrows(IllegalStateException.class, () -> Tests.runTests(io, null));
        assertNull(io.argv, "nothing may be spawned before the build is known");
        assertEquals(List.of(), io.events, "and nothing may be recorded as a test outcome");
    }
}
