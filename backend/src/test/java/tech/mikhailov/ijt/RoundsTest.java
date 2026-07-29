package tech.mikhailov.ijt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static tech.mikhailov.ijt.Rounds.Baseline;
import static tech.mikhailov.ijt.Rounds.Decision;
import static tech.mikhailov.ijt.Rounds.Kind;
import static tech.mikhailov.ijt.Rounds.MissOutcome;
import static tech.mikhailov.ijt.Rounds.Options;
import static tech.mikhailov.ijt.Rounds.PitResult;
import static tech.mikhailov.ijt.Rounds.TargetMutant;

/// Whether a round earned its place, and whether another one is worth its machine time —
/// two separate questions, and every rule below is a run that got one of them wrong.
///
/// Ported from sidecar/test/unit/rounds.test.js and sidecar/test/unit/baseline.test.js, which
/// both exercise this module.
class RoundsTest {

    /// the JS `const base = { improvedAny: true, degradedAny: false, rounds: 0, maxRounds: 5 }`
    private static Options base() {
        return Options.of().withImprovedAny(true).withDegradedAny(false).withRounds(0).withMaxRounds(5);
    }

    @Test
    void aHealthyRoundIsKeptAndBuysAnotherRound() {
        Decision d = Rounds.decide(base().withMacBase(0.0).withMacAfter(40.0));
        assertTrue(d.keepRound());
        assertTrue(d.continueRounds());
        assertEquals(40.0, d.gain());
        assertEquals(0.4, d.gapClosed());
    }

    @Test
    void aDegradingRoundIsDroppedAndTheUnitContinuesIfWorkRemains() {
        Decision d = Rounds.decide(base().withDegradedAny(true)
                .withMacBase(40.0).withMacAfter(38.0).withSurvivorsLeft(12));
        assertFalse(d.keepRound(), "a round that made things worse is never kept");
        assertTrue(d.continueRounds(), "but one bad test says nothing about the next mutant");
        assertTrue(d.verdict().contains("DEGRADED"));
        Decision done = Rounds.decide(base().withDegradedAny(true)
                .withMacBase(40.0).withMacAfter(38.0).withSurvivorsLeft(0));
        assertFalse(done.continueRounds(), "nothing left to try → stop");
    }

    @Test
    void aRoundThatChangedNothingIsDroppedAndTheNextMutantIsTried() {
        Decision d = Rounds.decide(base().withImprovedAny(false)
                .withMacBase(40.0).withMacAfter(40.0).withSurvivorsLeft(5));
        assertFalse(d.keepRound());
        assertTrue(d.continueRounds());
        assertTrue(d.verdict().contains("MISS"));
    }

    @Test
    void aMarginalRoundIsKeptButEndsTheLoopMutantTarPit() {
        // the live case: 782-mutant schema file, MAC 0 → 0.38 per ~15 min round
        Decision d = Rounds.decide(base().withMacBase(0.0).withMacAfter(0.38));
        assertTrue(d.keepRound(), "the round improved something — it must not be dropped");
        assertFalse(d.continueRounds());
        assertTrue(d.marginal());
        assertTrue(d.verdict().contains("MARGINAL"));
    }

    @Test
    void aSmallAbsoluteGainHighUpTheScaleIsStillWorthAnotherRound() {
        // +1 point at MAC 96 closes 25 % of the remaining gap — the reward's currency
        Decision d = Rounds.decide(base().withMacBase(96.0).withMacAfter(97.0));
        assertTrue(d.continueRounds());
        assertEquals(0.25, d.gapClosed());
    }

    @Test
    void gainsBelowTheAbsoluteFloorStopTheLoopEvenAtAHighGapShare() {
        Decision d = Rounds.decide(base().withMacBase(99.5).withMacAfter(99.7));
        assertTrue(d.keepRound());
        assertFalse(d.continueRounds());
    }

