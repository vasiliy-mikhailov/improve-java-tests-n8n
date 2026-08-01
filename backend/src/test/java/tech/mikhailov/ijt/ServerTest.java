package tech.mikhailov.ijt;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/// The sidecar's HTTP layer: its route table, its wire shapes, and the decisions server.js
/// makes on its own before delegating to a module.
///
/// server.js has no unit test in the Node sidecar, which is why this file exists. Everything
/// pinned here is either (a) a decision that lives nowhere else — the repo headline average,
/// the work accounting, the per-method expansion, the candidate gates — or (b) a contract the
/// n8n workflow depends on and that is not being changed: the route paths and the field names.
///
/// These tests share one process-wide store, exactly as the JS module does, so they must not
/// run in parallel; each resets it and points DATA_DIR at its own temp directory first.
class ServerTest {

    @BeforeEach
    void fresh(@TempDir Path dir) throws IOException {
        State.DATA_DIR = dir;
        State.flushNow();
        resetStore();
        Server.clearReachCache();
        Files.deleteIfExists(State.stateFile());
        Files.deleteIfExists(State.eventsFile());
    }

    @AfterAll
    static void drainPendingFlushes() throws IOException {
        Path dir = Files.createTempDirectory("ijt-server-drain-");
        State.DATA_DIR = dir;
        State.flushNow();
        Files.deleteIfExists(dir.resolve("state.json"));
        Files.deleteIfExists(dir);
    }

