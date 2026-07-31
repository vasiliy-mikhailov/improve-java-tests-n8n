package tech.mikhailov.ijt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The model was never told what its last attempt did wrong.
///
/// `lastRoundBlock` is careful, specific prompt engineering. It distinguishes three outcomes that
/// need three different fixes, and for a test that would not compile it goes as far as quoting
/// the build error and saying "do not refine the assertion; the file never ran".
///
/// It was rendered by exactly one prompt — `mutationPrompt`, the RETIRED one-mutant prompt that
/// nothing calls. The two the pipeline actually runs, `coveragePrompt` and `batchPrompt`, never
/// included it. Every one of the five tests covering the block called `mutationPrompt`, so all
/// five passed while the live prompts said nothing.
///
/// That is the sixth component in this project built, tested, and connected to nothing — after
/// the H2 store, eligibleUnit, Collaborators, the restore's dropped fields, and partial salvage.
///
/// It matters most right now. With salvage handling the assertion failures, compile errors are
/// 62% of the suite failures still being recorded, and this block is the only thing that would
/// tell a model its previous test did not compile — instead of letting it spend the next round
/// refining an assertion in a file that never ran.
///
/// Two things had to be true before rendering it was safe, and neither was:
///
///  1. `lastRoundBroken` was written only inside `if (outcome != null)`, and `RoundOutcome.of`
///     returns null whenever there is no single target mutant — which is every batch round, and
///     batch is what the pipeline runs. So in batch mode the flag was never set and the block
///     would have fallen through to "the path is right; the assertion is not".
///
///  2. `roundTestPaths` was reset only in the `targetMutant != null` branch of writeTests, which
///     a batch round never enters. The list accumulated across the whole attempt, so
///     `testsPresent` — the fact brokenness is derived from — was answering about a mixture of
///     this round's files and earlier ones.
class LastRoundFeedbackTest {

    private static final Prompts.Gaps GAPS = Prompts.Gaps.builder()
            .path("src/main/java/a/B.java").fqcn("a.B").method("calc").pkg("a").module(".")
            .jdk(17).testFramework("junit5").coverage(90.0).covPhaseMaxPct(0.0)
            .source("package a;\nclass B { int calc(int x){ return x + 1; } }")
            .methodSource("  int calc(int x){\n    return x + 1;\n  }")
            .classHeader("package a;\nclass B {")
            .siblingSignatures(java.util.List.of())
            .uncovered(Prompts.Uncovered.of(java.util.List.of(4)))
            .covTestPath("src/test/java/a/BMacCovTest.java")
            .mutTestPath("src/test/java/a/BMacMutTest.java")
            .projectTestPath(null).existingTest(null).constraints(java.util.List.of()).mutantsPerRound(1)
            .survived(java.util.List.of(new Prompts.Mutant("SURVIVED", "MathMutator", 3, "calc", null)))
            .build();

    private static final java.util.List<Prompts.Target> TARGETS = Prompts.groupTargets(java.util.List.of(
            new Prompts.Mutant("SURVIVED", "ConditionalsBoundaryMutator", 170, "calc", "changed boundary")));

    private static Prompts.Gaps gapsWith(Prompts.LastRound lastRound) {
        return GAPS.toBuilder().lastRound(lastRound).build();
    }

    /// `LastRound(broken, error, reached, coverage)`.
    private static Prompts.LastRound broke(String error) {
        return new Prompts.LastRound(true, error, null, null);
    }

    // ── the live prompts must say it ──────────────────────────────────────

    @Test
    void theBatchPromptTellsTheModelItsLastTestDidNotCompile() {
        // batchPrompt is what Phase.MUTATION builds — the prompt the pipeline actually runs
        String prompt = Prompts.batchPrompt(
                gapsWith(broke("cannot find symbol: method setKeepStrings(boolean)")), TARGETS).prompt();

        assertTrue(prompt.contains("DID NOT COMPILE"),
                "the model has to know the file never ran");
        assertTrue(prompt.contains("setKeepStrings"),
                "and the build's own words are the most useful thing we have");
    }

    @Test
    void theCoveragePromptSaysItToo() {
        // Phase.COVERAGE runs first and writes into the same file, so telling only one of the two
        // leaves half the rounds uninformed.
        //
        // The fixture is an UNCOVERED method on purpose: coveragePrompt skips a method that is
        // already executed ("mutation tests will extend coverage as they kill NO_COVERAGE
        // mutants"), and a skipped Ask has a null prompt. That is correct behaviour and is why
        // batchPrompt is the one that matters for the hard tail, where units are well covered
        // and the failures are compile errors in the mutation phase.
        Prompts.Gaps uncovered = GAPS.toBuilder()
                .coverage(0.0)
                .uncovered(Prompts.Uncovered.entireMethod())
                .lastRound(broke("cannot find symbol"))
                .build();
        assertTrue(Prompts.coveragePrompt(uncovered).prompt().contains("DID NOT COMPILE"));
    }

    @Test
    void aFirstRoundIsToldNothing() {
        // the negative half: with no previous round there is nothing to feed back, and inventing
        // a failure would be worse than silence
        String prompt = Prompts.batchPrompt(gapsWith(null), TARGETS).prompt();
        assertFalse(prompt.contains("YOUR PREVIOUS ATTEMPT"));
    }

    // ── and the flag it reads has to be set in batch mode ─────────────────

    @Test
    void aRoundThatWroteATestThatVanishedIsBroken() {
        // wrote something, and it was not there when PIT ran: it broke the suite and was deleted
        assertTrue(RoundOutcome.broken(new RoundOutcome.Evidence(true, false, 3)));
    }

    @Test
    void aRoundWhoseTestSURVIVEDToTheMeasurementIsNotBroken() {
        assertFalse(RoundOutcome.broken(new RoundOutcome.Evidence(true, true, 3)));
    }

    @Test
    void aRoundThatWroteNothingIsNotBroken() {
        // nothing to compile, so nothing failed to. Every survivor already had a named test —
        // that is a settled unit, not a broken build, and telling the model its test did not
        // compile when it never wrote one is a lie it would act on.
        assertFalse(RoundOutcome.broken(new RoundOutcome.Evidence(false, false, 0)));
    }

    @Test
    void brokennessNeedsNoTargetMutant() {
        // THE reason this is read off Evidence and not off an Outcome. RoundOutcome.of returns
        // null without a target mutant, and a batch round never has one — so anything derived
        // from the Outcome is silently absent in exactly the mode the pipeline runs.
        assertTrue(RoundOutcome.broken(new RoundOutcome.Evidence(true, false, 0)),
                "a batch round has no single target and must still be able to report a broken build");
    }
}