    @Test
    void theRoundBudgetIsSpentExactlyWithNoWastedExtraRound() {
        // rounds=3 → this is round 4 of 5 → one more is allowed
        assertTrue(Rounds.decide(base().withRounds(3).withMacBase(0.0).withMacAfter(40.0)).continueRounds());
        // rounds=4 → this is round 5 of 5 → keep it and stop (do not spend a 6th)
        Decision last = Rounds.decide(base().withRounds(4).withMacBase(0.0).withMacAfter(40.0));
        assertTrue(last.keepRound());
        assertFalse(last.continueRounds());
        assertTrue(last.verdict().contains("round budget 5 spent"));
    }

    @Test
    void aFullyImprovedFileMac100DoesNotLoopForever() {
        Decision d = Rounds.decide(base().withMacBase(100.0).withMacAfter(100.0).withImprovedAny(true));
        assertFalse(d.continueRounds());
    }

    @Test
    void reachingAPerfectScoreEndsTheLoopImmediately() {
        // with a METHOD as the unit this is the common case, and the extra round used to cost
        // an LLM call, a suite run and a PIT run to learn there was nothing left
        Decision d = Rounds.decide(base().withMacBase(0.0).withMacAfter(100.0));
        assertTrue(d.keepRound());
        assertFalse(d.continueRounds());
        assertTrue(d.perfect());
        assertTrue(d.verdict().contains("nothing left to improve"));
        // and the number reads as a person writes it: JS prints 100, Java's default is "100.0"
        assertTrue(d.verdict().contains("MAC 100 "), d.verdict());
    }

    @Test
    void killingOneMutantAlwaysCountsAsProgressWhateverTheFloorsSay() {
        // 306 mutants at 88 % coverage: one kill moves MAC by 88/306 = 0.29, under the 0.5
        // default floor. Without clamping the floors to the measurement's granularity, the
        // one-mutant-per-round strategy would stop after its first success every time.
        Decision d = Rounds.decide(base().withMacBase(0.0).withMacAfter(0.29)
                .withTotalMutants(306).withCoverage(88.0));
        assertTrue(d.continueRounds());
        assertTrue(d.effMinGain() <= 0.29, "floor must not exceed what one mutant is worth");
        Decision unclamped = Rounds.decide(base().withMacBase(0.0).withMacAfter(0.29));
        assertFalse(unclamped.continueRounds(), "without the clamp this is judged marginal");
    }

    @Test
    void aGenuinelyMarginalRoundStillStopsWhenMutantsAreCoarse() {
        // 4 mutants: one kill is worth 25 points, so a 0.29 gain really is noise
        Decision d = Rounds.decide(base().withMacBase(50.0).withMacAfter(50.29)
                .withTotalMutants(4).withCoverage(100.0));
        assertFalse(d.continueRounds());
    }

    @Test
    void theUnitTimeBudgetEndsTheLoopOnceARoundStopsPaying() {
        // This asserted that the budget ends the loop even mid-climb, without saying why, and
        // it cost DataLoaderFactory#newDataLoaderWithTry the rest of its ascent: MAC 0 → 44.45
        // → 69.44, improving on every round, stopped at 900s. The unit the budget was written
        // for — DataLoader#getValueCache, 984 seconds — improved on no round at all. Elapsed
        // time cannot tell those apart; whether the last round gained can.
        Decision stalled = Rounds.decide(base().withMacBase(40.0).withMacAfter(40.0)
                .withImprovedAny(false).withElapsedSec(3000).withBudgetSec(2400));
        assertFalse(stalled.continueRounds());
        assertTrue(stalled.outOfTime());
        assertTrue(stalled.verdict().contains("time budget"));
    }

    @Test
    void aUnitStillClimbingIsGivenMoreThanItsBudgetButNotUnboundedly() {
        Decision climbing = Rounds.decide(base().withMacBase(0.0).withMacAfter(40.0)
                .withElapsedSec(3000).withBudgetSec(2400));
        assertTrue(climbing.keepRound());
        assertTrue(climbing.continueRounds(), "1.25x budget and still gaining — worth another round");

        Decision runaway = Rounds.decide(base().withMacBase(0.0).withMacAfter(40.0)
                .withElapsedSec(5000).withBudgetSec(2400));
        assertFalse(runaway.continueRounds(), "past twice the budget, gaining is no longer a licence");
        assertTrue(runaway.outOfTime());
    }

