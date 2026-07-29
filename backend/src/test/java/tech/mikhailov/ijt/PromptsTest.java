package tech.mikhailov.ijt;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// TDD for the logic that used to live as JavaScript strings inside the workflow JSON.
/// Every case here is a defect that actually shipped, because a string in a JSON file has
/// no compiler, no linter and no tests: a `str.replace` that silently matched nothing left
/// the deployed prompt still asking the model to choose a mutant days after that decision
/// had moved into the pipeline.
class PromptsTest {

    private static final Prompts.Gaps GAPS = Prompts.Gaps.builder()
            .path("src/main/java/a/B.java").fqcn("a.B").method("calc").pkg("a").module(".")
            .jdk(17).testFramework("junit5").coverage(90.0).covPhaseMaxPct(0.0)
            .source("package a;\nclass B { int calc(int x){ return x + 1; } int other(){ return 2; } }")
            .methodSource("  int calc(int x){\n    return x + 1;\n  }")
            .classHeader("package a;\nclass B {")
            .siblingSignatures(List.of("int other();"))
            .uncovered(Prompts.Uncovered.of(List.of(4)))
            .covTestPath("src/test/java/a/BMacCovTest.java")
            .mutTestPath("src/test/java/a/BMacMutTest.java")
            .projectTestPath(null).existingTest(null).constraints(List.of()).mutantsPerRound(1)
            .survived(List.of(new Prompts.Mutant("SURVIVED", "MathMutator", 3, "calc",
                    "Replaced integer addition with subtraction")))
            .build();

    // ── coverage phase ──────────────────────────────────────────────────────────

    @Test
    void theCoveragePhaseIsSkippedForAMethodThatAlreadyExecutes() {
        Prompts.Ask r = Prompts.coveragePrompt(GAPS);
        assertEquals(Boolean.TRUE, r.skip());
        assertMatches("already executed", r.reason());
    }

    @Test
    void theCoveragePhaseRunsForAMethodNothingExecutes() {
        Prompts.Ask r = Prompts.coveragePrompt(
                GAPS.toBuilder().coverage(0.0).uncovered(Prompts.Uncovered.entireMethod()).build());
        assertNull(r.skip());
        assertMatches("FIRST test", r.system());
        assertEquals(GAPS.covTestPath(), r.targetPath());
        assertTrue(r.maxTokens() <= 3000, "the first test is meant to be small");
    }

    @Test
    void aFullyCoveredMethodNeedsNoCoverageWork() {
        Prompts.Ask r = Prompts.coveragePrompt(
                GAPS.toBuilder().coverage(0.0).uncovered(Prompts.Uncovered.of(List.of())).build());
        assertEquals(Boolean.TRUE, r.skip());
        assertMatches("fully covered", r.reason());
    }

    // ── mutation phase ──────────────────────────────────────────────────────────

    @Test
    void theMutationPromptNamesTheMutantToKillAndDoesNotAskTheModelToChoose() {
        Prompts.Ask r = Prompts.mutationPrompt(GAPS);
        assertMatches("MUTANT TO KILL", r.prompt());
        assertMatches("MathMutator at line 3", r.prompt());
        assertDoesNotMatch("(?i)CHOOSE", r.prompt(), "selection is the pipeline's job, not the model's");
        assertDoesNotMatch("\"mutant\"", r.system(), "no mutant-number field to invent");
    }

    @Test
    void thePromptCarriesTheTargetMethodNotTheWholeClass() {
        Prompts.Ask r = Prompts.mutationPrompt(GAPS);
        assertMatches("int calc\\(int x\\)", r.prompt());
        assertDoesNotMatch("int other\\(\\)\\{ return 2; \\}", r.prompt(), "other method bodies are noise");
        assertMatches("int other\\(\\);", r.prompt(), "but their signatures help build inputs");
    }

    @Test
    void exactlyOneMutantIsOfferedAndItIsTheFirstBestRankedSurvivor() {
        List<Prompts.Mutant> many = new ArrayList<>(GAPS.survived());
        many.add(new Prompts.Mutant("SURVIVED", "VoidMethodCallMutator", 9, null, null));
        Prompts.Ask r = Prompts.mutationPrompt(GAPS.toBuilder().survived(many).build());
        assertEquals(1, r.offered().size());
        assertEquals("MathMutator", r.offered().get(0).mutator());
        assertDoesNotMatch("VoidMethodCall", r.prompt());
    }

