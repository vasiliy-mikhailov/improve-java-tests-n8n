package tech.mikhailov.ijt;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static tech.mikhailov.ijt.Select.Mutant;
import static tech.mikhailov.ijt.Select.MutatorStat;
import static tech.mikhailov.ijt.Select.Ranking;
import static tech.mikhailov.ijt.Select.Unit;
import static tech.mikhailov.ijt.Select.UnitState;

/// What the pipeline attacks next, and in what order — decided by PIT's data and this run's
/// own kills, never by asking the model which mutant it fancies.
class SelectTest {

    private static Mutant m(String mutator, Integer difficulty, Integer line) {
        return new Mutant(mutator, line, null, difficulty);
    }

    private static List<String> order(List<Mutant> list, Map<String, MutatorStat> stats) {
        return Select.rankSurvivors(list, stats).stream().map(x -> x.mutator() + "@" + x.line()).toList();
    }

    @Test
    void withNoEvidenceRankingFollowsPitDifficultyThenLine() {
        List<Mutant> list = List.of(m("RemoveConditional", 2, 40), m("Math", 0, 90), m("NegateConditionals", 1, 10));
        assertEquals(List.of("Math@90", "NegateConditionals@10", "RemoveConditional@40"), order(list, Map.of()));
    }

    @Test
    void aMutatorKindAttackedTwiceWithNoKillsGoesLast() {
        List<Mutant> list = List.of(m("RemoveConditional", 2, 40), m("Math", 0, 90));
        Map<String, MutatorStat> stats = Map.of("Math", new MutatorStat(2, 0));
        assertEquals(List.of("RemoveConditional@40", "Math@90"), order(list, stats));
    }

    @Test
    void oneAttemptIsNotEvidenceASingleMissMustNotDemoteAKind() {
        assertEquals(0, Select.penalty(new MutatorStat(1, 0)));
    }

    @Test
    void aKindThatKeepsMissingIsDemotedEvenAfterAnEarlyLuckyKill() {
        // the run that motivated this: ConditionalsBoundary killed once at line 73, then
        // missed at 164, 191 and 234 — and stayed top-ranked the whole time, because
        // "killed > 0" immunised it permanently.
        MutatorStat cb = new MutatorStat(4, 1);
        MutatorStat fresh = new MutatorStat(2, 1);
        assertTrue(Select.penalty(cb) > 0, "a 25% kill rate over 4 tries is evidence of a bad bet");
        assertEquals(0, Select.penalty(fresh), "a 50% kill rate is still worth trying");
        List<Mutant> list = List.of(m("ConditionalsBoundary", 0, 164), m("NegateConditionals", 1, 10));
        assertEquals(List.of("NegateConditionals@10", "ConditionalsBoundary@164"),
                order(list, Map.of("ConditionalsBoundary", cb)));
    }

    @Test
    void aProductiveKindStaysInFrontOfAnUntriedOne() {
        List<Mutant> list = List.of(m("RemoveConditional", 2, 5), m("Math", 2, 90));
        assertEquals(List.of("Math@90", "RemoveConditional@5"),
                order(list, Map.of("Math", new MutatorStat(3, 3))));
    }

    @Test
    void neverDemotedBelowTheNeverKilledKindsHoweverGoodTheRestLook() {
        List<Mutant> list = List.of(m("VoidMethodCall", 0, 5), m("Math", 2, 90));
        Map<String, MutatorStat> stats = Map.of(
                "VoidMethodCall", new MutatorStat(5, 0),
                "Math", new MutatorStat(4, 1));
        assertEquals(List.of("Math@90", "VoidMethodCall@5"), order(list, stats), "zero kills is worse than a low rate");
    }

    @Test
    void rankingIsDeterministicAndDoesNotMutateItsInput() {
        List<Mutant> list = new ArrayList<>(List.of(m("Math", 1, 90), m("Math", 1, 10)));
        List<Mutant> copy = List.copyOf(list);
        assertEquals(List.of("Math@10", "Math@90"), order(list, Map.of()));
        assertEquals(copy, list);
    }

    @Test
    void anAlreadyAttemptedMutantIsNeverOfferedAgain() {
        List<Mutant> list = List.of(m("Math", 0, 90), m("NegateConditionals", 1, 10));
        assertEquals(List.of(10), Select.eligible(list, List.of("Math@90")).stream().map(Mutant::line).toList());
        assertEquals(2, Select.eligible(list, List.of()).size());
        assertEquals(List.of(), Select.eligible(list, List.of("Math@90", "NegateConditionals@10")));
    }