    private static void resetStore() {
        State.Store s = State.STATE;
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

    private static final UnaryOperator<String> NO_ENV = k -> null;

    /// A run with a repo URL, so `slugify(config.repoUrl)` has something to key the ledgers on.
    private static void startRun(Map<String, Object> overrides) {
        startRun(NO_ENV, overrides);
    }

    private static void startRun(UnaryOperator<String> env, Map<String, Object> overrides) {
        Map<String, Object> o = new LinkedHashMap<>(overrides);
        o.putIfAbsent("repoUrl", "https://github.com/acme/widgets");
        State.STATE.run = State.freshRun(State.envConfig(env), o);
    }

    private static void startRun() { startRun(Map.of()); }

    /// One unit record, spelled the way the store spells it.
    private static Map<String, Object> unit(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static void put(String key, Map<String, Object> record) {
        record.putIfAbsent("path", key.contains("::") ? key.substring(0, key.indexOf("::")) : key);
        State.STATE.files.put(key, record);
    }

    // ── unit keys ─────────────────────────────────────────────────────────────

    @Test
    void aUnitKeyIsSplitOnlyWhenTheRecordDoesNotKnowItsPath() {
        put("src/main/java/a/B.java::run", unit("path", "src/main/java/a/B.java", "method", "run"));
        assertEquals("src/main/java/a/B.java", Server.unitPath("src/main/java/a/B.java::run"));
        assertEquals("run", Server.unitMethod("src/main/java/a/B.java::run"));
        // a key nothing has recorded still yields both halves
        assertEquals("x/Y.java", Server.unitPath("x/Y.java::z"));
        assertEquals("z", Server.unitMethod("x/Y.java::z"));
    }

    @Test
    void aFileKeyHasNoMethod() {
        assertEquals("x/Y.java", Server.unitPath("x/Y.java"));
        assertNull(Server.unitMethod("x/Y.java"));
    }

    @Test
    void theLabelIsClassHashMethod() {
        put("src/main/java/org/json/XML.java::parse",
                unit("fqcn", "org.json.XML", "method", "parse"));
        assertEquals("XML#parse()", Server.unitLabel("src/main/java/org/json/XML.java::parse"));
    }

    @Test
    void aUnitWithNoFqcnLabelsItselfFromThePathAndReadsPoorly() {
        // Faithful to the Node sidecar: the split is on `[./]` and takes the LAST segment, so a
        // path ending in `.java` yields the segment "java". The trailing `.replace(/\.java$/, '')`
        // can never fire, because the split has already removed the dot. It only ever reaches an
        // event line, and correcting it here would make the two implementations disagree.
        put("src/main/java/org/json/XML.java", unit());
        assertEquals("java", Server.unitLabel("src/main/java/org/json/XML.java"));
    }

    // ── reachability ──────────────────────────────────────────────────────────

    @Test
    void anythingButPrivateIsDirectlyCallableFromATestInTheSamePackage() {
        // Generated tests live in the same package (src/test/java mirrors src/main/java), so
        // package-private and protected members are callable. Treating them as hidden dropped
        // ordinary, testable methods from the queue.
        String src = """
                class A {
                  void packagePrivate() { }
                  protected void guarded() { }
                  public void open() { }
                }
                """;
        assertEquals("public", Server.reachFor(src, "packagePrivate"));
        assertEquals("public", Server.reachFor(src, "guarded"));
        assertEquals("public", Server.reachFor(src, "open"));
    }

    @Test
    void anUnknownVisibilityCountsAsCallableRatherThanCostingTheUnit() {
        assertEquals("public", Server.reachFor("class A { }", "inherited"));
    }

    @Test
    void aPrivateMethodIsReachableOnlyThroughItsCallers() {
        String withCaller = """
                class A {
                  public int open(int x) { return hidden(x); }
                  private int hidden(int x) { return x; }
                }
                """;
        assertEquals("route", Server.reachFor(withCaller, "hidden"));

        String orphan = """
                class A {
                  private int hidden(int x) { return x; }
                }
                """;
        assertEquals("none", Server.reachFor(orphan, "hidden"));
    }

    @Test
    void aConstructorIsAlwaysCallableAndNeverConsultsTheSource() {
        assertEquals("public", Server.reachOf("does/not/exist.java", "<init>"));
        assertEquals("public", Server.reachOf("does/not/exist.java", "<clinit>"));
        assertEquals("public", Server.reachOf("does/not/exist.java", null));
    }

    // ── the repo headline ─────────────────────────────────────────────────────

    @Test
    void theRepoMutationScoreIsTheMeanOfEveryMeasuredUnitNotTheLastOne() {
        startRun();
        State.STATE.run.baseline.coveragePct = 50.0;
        put("a.java::x", unit("mutationBefore", 20.0, "mutationAfter", 40.0));
        put("b.java::y", unit("mutationBefore", 60.0, "mutationAfter", 80.0));
        Server.refreshTotals();
        assertEquals(40.0, State.STATE.run.baseline.mutationPct);
        assertEquals(60.0, State.STATE.run.result.mutationPct);
        assertEquals(2, State.STATE.run.measuredFiles);
        // MAC = coverage% x mutation% / 100
        assertEquals(20.0, State.STATE.run.baseline.mac);
    }

    @Test
    void aUnitWithNoMutationSurfaceIsLeftOutOfTheHeadline() {
        // Averaging its 0 % in says the repo is weaker than it is, on the strength of methods
        // that cannot be mutated at all.
        startRun();
        put("a.java::x", unit("mutationBefore", 80.0));
        put("b.java::y", unit("mutationBefore", 0.0, "status", "no_mutants"));
        Server.refreshTotals();
        assertEquals(80.0, State.STATE.run.baseline.mutationPct);
        assertEquals(1, State.STATE.run.measuredFiles);
    }

    @Test
    void aUnitNotYetImprovedContributesItsBaselineToTheAfterAverage() {
        startRun();
        put("a.java::x", unit("mutationBefore", 30.0, "mutationAfter", 90.0));
        put("b.java::y", unit("mutationBefore", 10.0));
        Server.refreshTotals();
        assertEquals(20.0, State.STATE.run.baseline.mutationPct);
        assertEquals(50.0, State.STATE.run.result.mutationPct);
    }

    @Test
    void nothingMeasuredLeavesTheTotalsUntouched() {
        startRun();
        put("a.java::x", unit("status", "candidate"));
        Server.refreshTotals();
        assertNull(State.STATE.run.baseline.mutationPct);
        assertNull(State.STATE.run.measuredFiles);
    }

    @Test
    void theResultMacFallsBackToTheBaselineCoverageWhenNoNewOneWasMeasured() {
        startRun();
        State.STATE.run.baseline.coveragePct = 40.0;
        put("a.java::x", unit("mutationBefore", 50.0));
        Server.refreshTotals();
        assertEquals(20.0, State.STATE.run.result.mac);
    }

    // ── work accounting ───────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> work() {
        return (Map<String, Object>) Server.metricsPayload().get("work");
    }

    private static Map<String, Object> timesheet(int totalMin) {
        return unit("totalMin", totalMin, "hours", Util.round2(totalMin / 60.0));
    }

    @Test
    void humanHoursAreNotIntegerDivided() {
        // 88 minutes is 1.47 h. Integer division would report every sub-hour total as 0 and the
        // dashboard would read "Human-equivalent work 0" beside two improved files.
        startRun();
        put("a.java::x", unit("status", "improved", "timesheet", timesheet(88), "spentSec", 100L));
        assertEquals(1.47, work().get("humanHours"));
    }

    @Test
    void theFteRatioWaitsUntilThereIsRealMachineTimeToDivideBy() {
        startRun();
        put("a.java::x", unit("status", "improved", "timesheet", timesheet(60), "spentSec", 600L));
        assertNull(work().get("fte"), "600 s is not MORE than 600 s");

        State.STATE.files.clear();
        put("a.java::x", unit("status", "improved", "timesheet", timesheet(60), "spentSec", 1200L));
        // 60 human minutes = 3600 human seconds against 1200 machine seconds
        assertEquals(3.0, work().get("fte"));
        assertEquals(1, work().get("fteBasis"));
    }

    @Test
    void fteComparesOnlyFilesThatHaveBothANumberOfHoursAndAMeasuredMachineTime() {
        // Files improved before the stopwatch existed carry human-equivalent hours but no machine
        // time, and would inflate the ratio.
        startRun();
        put("a.java::x", unit("status", "improved", "timesheet", timesheet(120), "spentSec", 3600L));
        put("b.java::y", unit("status", "improved", "timesheet", timesheet(600)));      // no stopwatch
        put("c.java::z", unit("status", "improved", "spentSec", 3600L));                // no timesheet
        Map<String, Object> w = work();
        assertEquals(1, w.get("fteBasis"));
        assertEquals(2.0, w.get("fte"));
        // the headline hours still count every file, measured or not
        assertEquals(Util.round2(720 / 60.0), w.get("humanHours"));
    }

    @Test
    void anEtaNeedsAtLeastOneSettledFileThatWasActuallyTimed() {
        startRun();
        put("a.java::x", unit("status", "improved"));               // settled, never timed
        put("b.java::y", unit("status", "candidate"));
        assertNull(work().get("etaSec"));

        State.STATE.files.clear();
        put("a.java::x", unit("status", "improved", "spentSec", 300L));
        put("b.java::y", unit("status", "candidate"));
        put("c.java::z", unit("status", "candidate"));
        Map<String, Object> w = work();
        assertEquals(2L, w.get("remaining"));
        assertEquals(600L, w.get("etaSec"));
    }

    /// A run that is not going has no pace, no ETA and no growing clock.
    ///
    /// These three are what made a dead run look busy. `picked` with a live `attemptStartedAt`
    /// is exactly what a crash leaves behind, so machineHours grew a second per second while
    /// nothing executed, and the ETA card went on predicting a future for a run that needed
    /// someone to restart it first. Observed on the deployment, 21 units in, nothing running.
    @Test
    void aRunThatIsNotRunningReportsNoPaceAndNoGrowingClock() {
        startRun();
        long start = Util.nowSec() - 100;
        put("a.java::x", unit("status", "improved", "spentSec", 300L));
        put("b.java::y", unit("status", "picked", "attemptStartedAt", start));
        put("c.java::z", unit("status", "candidate"));

        // while it is running, both are reported
        Map<String, Object> running = work();
        assertNotNull(running.get("etaSec"));
        assertEquals(0.11, running.get("machineHours"));   // 300s settled + 100s live

        State.STATE.run.status = "interrupted";
        Map<String, Object> dead = work();
        assertNull(dead.get("etaSec"), "an ETA for a run nobody is running");
        assertEquals(0.08, dead.get("machineHours"), "the stopwatch kept ticking on a dead run");
    }

    @Test
    void metricsReportsHowLongTheStageHasBeenStill() {
        // Already computed for the run/start 409 message and never put on the wire, so the page
        // could not tell a slow unit from a stopped pipeline.
        startRun();
        State.setStage("improving", "a.java::x");
        State.STATE.stage = new State.Stage(State.STATE.stage.name(), State.STATE.stage.detail(),
                Util.nowSec() - 240, null);
        assertEquals(240L, State.asLong(work().get("idleSec")), 1.0);
    }

    @Test
    void tokensPerImprovedIsNullUntilSomethingHasBeenImproved() {
        startRun();
        State.STATE.run.tokens.in = 1000;
        State.STATE.run.tokens.out = 500;
        put("a.java::x", unit("status", "failed"));
        assertNull(work().get("tokensPerImproved"));

        put("b.java::y", unit("status", "improved"));
        assertEquals(1500L, work().get("tokensPerImproved"));
    }

    @Test
    void onlyThePickedFilesStopwatchIsStillRunning() {
        // A crashed attempt must not keep accruing time forever, so only the file the pipeline is
        // actually working on has its live stopwatch counted.
        startRun();
        long start = Util.nowSec() - 100;
        put("a.java::x", unit("status", "picked", "attemptStartedAt", start));
        put("b.java::y", unit("status", "failed", "attemptStartedAt", start));
        // 100 s is 0.03 h to two places; counting both stopwatches would report 0.06
        assertEquals(0.03, work().get("machineHours"));
    }

    @Test
    void cloneInstallAndBaselineTimeCountAsMachineTimeToo() {
        // Without them the FTE ratio flatters the pipeline: on short batches the setup dominates.
        startRun();
        State.STATE.overheadLedger.put(Util.slugify("https://github.com/acme/widgets"), 3600L);
        put("a.java::x", unit("status", "improved", "spentSec", 3600L));
        assertEquals(2.0, work().get("machineHours"));
    }

    // ── the dashboard's file rows ─────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows() {
        return (List<Map<String, Object>>) Server.metricsPayload().get("files");
    }