    @Test
    void noSurvivorsLeftEndsTheUnitAndSaysWhetherAnyWereAttempted() {
        assertMatches("no surviving mutants",
                Prompts.mutationPrompt(GAPS.toBuilder().survived(List.of()).build()).reason());
        assertMatches("already been attempted",
                Prompts.mutationPrompt(GAPS.toBuilder().survived(List.of()).attemptedMutants(7).build()).reason());
    }

    @Test
    void theModelMayDeclineWhenAMutationChangesNothingObservable() {
        assertMatches("empty tests array", Prompts.mutationPrompt(GAPS).prompt());
    }

    // ── repair ──────────────────────────────────────────────────────────────────

    @Test
    void theRepairPromptShowsTheFailureAndTheMethodAndPinsThePath() {
        Prompts.Ask r = Prompts.repairPrompt(GAPS, new Prompts.Failure("expected:<2> but was:<3>"),
                List.of(new Prompts.TestFile(GAPS.mutTestPath(), "class BMacMutTest {}")));
        assertMatches("expected:<2> but was:<3>", r.prompt());
        assertMatches("int calc\\(int x\\)", r.prompt());
        assertMatches("SAME file path", r.system());
    }

    // ── reaching a method a test cannot call directly ──────────────────────────
    private static final Prompts.Gaps PRIVATE_GAPS = GAPS.toBuilder()
            .method("isRecordStyleAccessor")
            .visibility("private")
            .routes(List.of(List.of(
                    new JavaSrc.MethodRef("JSONObject", "public", null),
                    new JavaSrc.MethodRef("populateMap", "private", null),
                    new JavaSrc.MethodRef("isRecordStyleAccessor", "private", null))))
            .survived(List.of(new Prompts.Mutant("NO_COVERAGE", "BooleanTrueReturnValsMutator", 2072,
                    "isRecordStyleAccessor", null)))
            .uncovered(Prompts.Uncovered.entireMethod())
            .build();

    @Test
    void aPrivateTargetIsNamedAsPrivateWithTheRoutesThatReachIt() {
        // The model wrote a compiling, passing test for this private method that executed none
        // of it: cov 0 → 0, all 14 mutants alive, one round gone. It was never told it could
        // not call the method.
        Prompts.Ask r = Prompts.mutationPrompt(PRIVATE_GAPS);
        assertMatches("(?i)private", r.prompt());
        assertMatches("JSONObject", r.prompt(), "the public route must be named");
        assertMatches("populateMap", r.prompt());
    }

    @Test
    void theCoveragePhaseGetsTheSameRoutes() {
        Prompts.Ask r = Prompts.coveragePrompt(PRIVATE_GAPS);
        assertNull(r.skip());
        assertMatches("(?i)private", r.prompt());
        assertMatches("JSONObject", r.prompt());
    }

    @Test
    void reflectionStaysForbiddenTheRouteIsPublicApiNotABackDoor() {
        Prompts.Ask r = Prompts.mutationPrompt(PRIVATE_GAPS);
        assertMatches("(?i)never use reflection", r.system());
        assertDoesNotMatch("(?i)setAccessible", r.prompt());
    }

    @Test
    void aPublicTargetGetsNoRoutingSectionToWadeThrough() {
        Prompts.Ask r = Prompts.mutationPrompt(
                PRIVATE_GAPS.toBuilder().visibility("public").routes(List.of()).build());
        assertDoesNotMatch("(?i)REACHED VIA|cannot be called directly", r.prompt());
    }

    @Test
    void aPrivateMethodNothingCallsIsReportedAsUnreachableNotAttempted() {
        // dead private code: no test can execute it, so the round can only waste a call
        Prompts.Ask r = Prompts.mutationPrompt(PRIVATE_GAPS.toBuilder().routes(List.of()).build());
        assertEquals(Boolean.TRUE, r.skip());
        assertMatches("(?i)no public method|unreachable", r.reason());
    }

    // ── repair must not abandon the mutant ────────────────────────────────────
    private static final List<Prompts.TestFile> FAILING =
            List.of(new Prompts.TestFile("src/test/java/a/BMacMutTest.java", "assertFalse(jo.has(\"x\"));"));

    @Test
    void aRepairIsToldWhichMutantTheTestStillHasToDistinguish() {
        // The round targeted BooleanTrueReturnVals at line 2072. The test asserted the mutant's
        // absence, failed against real behaviour, and the repair simply flipped assertFalse to
        // assertTrue — a passing test that no longer distinguishes anything. The round missed.
        Prompts.Ask r = Prompts.repairPrompt(
                GAPS.toBuilder().targetMutant(new RoundOutcome.Mutant("BooleanTrueReturnValsMutator", 2072)).build(),
                new Prompts.Failure("expected:<false> but was:<true>"), FAILING);
        assertMatches("BooleanTrueReturnValsMutator", r.prompt());
        assertMatches("2072", r.prompt());
    }

