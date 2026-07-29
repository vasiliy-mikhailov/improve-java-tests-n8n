package tech.mikhailov.ijt;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static tech.mikhailov.ijt.RoundOutcome.Kind;
import static tech.mikhailov.ijt.RoundOutcome.Mutant;
import static tech.mikhailov.ijt.RoundOutcome.Outcome;

/// A round can fail in two completely different ways, and the pipeline reported them as one.
///
/// Observed on the live JSON-java run, 41 times across 19 units. The round writes a test, the
/// suite goes RED, the repair fails, the workflow DELETES the test — and then verification
/// re-measures, finds the score unchanged (of course: there is no test) and announces
///
///   targeted mutant NullReturnValsMutator at line 360: STILL ALIVE — the new test does not
///   distinguish it
///
/// about a file that is not on disk. CDL#shouldQuoteValue burned 8 rounds that way,
/// JSONTokener#nextSimpleValue 7, and JSONWriter#value was abandoned at 20% after three of
/// them — then re-picked and improved to 40% on the first round that actually compiled.
///
/// The cost is not only the false sentence. The mutant was marked attempted at write time, so
/// selection strikes it off for good; mutator statistics record a try with no kill, which
/// demotes that whole mutator kind after two; and the miss counter ends the unit. Every one of
/// those is a conclusion drawn from a measurement that never happened.
class RoundOutcomeTest {

    private static final Mutant TARGET = new Mutant("NullReturnValsMutator", 360);
    private static List<Mutant> survivedWith(Mutant m) { return List.of(m); }

    @Test
    void aTestThatNeverReachedTheMeasurementSaysNothingAboutTheMutant() {
        Outcome r = RoundOutcome.of(TARGET, survivedWith(TARGET), false, true, 0, 0);
        assertEquals(Kind.BROKEN, r.kind());
        assertNull(r.stillAlive(), "no claim either way — the test was not there");
        assertFalse(r.message().contains("does not distinguish"),
                "that sentence describes a test that ran; this one did not");
        assertTrue(r.message().contains("never reached the measurement"));
    }

    @Test
    void aBrokenRoundDoesNotCountAsATryAgainstTheMutatorKind() {
        // two tries with no kill demotes a mutator for the rest of the run. Compile failures
        // are evidence about the generated test, not about NullReturnVals mutants.
        Outcome r = RoundOutcome.of(TARGET, survivedWith(TARGET), false, true, 0, 0);
        assertFalse(r.countMutatorTry());
    }

    @Test
    void withOtherMutantsLeftABrokenRoundMovesOnRatherThanRetrying() {
        // The run's own evidence: JSONWriter#value broke on NullReturnVals at lines 360, 370
        // and 380, then killed a RemoveConditional mutant on the first round that compiled. At
        // temperature 0.2 the same prompt yields the same uncompilable test, so handing the
        // mutant back would spend the round re-learning that.
        Outcome r = RoundOutcome.of(TARGET, survivedWith(TARGET), false, true, 3, 0);
        assertFalse(r.unattempt());
    }

    @Test
    void butTheLastEligibleMutantIsHandedBack() {
        // with nothing else to try, leaving it marked reads as "no un-attempted survivors
        // left" and ends the unit after a single round that measured nothing
        Outcome r = RoundOutcome.of(TARGET, survivedWith(TARGET), false, true, 0, 0);
        assertTrue(r.unattempt());
        assertTrue(r.message().contains("only remaining"));
    }

    @Test
    void andOnlyOnceAMutantThatBreaksTwiceStaysMarkedEvenAsTheLastOne() {
        // otherwise the unit loops on it until the miss budget runs out
        Outcome r = RoundOutcome.of(TARGET, survivedWith(TARGET), false, true, 0, 1);
        assertEquals(Kind.BROKEN, r.kind());
        assertFalse(r.unattempt());
        assertTrue(r.message().contains("twice"));
    }

    @Test
    void aTestThatRanAndLeftTheMutantAliveIsReportedAsResistingIt() {
        Outcome r = RoundOutcome.of(TARGET, survivedWith(TARGET), true, true, 0, 0);
        assertEquals(Kind.RESISTED, r.kind());
        assertEquals(Boolean.TRUE, r.stillAlive());
        assertTrue(r.message().contains("STILL ALIVE — the new test does not distinguish it"));
        assertTrue(r.countMutatorTry());
        assertFalse(r.unattempt());
    }

    @Test
    void aKillIsAKill() {
        Outcome r = RoundOutcome.of(TARGET, List.of(), true, true, 0, 0);
        assertEquals(Kind.KILLED, r.kind());
        assertEquals(Boolean.FALSE, r.stillAlive());
        assertTrue(r.countMutatorTry());
    }

    @Test
    void theMutantIsMatchedOnKindAndLineNotEitherAlone() {
        // a different mutator at the same line is a different mutant
        Outcome other = RoundOutcome.of(TARGET, survivedWith(new Mutant("VoidMethodCallMutator", 360)), true, true, 0, 0);
        assertEquals(Kind.KILLED, other.kind(), "ours is gone from the survivor list");
        Outcome elsewhere = RoundOutcome.of(TARGET, survivedWith(new Mutant("NullReturnValsMutator", 999)), true, true, 0, 0);
        assertEquals(Kind.KILLED, elsewhere.kind());
    }

