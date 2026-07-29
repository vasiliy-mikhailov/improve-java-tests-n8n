package tech.mikhailov.ijt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// The model is free-form and the file system is not.
///
/// Two production defects are pinned here. A path check that knew about exactly one existing
/// test let the model name any OTHER test in the repo and have it overwritten — then deleted
/// when the suite went red. And reading `offered[0]` out of a batch round, which offers no
/// mutator at all, produced a target of `{line: 78, mutator: undefined}` that the kill check
/// could never match, so no batch round could ever report a kill.
class ParseTest {

    private static final Parse.Plan PLAN =
            new Parse.Plan("src/test/java/a/BMacMutTest.java", "src/test/java/a/BTest.java", null);

    private static final String BODY =
            "package a;\n\npublic class BMacMutTest { @Test public void t() { assertEquals(2, x); } }";

    /// a well-formed answer: the planned path, and a body long enough to be a test
    private static Parse.Response ok(String content) {
        return answer(new Parse.TestFile(PLAN.targetPath(), content));
    }

    private static Parse.Response answer(Parse.TestFile... tests) {
        return new Parse.Response(true, new Parse.Answer(List.of(tests)));
    }

    /// what the generation phase handed on: the paths it wrote, nothing else read
    private static Parse.Parsed previously(String... paths) {
        return new Parse.Parsed(List.of(), List.of(paths), paths.length, null);
    }

    @Test
    void aWellFormedAnswerIsAcceptedAsIs() {
        Parse.Parsed r = Parse.parseGeneratedTests(ok(BODY), PLAN);
        assertEquals(1, r.count());
        assertEquals(PLAN.targetPath(), r.tests().get(0).path());
    }

    @Test
    void aPathOutsideSrcTestJavaIsReplacedWithThePlannedOne() {
        Parse.Parsed r = Parse.parseGeneratedTests(answer(new Parse.TestFile("../../etc/passwd", BODY)), PLAN);
        assertEquals(PLAN.targetPath(), r.tests().get(0).path(), "never write outside the test tree");
    }

    @Test
    void theProjectsOwnTestFileIsNeverOverwritten() {
        Parse.Parsed r = Parse.parseGeneratedTests(
                answer(new Parse.TestFile(PLAN.projectTestPath(), BODY)), PLAN);
        assertEquals(PLAN.targetPath(), r.tests().get(0).path());
    }

    @Test
    void thePublicClassIsRenamedToMatchTheFileOrJavacRefusesToCompile() {
        String wrong = "package a;\n\npublic class SomethingElse { @Test public void t() { assertEquals(2, x); } }";
        Parse.Parsed r = Parse.parseGeneratedTests(answer(new Parse.TestFile(PLAN.targetPath(), wrong)), PLAN);
        assertTrue(r.tests().get(0).content().contains("public class BMacMutTest"));
        assertFalse(r.tests().get(0).content().contains("SomethingElse"));
    }

    @Test
    void onlyOneTestFileIsTakenHoweverManyAreOffered() {
        Parse.Parsed r = Parse.parseGeneratedTests(answer(
                new Parse.TestFile(PLAN.targetPath(), BODY),
                new Parse.TestFile("src/test/java/a/Other.java", BODY)), PLAN);
        assertEquals(1, r.count());
    }

    @Test
    void anEmptyAnswerIsALegitimateThisMutationChangesNothing() {
        Parse.Parsed r = Parse.parseGeneratedTests(answer(), PLAN);
        assertEquals(0, r.count());
        assertEquals(List.of(), r.tests());
    }

    @Test
    void aStubTooShortToBeATestIsRejected() {
        Parse.Parsed r = Parse.parseGeneratedTests(
                answer(new Parse.TestFile(PLAN.targetPath(), "class X{}")), PLAN);
        assertEquals(0, r.count());
    }

    @Test
    void aFailedOrUnparseableModelCallYieldsNothingNotACrash() {
        assertEquals(0, Parse.parseGeneratedTests(new Parse.Response(false, null), PLAN).count());
        assertEquals(0, Parse.parseGeneratedTests(new Parse.Response(true, null), PLAN).count());
        assertEquals(0, Parse.parseGeneratedTests(null, PLAN).count());
        // the JS guarded this last shape with Array.isArray; in Java it is a null list
        assertEquals(0, Parse.parseGeneratedTests(new Parse.Response(true, new Parse.Answer(null)), PLAN).count());
    }