    @Test
    void thresholdsAreConfigurable() {
        Decision strict = Rounds.decide(base().withMacBase(0.0).withMacAfter(40.0).withMinGapFrac(0.5));
        assertFalse(strict.continueRounds());
        // 0 is a stated floor of none, NOT "unstated" — the boxed field is what keeps those apart
        Decision loose = Rounds.decide(base().withMacBase(0.0).withMacAfter(0.38)
                .withMinGapFrac(0.0).withMinGain(0.0));
        assertTrue(loose.continueRounds());
    }

    // ── mutant selection comes from PIT's data, not from anyone's judgement ──────
    // rounds.test.js continues here with three killDifficulty cases, which belong to pit.js
    // and are not this module's to assert. They move with that port.

    // ── a missed kill drops the round, not the unit ─────────────────────────────

    /// the JS `const miss = { macBase: 62, macAfter: 62, improvedAny: false, degradedAny: false,
    /// rounds: 1, maxRounds: 0, totalMutants: 284, coverage: 88 }`
    private static Options miss() {
        return Options.of().withMacBase(62.0).withMacAfter(62.0)
                .withImprovedAny(false).withDegradedAny(false)
                .withRounds(1).withMaxRounds(0).withTotalMutants(284).withCoverage(88.0);
    }

    @Test
    void aMissedKillContinuesToTheNextMutantWhileSurvivorsRemain() {
        // XML#parse had 83 un-attempted survivors when one bad guess ended the unit
        Decision d = Rounds.decide(miss().withSurvivorsLeft(83).withConsecutiveMisses(0));
        assertFalse(d.keepRound(), "the round itself is worthless and gets dropped");
        assertTrue(d.continueRounds(), "but the unit still has work");
        assertTrue(d.verdict().contains("try the next mutant"));
    }

    @Test
    void repeatedMissesInARowDoEndTheUnit() {
        Decision d = Rounds.decide(miss().withSurvivorsLeft(83).withConsecutiveMisses(3).withMaxMisses(3));
        assertFalse(d.continueRounds());
        assertTrue(d.verdict().contains("in a row"));
    }

    @Test
    void aMissWithNoUnattemptedSurvivorsLeftEndsTheUnit() {
        Decision d = Rounds.decide(miss().withSurvivorsLeft(0).withConsecutiveMisses(0));
        assertFalse(d.continueRounds());
        assertTrue(d.verdict().contains("no un-attempted survivors"));
    }

    @Test
    void aKeptRoundPastTwiceTheBudgetStillStops() {
        // a kept round buys a reprieve, not immunity — 50 survivors left is exactly the shape
        // that would otherwise hold the run for ever
        Decision d = Rounds.decide(miss().withImprovedAny(true).withMacAfter(63.0)
                .withSurvivorsLeft(50).withElapsedSec(8000).withBudgetSec(3600));
        assertTrue(d.keepRound());
        assertFalse(d.continueRounds());
    }

    @Test
    void aKeptRoundThatEndsTheUnitDoesNotAnnounceAnotherOne() {
        // decide() returned {keepRound:true, continueRounds:false, verdict:'PROGRESS (another
        // round)'}, and the server writes that verdict verbatim into the activity stream — so
        // the log promised a round that never came.
        Decision d = Rounds.decide(Options.of().withImprovedAny(true)
                .withMacBase(0.0).withMacAfter(60.0).withRounds(0).withMaxRounds(0)
                .withConsecutiveMisses(0).withMaxMisses(3).withSurvivorsLeft(0));
        assertTrue(d.keepRound());
        assertFalse(d.continueRounds());
        assertFalse(d.verdict().toLowerCase().contains("another round"), d.verdict());
        assertTrue(d.verdict().contains("PROGRESS"));
    }

