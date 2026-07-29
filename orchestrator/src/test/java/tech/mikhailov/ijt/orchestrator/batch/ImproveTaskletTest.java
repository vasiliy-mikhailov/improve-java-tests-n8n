package tech.mikhailov.ijt.orchestrator.batch;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.batch.test.MetaDataInstanceFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Every branch of the UNIT loop, one test each.
///
/// These are read off `n8n/generate-workflows.mjs`: More Work?, File Picked?, Pick Retryable?,
/// Has Mutants?, Keep Round?, Another Round?, Approved?. Getting one wrong is a silent behaviour
/// change — the workflow still runs, it just does something else — so each one is pinned by the
/// call sequence it produces rather than by an outcome flag.
class ImproveTaskletTest {

    private final FakeBackend backend = new FakeBackend();

    private final ImproveTasklet tasklet = new ImproveTasklet(backend);

    private final StepExecution stepExecution = MetaDataInstanceFactory.createStepExecution();

    private RepeatStatus runOnce() {
        StepContribution contribution = new StepContribution(stepExecution);
        RepeatStatus status = tasklet.execute(contribution, new ChunkContext(new StepContext(stepExecution)));
        stepExecution.apply(contribution);
        return status;
    }

    @Test
    void more_work_no_finishes_the_step_without_picking() {
        RepeatStatus status = runOnce();

        assertEquals(RepeatStatus.FINISHED, status);
        assertEquals(List.of("candidates"), backend.calls);
    }

    @Test
    void a_retryable_pick_failure_loops_without_starting_an_iteration() {
        backend.queue.add("A.java::one");
        backend.pickReturnsNoFile = true;
        backend.pickRetry = true;

        RepeatStatus status = runOnce();

        // A transient failure — the model returned something unusable — is worth another pick.
        // The route caps how many in a row, which is what stops this looping for ever.
        assertEquals(RepeatStatus.CONTINUABLE, status);
        assertEquals(List.of("candidates", "applyRules(pick_file)"), backend.calls);
    }

    @Test
    void a_terminal_pick_failure_ends_the_batch() {
        backend.queue.add("A.java::one");
        backend.pickReturnsNoFile = true;
        backend.pickRetry = false;

        RepeatStatus status = runOnce();

        // The team rule excludes every remaining candidate: picking again can only produce the
        // same answer, and the queue is not going to change on its own.
        assertEquals(RepeatStatus.FINISHED, status);
        assertEquals(List.of("candidates", "applyRules(pick_file)"), backend.calls);
    }

    @Test
    void a_class_with_nothing_to_mutate_is_skipped_before_the_round_budget_is_spent() {
        backend.queue.add("A.java::one");
        backend.pitSkip = true;

        RepeatStatus status = runOnce();

        assertEquals(RepeatStatus.CONTINUABLE, status);
        assertEquals(List.of("candidates", "applyRules(pick_file)", "startIteration(A.java::one)",
                "baselineMutation(A.java::one)"), backend.calls);
        // no round, no verification, and no unit counted as settled
        assertEquals(0L, stepExecution.getWriteCount());
    }

    @Test
    void a_kept_round_is_accepted_and_a_missed_round_is_binned() {
        backend.queue.add("A.java::one");
        backend.keepRound = false;

        runOnce();

        assertTrue(backend.calls.contains("missRound(A.java::one)"));
        assertFalse(backend.calls.contains("acceptRound(A.java::one)"));

        backend.reset();
        backend.queue.add("A.java::one");
        backend.keepRound = true;

        runOnce();

        assertTrue(backend.calls.contains("acceptRound(A.java::one)"));
        assertFalse(backend.calls.contains("missRound(A.java::one)"));
    }

    @Test
    void another_round_returns_to_coverage_gaps_and_not_to_the_pick() {
        backend.queue.add("A.java::one");
        backend.roundsPerUnit = 3;

        RepeatStatus status = runOnce();

        assertEquals(RepeatStatus.CONTINUABLE, status);
        // three rounds within ONE unit: the ROUND loop is inside the unit, and the candidate
        // queue is asked once
        assertEquals(3L, backend.callCount("coverageGaps"));
        assertEquals(3L, backend.callCount("verify"));
        assertEquals(1L, backend.callCount("candidates"));
        assertEquals(1L, backend.callCount("startIteration"));
        // and the baseline PIT run is NOT repeated per round
        assertEquals(1L, backend.callCount("baselineMutation"));
    }

    @Test
    void the_loop_condition_is_the_round_verdict_and_not_the_verification() {
        backend.queue.add("A.java::one");
        backend.roundsPerUnit = 2;
        // verify answers `continueRounds` too, and the fake makes it the opposite of accept's
        // answer on purpose: reading the loop condition off the wrong node reverses this test.
        backend.keepRound = true;

        runOnce();

        assertEquals(2L, backend.callCount("coverageGaps"));
    }

    @Test
    void an_approved_unit_is_cleaned_up_composed_and_opened_as_a_pr() {
        backend.queue.add("A.java::one");
        backend.approved = true;
        backend.improved = true;

        runOnce();

        assertEquals(List.of("dropLastRound(A.java::one)", "applyRules(check_changes)",
                        "cleanupTests(A.java::one)", "applyRules(make_pr)", "createPr(A.java::one)"),
                backend.calls.subList(backend.calls.indexOf("dropLastRound(A.java::one)"), backend.calls.size()));
        assertEquals("test: improve MAC", backend.createdPrs.get(0).get("title"));
        assertEquals(List.of("tests"), backend.createdPrs.get(0).get("labels"));
        assertEquals(1L, stepExecution.getWriteCount());
    }

    @Test
    void a_rule_rejection_discards_with_the_reason_the_rule_gave() {
        backend.queue.add("A.java::one");
        backend.approved = false;
        backend.checkReason = "touches ui code";

        runOnce();

        assertEquals(List.of("touches ui code"), backend.discardReasons);
        assertFalse(backend.calls.contains("cleanupTests(A.java::one)"));
        assertFalse(backend.calls.contains("applyRules(make_pr)"));
    }

    @Test
    void a_rejection_with_no_stated_reason_still_says_something() {
        backend.queue.add("A.java::one");
        backend.approved = false;
        backend.checkReason = null;

        runOnce();

        assertEquals(List.of("not approved"), backend.discardReasons);
    }

    @Test
    void an_approving_rule_does_not_open_a_pr_for_an_unimproved_unit() {
        backend.queue.add("A.java::one");
        backend.approved = true;
        // the branch changed no test file, or the MAC did not move: any measured delta is PIT
        // run-to-run noise, and a PR for it is noise a reviewer pays for
        backend.improved = false;

        runOnce();

        assertTrue(backend.createdPrs.isEmpty());
        assertEquals(1, backend.discardReasons.size());
    }
}
