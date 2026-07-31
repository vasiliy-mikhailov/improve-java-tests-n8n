package tech.mikhailov.ijt;

/// Per-file round control: should this round be kept, and is another one worth it?
///
/// Two separate questions, deliberately:
///
///   keepRound      — the round earned its place: ≥1 of coverage/mutation/MAC improved and
///                    none of them degraded (the user's criterion).
///   continueRounds — a FURTHER round is worth its machine time. A round costs a full suite
///                    run + coverage + a PIT re-run of the whole file (minutes, tens of
///                    minutes on mutant-heavy files), so a round that barely moved the
///                    needle ends the loop even though it is kept.
///
/// The yardstick for "barely" is the share of the REMAINING MAC gap the round closed, which
/// is the same currency the reward formula pays in: +1 point at MAC 96 closes 25 % of what
/// is left and is worth another round; +0.4 points at MAC 0 on a 782-mutant schema file
/// closes 0.4 % and is a tar pit.
public final class Rounds {

    private Rounds() {}

    /// The floors and caps a round is judged against when the caller states none.
    ///
    /// @param maxRounds 0 is not "no rounds": it documents "uncapped". What ends a unit is a
    ///                  round that achieves nothing, an exhausted mutant list, or the time
    ///                  budget — never an arbitrary count.
    public record Defaults(double minGapFrac, double minGain, int maxRounds) {}

    public static final Defaults DEFAULTS = new Defaults(0.05, 0.5, 0);

    /// Consecutive misses allowed before the unit stops, when the caller states none.
    /// 0 or less means uncapped, which is why this is not simply "the default".
    public static final int DEFAULT_MAX_MISSES = 3;

    /// The mutation surface a unit needs before it is worth working on, when the caller
    /// states none.
    public static final int DEFAULT_MIN_MUTANTS = 3;

    // ── decide: keep this round, and run another? ─────────────────────────────