    @Test
    void aRoundWithNothingToCompareIsNotProgress() {
        // Property#<init> is 100% covered but PIT finds no tests for it, so its mutation score
        // is UNMEASURED and macAfter is null. decide() compared null against undefined, found
        // no degradation, and announced "PROGRESS but MARGINAL (+0 MAC)" — then the unit was
        // discarded for not improving. Arithmetic over an absent number is not a measurement.
        Decision d = Rounds.decide(Options.of().withImprovedAny(true)
                .withMacBase(null).withMacAfter(null).withRounds(0).withSurvivorsLeft(5));
        assertFalse(d.keepRound());
        assertFalse(d.continueRounds());
        String v = d.verdict().toLowerCase();
        assertTrue(v.contains("unmeasured") || v.contains("not measured"), d.verdict());
        assertFalse(d.verdict().contains("PROGRESS"), d.verdict());
    }

    @Test
    void aMeasuredZeroIsStillARealMeasurement() {
        Decision d = Rounds.decide(Options.of().withImprovedAny(true)
                .withMacBase(0.0).withMacAfter(12.0).withRounds(0).withSurvivorsLeft(5));
        assertTrue(d.keepRound());
    }

    // ── how many mutants a round takes on ─────────────────────────────────────
    // A round costs four JVM builds - baseline PIT, coverage, suite, verify PIT - whether it
    // kills one mutant or eight, and on java-dataloader that arithmetic put the remaining ETA
    // at 170 hours. So a round attacks a batch by default. Verified against the live model
    // first (test/model/llm.test.js): an eight-mutant prompt on org.json.XML#parse answers
    // inside its 6000-token ceiling and returns one class with a test per mutant, for 608 more
    // characters of prompt than the single-mutant form.
    //
    // The narrow path stays, as a fallback. A batch that kills nothing says nothing about
    // WHICH of the eight resisted, so the retry drops to one mutant, where a miss is
    // attributable and the mutator statistics mean something.

    @Test
    void aFreshRoundTakesTheConfiguredBatch() {
        assertEquals(8, Rounds.mutantsForRound(8, 0));
    }

    @Test
    void afterAMissItNarrowsToASingleMutant() {
        assertEquals(1, Rounds.mutantsForRound(8, 1));
        assertEquals(1, Rounds.mutantsForRound(8, 3));
    }

    @Test
    void aRunConfiguredForSingleMutantsStaysSingle() {
        assertEquals(1, Rounds.mutantsForRound(1, 0));
    }

    @Test
    void aMissingOrNonsenseSettingIsOneNeverZero() {
        // zero would offer the model no mutants and skip the round for ever
        assertEquals(1, Rounds.mutantsForRound(null, 0));
        assertEquals(1, Rounds.mutantsForRound(0, 0));
        assertEquals(1, Rounds.mutantsForRound(-5, 0));
    }

    // ── the time budget must not stop a unit that is still climbing ───────────
    // DataLoaderFactory#newDataLoaderWithTry went MAC 0 → 44.45 → 69.44, improving on every
    // round, and was cut off by "unit time budget 900s spent". The budget exists because
    // DataLoader#getValueCache once spent 984 seconds producing nothing — but that unit
    // improved on NO round, and this one improved on every round, and a wall-clock number
    // alone cannot tell them apart.
    //
    // Productivity can. A unit whose latest round gained is worth another; a unit out of time
    // AND not gaining is exactly what the budget was written to stop.

    @Test
    void timeIsUpButTheRoundImprovedSoTheUnitGetsAnother() {
        Decision d = Rounds.decide(Options.of()
                .withMacBase(44.45).withMacAfter(69.44).withImprovedAny(true).withDegradedAny(false)
                .withRounds(1).withElapsedSec(950).withBudgetSec(900).withSurvivorsLeft(3));
        assertTrue(d.keepRound());
        assertTrue(d.continueRounds(), "still climbing — the budget is not the whole story");
    }