    @Test
    void rowsAreWeakestFirstAndAnUnmeasuredUnitSortsLast() {
        startRun();
        put("a.java::x", unit("method", "x", "status", "picked", "mac", 40.0));
        put("b.java::y", unit("method", "y", "status", "picked"));            // no mac at all
        put("c.java::z", unit("method", "z", "status", "picked", "mac", 5.0));
        assertEquals(List.of("c.java::z", "a.java::x", "b.java::y"),
                rows().stream().map(r -> String.valueOf(r.get("path"))).toList());
    }

    @Test
    void filesThePipelineHasTouchedWinTheRowsBeforeUntouchedCandidates() {
        startRun();
        put("cand.java::a", unit("method", "a", "status", "candidate", "mac", 1.0));
        put("done.java::b", unit("method", "b", "status", "improved", "mac", 99.0));
        assertEquals(List.of("done.java::b", "cand.java::a"),
                rows().stream().map(r -> String.valueOf(r.get("path"))).toList());
    }

    @Test
    void aRowNamesTheUnitAndTheSourceFileSeparately() {
        // `path` is the unit key the workflow passes back; `sourcePath` is what git cares about.
        startRun();
        put("src/main/java/a/B.java::run", unit("method", "run", "status", "picked"));
        Map<String, Object> row = rows().get(0);
        assertEquals("src/main/java/a/B.java::run", row.get("path"));
        assertEquals("src/main/java/a/B.java", row.get("sourcePath"));
        assertEquals("run", row.get("method"));
    }

    @Test
    void aUnitWithNoTimesheetOmitsTheKeyAndOneWhoseEstimateFailedKeepsItAsNull() {
        startRun();
        put("a.java::x", unit("status", "picked"));
        put("b.java::y", unit("status", "picked", "timesheet", null));
        List<Map<String, Object>> r = rows();
        assertFalse(r.get(0).containsKey("timesheet"), "never estimated → the key is absent");
        assertTrue(r.get(1).containsKey("timesheet"), "estimate attempted and failed → explicit null");
        assertNull(r.get(1).get("timesheet"));
    }

    @Test
    void theRowCapIsTwoHundredAndFifty() {
        startRun();
        for (int i = 0; i < 300; i++) put("f" + i + ".java::m", unit("status", "candidate"));
        assertEquals(250, rows().size());
        assertEquals(300, work().get("totalFiles"), "the count is of everything, not of what is shown");
    }

    // ── one unit per method ───────────────────────────────────────────────────

    private static Coverage.ClassCov classCov(Object... nameMissedCoveredLine) {
        Coverage.ClassCov c = new Coverage.ClassCov();
        for (int i = 0; i < nameMissedCoveredLine.length; i += 4) {
            Coverage.MethodCov m = methodCov((int) nameMissedCoveredLine[i + 3]);
            m.missed = (int) nameMissedCoveredLine[i + 1];
            m.covered = (int) nameMissedCoveredLine[i + 2];
            c.methods.put(String.valueOf(nameMissedCoveredLine[i]), m);
        }
        return c;
    }