    @Test
    void flippingAnAssertionToMatchTheMutationIsCalledOutAsWorthless() {
        Prompts.Ask r = Prompts.repairPrompt(
                GAPS.toBuilder().targetMutant(new RoundOutcome.Mutant("BooleanTrueReturnValsMutator", 2072)).build(),
                new Prompts.Failure("boom"), FAILING);
        assertMatches("(?i)still fail on the mutated code|must still distinguish", r.system() + r.prompt());
        assertMatches("(?i)drop the test|return an empty", r.system() + r.prompt(),
                "if it cannot be both correct and distinguishing, dropping it is the honest answer");
    }

    @Test
    void correctingAGenuinelyWrongExpectedValueIsStillAllowed() {
        Prompts.Ask r = Prompts.repairPrompt(
                GAPS.toBuilder().targetMutant(new RoundOutcome.Mutant("MathMutator", 3)).build(),
                new Prompts.Failure("boom"), FAILING);
        assertMatches("(?i)correct the EXPECTED values", r.system());
    }

    @Test
    void aCoveragePhaseRepairHasNoMutantToProtectAndSaysNothingAboutOne() {
        Prompts.Ask r = Prompts.repairPrompt(GAPS, new Prompts.Failure("boom"), FAILING, "improving_coverage");
        assertDoesNotMatch("MUTANT THIS TEST MUST STILL KILL", r.prompt());
    }

    // ── telling the model its last test never reached the method ──────────────
    @Test
    void aTestThatNeverExecutedTheMethodIsFedBackAsExactlyThat() {
        // JSONObject#isRecordStyleAccessor is called under `if (isRecordType && ...)`. The model
        // built a plain bean, the guard was false, the method never ran — and the pipeline
        // reported "the new test does not distinguish it", which is a different problem with a
        // different fix. Round after round it rewrote assertions for code it never reached.
        Prompts.Ask r = Prompts.mutationPrompt(GAPS.toBuilder()
                .lastRound(new Prompts.LastRound(null, null, false, 0.0)).build());
        assertMatches("(?i)never executed|did not reach", r.prompt());
        assertMatches("(?i)guard|condition|path", r.prompt());
    }

    @Test
    void aTestThatReachedTheMethodButMissedIsToldTheOtherThing() {
        Prompts.Ask r = Prompts.mutationPrompt(GAPS.toBuilder()
                .lastRound(new Prompts.LastRound(null, null, true, null)).build());
        assertMatches("(?i)reached|executed", r.prompt());
        assertDoesNotMatch("(?i)never executed", r.prompt());
    }

    @Test
    void theFirstRoundOnAUnitHasNothingToFeedBack() {
        Prompts.Ask r = Prompts.mutationPrompt(GAPS);
        assertDoesNotMatch("YOUR PREVIOUS ATTEMPT", r.prompt());
    }

    // ── and the third case: the last test never compiled ──────────────────────
    @Test
    void aTestThatBrokeTheBuildIsToldThatNotThatItsAssertionWasWrong() {
        // Three outcomes need three different fixes, and the block had two branches. A test
        // deleted for breaking the suite came back to the model as "the path is right; the
        // assertion is not" — advice about an assertion in a file that never compiled. On the
        // live run CDL#getValue and JSONObject#parseEndOfKeyValuePair each burned their whole
        // miss budget this way.
        Prompts.Ask r = Prompts.mutationPrompt(GAPS.toBuilder()
                .lastRound(new Prompts.LastRound(true, "cannot find symbol: method nextEntity()", true, 100.0))
                .build());
        assertMatches("(?i)did not compile|broke the build|broke the suite", r.prompt());
        assertDoesNotMatch("The path is right; the assertion is not", r.prompt());
    }

    @Test
    void andItIsShownTheActualFailureNotAskedToGuess() {
        Prompts.Ask r = Prompts.mutationPrompt(GAPS.toBuilder()
                .lastRound(new Prompts.LastRound(true, "cannot find symbol: method nextEntity()", true, 100.0))
                .build());
        assertMatches("cannot find symbol: method nextEntity\\(\\)", r.prompt());
    }

    @Test
    void aBrokenRoundWithNoCapturedErrorStillSaysTheTestDidNotSurvive() {
        Prompts.Ask r = Prompts.mutationPrompt(GAPS.toBuilder()
                .lastRound(new Prompts.LastRound(true, null, true, 100.0)).build());
        assertMatches("(?i)did not compile|broke the build|broke the suite", r.prompt());
    }

