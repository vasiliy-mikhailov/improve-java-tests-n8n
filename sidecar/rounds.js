'use strict';
// Per-file round control: should this round be kept, and is another one worth it?
//
// Two separate questions, deliberately:
//   keepRound       — the round earned its place: ≥1 of coverage/mutation/MAC
//                     improved and none of them degraded (the user's criterion).
//   continueRounds  — a FURTHER round is worth its machine time. A round costs a
//                     full suite run + coverage + a Stryker re-run of the whole
//                     file (minutes, tens of minutes on mutant-heavy files), so a
//                     round that barely moved the needle ends the loop even though
//                     it is kept.
//
// The yardstick for "barely" is the share of the REMAINING MAC gap the round
// closed, which is the same currency the reward formula pays in: +1 point at
// MAC 96 closes 25 % of what is left and is worth another round; +0.4 points at
// MAC 0 on a 782-mutant schema file closes 0.4 % and is a tar pit.
const { round2 } = require('./util');

const DEFAULTS = { minGapFrac: 0.05, minGain: 0.5, maxRounds: 0 };

/**
 * @param {object} a
 * @param {number} a.macBase   MAC at the start of this round (roundBase)
 * @param {number} a.macAfter  MAC measured after this round
 * @param {boolean} a.improvedAny  ≥1 metric improved (and files actually changed)
 * @param {boolean} a.degradedAny  ≥1 metric degraded
 * @param {number} a.rounds    rounds ALREADY accepted for this file
 */
function decide({ macBase, macAfter, improvedAny, degradedAny, rounds = 0,
  maxRounds = DEFAULTS.maxRounds, minGapFrac = DEFAULTS.minGapFrac, minGain = DEFAULTS.minGain,
  totalMutants = null, coverage = null, elapsedSec = 0, budgetSec = 0,
  consecutiveMisses = 0, maxMisses = 3, survivorsLeft = null } = {}) {
  const base = macBase ?? 0;
  const after = macAfter ?? 0;
  const keepRound = !!improvedAny && !degradedAny;
  const gain = round2(after - base);
  const gapLeft = 100 - base;
  const gapClosed = gapLeft > 0 ? gain / gapLeft : 0;
  // A floor above the measurement's own granularity stops the loop on its first success.
  // Killing ONE mutant of N moves MAC by coverage/N — on a 306-mutant method at 88 %
  // coverage that is 0.29 points, under the 0.5 default. With one mutant per round that
  // rule would end the loop after round 1, every time. So the floors are clamped to what
  // a single mutant is worth: killing one is always progress.
  const oneMutant = (totalMutants > 0 && coverage != null) ? (coverage / totalMutants) : null;
  const effMinGain = oneMutant != null ? Math.min(minGain, oneMutant * 0.99) : minGain;
  const effMinGapFrac = (oneMutant != null && gapLeft > 0)
    ? Math.min(minGapFrac, (oneMutant * 0.99) / gapLeft) : minGapFrac;
  // rounds is the count already accepted; this one is number rounds+1
  // maxRounds 0 = uncapped: what ends a unit is a round that achieves nothing, an
  // exhausted mutant list, or the time budget — never an arbitrary count
  const roundsLeft = !maxRounds || rounds + 1 < maxRounds;
  const marginal = keepRound && (gapClosed < effMinGapFrac || gain < effMinGain);
  // a mutant-dense method must not hold the run for ever, however cheap each round is
  const outOfTime = budgetSec > 0 && elapsedSec >= budgetSec;
  // A perfect score leaves nothing to buy. Without this the loop always spends one more
  // round to discover that — cheap on a whole class, wasteful when the unit is a single
  // method, where reaching 100 in the first round is the common case.
  const perfect = after >= 100;
  // A round that failed to kill its mutant is DROPPED, but it does not end the unit: the
  // mutant is now marked attempted and dozens of survivors usually remain. Stopping on
  // the first miss abandoned a method with 83 live mutants after one bad guess. What ends
  // a unit is running out of un-attempted survivors, missing repeatedly in a row, running
  // out of time, or reaching 100.
  const missesLeft = maxMisses <= 0 || consecutiveMisses < maxMisses;
  const workLeft = survivorsLeft == null || survivorsLeft > 0;
  const continueRounds = roundsLeft && !perfect && !outOfTime && workLeft && missesLeft
    && (keepRound ? !marginal : true);
  const verdict = !keepRound
    ? (degradedAny ? `DEGRADED (drop this round${continueRounds ? ', try the next mutant' : ', stop)'}`
      : !continueRounds
        ? (!workLeft ? 'MISS (no un-attempted survivors left — stop)'
          : !missesLeft ? `MISS (${consecutiveMisses + 1} in a row — stop)`
            : outOfTime ? 'MISS (time budget spent — stop)' : 'MISS (stop)')
        : `MISS (drop this round, try the next mutant — miss ${consecutiveMisses + 1}/${maxMisses})`)
    : outOfTime ? `PROGRESS (unit time budget ${budgetSec}s spent — keep and stop)`
      : perfect ? `PROGRESS (MAC ${after} — nothing left to improve, keep and stop)`
      : !roundsLeft ? `PROGRESS (round budget ${maxRounds} spent — keep and stop)`
      : marginal ? `PROGRESS but MARGINAL (+${gain} MAC = ${round2(gapClosed * 100)}% of the remaining gap; `
        + `floors ${round2(minGapFrac * 100)}% / +${minGain}) — keep and stop`
        // the verdict must describe what is about to happen: continueRounds can be
        // false here for a reason none of the branches above covers (no survivors
        // left, the miss cap), and the log promised a round that never came
        : continueRounds ? 'PROGRESS (another round)'
          : 'PROGRESS (nothing left to attack — keep and stop)';
  return { keepRound, continueRounds, marginal, perfect, outOfTime, workLeft, missesLeft, gain,
    gapClosed: round2(gapClosed), effMinGain: round2(effMinGain), verdict };
}


