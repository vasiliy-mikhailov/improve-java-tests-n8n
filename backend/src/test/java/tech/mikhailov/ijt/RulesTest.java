package tech.mikhailov.ijt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// Per-stage team rules: the model interprets the rule, mechanical guardrails decide.
///
/// rules.js has no unit test in the Node sidecar, which is why this file exists. What is pinned
/// here is the part that is not the model's: the mechanical gate, the guard on a template the
/// model made up, what happens when the answer does not parse at all, and the ledger entry
/// every stage leaves behind — `state.decisions`, which `/api/metrics` reports and
/// `Server.branchTemplate()` reads.
class RulesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /// The whole world this module touches, recorded rather than performed.
    private static final class FakeIo implements Rules.Io {
        final Map<String, String> rules = new LinkedHashMap<>();
        final Map<String, String> files = new LinkedHashMap<>();
        final Map<String, Object> decisions = new LinkedHashMap<>();
        final Map<String, Object> runner = new LinkedHashMap<>();
        final List<String> events = new ArrayList<>();
        final List<Llm.Options> asked = new ArrayList<>();
        /// null answers stand for "the model produced no JSON"
        final List<String> answers = new ArrayList<>();
        RuntimeException readThrows;
        int pickFailures;
        long now = 1_700_000_000_000L;

        public String rule(String stage) { return rules.getOrDefault(stage, ""); }

        public String readFileSafe(String rel, int maxLen) {
            if (readThrows != null) throw readThrows;
            return files.get(rel);
        }

        public Llm.Answer chat(Llm.Options opts) {
            asked.add(opts);
            String body = asked.size() <= answers.size() ? answers.get(asked.size() - 1) : null;
            try {
                return new Llm.Answer(body == null ? "not json" : body,
                        body == null ? null : MAPPER.readTree(body));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        public void event(String stage, String msg) { events.add(stage + ": " + msg); }

        public Map<String, Object> decisions() { return decisions; }

        public void recordDecision(String stage, Map<String, Object> entry) { decisions.put(stage, entry); }

        public int bumpPickFailures() { return ++pickFailures; }

        public void resetPickFailures() { pickFailures = 0; }

        public Object runnerField(String name) { return runner.get(name); }

        public Object runnerJdk(String name) {
            Map<?, ?> jdk = (Map<?, ?>) runner.get("jdk");
            return jdk == null ? null : jdk.get(name);
        }

        public long nowMillis() { return now; }
    }

    private static FakeIo io() { return new FakeIo(); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) { return (Map<String, Object>) o; }

    // ── the ledger every stage leaves ─────────────────────────────────────────

    @Test
    void everyAppliedStageIsRecordedWithItsRuleItsResultAndWhenItRan() {
        // state.decisions is not bookkeeping: Server.branchTemplate() reads
        // decisions.pre_pick.result.branchTemplate and falls back to tests/improve-{file}
        // without it, and GET /api/rules and /api/metrics both report the whole object.
        FakeIo io = io();
        Object result = new Rules(io).apply("pre_pick", Map.of());
        Map<String, Object> entry = map(io.decisions.get("pre_pick"));
        assertEquals(List.of("rule", "result", "ts"), List.copyOf(entry.keySet()));
        assertEquals("", entry.get("rule"));
        assertSame(result, entry.get("result"));
        // milliseconds, and an integer: a double reaches the state file as 1.7E12
        assertEquals(1_700_000_000_000L, entry.get("ts"));
        assertEquals(1, io.events.size());
        assertTrue(io.events.get(0).startsWith("rules: applied pre_pick rules: {"), io.events.get(0));
    }

    @Test
    void theRecordedTemplateIsExactlyWhatServerBranchTemplateReadsBack() {
        FakeIo io = io();
        new Rules(io).apply("pre_pick", Map.of());
        Map<String, Object> entry = map(io.decisions.get("pre_pick"));
        assertEquals("tests/improve-{file}", map(entry.get("result")).get("branchTemplate"));
    }

    @Test
    void anUnknownStageIsACallerErrorNotASilentNoOp() {
        FakeIo io = io();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new Rules(io).apply("post_merge", Map.of()));
        assertEquals("unknown rule stage: post_merge", e.getMessage());
        assertTrue(io.decisions.isEmpty(), "a stage that threw records nothing");
    }

    // ── post_clone ────────────────────────────────────────────────────────────

    @Test
    void withNoPostCloneRuleTheModelIsNotAskedAtAll() {
        FakeIo io = io();
        Map<String, Object> r = map(new Rules(io).apply("post_clone", Map.of()));
        assertEquals(List.of(), r.get("constraints"));
        assertEquals("no post-clone rules configured", r.get("notes"));
        assertTrue(io.asked.isEmpty(), "no rule, no model call");
    }

    @Test
    void anAnswerThatDoesNotParseKeepsTheRuleTextRatherThanLosingIt() {
        FakeIo io = io();
        io.rules.put("post_clone", "no mocks");
        Map<String, Object> r = map(new Rules(io).apply("post_clone", Map.of()));
        assertEquals(List.of("no mocks"), r.get("constraints"));
        assertEquals("LLM parse failed, using raw rule text", r.get("notes"));
    }

    @Test
    void theRepoDocsAreOfferedToTheModelAndAMissingCheckoutIsNotAnError() {
        // readFileSafe swallows a missing FILE, but readFileSafe -> repoDir() throws outright
        // when there is no checkout, and that exception took down the whole PR composition.
        FakeIo io = io();
        io.rules.put("post_clone", "follow CONTRIBUTING");
        io.files.put("CONTRIBUTING.md", "run ./gradlew check");
        new Rules(io).apply("post_clone", Map.of());
        assertTrue(io.asked.get(0).prompt().contains("--- CONTRIBUTING.md (head) ---\nrun ./gradlew check"));

        FakeIo broken = io();
        broken.rules.put("post_clone", "follow CONTRIBUTING");
        broken.readThrows = new IllegalStateException("no repo checked out");
        assertDoesNotThrow(() -> new Rules(broken).apply("post_clone", Map.of()));
        assertTrue(broken.asked.get(0).prompt().contains("(no AGENTS.md / CONTRIBUTING.md / README.md found)"));
    }

    // ── pre_pick: the branch name ─────────────────────────────────────────────

    @Test
    void aTemplateWithNoFilePlaceholderIsRefusedItWouldNameEveryBranchTheSame() {
        FakeIo io = io();
        io.rules.put("pre_pick", "prefix branches with chore/");
        io.answers.add("{\"branchTemplate\":\"chore/tests\",\"notes\":\"as asked\"}");
        Map<String, Object> r = map(new Rules(io).apply("pre_pick", Map.of()));
        assertEquals("tests/improve-{file}", r.get("branchTemplate"));
        assertEquals("rule did not yield a usable template", r.get("notes"));
    }

    @Test
    void everyRunOfCharactersAGitBranchCannotHoldCollapsesToADashNotJustTheFirst() {
        // /g in the JS regex: replaceFirst would leave the second offending run in place and
        // `git checkout -b` would reject the name at the first round.
        FakeIo io = io();
        io.rules.put("pre_pick", "use spaces, apparently");
        io.answers.add("{\"branchTemplate\":\"test coverage/{file} v2!!\",\"notes\":\"x\"}");
        Map<String, Object> r = map(new Rules(io).apply("pre_pick", Map.of()));
        assertEquals("test-coverage/{file}-v2-", r.get("branchTemplate"));
    }

    // ── pick_file: the rule vetoes, the measurements choose ───────────────────

    private static Map<String, Object> candidate(String path, String file, String method) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("path", path);
        c.put("file", file);
        c.put("method", method);
        return c;
    }

    @Test
    void withNoRuleTheWeakestUnitIsTakenAndTheModelIsNeverConsulted() {
        // The model used to pick, and picked the fifth-ranked unit — a private method three hops
        // from any public entry — over three directly callable ones.
        FakeIo io = io();
        Map<String, Object> r = map(new Rules(io).apply("pick_file",
                Map.of("candidates", List.of(candidate("A.java::m", "A.java", "m"),
                        candidate("B.java::n", "B.java", "n")))));
        assertEquals("A.java::m", r.get("file"));
        assertTrue(io.asked.isEmpty());
        assertFalse(r.containsKey("retry"), "a successful pick states no retry, exactly as the JS answer does");
    }

    @Test
    void anExcludedFileTakesItsMethodsWithIt() {
        FakeIo io = io();
        io.rules.put("pick_file", "do not touch ui");
        io.answers.add("{\"exclude\":[\"A.java\"],\"reason\":\"ui\"}");
        Map<String, Object> r = map(new Rules(io).apply("pick_file",
                Map.of("candidates", List.of(candidate("A.java::m", "A.java", "m"),
                        candidate("B.java::n", "B.java", "n")))));
        assertEquals("B.java::n", r.get("file"));
        assertTrue(io.events.stream().anyMatch(e -> e.contains("team rule excluded 1 candidate(s): ui")));
    }

    @Test
    void anUnusableAnswerRetriesTwiceAndThenGivesUpRatherThanPickingBlind() {
        // Picking anyway might violate the rule, which is the one thing this stage exists to
        // prevent — so a model hiccup buys a retry, and three of them end the batch.
        FakeIo io = io();
        io.rules.put("pick_file", "do not touch ui");
        List<Map<String, Object>> candidates = List.of(candidate("A.java::m", "A.java", "m"));
        for (int attempt = 1; attempt <= 3; attempt++) {
            Map<String, Object> r = map(new Rules(io).apply("pick_file", Map.of("candidates", candidates)));
            assertNull(r.get("file"));
            assertEquals(attempt < 3, r.get("retry"), "attempt " + attempt);
        }
        assertTrue(io.events.stream().anyMatch(e -> e.contains("(invalid LLM output, 3/3) — giving up on this batch")));
    }

    @Test
    void anAnswerWhoseExcludeIsNotAListIsNotAnEmptyExclusionList() {
        // `Array.isArray(j.exclude)`: treating a string as "nothing excluded" would claim the
        // rule had been applied when it had not.
        FakeIo io = io();
        io.rules.put("pick_file", "do not touch ui");
        io.answers.add("{\"exclude\":\"A.java\",\"reason\":\"ui\"}");
        Map<String, Object> r = map(new Rules(io).apply("pick_file",
                Map.of("candidates", List.of(candidate("A.java::m", "A.java", "m")))));
        assertNull(r.get("file"));
        assertEquals(1, io.pickFailures);
    }

    @Test
    void aGoodAnswerClearsTheFailureCountSoThreeFailuresMeansThreeInARow() {
        FakeIo io = io();
        io.pickFailures = 2;
        io.rules.put("pick_file", "do not touch ui");
        io.answers.add("{\"exclude\":[],\"reason\":\"nothing to exclude\"}");
        new Rules(io).apply("pick_file",
                Map.of("candidates", List.of(candidate("A.java::m", "A.java", "m"))));
        assertEquals(0, io.pickFailures);
    }

    @Test
    void noCandidatesIsATerminalAnswerNotARetry() {
        FakeIo io = io();
        io.rules.put("pick_file", "do not touch ui");
        Map<String, Object> r = map(new Rules(io).apply("pick_file", Map.of("candidates", List.of())));
        assertNull(r.get("file"));
        assertEquals(false, r.get("retry"));
        assertEquals("no candidates left to work on", r.get("reason"));
        assertTrue(io.asked.isEmpty(), "nothing to apply a rule to");
    }

    @Test
    void theCandidateTableShowsWholeNumbersTheWayTheJsPrintedThem() {
        // The store round-trips through JSON, so these are boxed Doubles; plain concatenation
        // would offer the model `coverage=80.0%` where the JS offered `coverage=80%`, and `?`
        // is what an unmeasured unit prints.
        Map<String, Object> c = candidate("A.java::m", "A.java", "m");
        c.put("coverage", 80.0);
        c.put("mutation", 66.67);
        c.put("mac", null);
        c.put("lines", 12.0);
        assertEquals("A.java::m | method=m() coverage=80% mutation=66.67% mac=? lines=12",
                Rules.candidateTable(List.of(c)));
    }

    @Test
    void aWholeFileCandidateNamesNoMethod() {
        Map<String, Object> c = candidate("A.java", "A.java", null);
        assertEquals("A.java | coverage=?% mutation=?% mac=? lines=?", Rules.candidateTable(List.of(c)));
    }

    // ── check_changes: mechanical first, always ───────────────────────────────

    @Test
    void aRedSuiteIsRefusedBeforeTheModelIsAskedAnything() {
        FakeIo io = io();
        io.rules.put("check_changes", "no flaky tests");
        Map<String, Object> r = map(new Rules(io).apply("check_changes",
                new LinkedHashMap<>(Map.of("testsGreen", false, "macBefore", 10.0, "macAfter", 50.0))));
        assertEquals(false, r.get("approved"));
        assertEquals("test suite is red", r.get("reason"));
        assertTrue(io.asked.isEmpty());
    }

    @Test
    void macMustStrictlyImproveAndTheReasonPrintsTheNumbersAsJsDid() {
        FakeIo io = io();
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("testsGreen", true);
        ctx.put("macBefore", 50.0);
        ctx.put("macAfter", 50.0);
        Map<String, Object> r = map(new Rules(io).apply("check_changes", ctx));
        assertEquals(false, r.get("approved"));
        assertEquals("MAC did not improve (50 → 50)", r.get("reason"));
        assertEquals(Map.of("testsGreen", true, "macImproved", false), r.get("mechanical"));
    }

    @Test
    void withBothMechanicalChecksPassedAndNoRuleTheChangeIsApproved() {
        FakeIo io = io();
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("testsGreen", true);
        ctx.put("macBefore", 10.0);
        ctx.put("macAfter", 42.5);
        Map<String, Object> r = map(new Rules(io).apply("check_changes", ctx));
        assertEquals(true, r.get("approved"));
        assertEquals("MAC 10 → 42.5, suite green", r.get("reason"));
        assertTrue(io.asked.isEmpty());
    }

    @Test
    void anUnparseableVerdictDefersToTheMechanicalChecksItDoesNotBlockTheRound() {
        FakeIo io = io();
        io.rules.put("check_changes", "no flaky tests");
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("testsGreen", true);
        ctx.put("macBefore", 10.0);
        ctx.put("macAfter", 42.5);
        Map<String, Object> r = map(new Rules(io).apply("check_changes", ctx));
        assertEquals(true, r.get("approved"));
        assertEquals("LLM verdict unparseable — mechanical checks passed", r.get("reason"));
        // and the mechanical verdict is always attached, whoever produced the rest
        assertEquals(Map.of("testsGreen", true, "macImproved", true), r.get("mechanical"));
    }

    @Test
    void theModelMayRefuseOnTheTeamRuleAloneAndItsVerdictCarriesTheMechanicalOne() {
        FakeIo io = io();
        io.rules.put("check_changes", "no flaky tests");
        io.answers.add("{\"approved\":false,\"reason\":\"uses Thread.sleep\"}");
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("testsGreen", true);
        ctx.put("macBefore", 10.0);
        ctx.put("macAfter", 42.5);
        ctx.put("diff", "x".repeat(20000));
        Map<String, Object> r = map(new Rules(io).apply("check_changes", ctx));
        assertEquals(false, r.get("approved"));
        assertEquals("uses Thread.sleep", r.get("reason"));
        assertEquals(Map.of("testsGreen", true, "macImproved", true), r.get("mechanical"));
        // a whole-branch diff would otherwise be most of the context window
        assertTrue(io.asked.get(0).prompt().endsWith("x".repeat(12000)));
    }

    // ── make_pr: the body may state only what this run measured ───────────────

    @Test
    void theDefaultBodyCarriesTheMeasuredNumbersAndNothingElse() {
        FakeIo io = io();
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("file", "A.java::m");
        ctx.put("coverageBefore", 40.0);
        ctx.put("coverageAfter", 80.0);
        ctx.put("mutationBefore", 0.0);
        ctx.put("mutationAfter", 50.0);
        ctx.put("macBefore", 0.0);
        ctx.put("macAfter", 40.0);
        Map<String, Object> r = map(new Rules(io).apply("make_pr", ctx));
        assertEquals("test: improve mutation-adjusted coverage of A.java::m", r.get("title"));
        String body = String.valueOf(r.get("body"));
        assertTrue(body.contains("| line coverage | 40% | 80% |"), body);
        assertTrue(body.contains("| **MAC** | **0** | **40** |"), body);
        assertEquals(List.of(), r.get("labels"));
    }

    @Test
    void theModelIsToldWhatWasActuallyVerifiedAndOnlyThat() {
        // A generated PR claimed "Tests pass on Java 1.6 - 25" for work verified on one JDK.
        FakeIo io = io();
        io.rules.put("make_pr", "our PR template");
        io.runner.put("jdk", Map.of("chosen", "21"));
        io.runner.put("tool", "gradle");
        io.answers.add("{\"title\":\"t\",\"body\":\"b\",\"labels\":[\"tests\"]}");
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("file", "A.java::m");
        ctx.put("changedFiles", List.of("src/test/java/ATest.java"));
        Map<String, Object> r = map(new Rules(io).apply("make_pr", ctx));
        String prompt = io.asked.get(0).prompt();
        assertTrue(prompt.contains("VERIFIED ON: JDK 21, gradle, ? — and no other configuration"), prompt);
        assertTrue(prompt.contains("CHANGED TEST FILES: src/test/java/ATest.java"), prompt);
        assertEquals("t", r.get("title"));
        assertEquals(List.of("tests"), r.get("labels"));
    }

    @Test
    void anAnswerMissingATitleOrABodyFallsBackRatherThanOpeningAPrWithout() {
        FakeIo io = io();
        io.rules.put("make_pr", "our PR template");
        io.answers.add("{\"title\":\"t\"}");
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("file", "A.java::m");
        Map<String, Object> r = map(new Rules(io).apply("make_pr", ctx));
        assertEquals("test: improve mutation-adjusted coverage of A.java::m", r.get("title"));
    }

    @Test
    void labelsThatAreNotAListBecomeAnEmptyOneTheyAreHandedToGhAsArgv() {
        FakeIo io = io();
        io.rules.put("make_pr", "our PR template");
        io.answers.add("{\"title\":\"t\",\"body\":\"b\",\"labels\":\"tests\"}");
        Map<String, Object> r = map(new Rules(io).apply("make_pr", new LinkedHashMap<>(Map.of("file", "A.java::m"))));
        assertEquals(List.of(), r.get("labels"));
    }

    // ── write_test: the constraints every prompt carries ──────────────────────

    @Test
    void theWriteTestRuleReachesEveryPromptThatAsksForConstraints() {
        FakeIo io = io();
        io.rules.put("write_test", "no Mockito");
        assertEquals(List.of("no Mockito"), new Rules(io).testWritingConstraints());
        assertEquals(Map.of("constraints", List.of("no Mockito")),
                new Rules(io).apply("write_test", Map.of()));
    }

    @Test
    void withNoRuleThereAreNoConstraintsAndThatIsNotAFailure() {
        // Server.gapsFor puts these behind `Team constraints:` and builds the prompt either way;
        // an absent rules engine must not take the round with it.
        assertEquals(List.of(), new Rules(io()).testWritingConstraints());
    }

    @Test
    void thePostCloneConstraintsAreReadFromTheKeyTheJsReadsWhichIsNotTheKeyApplyWrites() {
        // Documented, not repaired. rules.js `apply` stores {rule, result, ts} and
        // testWritingConstraints looks for `decisions.post_clone.constraints` — one level up
        // from where the constraints actually are — so in production this branch has never
        // fired and every prompt ships with the write_test rule alone. Reading
        // `.result.constraints` instead would change what every prompt says, so the port keeps
        // the JS reading and this test states plainly what that means.
        FakeIo io = io();
        io.rules.put("write_test", "no Mockito");
        io.decisions.put("post_clone", Map.of("rule", "no mocks",
                "result", Map.of("constraints", List.of("no mocks")), "ts", 1L));
        assertEquals(List.of("no Mockito"), new Rules(io).testWritingConstraints(),
                "the post-clone constraints are NOT picked up — this is the JS behaviour");

        // and the shape the JS would have needed for the branch to fire
        FakeIo flat = io();
        flat.decisions.put("post_clone", Map.of("constraints", List.of("no mocks")));
        assertEquals(List.of("no mocks"), new Rules(flat).testWritingConstraints());
    }

    // ── the seam is wired ─────────────────────────────────────────────────────

    @Test
    void theServerEngineReadsTheRunsRulesAndNotTheEnvironments() {
        // rules.js reads `state.run?.config?.rules` and has no env fallback — only
        // GET /api/rules falls back, and that is a different question.
        State.Run before = State.STATE.run;
        try {
            State.STATE.run = null;
            assertEquals(List.of(), Rules.forServer().testWritingConstraints());
            State.Run run = State.freshRun();
            run.config = State.applyOverrides(run.config,
                    Map.of("rules", Map.of("write_test", "no Mockito")));
            State.STATE.run = run;
            assertEquals(List.of("no Mockito"), Rules.forServer().testWritingConstraints());
        } finally {
            State.STATE.run = before;
        }
    }

    @Test
    void aJsonAnswerBecomesPlainMapsBecauseItIsStoredAndReadBackAsOne() throws Exception {
        JsonNode node = MAPPER.readTree("{\"a\":[1,\"x\"],\"b\":{\"c\":true}}");
        Object plain = Rules.plain(node);
        assertInstanceOf(Map.class, plain);
        assertEquals(List.of(1, "x"), map(plain).get("a"));
        assertEquals(Map.of("c", true), map(plain).get("b"));
        assertNull(Rules.plain(null));
        assertNull(Rules.plain(MAPPER.readTree("null")));
    }
}