    // ── the coverage phase must run when nothing executes the survivors ────────
    // DataLoaderFactory#newPublisherDataLoaderWithTry and its mapped sibling each measured 6
    // mutants, ALL of them NO_COVERAGE, at 16.67% line coverage. The coverage phase skipped
    // both — covPhaseMaxPct is 0, so it only fires at exactly 0% — on the stated grounds that
    // "mutation tests will extend coverage as they kill NO_COVERAGE mutants".
    //
    // That is plausible and it was measured false: seven rounds per unit, coverage never
    // moved off 16.67, mutation never left 0, ~15 minutes each. Killing a NO_COVERAGE mutant
    // does require executing its line, which is precisely why asking for a distinguishing
    // assertion is the wrong instruction — the test has to REACH the code first. Same
    // distinction the round feedback already makes between "never executed it" and "executed
    // it but did not distinguish the mutation".
    private static final List<Prompts.Mutant> ALL_NO_COVERAGE = List.of(
            new Prompts.Mutant("NO_COVERAGE", "RemoveConditionalMutator_EQUAL_ELSE", 120, null, null),
            new Prompts.Mutant("NO_COVERAGE", "NullReturnValsMutator", 121, null, null));

    @Test
    void aPartlyCoveredMethodWhoseEverySurvivorIsNoCoverageStillGetsACoverageTest() {
        Prompts.Ask r = Prompts.coveragePrompt(GAPS.toBuilder()
                .coverage(16.67).covPhaseMaxPct(0.0)
                .uncovered(Prompts.Uncovered.of(List.of(120, 121))).survived(ALL_NO_COVERAGE)
                .build());
        assertNull(r.skip(), "skipped with: " + r.reason());
    }

    @Test
    void butAMethodWhoseSurvivorsActuallyRunIsLeftToTheMutationPhase() {
        // these lines execute; nothing asserts on them. That is the mutation phase's job.
        Prompts.Ask r = Prompts.coveragePrompt(GAPS.toBuilder()
                .coverage(16.67).covPhaseMaxPct(0.0)
                .uncovered(Prompts.Uncovered.of(List.of(120)))
                .survived(List.of(new Prompts.Mutant("SURVIVED", "MathMutator", 120, null, null)))
                .build());
        assertEquals(Boolean.TRUE, r.skip());
        assertMatches("already executed", r.reason());
    }

    @Test
    void aMixedSurvivorListIsLeftToTheMutationPhaseToo() {
        // one reachable survivor means a mutation test has something to bite on
        List<Prompts.Mutant> mixed = new ArrayList<>(ALL_NO_COVERAGE);
        mixed.add(new Prompts.Mutant("SURVIVED", "MathMutator", 122, null, null));
        Prompts.Ask r = Prompts.coveragePrompt(GAPS.toBuilder()
                .coverage(16.67).covPhaseMaxPct(0.0)
                .uncovered(Prompts.Uncovered.of(List.of(120, 121))).survived(mixed)
                .build());
        assertEquals(Boolean.TRUE, r.skip());
    }

    @Test
    void aFullyCoveredMethodIsStillSkippedWhateverItsSurvivorsSay() {
        Prompts.Ask r = Prompts.coveragePrompt(GAPS.toBuilder()
                .coverage(100.0).covPhaseMaxPct(0.0)
                .uncovered(Prompts.Uncovered.of(List.of())).survived(ALL_NO_COVERAGE)
                .build());
        assertEquals(Boolean.TRUE, r.skip());
        assertMatches("fully covered", r.reason());
    }

    // ── collaborators the test has to build ───────────────────────────────────
    // DelegatingStatisticsCollector's tests failed to compile four rounds running with
    //   error: <anonymous ...MacMutTest$1> is not abstract and does not override abstract
    //          method incrementBatchLoadCountBy(long) in StatisticsCollector
    // The model hand-rolled an anonymous StatisticsCollector and implemented some of its
    // methods. The prompt shows the class under test but not the full contract of the
    // interfaces its test must instantiate, and java-dataloader ships concrete
    // implementations that would have done the job. Two of eight recorded failures on this
    // repo are this exact shape.
    @Test
    void theRulesSayHowToBuildACollaboratorWithoutInventingHalfAnInterface() {
        Prompts.Ask r = Prompts.mutationPrompt(GAPS);
        assertMatches("(?i)every abstract method|all of its abstract methods", r.system());
        assertMatches("(?i)existing|concrete", r.system(),
                "preferring an implementation the project already has is cheaper than writing one");
    }

