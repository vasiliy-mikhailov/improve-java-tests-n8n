package tech.mikhailov.ijt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static tech.mikhailov.ijt.Budget.Completion;
import static tech.mikhailov.ijt.Budget.Options;

class BudgetTest {

    private static final Options OPTS = new Options(3000, 16000);

    @Test
    void theCeilingIsTheAnswerBudgetPlusTheThinkingReserve() {
        Budget b = new Budget(OPTS);
        assertEquals(5500, b.ceiling("improving_mutation", 2500));
    }

    @Test
    void withThinkingOffTheAnswerBudgetIsTheWholeCeiling() {
        Budget b = new Budget(new Options(0, 16000));
        assertEquals(2500, b.ceiling("improving_mutation", 2500));
    }

    @Test
    void aTruncatedAnswerEscalatesAndTheEscalationIsCapped() {
        Budget b = new Budget(OPTS);
        assertEquals(11000, b.escalate("improving_mutation", 5500));
        assertEquals(16000, b.escalate("improving_mutation", 11000));
        assertEquals(16000, b.escalate("improving_mutation", 16000));
    }

    @Test
    void aStageThatTruncatedOnceStartsHigherNextTime() {
        // 4 of 12 calls in the JSON-java run finished with finish_reason=length, and each
        // re-ran the whole prompt at ~100s. Paying that again for the same stage is the bug.
        Budget b = new Budget(OPTS);
        b.record("improving_mutation", new Completion("length", 5500, null));
        assertEquals(11000, b.ceiling("improving_mutation", 2500), "learned from the truncation");
    }

    @Test
    void whatOneStageLearnsDoesNotInflateTheOthers() {
        Budget b = new Budget(OPTS);
        b.record("improving_mutation", new Completion("length", 5500, null));
        assertEquals(6000, b.ceiling("improving_coverage", 3000));
    }

    @Test
    void aStageThatAnswersComfortablyIsNeverInflated() {
        Budget b = new Budget(OPTS);
        for (int i = 0; i < 5; i++) b.record("improving_coverage", new Completion("stop", 6000, 900));
        assertEquals(6000, b.ceiling("improving_coverage", 3000));
    }

    @Test
    void aStageThatKeepsAnsweringNearItsCeilingGetsHeadroomBeforeItTruncates() {
        Budget b = new Budget(OPTS);
        // stopping cleanly at 5200 of 5500 twice means the next answer very likely will not fit
        b.record("improving_mutation", new Completion("stop", 5500, 5200));
        b.record("improving_mutation", new Completion("stop", 5500, 5300));
        assertTrue(b.ceiling("improving_mutation", 2500) > 5500, "should widen before hitting the wall");
    }

    @Test
    void theLearnedCeilingNeverExceedsTheHardMaximum() {
        Budget b = new Budget(OPTS);
        for (int i = 0; i < 10; i++) b.record("improving_mutation", new Completion("length", 16000, null));
        assertEquals(16000, b.ceiling("improving_mutation", 2500));
    }

    @Test
    void learningSurvivesARestart() {
        Budget b = new Budget(OPTS);
        b.record("improving_mutation", new Completion("length", 5500, null));
        Budget revived = new Budget(OPTS, b.snapshot());
        assertEquals(11000, revived.ceiling("improving_mutation", 2500));
    }

    @Test
    void atTheHardMaximumThereIsNothingToEscalateToAndTheCallIsNotRepeated() {
        // escalate() returns the same ceiling once capped, and the LLM client re-issued a
        // byte-identical request: another ~100-200 s for a deterministically identical
        // truncated answer, with an event line claiming a bigger budget.
        Budget b = new Budget(OPTS);
        assertFalse(b.grew("improving_mutation", 16000), "already at the cap");
        assertTrue(b.grew("improving_mutation", 5500));
    }

    // ── what the boxed fields mean, which the JS left implicit ─────────────
    @Test
    void anUnstatedAnswerBudgetFallsBackToTheDefaultNeed() {
        // the JS wrote `answerTokens || 4096`, so both an absent budget and a zero one mean
        // "the caller stated no need" — a primitive 0 here would ask for the reserve alone
        Budget b = new Budget(OPTS);
        assertEquals(7096, b.ceiling("improving_mutation", null));
        assertEquals(7096, b.ceiling("improving_mutation", 0));
    }

    @Test
    void aCompletionThatReportsNothingTeachesNothing() {
        // no finish_reason and no ceiling is not a near-ceiling answer; it is no evidence at all
        Budget b = new Budget(OPTS);
        b.record("improving_mutation", new Completion(null, null, null));
        b.record("improving_mutation", null);
        assertEquals(5500, b.ceiling("improving_mutation", 2500));
    }

    @Test
    void aRunOfNearCeilingAnswersIsBrokenByOneComfortableOne() {
        // the two must be consecutive — one long test in the middle of ordinary ones is not a
        // stage that has outgrown its ceiling
        Budget b = new Budget(OPTS);
        b.record("improving_mutation", new Completion("stop", 5500, 5200));
        b.record("improving_mutation", new Completion("stop", 5500, 900));
        b.record("improving_mutation", new Completion("stop", 5500, 5300));
        assertEquals(5500, b.ceiling("improving_mutation", 2500), "one near miss on its own proves nothing");
    }

    @Test
    void theSnapshotIsACopyAndNotAWindowOntoLaterLearning() {
        // the JS toJSON() returned its live maps; here the state file gets a copy, so a budget
        // that keeps learning cannot rewrite what was already reported as saved
        Budget b = new Budget(OPTS);
        Budget.Saved before = b.snapshot();
        b.record("improving_mutation", new Completion("length", 5500, null));
        assertTrue(before.learned().isEmpty());
        assertEquals(Integer.valueOf(11000), b.snapshot().learned().get("improving_mutation"));
    }
}