    @Test
    void noTargetMutantMeansNoVerdictToReport() {
        assertNull(RoundOutcome.of(null, List.of(), true, true, 0, 0));
        assertNull(RoundOutcome.of(new Mutant("X", 0), List.of(), true, true, 0, 0),
                "a target without a line cannot be matched against PIT output");
    }

    @Test
    void aRoundThatWroteNoTestAtAllMakesNoClaimEither() {
        // The model declines when it judges the mutation equivalent, and the prompt tells it
        // to. That is a deliberate judgement about the mutant, so the mutant stays attempted —
        // but "the new test does not distinguish it" is still a sentence about a test that is
        // not there, and "broken" would be wrong too: nothing broke.
        assertNull(RoundOutcome.of(TARGET, survivedWith(TARGET), false, false, 0, 0));
    }

    @Test
    void presentNessIsOnlyConsultedWhenTheRoundActuallyWroteSomething() {
        Outcome r = RoundOutcome.of(TARGET, survivedWith(TARGET), false, true, 0, 0);
        assertEquals(Kind.BROKEN, r.kind());
    }

    @Test
    void aBrokenRoundIsNeverCreditedWithAKillItDidNotEarn() {
        // the survivor list will not contain the mutant if PIT could not run the class at all;
        // absent a test, that must not read as success
        Outcome r = RoundOutcome.of(TARGET, List.of(), false, true, 0, 0);
        assertEquals(Kind.BROKEN, r.kind());
        assertNotEquals(Boolean.FALSE, r.stillAlive(), "silence is not a kill");
    }

    // ── the inputs it is given: gathered, not assumed ──────────────────────
    // The verify route computed these inline, so nothing tested them — and every verdict
    // above is only as good as they are.

    /// A PIT survivor as the report carries it: kind, line and the per-mutant index that
    /// {@link Select#attemptKey} needs to tell two mutants of one kind on one line apart.
    private static Select.Mutant surv(String mutator, int line, int index) {
        return new Select.Mutant(mutator, line, index, null);
    }

    @Test
    void aRoundIsPresentOnlyWhenEveryFileItWroteIsStillThere() {
        Set<String> on = Set.of("a/X.java");
        var e = RoundOutcome.evidence(List.of("a/X.java"), on::contains);
        assertTrue(e.wroteAny());
        assertTrue(e.testsPresent());
    }

    @Test
    void oneDeletedFileOutOfTwoIsNotPresent() {
        // generation writes the mut test, repair rewrites it; if the deleter takes either,
        // the measurement did not see what the round produced
        Set<String> on = Set.of("a/X.java");
        var e = RoundOutcome.evidence(List.of("a/X.java", "a/Y.java"), on::contains);
        assertFalse(e.testsPresent());
    }

    @Test
    void aRoundThatWroteNothingIsNotABrokenRound() {
        var e = RoundOutcome.evidence(List.of(), p -> false);
        assertFalse(e.wroteAny());
    }

    @Test
    void aNullPathListIsTreatedAsHavingWrittenNothing() {
        var e = RoundOutcome.evidence(null, p -> false);
        assertFalse(e.wroteAny());
        assertTrue(e.testsPresent(), "vacuously — but wroteAny is what gates the verdict");
    }

    @Test
    void otherEligibleExcludesTheTargetItselfAndEverythingAlreadyAttempted() {
        Select.Mutant target = surv("RemoveConditionalMutator_EQUAL_ELSE", 248, 11);
        var e = RoundOutcome.evidence(
                List.of(), p -> false,
                List.of(target, surv("RemoveConditionalMutator_EQUAL_ELSE", 248, 14), surv("MathMutator", 90, 3)),
                List.of(Select.attemptKey(target)),
                Select.attemptKey(target),
                null);
        assertEquals(2, e.otherEligible(), "the sibling at index 14 and the Math mutant both remain");
    }

    @Test
    void otherEligibleCountsOnlyMutantsTheNextRoundWillActuallyBeOffered() {
        // lastSurvived is capped, so a survivor past the cap is not something to "move on to"
        Select.Mutant target = surv("A", 1, 0);
        List<Select.Mutant> many = new ArrayList<>();
        many.add(target);
        for (int i = 0; i < 40; i++) many.add(surv("B", i + 2, i));
        var e = RoundOutcome.evidence(
                List.of(), p -> false, many,
                List.of(Select.attemptKey(target)), Select.attemptKey(target), 10);
        assertEquals(9, e.otherEligible(), "ten offerable, minus the attempted target");
    }

    @Test
    void withNoSurvivorListThereIsNothingElseToMoveOnTo() {
        var e = RoundOutcome.evidence(List.of(), p -> false);
        assertEquals(0, e.otherEligible());
    }
}