    @Test
    void theCoveragePromptCarriesTheSameRule() {
        Prompts.Ask r = Prompts.coveragePrompt(
                GAPS.toBuilder().coverage(0.0).uncovered(Prompts.Uncovered.entireMethod()).build());
        assertMatches("(?i)every abstract method|all of its abstract methods", r.system());
    }

    // ── batch prompt: one call for many targets, one measurement after ─────────
    // The per-round loop pays two PIT runs per round per method-unit — 609 across 22 classes
    // on JSON-java, 43% of wall-clock. This asks once for every surviving line of a CLASS and
    // measures once. Each test carries the deterministic name from Targets so the next run
    // can skip it without a model call.
    private static final List<Prompts.Target> TARGETS = Prompts.groupTargets(List.of(
            new Prompts.Mutant("SURVIVED", "ConditionalsBoundaryMutator", 170, "mustEscape", "changed boundary"),
            new Prompts.Mutant("SURVIVED", "RemoveConditionalMutator_EQUAL_ELSE", 170, "mustEscape", null),
            new Prompts.Mutant("NO_COVERAGE", "NullReturnValsMutator", 205, "unescape", null)));

    private static final Prompts.Gaps BGAPS =
            GAPS.toBuilder().mutTestPath("src/test/java/a/BMacBatchTest.java").build();

    @Test
    void theBatchPromptCarriesEveryTargetMarkerAndDefersNamingToTheRepo() {
        Prompts.Ask r = Prompts.batchPrompt(BGAPS, TARGETS);
        assertNull(r.skip());
        for (Prompts.Target t : TARGETS) assertMatches(Pattern.quote(t.marker()), r.prompt());
        assertMatches("(?i)named the way the project|project's own tests are named", r.system(),
                "kill_method_line170 is not how these repos name tests, and reads as machine output");
        assertMatches("(?i)marker comment", r.system(), "the marker is the contract, not the name");
    }

    @Test
    void everyMutationOnALineIsShownSoOneTestCanCoverThemAll() {
        Prompts.Ask r = Prompts.batchPrompt(BGAPS, TARGETS);
        assertMatches("ConditionalsBoundaryMutator", r.prompt());
        assertMatches("RemoveConditionalMutator_EQUAL_ELSE", r.prompt());
        assertMatches("NullReturnValsMutator", r.prompt());
    }

    @Test
    void aNoCoverageTargetIsFlaggedAsNeedingToBeReachedNotJustAsserted() {
        Prompts.Ask r = Prompts.batchPrompt(BGAPS, TARGETS);
        assertMatches("(?i)NO_COVERAGE|never runs|not executed", r.prompt());
    }

    @Test
    void theTokenCeilingScalesWithTheNumberOfTargets() {
        Prompts.Ask few = Prompts.batchPrompt(BGAPS, TARGETS.subList(0, 1));
        List<Prompts.Mutant> twelve = new ArrayList<>();
        for (int i = 0; i < 12; i++) twelve.add(new Prompts.Mutant("SURVIVED", "MathMutator", 100 + i, "m", null));
        Prompts.Ask many = Prompts.batchPrompt(BGAPS, Prompts.groupTargets(twelve));
        assertTrue(many.maxTokens() > few.maxTokens(), "twelve tests do not fit in one test's budget");
        assertTrue(many.maxTokens() <= 12000, "and the ceiling is still bounded");
    }

    @Test
    void noTargetsMeansNoCall() {
        Prompts.Ask r = Prompts.batchPrompt(BGAPS, List.of());
        assertEquals(Boolean.TRUE, r.skip());
        assertMatches("(?i)no targets|nothing", r.reason());
    }

    @Test
    void theBatchStillForbidsReflectionAndPinsTheFilePath() {
        Prompts.Ask r = Prompts.batchPrompt(BGAPS, TARGETS);
        assertMatches("(?i)never use reflection", r.system());
        assertEquals(BGAPS.mutTestPath(), r.targetPath());
    }

    // ── assert.match / assert.doesNotMatch, which test a regex ANYWHERE in the string ──

    private static void assertMatches(String regex, String actual) {
        assertMatches(regex, actual, null);
    }

    private static void assertMatches(String regex, String actual, String message) {
        assertTrue(Pattern.compile(regex).matcher(actual == null ? "" : actual).find(),
                message == null ? "expected to contain a match for /" + regex + "/" : message);
    }

    private static void assertDoesNotMatch(String regex, String actual) {
        assertDoesNotMatch(regex, actual, null);
    }

    private static void assertDoesNotMatch(String regex, String actual, String message) {
        assertTrue(!Pattern.compile(regex).matcher(actual == null ? "" : actual).find(),
                message == null ? "expected NO match for /" + regex + "/" : message);
    }
}