    @Test
    void theAttemptKeyPinsMutatorAndLineSameKindElsewhereIsFairGame() {
        List<Mutant> list = List.of(m("Math", 0, 90), m("Math", 0, 91));
        assertEquals(List.of(91), Select.eligible(list, List.of("Math@90")).stream().map(Mutant::line).toList());
    }

    // ── which unit to work on next ────────────────────────────────────────────

    /// The JS test's `u({ …overrides })`: the same fixture defaults — `F.java::m`, method `m`,
    /// mac 0, 10 executable lines, publicly reachable — with only the fields a case overrides.
    private static final class U {
        private String path = "F.java::m";
        private String method = "m";
        private Double mac = 0.0;
        private Double coverage;
        private String reach = "public";
        private Integer executableLines = 10;
        private Integer lines;              // the JS fixture carries none; `executableLines ?? lines ?? 0`
        private Integer misses;
        private Boolean everReached;

        U path(String v) { path = v; return this; }
        U method(String v) { method = v; return this; }
        U mac(Double v) { mac = v; return this; }
        U coverage(Double v) { coverage = v; return this; }
        U reach(String v) { reach = v; return this; }
        U executableLines(Integer v) { executableLines = v; return this; }
        U misses(Integer v) { misses = v; return this; }
        U everReached(Boolean v) { everReached = v; return this; }

        Unit u() { return new Unit(path, method, mac, coverage, reach, executableLines, lines, misses, everReached); }
    }

    private static U u(String path) { return new U().path(path); }

    private static List<String> paths(Ranking r) {
        return r.units().stream().map(Unit::path).toList();
    }

    @Test
    void theWeakestUnitComesFirstThatIsWhereTheGapIs() {
        Ranking r = Select.rankUnits(List.of(
                u("a").mac(40.0).u(), u("b").mac(5.0).u(), u("c").mac(20.0).u()));
        assertEquals(List.of("b", "c", "a"), paths(r));
    }

    @Test
    void aUnitWithNoKnownRouteSinksToTheBottomOfTheQueue() {
        // It used to be DROPPED, to save the four PIT runs a genuinely dead private method
        // costs. That traded a bounded cost for an unbounded one: reachability is regex over
        // Java source, and every construct it misreads deleted real, testable work from the run.
        // Last place keeps the saving where the analysis is right and costs only ordering where
        // it is wrong.
        Ranking r = Select.rankUnits(List.of(u("live").reach("route").u(), u("dead").reach("none").u()));
        assertEquals(List.of("live", "dead"), paths(r));
        assertEquals(0, r.unreachable());
    }

    @Test
    void atEqualWeaknessAMethodATestCanCallOutrightBeatsOneBehindPrivateHops() {
        Ranking r = Select.rankUnits(List.of(u("hidden").reach("route").u(), u("callable").reach("public").u()));
        assertEquals(List.of("callable", "hidden"), paths(r));
    }

    @Test
    void constructorsStillSortLastAmongEquals() {
        Ranking r = Select.rankUnits(List.of(u("ctor").method("<init>").u(), u("real").method("compute").u()));
        assertEquals(List.of("real", "ctor"), paths(r));
    }

    @Test
    void amongEqualsTheUnitWithTheMostCodeToMutateGoesFirst() {
        Ranking r = Select.rankUnits(List.of(u("small").executableLines(3).u(), u("big").executableLines(40).u()));
        assertEquals(List.of("big", "small"), paths(r));
    }

    @Test
    void aUnitNotYetMeasuredIsRankedOnWhatIsKnownNotTreatedAsPerfect() {
        Ranking r = Select.rankUnits(List.of(
                u("measured").mac(50.0).u(), u("unknown").mac(null).coverage(0.0).u()));
        assertEquals("unknown", r.units().get(0).path());
    }

    @Test
    void rankingIsTotalTheSameInputAlwaysYieldsTheSameOrder() {
        List<Unit> list = List.of(u("x").u(), u("y").u(), u("z").u());
        List<Unit> reversed = new ArrayList<>(list);
        Collections.reverse(reversed);
        assertEquals(paths(Select.rankUnits(list)), paths(Select.rankUnits(reversed)));
    }