    /// Everything {@link #decide} judges a round on. Build one with {@link #of()} and the
    /// `with…` methods, so a call states only what it means to state — every boxed field
    /// distinguishes "absent" from a value, and several of those distinctions are
    /// load-bearing (see the individual fields).
    ///
    /// @param macBase          MAC at the start of this round (roundBase). Absent reads as 0.
    /// @param macAfter         MAC measured after this round. **null is not zero**: it means
    ///                         the score could not be measured at all, and the round is then
    ///                         judged UNMEASURED rather than compared against an invented 0.
    /// @param improvedAny      ≥1 metric improved (and files actually changed)
    /// @param degradedAny      ≥1 metric degraded
    /// @param rounds           rounds ALREADY accepted for this file
    /// @param maxRounds        0 = uncapped, and that is the default — see {@link Defaults}
    /// @param minGapFrac       floor on the share of the remaining gap; absent means
    ///                         {@link #DEFAULTS}. Boxed because an explicit 0 is a real
    ///                         setting ("no floor") and must not read as "not stated".
    /// @param minGain          floor on the absolute MAC gain; absent means {@link #DEFAULTS},
    ///                         explicit 0 means no floor
    /// @param totalMutants     mutants in this unit, or null when unknown
    /// @param coverage         coverage % after this round, or null when unknown. With
    ///                         totalMutants it says what killing ONE mutant is worth.
    /// @param maxMisses        absent means {@link #DEFAULT_MAX_MISSES}; 0 or less is uncapped
    /// @param survivorsLeft    un-attempted survivors left, or null when unknown. **null is
    ///                         not 0**: an unknown count must not read as "nothing left".
    public record Options(Double macBase, Double macAfter, boolean improvedAny, boolean degradedAny,
                          int rounds, int maxRounds, Double minGapFrac, Double minGain,
                          Integer totalMutants, Double coverage, long elapsedSec, long budgetSec,
                          int consecutiveMisses, Integer maxMisses, Integer survivorsLeft) {

        /// Nothing stated — the JS `decide({})`.
        public static Options of() {
            return new Options(null, null, false, false, 0, DEFAULTS.maxRounds(), null, null,
                    null, null, 0, 0, 0, null, null);
        }

        public Options withMacBase(Double v) {
            return new Options(v, macAfter, improvedAny, degradedAny, rounds, maxRounds, minGapFrac,
                    minGain, totalMutants, coverage, elapsedSec, budgetSec, consecutiveMisses, maxMisses, survivorsLeft);
        }

        public Options withMacAfter(Double v) {
            return new Options(macBase, v, improvedAny, degradedAny, rounds, maxRounds, minGapFrac,
                    minGain, totalMutants, coverage, elapsedSec, budgetSec, consecutiveMisses, maxMisses, survivorsLeft);
        }

        public Options withImprovedAny(boolean v) {
            return new Options(macBase, macAfter, v, degradedAny, rounds, maxRounds, minGapFrac,
                    minGain, totalMutants, coverage, elapsedSec, budgetSec, consecutiveMisses, maxMisses, survivorsLeft);
        }

        public Options withDegradedAny(boolean v) {
            return new Options(macBase, macAfter, improvedAny, v, rounds, maxRounds, minGapFrac,
                    minGain, totalMutants, coverage, elapsedSec, budgetSec, consecutiveMisses, maxMisses, survivorsLeft);
        }

        public Options withRounds(int v) {
            return new Options(macBase, macAfter, improvedAny, degradedAny, v, maxRounds, minGapFrac,
                    minGain, totalMutants, coverage, elapsedSec, budgetSec, consecutiveMisses, maxMisses, survivorsLeft);
        }

        public Options withMaxRounds(int v) {
            return new Options(macBase, macAfter, improvedAny, degradedAny, rounds, v, minGapFrac,
                    minGain, totalMutants, coverage, elapsedSec, budgetSec, consecutiveMisses, maxMisses, survivorsLeft);
        }

        public Options withMinGapFrac(Double v) {
            return new Options(macBase, macAfter, improvedAny, degradedAny, rounds, maxRounds, v,
                    minGain, totalMutants, coverage, elapsedSec, budgetSec, consecutiveMisses, maxMisses, survivorsLeft);
        }

        public Options withMinGain(Double v) {
            return new Options(macBase, macAfter, improvedAny, degradedAny, rounds, maxRounds, minGapFrac,
                    v, totalMutants, coverage, elapsedSec, budgetSec, consecutiveMisses, maxMisses, survivorsLeft);
        }

        public Options withTotalMutants(Integer v) {
            return new Options(macBase, macAfter, improvedAny, degradedAny, rounds, maxRounds, minGapFrac,
                    minGain, v, coverage, elapsedSec, budgetSec, consecutiveMisses, maxMisses, survivorsLeft);
        }

        public Options withCoverage(Double v) {
            return new Options(macBase, macAfter, improvedAny, degradedAny, rounds, maxRounds, minGapFrac,
                    minGain, totalMutants, v, elapsedSec, budgetSec, consecutiveMisses, maxMisses, survivorsLeft);
        }

        public Options withElapsedSec(long v) {
            return new Options(macBase, macAfter, improvedAny, degradedAny, rounds, maxRounds, minGapFrac,
                    minGain, totalMutants, coverage, v, budgetSec, consecutiveMisses, maxMisses, survivorsLeft);
        }

        public Options withBudgetSec(long v) {
            return new Options(macBase, macAfter, improvedAny, degradedAny, rounds, maxRounds, minGapFrac,
                    minGain, totalMutants, coverage, elapsedSec, v, consecutiveMisses, maxMisses, survivorsLeft);
        }

        public Options withConsecutiveMisses(int v) {
            return new Options(macBase, macAfter, improvedAny, degradedAny, rounds, maxRounds, minGapFrac,
                    minGain, totalMutants, coverage, elapsedSec, budgetSec, v, maxMisses, survivorsLeft);
        }

        public Options withMaxMisses(Integer v) {
            return new Options(macBase, macAfter, improvedAny, degradedAny, rounds, maxRounds, minGapFrac,
                    minGain, totalMutants, coverage, elapsedSec, budgetSec, consecutiveMisses, v, survivorsLeft);
        }

        public Options withSurvivorsLeft(Integer v) {
            return new Options(macBase, macAfter, improvedAny, degradedAny, rounds, maxRounds, minGapFrac,
                    minGain, totalMutants, coverage, elapsedSec, budgetSec, consecutiveMisses, maxMisses, v);
        }
    }

    /// What a round earned and what happens next.
    ///
    /// @param gain       MAC points this round moved, rounded to 2 dp
    /// @param gapClosed  the share of the remaining gap it closed, rounded to 2 dp for
    ///                   reporting — the marginality test itself runs on the unrounded value
    /// @param effMinGain the absolute floor actually applied, after clamping to what one
    ///                   mutant is worth; also rounded for reporting only
    /// @param verdict    the sentence the activity stream shows verbatim, so it must describe
    ///                   what is about to happen and nothing else
    public record Decision(boolean keepRound, boolean continueRounds, boolean marginal, boolean perfect,
                           boolean outOfTime, boolean workLeft, boolean missesLeft,
                           double gain, double gapClosed, double effMinGain, String verdict) {}

