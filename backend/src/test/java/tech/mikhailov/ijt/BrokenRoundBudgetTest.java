package tech.mikhailov.ijt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// A round whose test never ran is not evidence about the unit.
///
/// The miss counter was incremented on every round that failed to raise MAC, with no reference to
/// whether the round's test was on disk when PIT ran. RoundOutcome already knew — it returns
/// Kind.BROKEN with "the round's test never reached the measurement (it broke the suite and was
/// deleted) — the mutant was not challenged" — and the unit-level accounting contradicted it.
///
/// Measured over the whole event log: 814 of roughly 1,395 misses were broken rounds. 58% of
/// every miss this pipeline has ever recorded was a test that never executed. In one evening
/// JSONObject#getNumber, #similar, #wrongValueFormatException and #getAnnotation each burned a
/// full three-miss attempt that way — coverage bit-identical every round, because the tree being
/// measured was unchanged — and each was then recorded `no_improvement`. That verdict goes into
/// improvedLedger and stops the unit being offered on later runs, so the mistake is durable.
///
/// The budget is separate and BOUNDED. Free retries are only worth having if the retry differs,
/// and until the last-round feedback reached the live prompts it could not: at temperature 0.2
/// the same prompt returns the same uncompilable test. The model is now told the build's own
/// error, so a retry asks a different question — three of them, and then broken rounds cost
/// misses like anything else, or a unit whose every round fails to compile would round until its
/// hour-long time budget ran out.
class BrokenRoundBudgetTest {

    private static final int MAX_BROKEN = Rounds.DEFAULT_MAX_BROKEN_ROUNDS;

    @Test
    void aBrokenRoundDoesNotSpendTheMissBudget() {
        // THE fix. Before this, four units lost a whole attempt to rounds that never executed.
        Rounds.MissBudget b = Rounds.afterRound(false, true, 0, 0, MAX_BROKEN);
        assertEquals(0, b.consecutiveMisses(), "nothing ran, so nothing was learned about the unit");
        assertEquals(1, b.brokenRounds());
    }

    @Test
    void anOrdinaryMissStillCosts() {
        // the negative half: a test that RAN and killed nothing is real evidence
        Rounds.MissBudget b = Rounds.afterRound(false, false, 1, 0, MAX_BROKEN);
        assertEquals(2, b.consecutiveMisses());
    }

    @Test
    void brokenRoundsAreNotFreeForEver() {
        // past its own budget a broken round costs a miss like any other, or a unit whose every
        // round fails to compile rounds until the unit time budget expires — an hour of tests
        // that never execute
        Rounds.MissBudget b = Rounds.afterRound(false, true, 0, MAX_BROKEN, MAX_BROKEN);
        assertEquals(1, b.consecutiveMisses(), "the free retries are spent");
        assertEquals(MAX_BROKEN + 1, b.brokenRounds());
    }

    @Test
    void theUnitStillTerminates() {
        // walked forward: three free broken rounds, then every one costs, so the ordinary
        // three-miss stop is still reached in a bounded number of rounds
        long misses = 0;
        long broken = 0;
        int rounds = 0;
        while (misses < 3 && rounds < 50) {
            Rounds.MissBudget b = Rounds.afterRound(false, true, misses, broken, MAX_BROKEN);
            misses = b.consecutiveMisses();
            broken = b.brokenRounds();
            rounds++;
        }
        assertEquals(3, misses, "a unit that never compiles anything still stops");
        assertEquals(MAX_BROKEN + 3, rounds, "after the free ones, each broken round costs a miss");
    }

    @Test
    void aTestThatRunsEndsTheBrokenStreak() {
        // `brokenRounds` counts CONSECUTIVE broken rounds. A unit that got a test to execute and
        // simply killed nothing is in the ordinary miss regime, not this one.
        Rounds.MissBudget b = Rounds.afterRound(false, false, 0, 2, MAX_BROKEN);
        assertEquals(0, b.brokenRounds());
        assertEquals(1, b.consecutiveMisses());
    }

    @Test
    void aKeptRoundClearsBoth() {
        Rounds.MissBudget b = Rounds.afterRound(true, false, 2, 2, MAX_BROKEN);
        assertEquals(0, b.consecutiveMisses());
        assertEquals(0, b.brokenRounds());
    }

    @Test
    void aKeptRoundClearsThemEvenIfTheRoundAlsoLooksBroken() {
        // contradictory inputs, and keeping wins: a round whose MAC rose plainly reached the
        // measurement, whatever the file-presence check thinks it saw
        assertEquals(0, Rounds.afterRound(true, true, 2, 2, MAX_BROKEN).consecutiveMisses());
    }
}