    @Test
    void theLastTieBreakOrdersPathsTheWayLocaleCompareDoesNotTheWayCollatorDoes() {
        // Everything above this tie ties on a fresh scan: nothing is measured, every unit of a
        // file shares the file's line count, and the run is then capped by scopeLimit — so the
        // order here decides which SET of units gets improved, not merely their sequence.
        //
        // java.text.Collator makes `-`, `/` and `.` ignorable at primary strength and gets the
        // OPPOSITE answer from Node on the shape every multi-module repo has. The expected
        // orders below are node v22's, taken from String.prototype.localeCompare.
        Ranking modules = Select.rankUnits(List.of(
                u("core/src/main/java/p/A.java::m").u(),
                u("core-api/src/main/java/p/A.java::m").u()));
        assertEquals(List.of("core-api/src/main/java/p/A.java::m", "core/src/main/java/p/A.java::m"),
                paths(modules), "'-' sorts before '/', so core-api comes first");

        Ranking mixed = Select.rankUnits(List.of(
                u("Foo.java::bar").u(), u("FooBar.java::x").u(),
                u("foo-2.java::y").u(), u("Foo2.java::z").u()));
        assertEquals(List.of("foo-2.java::y", "Foo.java::bar", "Foo2.java::z", "FooBar.java::x"),
                paths(mixed));
    }

    @Test
    void caseIsATertiaryDifferenceSoLowercaseWinsOnlyWhenNothingElseSeparatesThem() {
        // `apple.java` before `Zoo.java` is the whole reason the JS reaches for localeCompare
        // instead of comparing code points, and lowercase-before-uppercase is what it does when
        // two paths differ in nothing else.
        assertTrue(Select.comparePaths("apple.java", "Zoo.java") < 0);
        assertTrue(Select.comparePaths("src/main/java/a.java", "src/main/java/A.java") < 0);
        // '_' before '-', both before '.', and a digit before any letter
        assertTrue(Select.comparePaths("x_1.java", "x-1.java") < 0);
        assertTrue(Select.comparePaths("a_b.java", "a.java") < 0);
        assertTrue(Select.comparePaths("Foo2.java", "FooBar.java") < 0);
        // and no numeric collation: "10" is not ten here, it is '1' then '0'
        assertTrue(Select.comparePaths("m2.java", "m10.java") > 0);
    }

    // ── which units count as "targeted" in the headline ───────────────────────

    /// The measured-and-settled half of a unit's state: the JS reads it off the same object as
    /// the ranking metrics above.
    private static UnitState settled(Double macBefore, String status) {
        return new UnitState(macBefore, status, null, null);
    }

    @Test
    void aUnitWithNoMutationSurfaceIsNotATargetedImprovement() {
        // the dashboard reported avg MAC after = 13.33 while its one improved unit stood at
        // 66.67: four units settled as "no mutants" were averaged in at 0, understating the
        // work by a factor of five
        assertFalse(Select.isTargeted(settled(0.0, "no_mutants")));
        assertTrue(Select.isTargeted(settled(0.0, "improved")));
    }

    @Test
    void aUnitThatWasNeverMeasuredIsNotCountedEitherWay() {
        assertFalse(Select.isTargeted(settled(null, "candidate")));
        // and the same when the field is absent altogether rather than explicitly null
        assertFalse(Select.isTargeted(new UnitState(null, "candidate", null, null)));
    }

    @Test
    void aMeasuredUnitThatFailedToImproveStillCountsItWasReallyAttempted() {
        assertTrue(Select.isTargeted(settled(40.0, "no_improvement")));
    }

    @Test
    void aUnitTheAnalysisCannotFindARouteToIsRankedLastNeverDropped() {
        // Dropping on the strength of a regex over Java source deletes real work silently:
        // multi-line signatures, callers inside inner classes, `this::foo` method references
        // and overloads all defeated it. Ranking it last keeps the benefit and costs at most
        // some rounds at the very end of a run.
        Ranking r = Select.rankUnits(List.of(u("unsure").reach("none").u(), u("callable").reach("public").u()));
        assertEquals(List.of("callable", "unsure"), paths(r));
        assertEquals(0, r.unreachable(), "nothing is discarded");
    }

    @Test
    void behindAPrivateChainStillBeatsNoKnownRouteAtAll() {
        Ranking r = Select.rankUnits(List.of(u("none").reach("none").u(), u("route").reach("route").u()));
        assertEquals(List.of("route", "none"), paths(r));
    }

