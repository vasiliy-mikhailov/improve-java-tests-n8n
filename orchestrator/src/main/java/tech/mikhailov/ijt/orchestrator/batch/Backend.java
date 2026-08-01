package tech.mikhailov.ijt.orchestrator.batch;

import java.util.List;
import java.util.Map;

/// The 44 HTTP calls, as method calls.
///
/// One method per node of `n8n/generate-workflows.mjs`, named after the node, in the order the
/// workflow makes them. That is deliberate: the tasklets below are a transcription of a graph,
/// and a transcription is only checkable if both sides use the same names. Reading
/// {@link ImproveTasklet} beside the generator should be a diff, not an interpretation.
///
/// **Why a seam at all, rather than the tasklets calling {@link tech.mikhailov.ijt.Repo},
/// {@link tech.mikhailov.ijt.Pit} and friends directly.** Two reasons, both concrete:
///
/// 1. The per-call RULES are not in those classes. They are in the route bodies of
///    {@link tech.mikhailov.ijt.Server}, and every one of them was paid for: the PIT route
///    refunds the iteration a no-mutants class charged (three duds in a row used to end a run
///    before an improvable class was ever attempted), records `unmeasured` WITHOUT writing a
///    zero, and writes the improvement ledger that stops run 2 redoing run 1's work. Those
///    bodies are package-private to `tech.mikhailov.ijt`. Re-typing them here would be
///    reimplementing the domain layer — which the brief forbids — and the copies would drift.
///
/// 2. Everything below shells out to Maven, Gradle, PIT or a model. A test that could not
///    stand in for this interface could not test the control flow at all, and the control flow
///    — fifteen decision points — is the part being ported.
///
/// What is NOT here: `POST /api/run/start`. In the workflow it hangs off the trigger, not off
/// the spine; here the trigger is whatever launches the job, so the run must exist before
/// `improveJob` starts. A restart must NOT call it — it clears `state.files`, and the candidate
/// units that survived the crash are the whole reason the job can resume at the next unit.
///
/// Answers are `Map<String, Object>` and not typed records on purpose. These are the same JSON
/// objects the n8n nodes read, and the branches below test the same field names by the same
/// truthiness rules (see {@link Wire}). A typed layer here would be a second place for
/// `keepRound` to mean something slightly different.
public interface Backend {

    /// `POST /api/repo/clone` — n8n node timeout 20m.
    Map<String, Object> cloneRepo();

    /// `POST /api/rules/apply` with `{stage}` and an optional context.
    ///
    /// Six stages reach here: post_clone, pre_pick, write_test, pick_file, check_changes,
    /// make_pr. The answer is `{ok, stage, result}` and `result`'s shape is the stage's
    /// business — see {@link tech.mikhailov.ijt.Rules}.
    Map<String, Object> applyRules(String stage, Map<String, Object> context);

    /// `POST /api/repo/prepare` — build + toolchain detection. n8n node timeout 50m.
    Map<String, Object> prepare();

    /// `POST /api/coverage/run` with `{phase: 'baseline', stage: 'measuring_baseline'}`.
    ///
    /// The baseline phase is not just a measurement: it is what expands scanned FILES into
    /// `path::method` units and only then replays the ledgers against them. Both live in the
    /// route.
    Map<String, Object> baselineCoverage();

    /// `GET /api/files/candidates` — the queue, and whether the batch is finished.
    Map<String, Object> candidates();

    /// `POST /api/iteration/start` with `{file}`, where `file` is the unit key the pick chose.
    Map<String, Object> startIteration(String unit);

    /// `POST /api/pit/run` with `{file, phase: 'baseline', stage: 'improving_mutation'}`.
    Map<String, Object> baselineMutation(String unit);

    /// `GET /api/files/gaps?path=` — the round's entry point, re-read every round.
    ///
    /// The answer is not passed on anywhere; the prompt routes name the unit and read the gaps
    /// themselves, from the freshest state. Called for the same reason the workflow calls it:
    /// it is the node the ROUND loop returns to, and it sets the stage a human reads.
    Map<String, Object> coverageGaps(String unit);

    /// `POST /api/prompt/coverage` or `POST /api/prompt/batch`, per {@link Phase}.
    Map<String, Object> buildPrompt(Phase phase, String unit);

    /// `POST /api/llm/chat` with the whole prompt object as the body — the node sends `$json`.
    Map<String, Object> chat(Map<String, Object> ask);

    /// `POST /api/tests/parse` with `{resp, plan}`.
    Map<String, Object> parseTests(Map<String, Object> answer, Map<String, Object> plan);

    /// `POST /api/test/write-many`.
    ///
    /// `stage`, `note` and `targetMutant` are all null on the repair write — the workflow sends
    /// `{tests}` alone there — and that is load-bearing: the target stamp records which mutant a
    /// ROUND is trying to kill, and a repair re-stamping it would let a later round inherit a
    /// target it never aimed at.
    Map<String, Object> writeTests(Object tests, String stage, String note, Object targetMutant);

    /// `POST /api/test/run`. `stage` null on the post-repair re-run, exactly as the node sends
    /// `{}` — a null stage leaves the dashboard where the failing run put it.
    Map<String, Object> runTests(String stage);

    /// `POST /api/prompt/repair` with `{path, stage, summary, tests}`.
    Map<String, Object> buildRepair(String unit, String stage, Object summary, Object tests);

    /// `POST /api/tests/parse-repair` with `{resp, prev}`.
    Map<String, Object> parseRepair(Map<String, Object> answer, Map<String, Object> previous);

    /// `POST /api/test/delete-many` — the generated AND repaired paths, concatenated.
    Map<String, Object> deleteTests(List<String> paths);

    /// `POST /api/verify` — full suite, re-measure, and the round verdict (`keepRound`,
    /// `continueRounds`).
    Map<String, Object> verify(String unit);

    /// `POST /api/round/accept` — commit the round; answers with the STORED `continueRounds`.
    Map<String, Object> acceptRound(String unit);

    /// `POST /api/round/miss` — bin the round's test, keep the unit. Decides `continueRounds`
    /// afresh; it used to echo the previous round's answer and the loop ran for ever.
    Map<String, Object> missRound(String unit);

    /// `POST /api/round/drop` — discard whatever is uncommitted and report the unit's totals.
    Map<String, Object> dropLastRound(String unit);

    /// `POST /api/test/cleanup` — an LLM tidy-up of the generated tests, reverted unless the
    /// suite stays green and the mutation score holds.
    Map<String, Object> cleanupTests(String unit);

    /// `POST /api/pr/create` with the title/body/labels the make_pr rule produced.
    Map<String, Object> createPr(String unit, Object title, Object body, Object labels);

    /// `POST /api/iteration/discard` with the check_changes rule's reason.
    Map<String, Object> discardChanges(String unit, String reason);

    /// `POST /api/run/finish`.
    Map<String, Object> finishRun();

    /// `POST /api/run/finish` with a terminal status that is not `done`.
    ///
    /// Separate from {@link #finishRun()} because the caller is different in kind: finishRun is
    /// the last step of a job that worked, this is a listener reporting that the job did not.
    /// Same route — ending a run is one operation and should stay one.
    Map<String, Object> abortRun(String status, String detail);
}