    public static Decision decide(Options options) {
        Options a = options == null ? Options.of() : options;
        double base = a.macBase() == null ? 0 : a.macBase();
        double after = a.macAfter() == null ? 0 : a.macAfter();
        // A round can only be judged against numbers. When the mutation score could not be
        // measured at all, macAfter is null — and defaulting that to 0 turned it into a
        // comparison that reported "PROGRESS but MARGINAL (+0 MAC)" for a unit whose score
        // does not exist.
        boolean unmeasured = a.macAfter() == null;
        boolean keepRound = !unmeasured && a.improvedAny() && !a.degradedAny();
        double gain = Util.round2(after - base);
        double gapLeft = 100 - base;
        double gapClosed = gapLeft > 0 ? gain / gapLeft : 0;
        double minGapFrac = a.minGapFrac() == null ? DEFAULTS.minGapFrac() : a.minGapFrac();
        double minGain = a.minGain() == null ? DEFAULTS.minGain() : a.minGain();
        int maxMisses = a.maxMisses() == null ? DEFAULT_MAX_MISSES : a.maxMisses();
        // A floor above the measurement's own granularity stops the loop on its first success.
        // Killing ONE mutant of N moves MAC by coverage/N — on a 306-mutant method at 88 %
        // coverage that is 0.29 points, under the 0.5 default. With one mutant per round that
        // rule would end the loop after round 1, every time. So the floors are clamped to what
        // a single mutant is worth: killing one is always progress.
        Double oneMutant = null;
        if (a.totalMutants() != null && a.totalMutants() > 0 && a.coverage() != null) {
            oneMutant = a.coverage() / a.totalMutants();
        }
        double effMinGain = oneMutant != null ? Math.min(minGain, oneMutant * 0.99) : minGain;
        double effMinGapFrac = (oneMutant != null && gapLeft > 0)
                ? Math.min(minGapFrac, (oneMutant * 0.99) / gapLeft) : minGapFrac;
        // rounds is the count already accepted; this one is number rounds+1
        // maxRounds 0 = uncapped: what ends a unit is a round that achieves nothing, an
        // exhausted mutant list, or the time budget — never an arbitrary count
        boolean roundsLeft = a.maxRounds() == 0 || a.rounds() + 1 < a.maxRounds();
        // the UNROUNDED gapClosed and effMinGain, deliberately: at 306 mutants the floor and
        // the gain differ in the third decimal, and rounding either first flips the answer
        boolean marginal = keepRound && (gapClosed < effMinGapFrac || gain < effMinGain);
        // A mutant-dense method must not hold the run for ever, however cheap each round is.
        //
        // But wall-clock alone cannot tell a unit worth its time from one that is not.
        // DataLoader#getValueCache spent 984 seconds and improved on no round;
        // DataLoaderFactory#newDataLoaderWithTry went 0 → 44.45 → 69.44, improving on every
        // round, and was cut off by the same number. So a unit that is still climbing gets a
        // reprieve — and only a reprieve: past twice its budget it stops whatever it is doing,
        // because "still gaining" must not become an unbounded licence.
        boolean overBudget = a.budgetSec() > 0 && a.elapsedSec() >= a.budgetSec();
        boolean wayOverBudget = a.budgetSec() > 0 && a.elapsedSec() >= a.budgetSec() * 2;
        boolean outOfTime = overBudget && (wayOverBudget || !keepRound);
        // A perfect score leaves nothing to buy. Without this the loop always spends one more
        // round to discover that — cheap on a whole class, wasteful when the unit is a single
        // method, where reaching 100 in the first round is the common case.
        boolean perfect = after >= 100;
        // A round that failed to kill its mutant is DROPPED, but it does not end the unit: the
        // mutant is now marked attempted and dozens of survivors usually remain. Stopping on
        // the first miss abandoned a method with 83 live mutants after one bad guess. What ends
        // a unit is running out of un-attempted survivors, missing repeatedly in a row, running
        // out of time, or reaching 100.
        boolean missesLeft = maxMisses <= 0 || a.consecutiveMisses() < maxMisses;
        boolean workLeft = a.survivorsLeft() == null || a.survivorsLeft() > 0;
        boolean continueRounds = !unmeasured && roundsLeft && !perfect && !outOfTime && workLeft && missesLeft
                && (!keepRound || !marginal);

        String verdict;
        if (unmeasured) {
            verdict = "UNMEASURED (this unit's mutation score could not be measured — nothing to compare, stop)";
        } else if (!keepRound) {
            if (a.degradedAny()) {
                // the JS closes the bracket on the stopping branch only, and this sentence goes
                // into the activity stream verbatim — kept as it reads in production rather than
                // silently "fixed" here, so the two logs stay diffable
                verdict = "DEGRADED (drop this round" + (continueRounds ? ", try the next mutant" : ", stop)");
            } else if (!continueRounds) {
                verdict = !workLeft ? "MISS (no un-attempted survivors left — stop)"
                        : !missesLeft ? "MISS (" + (a.consecutiveMisses() + 1) + " in a row — stop)"
                        : outOfTime ? "MISS (time budget spent — stop)"
                        : "MISS (stop)";
            } else {
                verdict = "MISS (drop this round, try the next mutant — miss "
                        + (a.consecutiveMisses() + 1) + "/" + maxMisses + ")";
            }
        } else if (outOfTime) {
            verdict = "PROGRESS (unit time budget " + a.budgetSec() + "s spent — keep and stop)";
        } else if (perfect) {
            // showMetric, not plain concatenation: a MAC of 100 prints as "100.0" in Java and
            // "100" in JS, and this line is read by people
            verdict = "PROGRESS (MAC " + Util.showMetric(after) + " — nothing left to improve, keep and stop)";
        } else if (!roundsLeft) {
            verdict = "PROGRESS (round budget " + a.maxRounds() + " spent — keep and stop)";
        } else if (marginal) {
            verdict = "PROGRESS but MARGINAL (+" + Util.showMetric(gain) + " MAC = "
                    + Util.showMetric(Util.round2(gapClosed * 100)) + "% of the remaining gap; "
                    + "floors " + Util.showMetric(Util.round2(minGapFrac * 100)) + "% / +"
                    + Util.showMetric(minGain) + ") — keep and stop";
        } else if (continueRounds) {
            verdict = "PROGRESS (another round)";
        } else {
            // the verdict must describe what is about to happen: continueRounds can be false
            // here for a reason none of the branches above covers (no survivors left, the miss
            // cap), and the log promised a round that never came
            verdict = "PROGRESS (nothing left to attack — keep and stop)";
        }

        return new Decision(keepRound, continueRounds, marginal, perfect, outOfTime, workLeft, missesLeft,
                gain, Util.round2(gapClosed), Util.round2(effMinGain), verdict);
    }

