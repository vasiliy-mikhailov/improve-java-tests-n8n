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

const DEFAULTS = { minGapFrac: 0.05, minGain: 0.5, maxRounds: 5 };

/**
 * @param {object} a
 * @param {number} a.macBase   MAC at the start of this round (roundBase)
 * @param {number} a.macAfter  MAC measured after this round
 * @param {boolean} a.improvedAny  ≥1 metric improved (and files actually changed)
 * @param {boolean} a.degradedAny  ≥1 metric degraded
 * @param {number} a.rounds    rounds ALREADY accepted for this file
 */
function decide({ macBase, macAfter, improvedAny, degradedAny, rounds = 0,
  maxRounds = DEFAULTS.maxRounds, minGapFrac = DEFAULTS.minGapFrac, minGain = DEFAULTS.minGain } = {}) {
  const base = macBase ?? 0;
  const after = macAfter ?? 0;
  const keepRound = !!improvedAny && !degradedAny;
  const gain = round2(after - base);
  const gapLeft = 100 - base;
  const gapClosed = gapLeft > 0 ? gain / gapLeft : 0;
  // rounds is the count already accepted; this one is number rounds+1
  const roundsLeft = rounds + 1 < Math.max(1, maxRounds);
  const marginal = keepRound && (gapClosed < minGapFrac || gain < minGain);
  const continueRounds = keepRound && roundsLeft && !marginal;
  const verdict = !keepRound
    ? (degradedAny ? 'DEGRADED (stop, drop round)' : 'STALE (stop)')
    : !roundsLeft ? `PROGRESS (round budget ${Math.max(1, maxRounds)} spent — keep and stop)`
      : marginal ? `PROGRESS but MARGINAL (+${gain} MAC = ${round2(gapClosed * 100)}% of the remaining gap; `
        + `floors ${round2(minGapFrac * 100)}% / +${minGain}) — keep and stop`
        : 'PROGRESS (another round)';
  return { keepRound, continueRounds, marginal, gain, gapClosed: round2(gapClosed), verdict };
}

module.exports = { decide, DEFAULTS };
