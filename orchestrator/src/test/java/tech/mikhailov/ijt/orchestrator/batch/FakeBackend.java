package tech.mikhailov.ijt.orchestrator.batch;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// The backend, recorded rather than performed.
///
/// Every real call clones a repository, forks Maven, or asks a model. What is under test here is
/// the control flow — fifteen decision points read off `n8n/generate-workflows.mjs` — and the only
/// way to test a branch is to be able to answer the question it asks. So each knob below is one
/// branch of the workflow, named after it, and `calls` is the node sequence the flow actually
/// took: asserting on it compares this port against the graph it replaces, node for node.
final class FakeBackend implements Backend {

    /// Every operation, in order, as `name(argument)`.
    final List<String> calls = new ArrayList<>();

    /// The candidate queue. A unit leaves it when `startIteration` claims it, which is what makes
    /// a crash mid-unit resume at the NEXT one.
    final Deque<String> queue = new ArrayDeque<>();

    // ── the branches ──────────────────────────────────────────────────────────

    /// File Picked? — when true the pick answers with no file at all.
    boolean pickReturnsNoFile;

    /// Pick Retryable? — transient (retry) versus terminal (the rule excludes everything).
    boolean pickRetry;

    /// Has Mutants? — `skip` on the baseline PIT run.
    boolean pitSkip;

    /// Another Round? — how many rounds a unit runs before `continueRounds` turns false.
    int roundsPerUnit = 1;

    /// Keep Round? — accept versus miss.
    boolean keepRound = true;

    /// The `improved` half of Approved?, which the drop answers.
    boolean improved = true;

    /// The rule's half of Approved?.
    boolean approved = true;

    /// The reason a rejection carries, or null to make the rule state none.
    String checkReason = "coverage of the changed lines is unchanged";

    /// Has Work? — both phases skip by default, so a unit test of the UNIT loop does not have to
    /// script eleven nodes of a phase it is not about.
    boolean phaseSkip = true;

    /// Green? on the first suite run.
    boolean green = true;

    /// Green After Repair? on the re-run, which the workflow makes with no stage.
    boolean greenAfterRepair = true;

    /// Wrote Any? — `Parse Tests`'s count.
    long parsedCount = 1;

    /// `Parse Tests`'s chosen mutant, or null for a phase that targeted nothing.
    Map<String, Object> chosen;

    /// Throw from `startIteration` for this unit — a crash in the middle of the unit loop.
    String failOnUnit;

    // ── what the assertions read ──────────────────────────────────────────────

    final List<String> notes = new ArrayList<>();
    final List<Object> targets = new ArrayList<>();
    final List<List<String>> deleted = new ArrayList<>();
    final List<String> discardReasons = new ArrayList<>();
    final List<Map<String, Object>> createdPrs = new ArrayList<>();

    private int roundsRun;

    long callCount(String name) {
        return calls.stream().filter(c -> c.equals(name) || c.startsWith(name + "(")).count();
    }

    /// Back to defaults. The Spring context — and therefore this bean — is shared by every test
    /// in a class, and a call log that carried over would make `cloneRepo happened once` true for
    /// the wrong reason.
    void reset() {
        calls.clear();
        queue.clear();
        notes.clear();
        targets.clear();
        deleted.clear();
        discardReasons.clear();
        createdPrs.clear();
        pickReturnsNoFile = false;
        pickRetry = false;
        pitSkip = false;
        roundsPerUnit = 1;
        keepRound = true;
        improved = true;
        approved = true;
        checkReason = "coverage of the changed lines is unchanged";
        phaseSkip = true;
        green = true;
        greenAfterRepair = true;
        parsedCount = 1;
        chosen = null;
        failOnUnit = null;
        roundsRun = 0;
    }

    // ── setup ─────────────────────────────────────────────────────────────────

    @Override
    public Map<String, Object> cloneRepo() {
        calls.add("cloneRepo");
        return ok();
    }

    @Override
    public Map<String, Object> applyRules(String stage, Map<String, Object> context) {
        calls.add("applyRules(" + stage + ")");
        Map<String, Object> result = new LinkedHashMap<>();
        switch (stage) {
            case "pick_file" -> {
                if (pickReturnsNoFile) {
                    result.put("retry", pickRetry);
                } else {
                    result.put("file", queue.peek());
                }
            }
            case "check_changes" -> {
                result.put("approved", approved);
                result.put("reason", checkReason);
            }
            case "make_pr" -> {
                result.put("title", "test: improve MAC");
                result.put("body", "generated");
                result.put("labels", List.of("tests"));
            }
            default -> { }
        }
        Map<String, Object> out = ok();
        out.put("stage", stage);
        out.put("result", result);
        return out;
    }

    @Override
    public Map<String, Object> prepare() {
        calls.add("prepare");
        return ok();
    }

    @Override
    public Map<String, Object> baselineCoverage() {
        calls.add("baselineCoverage");
        return ok();
    }

    // ── the unit loop ─────────────────────────────────────────────────────────

    @Override
    public Map<String, Object> candidates() {
        calls.add("candidates");
        Map<String, Object> out = ok();
        out.put("done", queue.isEmpty());
        return out;
    }

    @Override
    public Map<String, Object> startIteration(String unit) {
        calls.add("startIteration(" + unit + ")");
        queue.poll();
        roundsRun = 0;
        if (unit.equals(failOnUnit)) {
            throw new IllegalStateException("killed while improving " + unit);
        }
        Map<String, Object> out = ok();
        out.put("file", unit);
        out.put("branch", "tests/improve-" + unit);
        return out;
    }

