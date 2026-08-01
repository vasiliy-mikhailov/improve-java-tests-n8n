package tech.mikhailov.ijt;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/// The store every other module mutates, and the two files it owns.
///
/// state.js has no unit test of its own in the Node sidecar — it is exercised through the
/// modules that import it. That is exactly why it is worth pinning here: the defaults below
/// decide whether a run stops after three attempts or never stops, the coercions decide what
/// `MAX_ATTEMPTS_PER_FILE=0` means, and the on-disk shape is read by the dashboard and by the
/// n8n workflow, neither of which is being changed.
///
/// These tests share one process-wide store (as the JS module does) and so must not run in
/// parallel; each resets it and points DATA_DIR at its own temp directory first.
class StateTest {

    @BeforeEach
    void fresh(@TempDir Path dir) throws IOException {
        State.DATA_DIR = dir;   // this test's own data directory…
        State.flushNow();       // …and drain whatever the previous test left owed, into it
        resetStore();
        Files.deleteIfExists(State.stateFile());
        Files.deleteIfExists(State.eventsFile());
    }

    @AfterAll
    static void drainPendingFlushes() throws IOException {
        // A debounced flush outlives the test that asked for it, and the shutdown hook would
        // otherwise recreate a deleted temp directory just to land it. Give it somewhere
        // disposable and take the directory with it.
        Path dir = Files.createTempDirectory("ijt-state-drain-");
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

    private static UnaryOperator<String> env(Map<String, String> vars) {
        return vars::get;
    }

    private static final UnaryOperator<String> NO_ENV = env(Map.of());

    // ── the environment ───────────────────────────────────────────────────────

    @Test
    void anEmptyEnvironmentStillDescribesACompleteRun() {
        State.Config c = State.envConfig(NO_ENV);
        assertEquals("", c.repoUrl());
        assertEquals("main", c.repoBranch());
        assertEquals("**/src/main/java/**/*.java", c.scopeGlob());
        assertEquals(0, c.scopeLimit());
        assertEquals(0, c.maxIterations(), "0 = unlimited");
        assertEquals(0, c.maxRoundsPerFile(), "0 = no cap");
        assertEquals(8, c.mutantsPerRound());
        assertEquals(20, c.mutantChoices());
        assertEquals(3600, c.unitBudgetSec());
        assertEquals(3, c.maxConsecutiveMisses());
        assertEquals(3, c.maxAttemptsPerFile());
        assertEquals(0.0, c.minRoundGapFrac());
        assertEquals(0.0, c.minRoundGain());
        assertEquals(1, c.minMutantsPerClass());
        assertEquals("method", c.pitScope());
        assertEquals(1, c.minUnitLines());
        assertEquals(10, c.maxFailures());
        assertEquals(0.0, c.covPhaseMaxPct());
        assertEquals("github", c.prMode());
        assertEquals("", c.prBase(), "envConfig alone does not fall back to the branch; freshRun does");
        assertEquals(State.Rules.EMPTY, c.rules());
        assertEquals(Boolean.FALSE, c.dryRun());
    }

    @Test
    void anEnvVarSetToNothingIsAnUnsetOne() {
        // `e.REPO_BRANCH || 'main'`. A compose file that passes REPO_BRANCH= must get main, not
        // a branch with no name — which would clone nothing and fail three phases later.
        State.Config c = State.envConfig(env(Map.of("REPO_BRANCH", "", "PIT_SCOPE", "", "MUTANTS_PER_ROUND", "")));
        assertEquals("main", c.repoBranch());
        assertEquals("method", c.pitScope());
        assertEquals(8, c.mutantsPerRound());
    }

    @Test
    void dryRunIsExactlyTheStringTrue() {
        assertEquals(Boolean.TRUE, State.envConfig(env(Map.of("DRY_RUN", "true"))).dryRun());
        assertEquals(Boolean.FALSE, State.envConfig(env(Map.of("DRY_RUN", "TRUE"))).dryRun());
        assertEquals(Boolean.FALSE, State.envConfig(env(Map.of("DRY_RUN", "1"))).dryRun());
        assertEquals(Boolean.FALSE, State.envConfig(env(Map.of("DRY_RUN", "yes"))).dryRun());
    }

    @Test
    void anUnparseableNumberIsUnknownRatherThanZero() {
        // parseInt('nonsense') is NaN, and JSON.stringify(NaN) is null. Reporting it as 0 would
        // read as a deliberate "unlimited" on scopeLimit and as a deliberate "no cap" on rounds.
        State.Config c = State.envConfig(env(Map.of("SCOPE_LIMIT", "nonsense", "MIN_ROUND_GAIN", "nonsense")));
        assertNull(c.scopeLimit());
        assertNull(c.minRoundGain());
    }

    @Test
    void numbersAreReadTheWayJavascriptReadsThem() {
        // Configuration is typed by humans into env files and dashboard forms.
        assertEquals(30, State.jsParseInt("30s"));
        assertEquals(7, State.jsParseInt(" 7"));
        assertEquals(-5, State.jsParseInt("-5"));
        assertEquals(3, State.jsParseInt(3.7), "parseInt stringifies first, so 3.7 truncates");
        assertNull(State.jsParseInt("abc"));
        assertNull(State.jsParseInt(""));
        assertNull(State.jsParseInt(null));
        assertEquals(0.25, State.jsParseFloat("0.25"));
        assertEquals(0.25, State.jsParseFloat(".25"));
        assertEquals(12.0, State.jsParseFloat("12"));
        assertNull(State.jsParseFloat("half"));
    }

    // ── the request body over the environment ─────────────────────────────────

    private static final State.Config BASE = State.envConfig(env(Map.of(
            "REPO_URL", "https://example.test/x.git", "REPO_BRANCH", "trunk", "SCOPE_LIMIT", "5")));

    @Test
    void theBodyOverridesTheEnvironment() {
        State.Config c = State.applyOverrides(BASE, Map.of(
                "repoUrl", "https://example.test/y.git",
                "scopeLimit", "50",          // an HTML form posts numbers as text
                "pitScope", "class"));
        assertEquals("https://example.test/y.git", c.repoUrl());
        assertEquals(50, c.scopeLimit());
        assertEquals("class", c.pitScope());
        assertEquals("trunk", c.repoBranch(), "what the body says nothing about is left alone");
    }

    @Test
    void anEmptyOrAbsentOverrideLeavesTheEnvironmentAlone() {
        // The dashboard posts every field it renders, blank ones included. Treating '' as a value
        // would blank out the repo URL of a run started from the environment.
        Map<String, Object> body = new HashMap<>();
        body.put("repoUrl", "");
        body.put("repoBranch", null);
        State.Config c = State.applyOverrides(BASE, body);
        assertEquals("https://example.test/x.git", c.repoUrl());
        assertEquals("trunk", c.repoBranch());
    }

    @Test
    void onlyConfigurationKeysAreTakenFromTheBody() {
        // /api/run/start hands freshRun the WHOLE request body, `force` and `clearLedger`
        // included. Those steer the route, not the run, and must not end up in its config.
        State.Config plain = State.applyOverrides(BASE, Map.of());
        State.Config withNoise = State.applyOverrides(BASE, Map.of(
                "force", true, "clearLedger", true, "somethingNew", 42));
        assertEquals(plain, withNoise);
    }

    @Test
    void zeroAttemptsPerFileConfiguresThreeAttempts() {
        // `parseInt(x, 10) || 3` is a FALSY fallback, so 0 lands on the default. Unintuitive and
        // load-bearing: a real 0 would settle every unit before its first attempt, and the run
        // would improve nothing while reporting that it had finished.
        assertEquals(3, State.applyOverrides(BASE, Map.of("maxAttemptsPerFile", 0)).maxAttemptsPerFile());
        assertEquals(3, State.applyOverrides(BASE, Map.of("maxAttemptsPerFile", "junk")).maxAttemptsPerFile());
        assertEquals(9, State.applyOverrides(BASE, Map.of("maxAttemptsPerFile", 9)).maxAttemptsPerFile());
    }

    @Test
    void aRoundAlwaysShowsTheModelAtLeastOneMutant() {
        assertEquals(1, State.applyOverrides(BASE, Map.of("mutantsPerRound", 0)).mutantsPerRound());
        assertEquals(1, State.applyOverrides(BASE, Map.of("mutantsPerRound", -4)).mutantsPerRound());
    }

    @Test
    void theClampsAreTheOnesTheJsHasAndNoOthers() {
        State.Config c = State.applyOverrides(BASE, Map.of(
                "maxIterations", -3, "maxRoundsPerFile", -3, "unitBudgetSec", -3,
                "minRoundGapFrac", -0.5, "minMutantsPerClass", -1,
                "scopeLimit", -7));
        assertEquals(0, c.maxIterations());
        assertEquals(0, c.maxRoundsPerFile());
        assertEquals(0, c.unitBudgetSec());
        assertEquals(0.0, c.minRoundGapFrac());
        assertEquals(0, c.minMutantsPerClass());
        // scopeLimit is deliberately NOT clamped: every read of it is `scopeLimit > 0`, so a
        // negative one means unlimited just as 0 does, and inventing a clamp here would be a
        // silent change to what a negative limit has always meant.
        assertEquals(-7, c.scopeLimit());
    }

    @Test
    void thePrOpensAgainstTheBranchWeClonedUnlessToldOtherwise() {
        assertEquals("trunk", State.applyOverrides(BASE, Map.of()).prBase());
        assertEquals("release", State.applyOverrides(BASE, Map.of("prBase", "release")).prBase());
        assertEquals("dev", State.applyOverrides(BASE, Map.of("repoBranch", "dev")).prBase(),
                "the fallback follows the overridden branch, not the configured one");
    }

    @Test
    void rulesAreOverriddenByNameAndClearedByAnEmptyOne() {
        State.Config withRules = State.applyOverrides(
                State.envConfig(env(Map.of("RULES_PICK_FILE", "prefer parsers", "RULES_MAKE_PR", "tag the team"))),
                Map.of("rules", Map.of("pick_file", "prefer writers", "make_pr", "")));
        assertEquals("prefer writers", withRules.rules().pickFile());
        // Unlike every other field, a rule IS cleared by an empty string: the JS guards these on
        // `!== undefined` alone, and deleting a rule from the dashboard posts exactly that.
        assertEquals("", withRules.rules().makePr());
    }

    @Test
    void anUnknownRuleNameIsIgnored() {
        State.Config c = State.applyOverrides(BASE, Map.of("rules", Map.of("post_merge", "nope")));
        assertEquals(State.Rules.EMPTY, c.rules());
        assertNull(c.rules().get("post_merge"));
    }

    @Test
    void aRuleIsFoundByItsOnDiskName() {
        // rules.js indexes them by stage — the same snake_case names the dashboard form posts.
        State.Rules r = new State.Rules("a", "b", "c", "d", "e", "f");
        assertEquals("a", r.get("post_clone"));
        assertEquals("d", r.get("write_test"));
        assertEquals("f", r.get("make_pr"));
    }

    // ── a fresh run ───────────────────────────────────────────────────────────

    @Test
    void aFreshRunHasMeasuredNothingYet() {
        State.Run r = State.freshRun(BASE, Map.of());
        assertTrue(r.id.startsWith("run-"), "the id names the run in every branch derived from it");
        assertFalse(r.id.contains("E"), "a millisecond timestamp, not an exponent");
        assertEquals("running", r.status);
        assertNull(r.finishedAt);
        assertEquals(0, r.iteration);
        assertEquals(0, r.tokens.in);
        assertEquals(0, r.tokens.out);
        assertEquals(0, r.tokens.calls);
        // null, not 0: a run that has not measured coverage has not measured 0% coverage, and
        // the dashboard prints the difference.
        assertNull(r.baseline.coveragePct);
        assertNull(r.baseline.mutationPct);
        assertNull(r.baseline.mac);
        assertNull(r.result.mac);
        assertTrue(r.startedAt > 0);
    }

    // ── events ────────────────────────────────────────────────────────────────

    @Test
    void eventsAreNumberedFromWhereverTheSequenceLeftOff() {
        State.event("starting", "one");
        State.event("starting", "two");
        assertEquals(2, State.STATE.seq);
        assertEquals(List.of(1L, 2L), State.STATE.events.stream().map(State.Event::seq).toList());
        assertEquals("two", State.STATE.events.get(1).msg());
        assertEquals("starting", State.STATE.events.get(1).stage());
    }

    @Test
    void theStoreKeepsOnlyTheLastFourHundredEvents() {
        // the whole store is written to disk on every change and served on every dashboard poll;
        // an unbounded log would make both grow without limit for the length of a batch
        for (int i = 0; i < State.MAX_EVENTS_IN_STATE + 25; i++) State.event("bulk", "line " + i);
        assertEquals(State.MAX_EVENTS_IN_STATE, State.STATE.events.size());
        assertEquals("line 25", State.STATE.events.get(0).msg(), "the OLDEST are the ones dropped");
        assertEquals(State.MAX_EVENTS_IN_STATE + 25, State.STATE.seq,
                "the sequence keeps counting, because /api/events?after= pages on it");
    }

    @Test
    void anEventIsRedactedAndTruncated() {
        State.event("cloning", "https://user:hunter2@example.test/x.git " + "y".repeat(600));
        String msg = State.STATE.events.get(0).msg();
        assertFalse(msg.contains("hunter2"), "tool output echoes the credentials we passed in");
        assertTrue(msg.startsWith("https://«redacted»@example.test/x.git"));
        assertEquals(500, msg.length());
    }

    @Test
    void everyEventIsAlsoAppendedToTheLogTheDashboardTails() throws IOException {
        State.event("one", "first");
        State.event("two", "second");
        List<String> lines = Files.readAllLines(State.eventsFile());
        assertEquals(2, lines.size(), "one JSON object per line, appended — never rewritten");
        JsonNode first = State.MAPPER.readTree(lines.get(0));
        assertEquals(1, first.get("seq").asInt());
        assertEquals("one", first.get("stage").asText());
        assertEquals("first", first.get("msg").asText());
        assertTrue(first.get("ts").asLong() > 0);
        assertEquals(4, first.size(), "seq, ts, stage, msg — the shape the dashboard parses");
    }

    @Test
    void aMissingDataDirectoryCostsALogLineAndNothingElse() {
        // The directory is created by the flush, so the first events of a cold boot can arrive
        // before it exists. Losing a log line must never be able to fail a round.
        State.DATA_DIR = State.DATA_DIR.resolve("not-created-yet");
        assertDoesNotThrow(() -> State.event("starting", "cold boot"));
        assertEquals(1, State.STATE.events.size(), "the in-memory event still happened");
    }

    // ── stages ────────────────────────────────────────────────────────────────

    @Test
    void aStageChangeIsLoggedOnceAndOnlyWhenItIsRealMovement() {
        State.setStage("measuring", "unit 1");
        State.setStage("measuring", "unit 1");
        assertEquals(1, State.STATE.events.size(), "re-entering the same stage is not news");
        State.setStage("measuring", "unit 2");
        assertEquals(2, State.STATE.events.size(), "a new detail under the same name is");
        assertEquals("measuring", State.STATE.stage.name());
        assertEquals("unit 2", State.STATE.stage.detail());
    }

    @Test
    void aStageWithNoDetailLogsItsOwnName() {
        State.setStage("cloning");
        assertEquals("cloning", State.STATE.events.get(0).msg());
        assertEquals("", State.STATE.stage.detail());
    }

    @Test
    void aStageChangeClearsTheProgressLineFromTheStageBefore() {
        State.setStage("building", "mvn");
        State.setProgress("[INFO] compiling", 12);
        assertNotNull(State.STATE.stage.progress());
        State.setStage("testing", "surefire");
        assertNull(State.STATE.stage.progress(), "the last line of the previous phase is not this phase's progress");
    }

    @Test
    void progressKeepsTheStageItBelongsToAndWhenThatStageStarted() {
        State.setStage("building", "mvn");
        long since = State.STATE.stage.since();
        State.setProgress("[INFO] compiling", 42);
        assertEquals("building", State.STATE.stage.name());
        assertEquals("mvn", State.STATE.stage.detail());
        assertEquals(since, State.STATE.stage.since(), "elapsed-time displays measure from here");
        assertEquals(42, State.STATE.stage.progress().elapsed());
        assertTrue(State.STATE.stage.progress().ts() > 0);
    }

    @Test
    void aProgressLineIsRedactedAndTruncatedShorterThanAnEvent() {
        // child-process output: may echo credentials we passed in
        State.setProgress("cloning https://user:hunter2@example.test/x.git " + "z".repeat(400), 1);
        String line = State.STATE.stage.progress().line();
        assertFalse(line.contains("hunter2"));
        assertEquals(200, line.length());
    }

    // ── file records ──────────────────────────────────────────────────────────

    @Test
    void aNewUnitIsACandidateWithNothingMeasured() {
        Map<String, Object> f = State.upsertFile("a/A.java::m", Map.of());
        assertEquals("a/A.java::m", f.get("path"));
        assertEquals("candidate", f.get("status"));
        assertEquals(0, f.get("attempts"));
        // every metric is present and null: absent is not zero, and the pick ranks on the
        // difference between "not measured" and "measured at 0"
        for (String k : List.of("coverage", "mutation", "mac", "macBefore", "macAfter")) {
            assertTrue(f.containsKey(k), k + " is seeded");
            assertNull(f.get(k), k + " starts unknown");
        }
        assertSame(f, State.STATE.files.get("a/A.java::m"));
    }

    @Test
    void aPatchIsAppliedIncludingItsNulls() {
        State.upsertFile("a/A.java::m", Map.of("attemptStartedAt", 1000L));
        Map<String, Object> patch = new HashMap<>();
        patch.put("spentSec", 30);
        patch.put("attemptStartedAt", null);   // this is how a caller stops the stopwatch
        Map<String, Object> f = State.upsertFile("a/A.java::m", patch);
        assertTrue(f.containsKey("attemptStartedAt"));
        assertNull(f.get("attemptStartedAt"), "skipping nulls would leave the stopwatch running for ever");
        assertEquals(30, f.get("spentSec"));
    }

    @Test
    void aPatchCannotForgeTheUpdateTime() {
        Map<String, Object> f = State.upsertFile("a/A.java::m", Map.of("updatedAt", 1L));
        assertNotEquals(1L, f.get("updatedAt"));
    }

    @Test
    void patchingAKnownUnitKeepsWhatWasAlreadyThere() {
        State.upsertFile("a/A.java::m", Map.of("coverage", 12.5, "lines", 40));
        Map<String, Object> f = State.upsertFile("a/A.java::m", Map.of("mutation", 20.0));
        assertEquals(12.5, f.get("coverage"));
        assertEquals(40, f.get("lines"));
        assertEquals(20.0, f.get("mutation"));
        assertEquals(1, State.STATE.files.size());
    }

    // ── tokens ────────────────────────────────────────────────────────────────

    @Test
    void tokensSpentWithNoRunGoNowhere() {
        assertDoesNotThrow(() -> State.addTokens(10, 20));
        assertNull(State.STATE.run);
    }

    @Test
    void tokensAreChargedToTheRunAndToTheUnitBeingImproved() {
        State.STATE.run = State.freshRun(BASE, Map.of());
        State.upsertFile("a/A.java::m", Map.of());
        State.STATE.currentUnit = "a/A.java::m";
        State.addTokens(100, 50);
        State.addTokens(10, 5);   // the retry costs the same unit
        assertEquals(110, State.STATE.run.tokens.in);
        assertEquals(55, State.STATE.run.tokens.out);
        assertEquals(2, State.STATE.run.tokens.calls, "every completion counts, repairs included");
        Map<String, Object> f = State.STATE.files.get("a/A.java::m");
        assertEquals(110L, State.asLong(f.get("tokensIn")));
        assertEquals(55L, State.asLong(f.get("tokensOut")));
        assertEquals(2L, State.asLong(f.get("llmCalls")));
    }

    @Test
    void tokensSpentOutsideAnyUnitStillCountAgainstTheRun() {
        State.STATE.run = State.freshRun(BASE, Map.of());
        State.STATE.currentUnit = "a/Gone.java::m";     // named, but never upserted
        assertDoesNotThrow(() -> State.addTokens(7, 3));
        assertEquals(7, State.STATE.run.tokens.in);
        assertFalse(State.STATE.files.containsKey("a/Gone.java::m"), "attribution never invents a unit");
    }

    // ── the live model dialog ─────────────────────────────────────────────────

    @Test
    void theDialogKeepsTheLastTwelveExchanges() {
        for (int i = 0; i < State.LLM_LOG_MAX + 5; i++) {
            State.addLlmExchange(Map.of("stage", "write_test", "response", "answer " + i));
        }
        assertEquals(State.LLM_LOG_MAX, State.STATE.llmLog.size());
        assertEquals("answer 5", State.STATE.llmLog.get(0).get("response"));
    }

    @Test
    void anExchangeIsStampedWithTheUnitItWasSpentOn() {
        State.STATE.currentUnit = "a/A.java::m";
        State.addLlmExchange(Map.of("stage", "pick_file"));
        assertEquals("a/A.java::m", State.STATE.llmLog.get(0).get("unit"));
        assertTrue(State.asLong(State.STATE.llmLog.get(0).get("ts")) > 0);

        State.STATE.currentUnit = null;
        State.addLlmExchange(Map.of("stage", "pre_pick"));
        assertTrue(State.STATE.llmLog.get(1).containsKey("unit"));
        assertNull(State.STATE.llmLog.get(1).get("unit"), "a call outside any unit is not attributed to the last one");
    }

    @Test
    void theStampWinsOverWhateverTheEntryCarried() {
        // `{ ...entry, ts, unit }` — the caller's own idea of when this happened does not survive
        Map<String, Object> entry = new HashMap<>();
        entry.put("ts", 1);
        entry.put("unit", "somewhere/else");
        State.STATE.currentUnit = "a/A.java::m";
        State.addLlmExchange(entry);
        assertNotEquals(1, State.STATE.llmLog.get(0).get("ts"));
        assertEquals("a/A.java::m", State.STATE.llmLog.get(0).get("unit"));
    }

    // ── the file on disk ──────────────────────────────────────────────────────

    @Test
    void theStateFileHasTheShapeTheDashboardAndTheWorkflowRead() throws IOException {
        State.flushNow();
        JsonNode s = State.MAPPER.readTree(Files.readString(State.stateFile()));
        for (String k : List.of("run", "stage", "runner", "files", "decisions", "prs", "events",
                "seq", "improvedLedger", "overheadLedger", "measureLedger", "currentUnit", "llmLog")) {
            assertTrue(s.has(k), "state.json must still carry '" + k + "'");
        }
        assertEquals(13, s.size(), "and nothing else: keys other modules add appear only once set");
        assertTrue(s.get("run").isNull());
        assertTrue(s.get("currentUnit").isNull(), "a null key is written, as JSON.stringify writes it");
        for (String k : List.of("name", "detail", "since", "progress")) {
            assertTrue(s.get("stage").has(k));
        }
        assertTrue(s.get("stage").get("progress").isNull());
    }

    @Test
    void keysTheRoutesAddLaterAppearOnlyOnceTheyAreSet() throws IOException {
        State.STATE.pickFailures = 2;
        State.flushNow();
        JsonNode s = State.MAPPER.readTree(Files.readString(State.stateFile()));
        assertEquals(2, s.get("pickFailures").asInt());
        assertFalse(s.has("mutatorStats"), "still absent — the JS key does not exist until assigned");
    }

    @Test
    void theTreeViewReadsTheStoreByPathTheWayTheJsDid() {
        // `state.run?.config?.pitScope` and `state.files[key]?.method`, in one navigable tree —
        // the modules that ported those chains read them through here.
        State.STATE.run = State.freshRun(BASE, Map.of("pitScope", "class"));
        State.upsertFile("a/A.java::m", Map.of("method", "m"));
        JsonNode s = State.state();
        assertEquals("class", s.path("run").path("config").path("pitScope").asText());
        assertEquals("m", s.path("files").path("a/A.java::m").path("method").asText());
        assertTrue(s.path("run").path("nothing").isMissingNode(), "an absent field is missing, not an exception");
    }

    @Test
    void theTreeViewIsASnapshotAndNotALiveWindow() {
        // it is taken under the store's monitor precisely so it can be walked while the pipeline
        // keeps working; what it must never do is change halfway through that walk
        JsonNode before = State.state();
        State.upsertFile("a/Late.java::m", Map.of());
        assertTrue(before.path("files").path("a/Late.java::m").isMissingNode());
        assertFalse(State.state().path("files").path("a/Late.java::m").isMissingNode());
    }

    @Test
    void theWriteIsAtomicAndLeavesNoTemporaryBehind() throws IOException {
        State.STATE.currentUnit = "a/A.java::m";
        State.flushNow();
        assertTrue(Files.exists(State.stateFile()));
        assertFalse(Files.exists(State.DATA_DIR.resolve("state.json.tmp")),
                "the temp file is renamed over the real one, never left beside it");
    }

    @Test
    void savesAreDebouncedIntoOneWrite() throws Exception {
        State.STATE.currentUnit = "a/A.java::m";
        State.save();
        State.save();
        State.save();
        assertFalse(Files.exists(State.stateFile()), "a round writes state dozens of times a second");
        awaitFile(State.stateFile());
        JsonNode s = State.MAPPER.readTree(Files.readString(State.stateFile()));
        assertEquals("a/A.java::m", s.get("currentUnit").asText());
    }

    // ── reading it back ───────────────────────────────────────────────────────

    @Test
    void aRunThatWasStillRunningComesBackInterrupted() throws IOException {
        // the process restarted mid-run: whatever execution owned that run is gone, and
        // /api/run/start reads this stage to tell a dead run from a live one
        writeState("""
                {"run": {"id": "run-1", "status": "running"}, "seq": 3}""");
        State.load();
        assertEquals("interrupted", State.STATE.stage.name());
        assertEquals("sidecar restarted", State.STATE.stage.detail());
        // and the STATUS, which is what the page header, the API and any watching script read.
        // Relabelling only the stage told the truth in the one place nothing looks.
        assertEquals("interrupted", State.STATE.run.status);
        assertEquals("run-1", State.STATE.run.id);
        assertEquals(3, State.STATE.seq);
    }

    @Test
    void aRunThatHadFinishedIsLeftAsItWas() throws IOException {
        writeState("""
                {"run": {"id": "run-1", "status": "done"}, "stage": {"name": "done", "detail": "", "since": 5, "progress": null}}""");
        State.load();
        assertEquals("done", State.STATE.stage.name());
    }

    @Test
    void theLedgersSurviveARestart() throws IOException {
        // this is the whole point of the file: a second batch must not redo the first
        writeState("""
                {"improvedLedger": {"example-x": {"a/A.java::m": {"state": "improved", "prUrl": "http://pr/1"}}},
                 "measureLedger": {"example-x": {"a/A.java::m": {"macBefore": 12.5, "v": 3}}},
                 "overheadLedger": {"example-x": 480}}""");
        State.load();
        assertEquals("improved", State.STATE.improvedLedger.get("example-x").get("a/A.java::m").get("state"));
        assertEquals(12.5, State.asDouble(State.STATE.measureLedger.get("example-x").get("a/A.java::m").get("macBefore")));
        assertEquals(480L, State.asLong(State.STATE.overheadLedger.get("example-x")));
    }

    @Test
    void aKeyTheFileDoesNotMentionKeepsItsDefault() throws IOException {
        // `Object.assign(state, raw)` is shallow and partial: it replaces the keys the file has
        // and says nothing about the rest
        State.upsertFile("a/Kept.java::m", Map.of());
        writeState("""
                {"seq": 9}""");
        State.load();
        assertEquals(9, State.STATE.seq);
        assertTrue(State.STATE.files.containsKey("a/Kept.java::m"));
    }

    @Test
    void aKeyTheFileDoesMentionIsReplacedWholesale() throws IOException {
        // …and where it does have the key, the file wins entirely — the in-memory map is not
        // merged into, so a unit that is gone from the file is gone from the run
        State.upsertFile("a/Old.java::m", Map.of());
        writeState("""
                {"files": {"a/New.java::m": {"path": "a/New.java", "status": "candidate"}}}""");
        State.load();
        assertEquals(List.of("a/New.java::m"), List.copyOf(State.STATE.files.keySet()));
    }

    @Test
    void aKeyThisBuildDoesNotKnowIsCarriedThroughRatherThanDropped() throws IOException {
        writeState("""
                {"seq": 1, "somethingNewer": {"a": 1}}""");
        State.load();
        State.flushNow();
        JsonNode s = State.MAPPER.readTree(Files.readString(State.stateFile()));
        assertTrue(s.has("somethingNewer"), "a state file written by another version survives a load/save cycle");
        assertEquals(1, s.get("somethingNewer").get("a").asInt());
    }

    @Test
    void anUnreadableFileIsAFirstBoot() throws IOException {
        writeState("{ this is not json");
        assertDoesNotThrow(State::load);
        assertEquals(0, State.STATE.seq);
        assertNull(State.STATE.run);
    }

    @Test
    void oneUnreadableKeyDoesNotCostTheRestOfTheFile() throws IOException {
        // JSON.parse either works or does not, so Object.assign could never fail on a single
        // value's SHAPE. Jackson can, and one corrupt ledger must not throw away the others.
        writeState("""
                {"seq": 4, "events": "not a list", "currentUnit": "a/A.java::m"}""");
        State.load();
        assertEquals(4, State.STATE.seq);
        assertEquals("a/A.java::m", State.STATE.currentUnit);
        assertTrue(State.STATE.events.isEmpty());
    }

    @Test
    void aRunRoundTripsThroughTheFileWithItsConfigIntact() throws IOException {
        State.STATE.run = State.freshRun(BASE, Map.of("prMode", "local", "rules", Map.of("write_test", "no mocks")));
        String id = State.STATE.run.id;
        State.STATE.run.baseline.coveragePct = 40.0;
        State.STATE.run.measuredFiles = 12;
        State.flushNow();
        resetStore();
        State.load();
        assertEquals(id, State.STATE.run.id);
        assertEquals("local", State.STATE.run.config.prMode());
        assertEquals("trunk", State.STATE.run.config.prBase());
        assertEquals("no mocks", State.STATE.run.config.rules().writeTest());
        assertEquals(40.0, State.STATE.run.baseline.coveragePct);
        assertNull(State.STATE.run.baseline.mutationPct);
        assertEquals(12, State.STATE.run.measuredFiles);
    }

    @Test
    void theRulesKeepTheirOnDiskSpelling() throws IOException {
        State.STATE.run = State.freshRun(BASE, Map.of("rules", Map.of("write_test", "no mocks")));
        State.flushNow();
        JsonNode rules = State.MAPPER.readTree(Files.readString(State.stateFile()))
                .get("run").get("config").get("rules");
        assertTrue(rules.has("write_test"), "snake_case is what the dashboard form and /api/rules use");
        assertTrue(rules.has("post_clone"));
        assertEquals("no mocks", rules.get("write_test").asText());
    }

    private static void writeState(String json) throws IOException {
        Files.createDirectories(State.DATA_DIR);
        Files.writeString(State.stateFile(), json);
    }

    private static void awaitFile(Path p) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(p)) return;
            Thread.sleep(10);
        }
        fail("nothing was flushed within 3s: " + p);
    }
}