    @Test
    void timeIsUpAndTheRoundGainedNothingSoStopWhichIsWhatTheBudgetIsFor() {
        Decision d = Rounds.decide(Options.of()
                .withMacBase(20.0).withMacAfter(20.0).withImprovedAny(false).withDegradedAny(false)
                .withRounds(4).withElapsedSec(950).withBudgetSec(900).withSurvivorsLeft(3));
        assertFalse(d.continueRounds());
        assertTrue(d.verdict().contains("time budget"));
    }

    @Test
    void aUnitFarPastItsBudgetStopsEvenWhileGaining() {
        // the reprieve is for a unit just over the line, not an unbounded licence
        Decision d = Rounds.decide(Options.of()
                .withMacBase(10.0).withMacAfter(20.0).withImprovedAny(true).withDegradedAny(false)
                .withRounds(2).withElapsedSec(2000).withBudgetSec(900).withSurvivorsLeft(3));
        assertFalse(d.continueRounds(), "twice the budget is enough for any unit");
    }

    @Test
    void withNoBudgetConfiguredNothingChanges() {
        Decision d = Rounds.decide(Options.of()
                .withMacBase(10.0).withMacAfter(20.0).withImprovedAny(true).withDegradedAny(false)
                .withRounds(1).withElapsedSec(99999).withBudgetSec(0).withSurvivorsLeft(3));
        assertTrue(d.continueRounds());
    }

    // ── strict one pass per method ────────────────────────────────────────────
    // The batch round already covers every surviving line of a method in one call, so a
    // second round asks for the same targets again. It happened on
    // DataLoaderFactory#newMappedPublisherDataLoaderWithTry: the generated file was deleted
    // for not compiling, the filter therefore saw nothing covered, and all six targets were
    // re-asked — three times. Under the one-mutant loop a miss cost one mutant's worth of
    // generation; batching makes it cost the batch's.
    //
    // So: mutate the method, measure it, move on. These three knobs together are what that
    // means, and their interaction is subtle enough to pin.

    /// the JS `const onePass = { maxRounds: 1, maxMisses: 1 }`, applied last so it overrides
    private static Options onePass(Options o) { return o.withMaxRounds(1).withMaxMisses(1); }

    @Test
    void aRoundThatGainedStillEndsTheMethod() {
        Decision d = Rounds.decide(onePass(base().withMacBase(0.0).withMacAfter(60.0).withSurvivorsLeft(9)));
        assertTrue(d.keepRound(), "the work is kept");
        assertFalse(d.continueRounds(), "and the method is done");
    }

    @Test
    void aRoundThatMissedEndsItTooOnTheFirstMiss() {
        Decision d = Rounds.decide(onePass(miss().withSurvivorsLeft(9).withConsecutiveMisses(0)));
        assertFalse(d.continueRounds());
    }

    @Test
    void survivorsLeftOverDoNotBuyAnotherRound() {
        // this is the trade: a lower ceiling per method for one measurement instead of several
        Decision d = Rounds.decide(onePass(base().withMacBase(0.0).withMacAfter(20.0).withSurvivorsLeft(40)));
        assertFalse(d.continueRounds());
    }

    @Test
    void theUncappedSettingStillMeansUncapped() {
        // 0 documents "no round cap" and must not be read as "zero rounds"
        Decision d = Rounds.decide(base().withMacBase(0.0).withMacAfter(60.0)
                .withSurvivorsLeft(9).withMaxRounds(0).withMaxMisses(3));
        assertTrue(d.continueRounds());
    }

    // ═══ from sidecar/test/unit/baseline.test.js ══════════════════════════════
    // What a baseline PIT result means for a unit, and what a missed round decides.
    // pit.js also reports a `score` alongside these fields; classifyBaseline deliberately
    // does not read it, so the narrow record here does not carry it.

    @Test
    void aMethodWithTooLittleMutationSurfaceIsSettledNotWorkedOn() {
        assertEquals(Kind.NO_MUTANTS, Rounds.classifyBaseline(PitResult.of(1), 3).kind());
        assertEquals(Kind.NO_MUTANTS, Rounds.classifyBaseline(PitResult.of(0), 3).kind());
    }