    @Override
    public Map<String, Object> baselineMutation(String unit) {
        calls.add("baselineMutation(" + unit + ")");
        Map<String, Object> out = ok();
        if (pitSkip) {
            out.put("skip", true);
            out.put("reason", "too few mutants to improve");
        }
        return out;
    }

    @Override
    public Map<String, Object> coverageGaps(String unit) {
        calls.add("coverageGaps(" + unit + ")");
        return ok();
    }

    // ── a phase ───────────────────────────────────────────────────────────────

    @Override
    public Map<String, Object> buildPrompt(Phase phase, String unit) {
        calls.add("buildPrompt(" + phase + ")");
        Map<String, Object> out = ok();
        if (phaseSkip) {
            out.put("skip", true);
            out.put("reason", "nothing to ask for");
        }
        out.put("system", "you write tests");
        out.put("prompt", "here is " + unit);
        return out;
    }

    @Override
    public Map<String, Object> chat(Map<String, Object> ask) {
        calls.add("chat");
        Map<String, Object> out = ok();
        out.put("text", "```java\n// a test\n```");
        return out;
    }

    @Override
    public Map<String, Object> parseTests(Map<String, Object> answer, Map<String, Object> plan) {
        calls.add("parseTests");
        Map<String, Object> out = ok();
        out.put("tests", List.of(Map.of("path", "src/test/java/T1.java", "content", "class T1 {}")));
        out.put("paths", List.of("src/test/java/T1.java"));
        out.put("count", parsedCount);
        out.put("chosen", chosen);
        return out;
    }

    @Override
    public Map<String, Object> writeTests(Object tests, String stage, String note, Object targetMutant) {
        calls.add("writeTests(" + stage + ")");
        notes.add(note);
        targets.add(targetMutant);
        return ok();
    }

    @Override
    public Map<String, Object> runTests(String stage) {
        calls.add("runTests(" + stage + ")");
        Map<String, Object> out = ok();
        // The re-run after a repair is the one the workflow makes with no stage — the same
        // distinction the port uses to tell the two suite runs apart.
        out.put("passed", stage == null ? greenAfterRepair : green);
        out.put("summary", "1 test failed: expected <2> but was <3>");
        return out;
    }

    @Override
    public Map<String, Object> buildRepair(String unit, String stage, Object summary, Object tests) {
        calls.add("buildRepair(" + stage + ")");
        Map<String, Object> out = ok();
        out.put("prompt", "fix it: " + summary);
        return out;
    }

    @Override
    public Map<String, Object> parseRepair(Map<String, Object> answer, Map<String, Object> previous) {
        calls.add("parseRepair");
        Map<String, Object> out = ok();
        out.put("tests", List.of(Map.of("path", "src/test/java/T2.java", "content", "class T2 {}")));
        out.put("paths", List.of("src/test/java/T2.java"));
        out.put("count", 1);
        return out;
    }

    @Override
    public Map<String, Object> deleteTests(List<String> paths) {
        calls.add("deleteTests");
        deleted.add(List.copyOf(paths));
        return ok();
    }

    // ── verification, the round verdict and the tail ──────────────────────────

    @Override
    public Map<String, Object> verify(String unit) {
        calls.add("verify(" + unit + ")");
        Map<String, Object> out = ok();
        out.put("keepRound", keepRound);
        // Deliberately the OPPOSITE of what accept/miss answers, so a port that read the loop
        // condition off verify — as the workflow's IF does not — fails these tests.
        out.put("continueRounds", !keepRound);
        return out;
    }

    @Override
    public Map<String, Object> acceptRound(String unit) {
        calls.add("acceptRound(" + unit + ")");
        return roundVerdict();
    }

    @Override
    public Map<String, Object> missRound(String unit) {
        calls.add("missRound(" + unit + ")");
        return roundVerdict();
    }

    private Map<String, Object> roundVerdict() {
        roundsRun += 1;
        Map<String, Object> out = ok();
        out.put("rounds", roundsRun);
        out.put("continueRounds", roundsRun < roundsPerUnit);
        return out;
    }

    @Override
    public Map<String, Object> dropLastRound(String unit) {
        calls.add("dropLastRound(" + unit + ")");
        Map<String, Object> out = ok();
        out.put("improved", improved);
        out.put("rounds", roundsRun);
        return out;
    }

    @Override
    public Map<String, Object> cleanupTests(String unit) {
        calls.add("cleanupTests(" + unit + ")");
        return ok();
    }

    @Override
    public Map<String, Object> createPr(String unit, Object title, Object prBody, Object labels) {
        calls.add("createPr(" + unit + ")");
        Map<String, Object> pr = new LinkedHashMap<>();
        pr.put("file", unit);
        pr.put("title", title);
        pr.put("body", prBody);
        pr.put("labels", labels);
        createdPrs.add(pr);
        return ok();
    }

    @Override
    public Map<String, Object> discardChanges(String unit, String reason) {
        calls.add("discardChanges(" + unit + ")");
        discardReasons.add(reason);
        return ok();
    }

    @Override
    public Map<String, Object> finishRun() {
        calls.add("finishRun");
        return ok();
    }

    /// The status is IN the recorded call, so a test can tell `failed` from `stopped` — the
    /// difference between a run that broke and one a person ended, which the page renders
    /// differently and a reader needs.
    @Override
    public Map<String, Object> abortRun(String status, String detail) {
        calls.add("abortRun(" + status + ")");
        aborted = detail;
        return ok();
    }

    /// The detail of the last abort, for asserting that the reason survived the trip.
    public String aborted;

    /// `{ok: true}`, as every route answers.
    private static Map<String, Object> ok() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        return out;
    }
}