    // ── what a baseline PIT result means ─────────────────────────────────────

    /// As much of a PIT run's result as classifying a baseline needs.
    ///
    /// Both flags are boxed and read for truth only, exactly as the JS does: absent and false
    /// mean the same thing here, and neither may be confused with true.
    ///
    /// @param totalMutants null when the run produced no usable result — the build broke, or
    ///                     no test ran. Not zero: see {@link #classifyBaseline}.
    /// @param noTests      PIT ran, but no test exercised this unit
    /// @param unmeasured   pit.js sets this only when JaCoCo saw coverage, which is what makes
    ///                     "no tests ran" a broken glob rather than an untested method
    public record PitResult(Integer totalMutants, Boolean noTests, Boolean unmeasured) {

        /// A result that carries a mutant count and nothing else.
        public static PitResult of(Integer totalMutants) { return new PitResult(totalMutants, null, null); }
    }

    /// What a baseline says about a unit. The `label` is the spelling the JS used and the
    /// workflow's JSON still carries.
    /// What a round costs the unit's budgets. See {@link #afterRound}.
    public record MissBudget(long consecutiveMisses, long brokenRounds) {}

    /// Consecutive broken rounds a unit gets for free before they start costing misses.
    ///
    /// Not unlimited, and not zero. A round whose test never compiled says nothing about whether
    /// the unit can be improved, so charging it against the three-miss budget writes units off
    /// for a failure of generation — that is how JSONObject#getNumber, #similar,
    /// #wrongValueFormatException and #getAnnotation each burned a full attempt in one evening
    /// with their coverage never moving, and were then recorded `no_improvement`, a verdict that
    /// goes into the ledger and stops them being offered again.
    ///
    /// But free retries are only worth having if the retry differs, and until the last-round
    /// feedback reached the live prompts it could not: at temperature 0.2 the same prompt returns
    /// the same uncompilable test. The model is now told "the test DID NOT COMPILE" with the
    /// build's own words, so a retry is genuinely a different question — three of them, then the
    /// ordinary budget resumes and the unit still terminates.
    public static final int DEFAULT_MAX_BROKEN_ROUNDS = 3;

