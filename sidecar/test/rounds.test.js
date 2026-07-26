'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { decide } = require('../rounds');

const base = { improvedAny: true, degradedAny: false, rounds: 0, maxRounds: 5 };

test('a healthy round is kept and buys another round', () => {
  const d = decide({ ...base, macBase: 0, macAfter: 40 });
  assert.equal(d.keepRound, true);
  assert.equal(d.continueRounds, true);
  assert.equal(d.gain, 40);
  assert.equal(d.gapClosed, 0.4);
});

test('a degrading round is dropped', () => {
  const d = decide({ ...base, degradedAny: true, macBase: 40, macAfter: 38 });
  assert.equal(d.keepRound, false);
  assert.equal(d.continueRounds, false);
  assert.match(d.verdict, /DEGRADED/);
});

test('a stale round is dropped', () => {
  const d = decide({ ...base, improvedAny: false, macBase: 40, macAfter: 40 });
  assert.equal(d.keepRound, false);
  assert.match(d.verdict, /STALE/);
});

test('a marginal round is KEPT but ends the loop (mutant tar pit)', () => {
  // the live case: 782-mutant schema file, MAC 0 → 0.38 per ~15 min round
  const d = decide({ ...base, macBase: 0, macAfter: 0.38 });
  assert.equal(d.keepRound, true, 'the round improved something — it must not be dropped');
  assert.equal(d.continueRounds, false);
  assert.equal(d.marginal, true);
  assert.match(d.verdict, /MARGINAL/);
});

test('a small absolute gain high up the scale is still worth another round', () => {
  // +1 point at MAC 96 closes 25 % of the remaining gap — the reward's currency
  const d = decide({ ...base, macBase: 96, macAfter: 97 });
  assert.equal(d.continueRounds, true);
  assert.equal(d.gapClosed, 0.25);
});

test('gains below the absolute floor stop the loop even at a high gap share', () => {
  const d = decide({ ...base, macBase: 99.5, macAfter: 99.7 });
  assert.equal(d.keepRound, true);
  assert.equal(d.continueRounds, false);
});

test('the round budget is spent exactly, with no wasted extra round', () => {
  // rounds=3 → this is round 4 of 5 → one more is allowed
  assert.equal(decide({ ...base, rounds: 3, macBase: 0, macAfter: 40 }).continueRounds, true);
  // rounds=4 → this is round 5 of 5 → keep it and stop (do not spend a 6th)
  const last = decide({ ...base, rounds: 4, macBase: 0, macAfter: 40 });
  assert.equal(last.keepRound, true);
  assert.equal(last.continueRounds, false);
  assert.match(last.verdict, /round budget 5 spent/);
});

test('a fully-improved file (MAC 100) does not loop forever', () => {
  const d = decide({ ...base, macBase: 100, macAfter: 100, improvedAny: true });
  assert.equal(d.continueRounds, false);
});

test('reaching a perfect score ends the loop immediately', () => {
  // with a METHOD as the unit this is the common case, and the extra round used to cost
  // an LLM call, a suite run and a PIT run to learn there was nothing left
  const d = decide({ ...base, macBase: 0, macAfter: 100 });
  assert.equal(d.keepRound, true);
  assert.equal(d.continueRounds, false);
  assert.equal(d.perfect, true);
  assert.match(d.verdict, /nothing left to improve/);
});

test('killing ONE mutant always counts as progress, whatever the floors say', () => {
  // 306 mutants at 88 % coverage: one kill moves MAC by 88/306 = 0.29, under the 0.5
  // default floor. Without clamping the floors to the measurement's granularity, the
  // one-mutant-per-round strategy would stop after its first success every time.
  const d = decide({ ...base, macBase: 0, macAfter: 0.29, totalMutants: 306, coverage: 88 });
  assert.equal(d.continueRounds, true);
  assert.ok(d.effMinGain <= 0.29, 'floor must not exceed what one mutant is worth');
  const unclamped = decide({ ...base, macBase: 0, macAfter: 0.29 });
  assert.equal(unclamped.continueRounds, false, 'without the clamp this is judged marginal');
});

test('a genuinely marginal round still stops when mutants are coarse', () => {
  // 4 mutants: one kill is worth 25 points, so a 0.29 gain really is noise
  const d = decide({ ...base, macBase: 50, macAfter: 50.29, totalMutants: 4, coverage: 100 });
  assert.equal(d.continueRounds, false);
});

test('the unit time budget ends the loop even while progress continues', () => {
  const d = decide({ ...base, macBase: 0, macAfter: 40, elapsedSec: 3000, budgetSec: 2400 });
  assert.equal(d.keepRound, true);
  assert.equal(d.continueRounds, false);
  assert.equal(d.outOfTime, true);
  assert.match(d.verdict, /time budget/);
});

test('thresholds are configurable', () => {
  const strict = decide({ ...base, macBase: 0, macAfter: 40, minGapFrac: 0.5 });
  assert.equal(strict.continueRounds, false);
  const loose = decide({ ...base, macBase: 0, macAfter: 0.38, minGapFrac: 0, minGain: 0 });
  assert.equal(loose.continueRounds, true);
});