    @Test
    void aMethodWithEnoughMutantsIsImprovable() {
        assertEquals(Kind.IMPROVABLE, Rounds.classifyBaseline(PitResult.of(14), 3).kind());
    }

    @Test
    void aResultPitCouldNotMeasureIsNeverAnnouncedAsZeroMutants() {
        // runPit returns totalMutants null when it produced no usable result. Defaulting that
        // to 0 turned it into a confident "0 mutants (min 3) — no meaningful mutation surface",
        // wrote mutationBefore into the ledger and settled the unit for good.
        Baseline r = Rounds.classifyBaseline(PitResult.of(null), 3);
        assertEquals(Kind.UNMEASURED, r.kind());
        String reason = r.reason().toLowerCase();
        assertTrue(reason.contains("not measured") || reason.contains("unmeasured"), r.reason());
    }

    @Test
    void aCoveredMethodPitCouldNotRunTestsForIsUnmeasuredNotAZero() {
        // `noTests` alone does not decide it — pit.js sets `unmeasured` only when JaCoCo saw
        // coverage, which is what makes "no tests ran" a broken glob rather than an untested
        // method. This fixture originally omitted the flag and so described neither case.
        Baseline r = Rounds.classifyBaseline(new PitResult(null, true, true), 3);
        assertEquals(Kind.UNMEASURED, r.kind());
        assertTrue(r.reason().toLowerCase().contains("no tests"), r.reason());
    }

    @Test
    void anUnmeasuredUnitIsNeverRecordedAsAMeasurement() {
        assertFalse(Rounds.classifyBaseline(PitResult.of(null), 3).recordable());
        assertTrue(Rounds.classifyBaseline(PitResult.of(0), 3).recordable());
    }

    // ── what a missed round decides ───────────────────────────────────────────

    @Test
    void theNextRoundRunsWhileMissesRemain() {
        MissOutcome r = Rounds.missOutcome(1, 3, 12);
        assertTrue(r.continueRounds());
    }

    @Test
    void theMissCapEndsTheUnit() {
        assertFalse(Rounds.missOutcome(3, 3, 12).continueRounds());
    }

    @Test
    void theCountIsReadNeverIncrementedHereVerifyAlreadyDidThat() {
        // both verify and /api/round/miss incremented, so every missed round counted twice:
        // the log said "miss 2/3" and then "4 in a row — stop", ending units at half the
        // budget the operator configured
        assertEquals(2, Rounds.missOutcome(2, 3, 12).consecutiveMisses());
    }

    @Test
    void aMissWithNothingLeftToAttackEndsTheUnitWhateverTheCountSays() {
        assertFalse(Rounds.missOutcome(1, 3, 0).continueRounds());
    }

    @Test
    void theDecisionIsMadeHereNeverEchoedFromTheLastRound() {
        // /api/round/miss returned f.continueRounds — the PREVIOUS round's answer. When verify
        // failed to measure, that stale `true` sent the workflow back for another round with
        // nothing advancing: the run spun forever, one suite + JaCoCo + PIT per cycle.
        //
        // The JS test passes the stale answer in and proves it is ignored; the Java signature
        // cannot express the mistake at all — there is nowhere to put a previous round's
        // verdict — so what is left to pin is that this round's own facts decide it.
        MissOutcome r = Rounds.missOutcome(5, 3, 99);
        assertFalse(r.continueRounds());
    }

    @Test
    void anUncappedMissBudgetStillStopsWhenTheWorkRunsOut() {
        assertTrue(Rounds.missOutcome(99, 0, 5).continueRounds());
        assertFalse(Rounds.missOutcome(99, 0, 0).continueRounds());
    }

    @Test
    void anUnknownSurvivorCountIsNotTreatedAsNothingLeft() {
        assertTrue(Rounds.missOutcome(0, 3, null).continueRounds());
    }

    // ── crediting a round for what it actually did ────────────────────────────

    @Test
    void aMutantTargetedInThisRoundIsTheOneTheRoundIsJudgedOn() {
        TargetMutant tm = new TargetMutant("MathMutator", 120, 4, null);
        assertEquals(tm, Rounds.targetForRound(tm, 4));
    }

