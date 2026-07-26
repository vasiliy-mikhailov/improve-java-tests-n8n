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
  totalMutants = null, coverage = null, elapsedSec = 0, budgetSec = 0 } = {}) {
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
  const continueRounds = keepRound && roundsLeft && !marginal && !perfect && !outOfTime;
  const verdict = !keepRound
    ? (degradedAny ? 'DEGRADED (stop, drop round)' : 'STALE (stop)')
    : outOfTime ? `PROGRESS (unit time budget ${budgetSec}s spent — keep and stop)`
      : perfect ? `PROGRESS (MAC ${after} — nothing left to improve, keep and stop)`
      : !roundsLeft ? `PROGRESS (round budget ${maxRounds} spent — keep and stop)`
      : marginal ? `PROGRESS but MARGINAL (+${gain} MAC = ${round2(gapClosed * 100)}% of the remaining gap; `
        + `floors ${round2(minGapFrac * 100)}% / +${minGain}) — keep and stop`
        : 'PROGRESS (another round)';
  return { keepRound, continueRounds, marginal, perfect, outOfTime, gain,
    gapClosed: round2(gapClosed), effMinGain: round2(effMinGain), verdict };
}

module.exports = { decide, DEFAULTS };