    /// The two counters after a round, given what the round actually did.
    ///
    /// `brokenRounds` counts CONSECUTIVE broken rounds and resets the moment a test reaches the
    /// measurement, whatever it then scores — a unit that got a test to run and simply killed
    /// nothing is in the ordinary miss regime, not this one.
    public static MissBudget afterRound(boolean keepRound, boolean brokenRound,
                                        long consecutiveMisses, long brokenRounds, int maxBroken) {
        if (keepRound) return new MissBudget(0, 0);
        if (!brokenRound) return new MissBudget(consecutiveMisses + 1, 0);
        long broken = brokenRounds + 1;
        // past its own budget, a broken round costs a miss like any other — or a unit whose every
        // round fails to compile would round until its hour-long time budget expired
        return new MissBudget(broken > maxBroken ? consecutiveMisses + 1 : consecutiveMisses, broken);
    }

    public enum Kind {
        UNTESTED("untested"),
        UNMEASURED("unmeasured"),
        NO_MUTANTS("no_mutants"),
        IMPROVABLE("improvable");

        private final String label;

        Kind(String label) { this.label = label; }

        public String label() { return label; }
    }

    /// @param recordable may this be written to the ledgers? Not measuring something is not
    ///                   the same as measuring nothing, and only one of the two may be recorded.
    /// @param total      null unless a count was actually measured
    /// @param reason     null for {@link Kind#IMPROVABLE}, which needs no explanation
    public record Baseline(Kind kind, boolean recordable, Integer total, String reason) {}

    /// What a baseline PIT result means for a unit.
    ///
    /// `runPit` returns totalMutants null when it produced no usable result — the build broke,
    /// or no test ran. Defaulting that to 0 turned it into a confident "0 mutants — no
    /// meaningful mutation surface", settled the unit for good and wrote the invented zero into
    /// both ledgers. Not measuring something is not the same as measuring nothing, and only one
    /// of the two may be recorded.
    public static Baseline classifyBaseline(PitResult result, int minMutants) {
        Integer total = result == null ? null : result.totalMutants();
        if (total == null) {
            // pit.js draws a line these two must not blur: no tests AND some coverage means our
            // targetTests glob is broken; no tests AND no coverage means nothing tests the method
            // yet — an ordinary starting point, and what the coverage phase exists for. Treating
            // the second as a measurement failure marks every untested method `failed`, and ten
            // failures end the run blaming the repo.
            if (result != null && truthy(result.noTests()) && !truthy(result.unmeasured())) {
                return new Baseline(Kind.UNTESTED, false, null,
                        "no test exercises this method yet — nothing to score until one does");
            }
            return new Baseline(Kind.UNMEASURED, false, null,
                    result != null && truthy(result.noTests())
                            ? "PIT ran no tests against this unit — its mutation score was not measured"
                            : "PIT produced no usable result — this unit was not measured");
        }
        if (total < minMutants) {
            return new Baseline(Kind.NO_MUTANTS, true, total,
                    total + " mutant(s) (min " + minMutants + ") — no meaningful mutation surface");
        }
        return new Baseline(Kind.IMPROVABLE, true, total, null);
    }

    /// What a baseline PIT result means, against the default mutation-surface floor.
    public static Baseline classifyBaseline(PitResult result) {
        return classifyBaseline(result, DEFAULT_MIN_MUTANTS);
    }

    // ── a missed kill drops the round, not the unit ──────────────────────────

    /// @param consecutiveMisses handed straight back, never incremented here — see
    ///                          {@link #missOutcome}
    /// @param settle            stop spending attempts on this unit altogether
    public record MissOutcome(int consecutiveMisses, boolean continueRounds, boolean settle, String verdict) {}