    @Test
    void aUnitRepeatedlyProvenUnexecutableSinksOnEvidenceRatherThanGuesswork() {
        // JSONObject#isRecordStyleAccessor is guarded by `if (isRecordType && ...)`: reaching it
        // needs a real Java record, which a test compiled at this project's source level cannot
        // declare. Static analysis sees a valid route; only the runs show the truth — three
        // attempts, coverage never off zero. It keeps winning rank 1 on 14 mutants and 0 MAC,
        // burning three rounds each time it is picked.
        Ranking r = Select.rankUnits(List.of(
                u("proven-dead").reach("route").misses(3).everReached(false).u(),
                u("ordinary").reach("route").u()));
        assertEquals(List.of("ordinary", "proven-dead"), paths(r));
    }

    @Test
    void oneFailedAttemptIsNotProofOnlyARepeatedOne() {
        Ranking r = Select.rankUnits(List.of(
                u("unlucky").reach("public").misses(1).everReached(false).u(),
                u("ordinary").reach("route").u()));
        assertEquals(List.of("unlucky", "ordinary"), paths(r), "still ahead: it is directly callable");
    }

    @Test
    void aUnitThatHasBeenReachedIsNeverDemotedForMissing() {
        Ranking r = Select.rankUnits(List.of(
                u("reached").reach("route").misses(5).everReached(true).u(),
                u("untried").reach("none").u()));
        assertEquals(List.of("reached", "untried"), paths(r));
    }

    /// The attempted-and-survived half of a unit's state.
    private static UnitState measured(List<Mutant> lastSurvived, List<String> attempted) {
        return new UnitState(null, null, lastSurvived, attempted);
    }

    @Test
    void aUnitWhoseEverySurvivorHasBeenAttemptedIsExhaustedNotRePicked() {
        // getRawType: 3 survivors, all attempted, so the round targets nothing and is discarded
        // — then it is picked again, and again, each time costing a PIT run and a coverage run
        // with no model call and no possible outcome.
        List<Mutant> survivors = List.of(
                new Mutant("NullReturnValsMutator", 3414), new Mutant("RemoveConditionalMutator", 3413));
        List<String> attempted = List.of("NullReturnValsMutator@3414", "RemoveConditionalMutator@3413");
        assertTrue(Select.exhausted(measured(survivors, attempted)));
    }

    @Test
    void aUnitWithAnUnAttemptedSurvivorIsNotExhausted() {
        List<Mutant> survivors = List.of(
                new Mutant("NullReturnValsMutator", 3414), new Mutant("MathMutator", 99));
        assertFalse(Select.exhausted(measured(survivors, List.of("NullReturnValsMutator@3414"))));
    }

    @Test
    void aUnitThatWasNeverMeasuredIsNotExhaustedNothingIsKnownAboutIt() {
        assertFalse(Select.exhausted(new UnitState(null, null, null, null)));
        assertFalse(Select.exhausted(measured(null, List.of())));
    }

    @Test
    void aUnitMeasuredAsHavingNoSurvivorsAtAllIsExhausted() {
        assertTrue(Select.exhausted(measured(List.of(), List.of())));
    }

    // ── what a kill is WORTH, not just how easily it comes ────────────────────
    // Across 476 targeted mutants the pipeline killed 77, and 63% of those kills came from
    // the two cheapest kinds: VoidMethodCall (58% kill rate) and NullReturnVals (26%). The
    // mutators that pin actual logic were barely touched — RemoveConditional 4%,
    // ConditionalsBoundary 14%, Math 11%.
    //
    // That is not an accident, it is the ranking. penalty() rewards a kind for its observed
    // kill RATE, so the easiest kinds are attacked first and the hardest last. A NullReturnVals
    // kill can be bought with assertNotNull — and 48 assertNotNull calls are exactly what the
    // generated tests contain. The bonus should go to kinds whose kills prove something.

    @Test
    void aKindThatKeepsDyingToAWeakAssertionEarnsNoRankingBonus() {
        // returns null → assertNotNull kills it and pins nothing else
        assertEquals(0, Select.penalty(new MutatorStat(10, 9), "NullReturnValsMutator"));
        assertEquals(0, Select.penalty(new MutatorStat(10, 9), "EmptyObjectReturnValsMutator"));
    }