    private static Coverage.MethodCov methodCov(int line) {
        // the package-private constructor is the only way in, and it is in this package
        return new Coverage.MethodCov(line);
    }

    @Test
    void aClassBecomesOneUnitPerMethodKeyedPathColonColonMethod() {
        startRun(Map.of("minUnitLines", 1));
        put("src/main/java/a/B.java", unit("fqcn", "a.B", "module", ".", "lines", 40));
        Server.expandFilesIntoMethodUnits(Map.of("a.B", classCov("run", 2, 6, 12)));

        assertFalse(State.STATE.files.containsKey("src/main/java/a/B.java"),
                "the class record is replaced by its method units");
        Map<String, Object> u = State.STATE.files.get("src/main/java/a/B.java::run");
        assertNotNull(u);
        assertEquals("run", u.get("method"));
        assertEquals("a.B", u.get("fqcn"));
        assertEquals(12, u.get("methodLine"));
        assertEquals(75.0, u.get("coverage"));      // 6 of 8 executable lines
        assertEquals(8, u.get("executableLines"));
        assertEquals("candidate", u.get("status"));
    }

    @Test
    void aStaticInitialiserIsNeverAUnitOfWork() {
        // PIT finds nothing worth mutating in one, and a run spent there buys nothing.
        startRun(Map.of("minUnitLines", 1));
        put("src/main/java/a/B.java", unit("fqcn", "a.B"));
        Server.expandFilesIntoMethodUnits(Map.of("a.B", classCov("<clinit>", 0, 9, 3)));
        assertTrue(State.STATE.files.isEmpty());
    }

    @Test
    void aMethodJaCoCoReportsNoExecutableLineForIsNotAUnit() {
        startRun(Map.of("minUnitLines", 1));
        put("src/main/java/a/B.java", unit("fqcn", "a.B"));
        Server.expandFilesIntoMethodUnits(Map.of("a.B", classCov("abstractish", 0, 0, 5)));
        assertTrue(State.STATE.files.isEmpty());
    }

    @Test
    void aMethodWithTooLittleBehaviourToVerifyIsSkipped() {
        // A one-line private constructor or a getter returning a field: PIT finds nothing worth
        // mutating. The first Gradle run picked `Assertions#<init>()` and failed on it.
        startRun(Map.of("minUnitLines", 3));
        put("src/main/java/a/B.java", unit("fqcn", "a.B"));
        Server.expandFilesIntoMethodUnits(Map.of("a.B",
                classCov("getter", 0, 1, 4, "worker", 1, 5, 20)));
        assertEquals(List.of("src/main/java/a/B.java::worker"), List.copyOf(State.STATE.files.keySet()));
    }

    @Test
    void anUnreadableFloorSettingLeavesTwoExecutableLinesAsTheDefault() {
        // The route's own `?? 2`, which the environment's own default of 1 usually hides.
        // `MIN_UNIT_LINES=nonsense` is `parseInt` → NaN → null, and a null floor is not a floor
        // of zero: without the fallback every one-line accessor in the repo becomes a unit.
        startRun(k -> "MIN_UNIT_LINES".equals(k) ? "nonsense" : null, Map.of());
        assertNull(State.STATE.run.config.minUnitLines());
        put("src/main/java/a/B.java", unit("fqcn", "a.B"));
        Server.expandFilesIntoMethodUnits(Map.of("a.B",
                classCov("getter", 0, 1, 4, "pair", 1, 1, 20)));
        assertEquals(List.of("src/main/java/a/B.java::pair"), List.copyOf(State.STATE.files.keySet()));
    }

    @Test
    void aClassTheCoverageReportNeverMentionsIsDroppedWithoutBecomingAUnit() {
        startRun(Map.of("minUnitLines", 1));
        put("src/main/java/a/B.java", unit("fqcn", "a.B"));
        Server.expandFilesIntoMethodUnits(Map.of());
        assertTrue(State.STATE.files.isEmpty());
    }

    @Test
    void alreadyExpandedUnitsAreLeftAlone() {
        startRun(Map.of("minUnitLines", 1));
        put("src/main/java/a/B.java::run", unit("fqcn", "a.B", "method", "run", "status", "picked"));
        Server.expandFilesIntoMethodUnits(Map.of("a.B", classCov("run", 0, 9, 3)));
        assertEquals("picked", State.STATE.files.get("src/main/java/a/B.java::run").get("status"));
    }

    // ── the candidate queue ───────────────────────────────────────────────────

    /// The reach memo, pre-seeded so the queue never reaches for a repository that is not there.
    private static void reachable(String path, String method) {
        Server.REACH_CACHE.put(path + "::" + method, "public");
    }