/**
 * What a baseline PIT result means for a unit.
 *
 * `runPit` returns totalMutants null when it produced no usable result — the build broke,
 * or no test ran. `(r.totalMutants ?? 0) < minMutants` turned that into a confident
 * "0 mutants — no meaningful mutation surface", settled the unit for good and wrote the
 * invented zero into both ledgers. Not measuring something is not the same as measuring
 * nothing, and only one of the two may be recorded.
 */
function classifyBaseline(result, minMutants = 3) {
  const total = result ? result.totalMutants : null;
  if (total == null) {
    // pit.js draws a line these two must not blur: no tests AND some coverage means our
    // targetTests glob is broken; no tests AND no coverage means nothing tests the method
    // yet — an ordinary starting point, and what the coverage phase exists for. Treating
    // the second as a measurement failure marks every untested method `failed`, and ten
    // failures end the run blaming the repo.
    if (result && result.noTests && !result.unmeasured) {
      return {
        kind: 'untested',
        recordable: false,
        reason: 'no test exercises this method yet — nothing to score until one does',
      };
    }
    return {
      kind: 'unmeasured',
      recordable: false,
      reason: result && result.noTests
        ? 'PIT ran no tests against this unit — its mutation score was not measured'
        : 'PIT produced no usable result — this unit was not measured',
    };
  }
  if (total < minMutants) {
    return { kind: 'no_mutants', recordable: true, total, reason: `${total} mutant(s) (min ${minMutants}) — no meaningful mutation surface` };
  }
  return { kind: 'improvable', recordable: true, total };
}

/**
 * A round that failed to kill its mutant. This decides whether another round follows —
 * it must not be answered by echoing the previous round's `continueRounds`, which is what
 * spun a run forever when verify stopped measuring: the stale `true` sent the workflow
 * back for another round with nothing advancing.
 */
function missOutcome({ consecutiveMisses = 0, maxMisses = 3, survivorsLeft = null } = {}) {
  // The count arrives ALREADY incremented by verify, which owns it. Incrementing again
  // here made every missed round count twice — the log read "miss 2/3" and then "4 in a
  // row — stop", ending units at half the configured budget.
  const misses = consecutiveMisses;
  const workLeft = survivorsLeft == null || survivorsLeft > 0;
  const missesLeft = maxMisses <= 0 || misses < maxMisses;
  return {
    consecutiveMisses: misses,
    continueRounds: workLeft && missesLeft,
    verdict: !workLeft ? 'MISS (no surviving mutants left — stop)'
      : !missesLeft ? `MISS (${misses} in a row — stop)`
        : `MISS (drop this round, try the next mutant — miss ${misses}/${maxMisses})`,
  };
}

/**
 * The mutant THIS round aimed at, or null.
 *
 * The target was read straight off the unit, so a round whose mutation phase skipped
 * inherited the previous round's target, found it already dead, and announced
 * "targeted mutant X at line N: KILLED" for a round that wrote nothing — and credited
 * that mutator with a second kill in the ranking statistics.
 */
function targetForRound(targetMutant, round) {
  if (!targetMutant || targetMutant.round == null) return null;
  return targetMutant.round === round ? targetMutant : null;
}

module.exports = {
  classifyBaseline, missOutcome, targetForRound, decide, DEFAULTS };