    @Test
    void aKindWhoseKillPinsRealBehaviourStillEarnsOne() {
        assertEquals(-1, Select.penalty(new MutatorStat(10, 9), "ConditionalsBoundaryMutator"));
        assertEquals(-1, Select.penalty(new MutatorStat(10, 9), "MathMutator"));
        assertEquals(-1, Select.penalty(new MutatorStat(10, 9), "RemoveConditionalMutator_EQUAL_ELSE"));
    }

    @Test
    void aKindThatNeverDiesIsStillDemotedWhateverItWouldProve() {
        // the original reason penalty exists: stop spending rounds on a kind nothing kills
        assertEquals(2, Select.penalty(new MutatorStat(6, 0), "ConditionalsBoundaryMutator"));
        assertEquals(2, Select.penalty(new MutatorStat(6, 0), "NullReturnValsMutator"));
    }

    @Test
    void aMostlyMissingKindIsDemotedShallowOrDeep() {
        assertEquals(1, Select.penalty(new MutatorStat(10, 2), "MathMutator"));
        assertEquals(1, Select.penalty(new MutatorStat(10, 2), "NullReturnValsMutator"));
    }

    @Test
    void oneAttemptIsStillNotEvidence() {
        assertEquals(0, Select.penalty(new MutatorStat(1, 0), "MathMutator"));
        assertEquals(0, Select.penalty(null, "MathMutator"));
    }

    /// PIT emits several DISTINCT mutants that share a mutator kind and a line, and the attempt
    /// register keyed them as one.
    ///
    /// `JSONObject#parseJSONObject` measured 14 mutants, 11 killed, 3 alive — and all three
    /// alive were `RemoveConditionalMutator_EQUAL_ELSE` at line 248 (two SURVIVED, one
    /// NO_COVERAGE; the line holds more than one conditional). One round attacked "the" mutant
    /// at that key, and every one of the three was then struck off. The unit settled at 78.57%
    /// reporting "no un-attempted survivors left — stop" with two survivors never attempted.
    ///
    /// Across the run this masked 37 survivors in 18 units, every one of them reported as
    /// nothing left to attack. PIT's `<index>` is the per-mutant identifier that separates them.
    @Nested
    class AttemptKey {

        /// The three of them, distinguished only by PIT's index. The JS fixture also carries a
        /// status (two SURVIVED, one NO_COVERAGE); nothing here reads it, because pit.js has
        /// already folded status into difficulty by the time selection sees a mutant.
        private Mutant at248(Integer index) {
            return new Mutant("RemoveConditionalMutator_EQUAL_ELSE", 248, index, null);
        }

        @Test
        void twoDistinctMutantsOfOneKindOnOneLineAreTwoKeysNotOne() {
            assertNotEquals(Select.attemptKey(at248(11)), Select.attemptKey(at248(14)));
        }

        @Test
        void attackingOneOfThemLeavesTheOthersEligible() {
            List<Mutant> survived = List.of(at248(11), at248(14), at248(17));
            List<Mutant> left = Select.eligible(survived, List.of(Select.attemptKey(at248(11))));
            assertEquals(2, left.size(), "two survivors were never attempted and must stay in the queue");
            assertEquals(List.of(14, 17), left.stream().map(Mutant::index).toList());
        }

        @Test
        void andTheUnitIsNotDeclaredExhaustedWhileTheyRemain() {
            UnitState f = new UnitState(null, null,
                    List.of(at248(11), at248(14)),
                    List.of(Select.attemptKey(at248(11))));
            assertFalse(Select.exhausted(f));
        }

        @Test
        void theSameMutantIsStillRecognisedAsAttempted() {
            assertEquals(0, Select.eligible(List.of(at248(11)), List.of(Select.attemptKey(at248(11)))).size());
        }

        @Test
        void aKeyRecordedBeforeIndexesExistedStillMatchesItsWholeLineAndKindGroup() {
            // Attempt registers persist across runs. A legacy `Mutator@248` entry cannot say WHICH
            // of the three it meant, so it must keep its old, broad meaning rather than silently
            // matching none of them and re-offering work already known to have failed.
            List<Mutant> survived = List.of(at248(11), at248(14));
            assertEquals(0, Select.eligible(survived, List.of("RemoveConditionalMutator_EQUAL_ELSE@248")).size());
        }

        @Test
        void aMutantPitGaveNoIndexKeepsThePlainKey() {
            Mutant noIndex = new Mutant("MathMutator", 90);
            assertEquals("MathMutator@90", Select.attemptKey(noIndex));
        }
    }
}