    @Test
    void aTargetLeftOverFromAnEarlierRoundIsNotThisRoundsAchievement() {
        // when the mutation phase skipped, verify still read f.targetMutant from the previous
        // round, found the mutant already dead and announced "targeted mutant MathMutator at
        // line 120: KILLED" for a round that attacked nothing and wrote nothing — and counted
        // a second kill for that mutator in the ranking stats.
        assertNull(Rounds.targetForRound(new TargetMutant("MathMutator", 120, 3, null), 4));
    }

    @Test
    void anUnstampedTargetIsNotAttributedToAnyRound() {
        assertNull(Rounds.targetForRound(new TargetMutant("MathMutator", 120, null, null), 4));
        assertNull(Rounds.targetForRound(null, 4));
    }

    @Test
    void noTestsRanAndNoCoverageIsAnUntestedMethodNotAFailureToMeasure() {
        // pit.js separates these deliberately: coverage>0 with no tests means our targetTests
        // glob is broken (UNMEASURED); coverage 0 means nothing tests the method yet, which is
        // an ordinary starting point and precisely what the coverage phase is for. Collapsing
        // them marked every untested method `failed`, and ten failures end a run with "the repo
        // or its toolchain is the problem" — on exactly the under-tested repos this tool exists
        // to improve.
        Baseline r = Rounds.classifyBaseline(new PitResult(null, true, null), 3);
        assertEquals(Kind.UNTESTED, r.kind());
        assertFalse(r.recordable(), "nothing was measured, so nothing is recorded");
        assertTrue(r.reason().toLowerCase().contains("no test"), r.reason());
    }

    @Test
    void noTestsRanButTheMethodIsCoveredIsABrokenMeasurement() {
        Baseline r = Rounds.classifyBaseline(new PitResult(null, true, true), 3);
        assertEquals(Kind.UNMEASURED, r.kind());
    }

    @Test
    void anUntestedMethodIsStillWorkedOnItIsNotSettled() {
        Baseline r = Rounds.classifyBaseline(new PitResult(null, true, null), 3);
        assertNotEquals(Kind.NO_MUTANTS, r.kind());
        assertNotEquals(Kind.UNMEASURED, r.kind());
    }

    @Test
    void aNewAttemptAtAUnitInheritsNoTargetFromThePreviousOne() {
        // The stamp is the round number, and a fresh attempt restarts at round 1 — so attempt
        // 3 round 1 matched the target left by attempt 2 round 1. The log then announced
        // "targeted mutant NullReturnVals at line 416" for a round whose prompt had skipped,
        // and mutatorStats recorded an attempt nobody made.
        assertNull(Rounds.targetForRound(new TargetMutant("NullReturnValsMutator", 416, 1, 2), 1, 3));
        assertEquals(new TargetMutant("NullReturnValsMutator", 416, 1, 3),
                Rounds.targetForRound(new TargetMutant("NullReturnValsMutator", 416, 1, 3), 1, 3));
    }

    @Test
    void aRoundThatHadNothingToAskForEndsTheUnitItDoesNotCountAsAMiss() {
        // XML#isStringAllWhiteSpace is private with no public route, so the mutation prompt
        // correctly skips — but each skip was counted as a MISS, so the unit burned three
        // rounds, settled, and was re-picked twice more: nine empty rounds, each paying for a
        // PIT run and a coverage run. A miss means a test was written and failed to kill;
        // nothing was written here.
        MissOutcome r = Rounds.missOutcome(1, 3, 5, true);
        assertFalse(r.continueRounds());
        assertTrue(r.settle());
        String v = r.verdict().toLowerCase();
        assertTrue(v.contains("nothing to ask") || v.contains("no work"), r.verdict());
    }

    @Test
    void anOrdinaryMissIsUnaffected() {
        MissOutcome r = Rounds.missOutcome(1, 3, 5);
        assertTrue(r.continueRounds());
        assertFalse(r.settle());
    }
}
