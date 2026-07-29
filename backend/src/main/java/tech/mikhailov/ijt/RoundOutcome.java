package tech.mikhailov.ijt;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/// What actually happened to the mutant this round was aiming at.
///
/// The verify route used to answer this with one boolean: is the target still in PIT's
/// survivor list? That reads the right data and draws the wrong conclusion, because it
/// cannot tell the difference between
///
///   - the test ran and does not distinguish the mutation
///   - the test never existed when PIT ran
///
/// and the second happens often. A generated test that breaks the suite is repaired once;
/// if the repair also fails, the workflow deletes the file. Verification then runs against a
/// repo with no new test, measures the score it started with, and concludes the mutant
/// resisted. On one JSON-java run that conclusion was drawn 41 times across 19 units.
///
/// It is not a cosmetic error. The mutant is marked attempted when the test is WRITTEN, so
/// selection strikes it off permanently; mutator statistics book a try with no kill, and two
/// of those demote a whole mutator kind for the rest of the run; the round counts as a miss,
/// and three misses end the unit. JSONWriter#value was abandoned at 20% on three such rounds,
/// then re-picked and taken to 40% by the first round whose test compiled.
///
/// So the question needs a third answer, and the caller has to say whether the round's test
/// was on disk at measurement time. That is a fact about the file system, not an inference —
/// hence `testsPresent` is passed in and never looked up here.
public final class RoundOutcome {

    private RoundOutcome() {}

    /// A mutant as PIT reports it: the mutation kind and the line it sits on.
    public record Mutant(String mutator, int line) {}

    public enum Kind { BROKEN, RESISTED, KILLED }

    /// @param stillAlive `null` for BROKEN — deliberately not a boolean. No caller may read
    ///                   a round that measured nothing as either outcome; in particular an
    ///                   absent mutant must never be credited as a kill.
    public record Outcome(Kind kind, Boolean stillAlive, String message,
                          boolean countMutatorTry, boolean unattempt) {}

    /// @param targetMutant  what the round aimed at, or null when it aimed at many
    /// @param survived      PIT survivors after the round
    /// @param testsPresent  was the round's generated test on disk when PIT ran?
    /// @param wroteAny      did the round write a test at all?
    /// @param otherEligible un-attempted survivors left besides this one
    /// @param brokenBefore  how many earlier rounds on THIS mutant were broken
    /// @return null when there is no verdict to give — never a fabricated one
    public static Outcome of(Mutant targetMutant, List<Mutant> survived, boolean testsPresent,
                             boolean wroteAny, int otherEligible, int brokenBefore) {
        // No line means PIT output cannot be matched against it. There is nothing to report,
        // and inventing a verdict here is how the false ones started.
        if (targetMutant == null || targetMutant.line() == 0) return null;
        // The model declines to write a test when it judges the mutation equivalent, and the
        // prompt tells it to. That is a judgement ABOUT the mutant, so the mutant stays
        // attempted — but there is still no test here, and every verdict below describes one.
        if (!wroteAny) return null;

        if (!testsPresent) {
            boolean again = brokenBefore >= 1;
            // Whether to hand the mutant back to the queue is a real trade, and the run
            // settled it. JSONWriter#value broke on NullReturnVals at 360, then 370, then
            // 380, and killed a RemoveConditional mutant on the first round that compiled:
            // at temperature 0.2 the same prompt returns the same uncompilable test, so
            // retrying THIS mutant while others are untried spends a round re-learning that.
            // Move on instead.
            //
            // Unless there is nothing to move on to. Leaving the last eligible mutant marked
            // makes the next round report "no un-attempted survivors left — stop" and abandon
            // the unit on the strength of a round that measured nothing at all. One retry is
            // cheaper than writing the unit off; a second is the loop this guards against.
            boolean last = otherEligible == 0;
            boolean unattempt = last && !again;
            String message = targetMutant.mutator() + " at line " + targetMutant.line()
                    + ": the round's test never reached the measurement "
                    + "(it broke the suite and was deleted) — the mutant was not challenged"
                    + (unattempt ? ", and as the only remaining survivor it goes back in the queue for one more try" : "")
                    + (last && again ? ", and that has now happened twice, so it stays marked as attempted" : "")
                    + (!last ? ", so the next round takes a different one" : "");
            // evidence about the generated test, not about this kind of mutation
            return new Outcome(Kind.BROKEN, null, message, false, unattempt);
        }

        boolean stillAlive = survived != null && survived.stream()
                .anyMatch(m -> m.line() == targetMutant.line() && m.mutator().equals(targetMutant.mutator()));
        String message = targetMutant.mutator() + " at line " + targetMutant.line() + ": "
                + (stillAlive ? "STILL ALIVE — the new test does not distinguish it" : "KILLED");
        return new Outcome(stillAlive ? Kind.RESISTED : Kind.KILLED, stillAlive, message, true, false);
    }

    /// The facts {@link #of} runs on, gathered rather than assumed.
    public record Evidence(boolean wroteAny, boolean testsPresent, int otherEligible) {}

    /// How many survivors the next round will actually be offered, when the caller states no
    /// cap — the same number `lastSurvived` is capped at.
    public static final int DEFAULT_CAP = 20;

    /// The facts, for a round with no survivor list to consult: nothing else to move on to.
    public static Evidence evidence(List<String> roundTestPaths, Predicate<String> exists) {
        return evidence(roundTestPaths, exists, null, null, null, null);
    }

    /// These were computed inline in the verify route, so nothing tested them — and every
    /// verdict is only as good as they are. `exists` is injected so this stays a pure
    /// function over a file-system answer instead of one that goes and looks.
    ///
    /// `otherEligible` is COMPUTED here and never handed in. It is the number {@link #of}
    /// reads to decide whether a broken round's mutant goes back in the queue, so the two
    /// have to agree on what "eligible" means — and that meaning lives in {@link Select}.
    /// Taking it as a parameter left the cap, the attempt filter and the target exclusion to
    /// whichever caller was writing the argument, which is where they were untested before.
    ///
    /// @param roundTestPaths what THIS round wrote
    /// @param exists         is that path still on disk?
    /// @param survived       PIT's survivors from the re-measurement
    /// @param attempted      attempt keys already spent on this unit
    /// @param targetKey      this round's target, excluded from the count
    /// @param cap            how many survivors the next round will be offered; null means
    ///                       {@link #DEFAULT_CAP}
    public static Evidence evidence(List<String> roundTestPaths, Predicate<String> exists,
                                    List<Select.Mutant> survived, List<String> attempted,
                                    String targetKey, Integer cap) {
        List<String> wrote = roundTestPaths == null ? List.of() : roundTestPaths;
        List<Select.Mutant> all = survived == null ? List.of() : survived;
        // capped the same way `lastSurvived` is: counting "is there anything else to move on
        // to" over the full report answers yes about mutants the queue will never show.
        // An explicit 0 is a real cap and floors at 10, exactly as the JS Math.max does — only
        // an absent cap falls back to the default.
        int offer = Math.max(10, cap == null ? DEFAULT_CAP : cap);
        List<Select.Mutant> offerable = all.subList(0, Math.min(offer, all.size()));
        long otherEligible = Select.eligible(offerable, attempted).stream()
                .filter(m -> !Objects.equals(Select.attemptKey(m), targetKey))
                .count();
        return new Evidence(
                !wrote.isEmpty(),
                // every file, not any: generation writes the test and repair rewrites it, and
                // losing either means the measurement did not see what the round produced
                wrote.stream().allMatch(exists),
                (int) otherEligible);
    }
}
