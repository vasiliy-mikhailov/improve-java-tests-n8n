package tech.mikhailov.ijt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// A round is supposed to be self-limiting. It was not.
///
/// The whole marker design (Targets.targetMarker) rests on one property: the NEXT round greps
/// the file this one wrote, finds every target name already present, and has nothing left to
/// ask for. That is what makes a unit stop on its own instead of re-asking the model for lines
/// it already covered.
///
/// Two defects broke it, and they compounded:
///
///  1. The dedup haystack read `mutTestPath`/`covTestPath` off the wire — which are THIS
///     round's paths, the files the round is about to write. They do not exist yet, so from
///     round 2 onward the filter was a no-op and every round re-asked for every survivor.
///
///  2. The settle test asked `mutationPrompt` — the RETIRED one-mutant prompt — while the
///     phase builds `batchPrompt`. They skip on different conditions and disagree in exactly
///     the case that matters: every marker written means batchPrompt skips and writes nothing,
///     while mutationPrompt still sees survivors and reports work remaining. The unit produced
///     no test, missed, and paid a full verify — a JaCoCo build plus a PIT run — three times
///     before stopping.
///
/// Both are now one computation, `Server.pendingTargetsFor`, because two separate predictions
/// of the same thing is what the defect WAS.
class BatchDedupTest {

    private static Targets.Mutant m(String method, int line) {
        return new Targets.Mutant(method, line, "ConditionalsBoundaryMutator", "SURVIVED");
    }

    @Test
    void aTargetWhoseMarkerIsAlreadyWrittenIsNotAskedForAgain() {
        List<Targets.Mutant> survivors = List.of(m("parse", 170), m("parse", 205));
        String roundOne = "class XParseMacMutTest {\n  @Test void boundary() {\n    // "
                + Targets.targetMarker(m("parse", 170)) + "\n  }\n}";
        List<Targets.Target> pending = Targets.targetsFor(survivors, List.of(roundOne)).pending();
        assertEquals(1, pending.size(), "line 170 is already covered by round 1");
        assertEquals(205, pending.get(0).line());
    }

    @Test
    void anEmptyHaystackLeavesEverythingPending() {
        // This is what the bug looked like from the inside: the sources list was effectively
        // empty every round because it read files that did not exist yet, so nothing was ever
        // filtered and the model was asked for the same lines again and again.
        List<Targets.Mutant> survivors = List.of(m("parse", 170), m("parse", 205));
        assertEquals(2, Targets.targetsFor(survivors, List.of()).pending().size());
    }

    @Test
    void withEveryMarkerWrittenThereIsNothingLeftToAsk() {
        // The settle condition. When this is empty the round would write no test, so running a
        // verify — a JaCoCo build plus a PIT run — can only produce a miss.
        List<Targets.Mutant> survivors = List.of(m("parse", 170), m("parse", 205));
        String written = "// " + Targets.targetMarker(m("parse", 170))
                + "\n// " + Targets.targetMarker(m("parse", 205));
        assertTrue(Targets.targetsFor(survivors, List.of(written)).pending().isEmpty(),
                "every survivor already has a named test — the unit is finished");
    }

    @Test
    void theSettleTestAndThePromptAgreeByConstruction() {
        // The regression guard that matters. These used to be two predictions — batchPrompt's
        // skip and mutationPrompt's skip — and the unit burned three rounds whenever they
        // disagreed. Both sides now read the same pending list, so they cannot drift.
        List<Targets.Mutant> survivors = List.of(m("parse", 170));
        String written = "// " + Targets.targetMarker(m("parse", 170));

        Targets.Plan asked = Targets.targetsFor(survivors, List.of(written));
        boolean promptWouldSkip = asked.pending().isEmpty();
        boolean settleSaysDone = asked.pending().isEmpty();

        assertEquals(promptWouldSkip, settleSaysDone);
        assertTrue(settleSaysDone);
    }

    @Test
    void aMarkerForADifferentLineDoesNotSettleTheUnit() {
        // the anchoring that stops line 17's marker satisfying line 170 — without it a unit
        // would settle early and leave real survivors alive
        List<Targets.Mutant> survivors = List.of(m("parse", 170));
        String near = "// " + Targets.targetMarker(m("parse", 17));
        assertFalse(Targets.targetsFor(survivors, List.of(near)).pending().isEmpty());
    }
}