    /// A round that failed to kill its mutant. This decides whether another round follows —
    /// it must not be answered by echoing the previous round's `continueRounds`, which is what
    /// spun a run forever when verify stopped measuring: the stale `true` sent the workflow
    /// back for another round with nothing advancing. There is deliberately no parameter for
    /// that earlier answer: the decision is made here, from this round's facts.
    ///
    /// @param consecutiveMisses arrives ALREADY incremented by verify, which owns it
    /// @param maxMisses         0 or less is uncapped
    /// @param survivorsLeft     null when unknown, which is not the same as none left
    /// @param nothingToAsk      both phases skipped, so no test was written at all
    public static MissOutcome missOutcome(int consecutiveMisses, int maxMisses, Integer survivorsLeft,
                                          boolean nothingToAsk) {
        // A round where BOTH phases skipped wrote no test at all — there was nothing to ask the
        // model for. Counting that as a miss made a unit with no reachable route burn three
        // rounds, settle, and come back for two more attempts: nine rounds, each paying for a
        // PIT run and a coverage run, to re-learn the same thing.
        if (nothingToAsk) {
            return new MissOutcome(consecutiveMisses, false, true,
                    "NOTHING TO ASK (no work this round could do — settling the unit)");
        }
        // The count arrives ALREADY incremented by verify, which owns it. Incrementing again
        // here made every missed round count twice — the log read "miss 2/3" and then "4 in a
        // row — stop", ending units at half the configured budget.
        int misses = consecutiveMisses;
        boolean workLeft = survivorsLeft == null || survivorsLeft > 0;
        boolean missesLeft = maxMisses <= 0 || misses < maxMisses;
        String verdict = !workLeft ? "MISS (no surviving mutants left — stop)"
                : !missesLeft ? "MISS (" + misses + " in a row — stop)"
                : "MISS (drop this round, try the next mutant — miss " + misses + "/" + maxMisses + ")";
        return new MissOutcome(misses, workLeft && missesLeft, false, verdict);
    }

    /// A missed kill, with the default miss cap and something still to ask for.
    public static MissOutcome missOutcome(int consecutiveMisses, int maxMisses, Integer survivorsLeft) {
        return missOutcome(consecutiveMisses, maxMisses, survivorsLeft, false);
    }

    // ── crediting a round for what it actually did ───────────────────────────

    /// The mutant a round aimed at, stamped with WHEN it was aimed at.
    ///
    /// The same mutation {@link RoundOutcome.Mutant} carries, plus the two numbers that say
    /// which round of which attempt claimed it — both boxed, because a target written before
    /// the stamps existed has neither.
    public record TargetMutant(String mutator, int line, Integer round, Integer attempt) {}

    /// The mutant THIS round aimed at, or null.
    ///
    /// The target was read straight off the unit, so a round whose mutation phase skipped
    /// inherited the previous round's target, found it already dead, and announced
    /// "targeted mutant X at line N: KILLED" for a round that wrote nothing — and credited
    /// that mutator with a second kill in the ranking statistics.
    ///
    /// @param attempt null means "not stated", the JS's absent third argument — the check is
    ///                then skipped rather than run against nothing
    public static TargetMutant targetForRound(TargetMutant targetMutant, int round, Integer attempt) {
        if (targetMutant == null || targetMutant.round() == null) return null;
        if (targetMutant.round().intValue() != round) return null;
        // The round number alone is not unique: a re-picked unit starts again at round 1, so
        // attempt 3's first round matched the target attempt 2 left behind, and a round that
        // targeted nothing was credited with someone else's mutant.
        if (attempt != null && !attempt.equals(targetMutant.attempt())) return null;
        return targetMutant;
    }

    /// The mutant THIS round aimed at, without checking which attempt stamped it.
    public static TargetMutant targetForRound(TargetMutant targetMutant, int round) {
        return targetForRound(targetMutant, round, null);
    }

    // ── how many mutants a round takes on ────────────────────────────────────

    /// How many surviving mutants this round should take on.
    ///
    /// A round costs four JVM builds — baseline PIT, coverage build, suite, verify PIT —
    /// whether it kills one mutant or eight. On java-dataloader that arithmetic put the
    /// remaining ETA at 170 hours, so a round attacks a batch by default.
    ///
    /// The narrow path remains as a fallback. A batch that kills nothing says nothing about
    /// WHICH of the eight resisted: the score is unchanged and every mutant is still alive, so
    /// there is no attribution and the mutator statistics learn nothing. One miss therefore
    /// drops the next round to a single mutant, where a miss means something.
    ///
    /// @param configured the configured batch size, or null when unset. Zero and negatives are
    ///                   nonsense settings and read as one: zero would offer the model no
    ///                   mutants and skip the round for ever.
    public static int mutantsForRound(Integer configured, int consecutiveMisses) {
        int stated = (configured == null || configured == 0) ? 1 : configured;
        int n = Math.max(1, stated);
        return consecutiveMisses > 0 ? 1 : n;
    }

    /// Truthiness, as the JS reads these flags: absent and false are the same answer.
    private static boolean truthy(Boolean b) { return Boolean.TRUE.equals(b); }
}