    private static Map<String, Object> candidatesOf() {
        return Server.candidates();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> candidateList() {
        return (List<Map<String, Object>>) candidatesOf().get("candidates");
    }

    private static void candidate(String key, Object... kv) {
        Map<String, Object> m = unit(kv);
        m.putIfAbsent("status", "candidate");
        m.putIfAbsent("attempts", 0);
        m.putIfAbsent("key", key);
        m.putIfAbsent("method", key.substring(key.indexOf("::") + 2));
        put(key, m);
        reachable(String.valueOf(m.get("path")), String.valueOf(m.get("method")));
    }

    @Test
    void anEmptyQueueEndsTheBatch() {
        startRun();
        Map<String, Object> c = candidatesOf();
        assertEquals(true, c.get("done"));
        assertEquals("no remaining candidate units", c.get("reason"));
    }

    @Test
    void unitsSettledInPreviousBatchesDoNotConsumeThisBatchesQuota() {
        // A ledger replay must not spend the scope limit on work that was already done.
        startRun(Map.of("scopeLimit", 2));
        put("old1.java::a", unit("status", "improved", "fromLedger", true));
        put("old2.java::b", unit("status", "no_improvement", "fromLedger", true));
        candidate("new.java::c");
        Map<String, Object> c = candidatesOf();
        assertEquals(0L, c.get("processed"));
        assertEquals(false, c.get("done"));
    }

    @Test
    void theScopeLimitCountsOnlyWhatThisBatchSettled() {
        startRun(Map.of("scopeLimit", 2));
        put("a.java::a", unit("status", "improved"));
        put("b.java::b", unit("status", "no_improvement"));
        candidate("c.java::c");
        Map<String, Object> c = candidatesOf();
        assertEquals(2L, c.get("processed"));
        assertEquals(true, c.get("done"));
        assertEquals("scope limit (2 units) reached", c.get("reason"));
    }

    @Test
    void failuresAreBoundedSeparatelySoOneBrokenMeasurementCannotSpendTheWholeQuota() {
        // A unit we could not even measure says nothing about the repo. java-dataloader produced
        // 0 PRs from its first pick because a failure consumed the scope limit.
        startRun(Map.of("scopeLimit", 1, "maxFailures", 3));
        put("a.java::a", unit("status", "failed"));
        candidate("b.java::b");
        assertEquals(false, candidatesOf().get("done"), "one failure is not one unit processed");

        put("c.java::c", unit("status", "failed"));
        put("d.java::d", unit("status", "failed"));
        Map<String, Object> c = candidatesOf();
        assertEquals(true, c.get("done"));
        assertEquals(3L, c.get("failed"));
        assertTrue(String.valueOf(c.get("reason")).contains("the repo or its toolchain is the problem"));
    }

    @Test
    void theIterationCapIsCheckedBeforeTheScopeLimit() {
        startRun(Map.of("maxIterations", 1, "scopeLimit", 1));
        State.STATE.run.iteration = 5;
        put("a.java::a", unit("status", "improved"));
        Map<String, Object> c = candidatesOf();
        assertEquals("max iterations (1) reached", c.get("reason"));
    }

    @Test
    void aUnitWithNoExecutableLinesIsNotACandidate() {
        startRun();
        candidate("a.java::a", "executableLines", 0);
        assertTrue(candidateList().isEmpty());
    }

    @Test
    void aUnitWithAnUnknownLineCountIsStillACandidate() {
        // null is not zero: a unit nothing has measured yet has not been ruled out.
        startRun();
        candidate("a.java::a", "executableLines", null);
        assertEquals(1, candidateList().size());
    }

    @Test
    void aUnitAtItsAttemptCapIsNoLongerOffered() {
        startRun(Map.of("maxAttemptsPerFile", 2));
        candidate("a.java::a", "attempts", 2);
        candidate("b.java::b", "attempts", 1);
        assertEquals(List.of("b.java::b"), candidateList().stream()
                .map(x -> String.valueOf(x.get("path"))).toList());
    }

    @Test
    void theQueueIsWeakestFirstWithConstructorsLastAmongEquals() {
        startRun();
        candidate("a.java::strong", "mac", 80.0, "executableLines", 10);
        candidate("a.java::<init>", "mac", 10.0, "executableLines", 10);
        candidate("a.java::weak", "mac", 10.0, "executableLines", 10);
        assertEquals(List.of("a.java::weak", "a.java::<init>", "a.java::strong"),
                candidateList().stream().map(x -> String.valueOf(x.get("path"))).toList());
    }

    @Test
    void aCandidateRowCarriesTheUnitKeyAsPathAndTheSourceFileBeside() {
        startRun();
        candidate("src/main/java/a/B.java::run", "coverage", 40.0);
        Map<String, Object> row = candidateList().get(0);
        assertEquals("src/main/java/a/B.java::run", row.get("path"));
        assertEquals("src/main/java/a/B.java", row.get("file"));
        assertEquals("run", row.get("method"));
        assertEquals("public", row.get("reach"));
    }

    @Test
    void theQueueIsCappedAtAHundredButTheCountsAreNot() {
        startRun();
        for (int i = 0; i < 130; i++) candidate("f" + i + ".java::m", "mac", (double) i);
        assertEquals(100, candidateList().size());
        assertEquals(false, candidatesOf().get("done"));
    }

    // ── the stopwatch ─────────────────────────────────────────────────────────

    @Test
    void closingTheStopwatchAddsToCumulativeMachineTimeAndStopsIt() {
        startRun();
        put("a.java::x", unit("spentSec", 40L, "attemptStartedAt", Util.nowSec() - 10));
        long spent = Server.accrueSpent("a.java::x");
        assertTrue(spent >= 50 && spent <= 52, "expected ~50 s, got " + spent);
        assertNull(State.STATE.files.get("a.java::x").get("attemptStartedAt"));
        assertEquals(spent, State.asLong(State.STATE.files.get("a.java::x").get("spentSec")));
    }

    @Test
    void aStopwatchThatWasNeverStartedReportsWhatIsAlreadyBanked() {
        startRun();
        put("a.java::x", unit("spentSec", 7L));
        assertEquals(7L, Server.accrueSpent("a.java::x"));
        assertEquals(0L, Server.accrueSpent("nobody.java::x"));
    }

    // ── the route table ───────────────────────────────────────────────────────

    @Test
    void everyRouteTheWorkflowCallsIsSpelledExactlyAsTheNodeSidecarSpelledIt() {
        // The n8n workflow calls these by path and is not being changed. A rename here is a
        // silent 404 in production, so the whole table is written out rather than sampled.
        Map<String, Server.Route> routes = Server.routes();
        for (String key : List.of(
                "GET /api/health", "GET /api/state", "GET /api/metrics", "GET /api/llm/log",
                "GET /api/rules", "GET /api/events", "GET /api/files/candidates", "GET /api/files/gaps",
                "POST /api/stage", "POST /api/run/start", "POST /api/run/finish",
                // resume, not start: reopening the run a crash left terminal, without clearing
                // the files, decisions and events that `start` exists to clear
                "POST /api/run/resume",
                "POST /api/repo/clone", "POST /api/repo/prepare", "POST /api/coverage/run",
                "POST /api/rules/apply", "POST /api/iteration/start", "POST /api/pit/run",
                "POST /api/prompt/coverage", "POST /api/prompt/mutation", "POST /api/prompt/batch",
                "POST /api/prompt/repair", "POST /api/tests/parse", "POST /api/tests/parse-repair",
                "POST /api/test/write", "POST /api/test/delete", "POST /api/test/write-many",
                "POST /api/test/delete-many", "POST /api/test/run", "POST /api/test/cleanup",
                "POST /api/llm/chat", "POST /api/verify", "POST /api/round/miss",
                "POST /api/round/accept", "POST /api/round/drop", "POST /api/pr/create",
                "POST /api/iteration/discard", "POST /api/admin/purge-repo", "POST /api/admin/reset")) {
            assertTrue(routes.containsKey(key), "missing route: " + key);
        }
        assertEquals(39, routes.size(), "a route was added or removed without updating the workflow");
    }

    // ── the composition root ──────────────────────────────────────────────────

    @Test
    void startingTheBackendInstallsTheThreeCollaboratorsTheRoutesCall() {
        // The route table asserted above proves the PATHS exist; it says nothing about whether
        // anything answers them. `llm`, `pr` and `rules` are seams whose defaults throw, and
        // main() used to call only setProgressSink/load/start — so `POST /api/rules/apply`,
        // which the workflow calls at six points, threw on the first `Rules: post-clone` call
        // of every run, and Server.handle turns a 500 from a POST into run.status = 'failed'.
        // The run died before it had picked a file, and no unit test noticed because every test
        // that touches those three installs its own.
        Server.LlmClient llm0 = Server.llm();
        Server.PrGateway pr0 = Server.pr();
        Server.RulesEngine rules0 = Server.rules();
        try {
            assertThrows(IllegalStateException.class, () -> Server.rules().apply("post_clone", Map.of()),
                    "the UNinstalled default must fail loudly — it is not a configuration");
            Server.installCollaborators();
            assertInstanceOf(Rules.class, Server.rules(), "rules.js's port, not the throwing stub");
            assertNotSame(llm0, Server.llm(), "an LLM client is installed");
            assertNotSame(pr0, Server.pr(), "a PR gateway is installed");
            // and it is the real one: the stub's answer to every call is an exception, so a
            // gateway that reads the run's configuration without throwing is not the stub
            assertNull(Server.prIo().prBase(), "no run configured yet, and that is not an error");
        } finally {
            Server.llm(llm0);
            Server.pr(pr0);
            Server.rules(rules0);
        }
    }

    @Test
    void everyRouteWithASubprocessCeilingActuallyExists() {
        // Timeouts states how long the n8n node must wait for each of these. A ceiling for a route
        // that is not in the table means the two files disagree about what the API is.
        Map<String, Server.Route> routes = Server.routes();
        for (String path : Timeouts.SUBPROCESS_CEILING_MS.keySet()) {
            assertTrue(routes.containsKey("POST " + path), "no route for ceiling " + path);
        }
    }

    // ── the static dashboard ──────────────────────────────────────────────────

    @Test
    void theDashboardRootServesIndexHtml() {
        Path root = Path.of("/app/dashboard");
        assertEquals(root.resolve("index.html"), Server.resolveStatic(root, "/"));
        assertEquals(root.resolve("index.html"), Server.resolveStatic(root, ""));
        assertEquals(root.resolve("index.html"), Server.resolveStatic(root, null));
    }

    @Test
    void aRequestThatClimbsOutOfTheDashboardIsRefused() {
        Path root = Path.of("/app/dashboard");
        assertNull(Server.resolveStatic(root, "/../../etc/passwd"));
        assertNull(Server.resolveStatic(root, "/a/../../secrets"));
        // stricter than the JS string-prefix check, which would have accepted a sibling directory
        // whose name merely starts with the dashboard's
        assertNull(Server.resolveStatic(root, "/../dashboard-evil/app.js"));
    }

    @Test
    void aNormalAssetResolvesUnderTheDashboard() {
        Path root = Path.of("/app/dashboard");
        assertEquals(root.resolve("app.js"), Server.resolveStatic(root, "/app.js"));
        assertEquals(root.resolve("assets/style.css"), Server.resolveStatic(root, "/assets/style.css"));
    }

    @Test
    void caddyStripsTheDashboardPrefixAndSoDoWe() {
        assertEquals("/app.js", Server.dashboardRelative("/dashboard/app.js"));
        assertEquals("/", Server.dashboardRelative("/dashboard"));
        assertEquals("/app.js", Server.dashboardRelative("/app.js"));
    }

    @Test
    void anAssetIsServedWithTheContentTypeItsExtensionNames() {
        assertEquals("text/html", Server.mimeOf(Path.of("/x/index.html")));
        assertEquals("text/javascript", Server.mimeOf(Path.of("/x/app.js")));
        assertEquals("text/css", Server.mimeOf(Path.of("/x/style.css")));
        assertEquals("application/octet-stream", Server.mimeOf(Path.of("/x/logo.png")));
        assertEquals("application/octet-stream", Server.mimeOf(Path.of("/x/LICENSE")));
    }

    // ── the request ───────────────────────────────────────────────────────────

    @Test
    void theQueryStringReadsLikeUrlSearchParams() {
        Server.Query q = Server.Query.of("after=12&after=99&name=a%20b&plus=a+b&flag");
        assertEquals("12", q.get("after"), "the FIRST value, as URLSearchParams.get answers");
        assertEquals("a b", q.get("name"));
        assertEquals("a b", q.get("plus"));
        assertEquals("", q.get("flag"));
        assertNull(q.get("missing"));
        assertNull(Server.Query.of(null).get("after"));
        assertNull(Server.Query.of("").get("after"));
    }

    @Test
    void aMalformedEscapeInTheQueryDoesNotTakeDownTheRoute() {
        // URLDecoder throws where URLSearchParams shrugs, and a route must not be reachable from
        // a hand-typed URL to a 500.
        assertEquals("%zz", Server.Query.of("x=%zz").get("x"));
    }

    @Test
    void anEmptyBodyIsAnEmptyObject() throws IOException {
        assertEquals(Map.of(), Server.readBody(body("")));
    }

    @Test
    void aBodyThatIsNotJsonIsRejectedByName() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Server.readBody(body("{oops")));
        assertEquals("bad JSON body", e.getMessage());
    }

    @Test
    void aBodyPastTwentyMegabytesIsRefused() {
        // A generated test file is kilobytes. Past this it is a mistake or an attack, not a round.
        byte[] big = new byte[Server.MAX_BODY_BYTES + 1];
        java.util.Arrays.fill(big, (byte) ' ');
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Server.readBody(new ByteArrayInputStream(big)));
        assertEquals("request body too large", e.getMessage());
    }

    private static ByteArrayInputStream body(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    // ── reordering, which is how the ranked lists keep their full records ─────

    @Test
    void rankingReturnsTheFullMutantRecordsInTheOrderSelectChose() {
        // Select reads only kind, line, index and difficulty; a prompt also needs PIT's status and
        // description. The records are matched back by key, and the match has to survive
        // duplicates — several mutations of one kind can share a line.
        List<Map<String, Object>> raw = List.of(
                unit("mutator", "MathMutator", "line", 10, "difficulty", 2, "description", "second"),
                unit("mutator", "MathMutator", "line", 10, "difficulty", 2, "description", "first-dup"),
                unit("mutator", "MathMutator", "line", 3, "difficulty", 0, "description", "easiest"));
        List<Map<String, Object>> ranked = Server.rankSurvivors(new ArrayList<>(raw));
        assertEquals(List.of("easiest", "second", "first-dup"),
                ranked.stream().map(m -> String.valueOf(m.get("description"))).toList(),
                "equal keys keep PIT's own order, because the sort is stable and the match is greedy");
    }

    @Test
    void aSurvivorAlreadyAttackedIsNotOfferedAgain() {
        List<Map<String, Object>> raw = List.of(
                unit("mutator", "MathMutator", "line", 10, "index", 1, "description", "tried"),
                unit("mutator", "MathMutator", "line", 10, "index", 2, "description", "fresh"));
        List<Map<String, Object>> left = Server.eligible(new ArrayList<>(raw), List.of("MathMutator@10#1"));
        assertEquals(List.of("fresh"), left.stream().map(m -> String.valueOf(m.get("description"))).toList());
    }

    // ── the JS value semantics this file leans on ─────────────────────────────

    @Test
    void aConfiguredZeroMeansUnsetAndAnAbsentValueMeansUnsetButNullishKeepsTheZero() {
        // `cfg.maxAttemptsPerFile || 3` — a 0 there configures THREE attempts, not zero, and a 0
        // would settle every unit before its first attempt. `?? 20` is the other rule entirely.
        assertEquals(3, Server.orDefault(0, 3));
        assertEquals(3, Server.orDefault(null, 3));
        assertEquals(5, Server.orDefault(5, 3));
        assertEquals(0, Server.nullish((Integer) 0, 20));
        assertEquals(20, Server.nullish((Integer) null, 20));
    }

    @Test
    void anEmptyStageNameFallsThroughToTheDefaultRatherThanNamingAStageNothing() {
        // `String(body.stage || 'idle')`, not `??`. An n8n Set node that leaves a field blank
        // posts an empty string, and a stage with no name shows as a blank strip on the
        // dashboard and matches nothing the workflow branches on.
        assertEquals("idle", Server.or("", "idle"));
        assertEquals("idle", Server.or(null, "idle"));
        assertEquals("cloning", Server.or("cloning", "idle"));
        // and the same rule read the other way: 0 is falsy too, which is why the token budget
        // spells `body.maxTokens || '4096'`
        assertEquals("4096", Server.or(0, "4096"));
    }

    @Test
    void anEmptyStageNameStillReachesSetStageAsIdle() throws IOException {
        Server.routes().get("POST /api/stage").handle(Server.Query.EMPTY,
                Map.of("stage", "", "detail", ""));
        assertEquals("idle", State.STATE.stage.name());
    }

    @Test
    void countingTestAnnotationsIsCountingOccurrencesNotLines() {
        assertEquals(0, Server.countOf("class A {}", "@Test"));
        assertEquals(2, Server.countOf("@Test void a(){} @Test void b(){}", "@Test"));
    }

    @Test
    void anExceptionWithNoMessageStillSaysSomething() {
        // JS `e.message` is always a string; a Java exception may carry none, and "null" in the
        // middle of an event line says less than the class that threw.
        assertEquals("java.lang.IllegalStateException", Server.message(new IllegalStateException()));
        assertEquals("boom", Server.message(new IllegalStateException("boom")));
    }

    // ── the run guard ─────────────────────────────────────────────────────────

    @Test
    void aSecondRunIsRefusedWithAConflictRatherThanAFailure() throws IOException {
        // A rejected concurrent start is a guard, not a run failure: one git worktree and one run
        // state, and overlapping executions would corrupt both.
        startRun();
        State.STATE.stage = new State.Stage("improving_mac", "busy", Util.nowSec(), null);
        Server.HttpFailure e = assertThrows(Server.HttpFailure.class,
                () -> Server.routes().get("POST /api/run/start").handle(Server.Query.EMPTY, Map.of()));
        assertEquals(409, e.statusCode);
        assertTrue(e.getMessage().contains("a run is already active"));
    }

    @Test
    void theBatchDriverCanTakeOverWithForce() throws IOException {
        startRun();
        String first = State.STATE.run.id;
        State.STATE.stage = new State.Stage("improving_mac", "busy", Util.nowSec(), null);
        Object out = Server.routes().get("POST /api/run/start")
                .handle(Server.Query.EMPTY, Map.of("force", true, "repoUrl", "https://github.com/acme/widgets"));
        assertEquals(true, ((Map<?, ?>) out).get("ok"));
        assertNotEquals(first, State.STATE.run.id);
    }

    @Test
    void aRunLeftBehindByARestartedSidecarIsNeverAConcurrencyConflict() throws IOException {
        // 'interrupted' means the sidecar restarted: whatever execution owned that run is gone.
        startRun();
        State.STATE.stage = new State.Stage("interrupted", "sidecar restarted", Util.nowSec(), null);
        assertDoesNotThrow(() -> Server.routes().get("POST /api/run/start")
                .handle(Server.Query.EMPTY, Map.of("repoUrl", "https://github.com/acme/widgets")));
    }

    @Test
    void startingARunForgetsWhatTheLastOneLearnedAboutMutatorKinds() throws IOException {
        // What a run learns about mutator kinds is about THIS repo and THIS run: carrying it over
        // demotes a mutator in run B for misses that happened in run A.
        startRun();
        State.STATE.mutatorStats = new LinkedHashMap<>(Map.of("MathMutator", unit("tried", 4, "killed", 0)));
        State.STATE.currentUnit = "a.java::x";
        put("a.java::x", unit("status", "picked"));
        Server.REACH_CACHE.put("a.java::x", "none");
        State.STATE.run.status = "done";   // nothing to take over, so no concurrency guard

        Server.routes().get("POST /api/run/start")
                .handle(Server.Query.EMPTY, Map.of("repoUrl", "https://github.com/acme/widgets"));

        assertTrue(State.STATE.mutatorStats.isEmpty());
        assertTrue(State.STATE.files.isEmpty());
        assertNull(State.STATE.currentUnit);
        // the reach memo is keyed by repo-RELATIVE path, so it would answer for the previous
        // repo's file of the same name
        assertTrue(Server.REACH_CACHE.isEmpty());
    }

    @Test
    void everyRouteThatNeedsARunSaysSoRatherThanFailingOnANullDereference() {
        assertNull(State.STATE.run);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Server.routes().get("POST /api/repo/clone").handle(Server.Query.EMPTY, Map.of()));
        assertTrue(e.getMessage().contains("POST /api/run/start"));
    }

    @Test
    void resettingClearsTheRunButNotTheLedgersThatOutliveIt() throws IOException {
        startRun();
        State.STATE.improvedLedger.put("acme-widgets", new LinkedHashMap<>(Map.of("a.java::x", unit("state", "improved"))));
        put("a.java::x", unit("status", "improved"));
        Server.routes().get("POST /api/admin/reset").handle(Server.Query.EMPTY, Map.of());
        assertNull(State.STATE.run);
        assertTrue(State.STATE.files.isEmpty());
        // the counter is zeroed and then the reset itself is logged, so the feed starts again at
        // seq 1 with one line in it — not at 0 with none
        assertEquals(1, State.STATE.seq);
        assertEquals(List.of("state reset"), State.STATE.events.stream().map(State.Event::msg).toList());
        assertFalse(State.STATE.improvedLedger.isEmpty(),
                "the ledger is what lets a batched run resume; only purge-repo removes it");
    }

    // ── health and events, which the dashboard polls ──────────────────────────

    @Test
    void healthNamesTheServiceAndTheStage() throws IOException {
        Object out = Server.routes().get("GET /api/health").handle(Server.Query.EMPTY, Map.of());
        Map<?, ?> m = (Map<?, ?>) out;
        assertEquals(true, m.get("ok"));
        assertEquals("improve-java-tests", m.get("service"));
        assertEquals("idle", m.get("stage"));
    }

    @Test
    void eventsArePagedOnASequenceThatOnlyEverGoesUp() throws IOException {
        State.event("starting", "one");
        State.event("starting", "two");
        Object out = Server.routes().get("GET /api/events").handle(Server.Query.of("after=1"), Map.of());
        List<?> events = (List<?>) ((Map<?, ?>) out).get("events");
        assertEquals(1, events.size());
        assertEquals("two", ((State.Event) events.get(0)).msg());
    }

    @Test
    void anAbsentAfterParameterReadsAsZeroAndReturnsEverything() throws IOException {
        State.event("starting", "one");
        Object out = Server.routes().get("GET /api/events").handle(Server.Query.EMPTY, Map.of());
        assertEquals(1, ((List<?>) ((Map<?, ?>) out).get("events")).size());
    }

    @Test
    void theModelDialogIsNewestFirstAndSeparateFromTheMetricsPoll() throws IOException {
        State.addLlmExchange(new LinkedHashMap<>(Map.of("prompt", "first")));
        State.addLlmExchange(new LinkedHashMap<>(Map.of("prompt", "second")));
        Object out = Server.routes().get("GET /api/llm/log").handle(Server.Query.EMPTY, Map.of());
        List<?> entries = (List<?>) ((Map<?, ?>) out).get("entries");
        assertEquals("second", ((Map<?, ?>) entries.get(0)).get("prompt"));
        // and it is NOT part of the metrics payload the dashboard polls every two seconds
        assertFalse(Server.metricsPayload().containsKey("llmLog"));
    }

    @Test
    void theStateRouteIsTheStoreVerbatim() throws IOException {
        startRun();
        Object out = Server.routes().get("GET /api/state").handle(Server.Query.EMPTY, Map.of());
        assertEquals(State.snapshotJson(), ((Server.RawJson) out).json());
    }
}