    @Test
    void theChosenMutantIsCarriedThroughForTheKillCheck() {
        Parse.Plan withOffer = new Parse.Plan(PLAN.targetPath(), PLAN.projectTestPath(),
                List.of(Parse.Offer.mutant(3, "MathMutator", null)));
        Parse.Parsed r = Parse.parseGeneratedTests(ok(BODY), withOffer);
        assertEquals(new RoundOutcome.Mutant("MathMutator", 3), r.chosen());
    }

    @Test
    void repairedTestsKeepTheOriginalPaths() {
        Parse.Repaired r = Parse.parseRepairedTests(
                answer(new Parse.TestFile("wherever/It.java", BODY)), previously(PLAN.targetPath()));
        assertEquals(PLAN.targetPath(), r.tests().get(0).path());
    }

    @Test
    void noAnswerMayNameATestFileOtherThanTheOneThisRoundPlansToWrite() {
        // safePath only guarded plan.projectTestPath — the ONE existing test it knew about. Any
        // other real test in the tree ("src/test/java/org/json/StringBuilderWriterTest.java")
        // passed the check and was overwritten with generated content; if the suite then went
        // red, Delete Broken Tests removed the team's file from the repo entirely.
        String victim = "src/test/java/org/json/StringBuilderWriterTest.java";
        Parse.Parsed r = Parse.parseGeneratedTests(answer(new Parse.TestFile(victim, BODY)), PLAN);
        assertEquals(PLAN.targetPath(), r.tests().get(0).path());
        assertNotEquals(victim, r.tests().get(0).path());
    }

    @Test
    void thePlannedPathIsTheOnlyPathHoweverPlausibleTheAlternativeLooks() {
        for (String p : List.of("src/test/java/a/BMacMutR2Test.java", "src/test/java/a/Helper.java",
                PLAN.targetPath())) {
            Parse.Parsed r = Parse.parseGeneratedTests(answer(new Parse.TestFile(p, BODY)), PLAN);
            assertEquals(PLAN.targetPath(), r.tests().get(0).path());
        }
    }

    // ── a batch round aims at many lines, so it aims at no single mutant ──────
    // batchPrompt offers `{marker, line, method}`; the single-mutant prompt offers
    // `{line, mutator, status}`. parse read offered[0] and built `chosen` from it either way,
    // so a batch round produced a chosen with no mutator — which the workflow printed verbatim
    // ("targeting undefined at line 78") and, worse, stored as targetMutant, where the kill
    // check matches on mutator AND line and therefore could never match anything.
    //
    // A batch has no single target. Saying so is the honest answer; RoundOutcome already
    // returns null for a round with no target mutant.
    @Test
    void aSingleMutantRoundStillReportsTheMutantItAimedAt() {
        Parse.Parsed r = Parse.parseGeneratedTests(
                answer(new Parse.TestFile("src/test/java/a/BMacMutTest.java", "x".repeat(40))),
                new Parse.Plan("src/test/java/a/BMacMutTest.java", null,
                        List.of(Parse.Offer.mutant(42, "MathMutator", "SURVIVED"))));
        assertEquals(new RoundOutcome.Mutant("MathMutator", 42), r.chosen());
    }

    @Test
    void aBatchRoundReportsNoSingleTargetRatherThanAnUndefinedOne() {
        Parse.Parsed r = Parse.parseGeneratedTests(
                answer(new Parse.TestFile("src/test/java/a/BMacMutTest.java", "x".repeat(40))),
                new Parse.Plan("src/test/java/a/BMacMutTest.java", null, List.of(
                        Parse.Offer.batch("covers m:78", 78, "m"),
                        Parse.Offer.batch("covers m:90", 90, "m"))));
        assertNull(r.chosen(), "no mutator field means this was never a single-mutant round");
    }

    @Test
    void anOfferWithNoMutatorIsNeverDressedUpAsOne() {
        Parse.Parsed r = Parse.parseGeneratedTests(
                answer(new Parse.TestFile("p", "x".repeat(40))),
                new Parse.Plan("p", null, List.of(new Parse.Offer(null, 5, null, null, null))));
        assertNull(r.chosen());
    }

    @Test
    void noOffersAtAllMeansNoTarget() {
        Parse.Parsed r = Parse.parseGeneratedTests(answer(), new Parse.Plan("p"));
        assertNull(r.chosen());
    }
}
