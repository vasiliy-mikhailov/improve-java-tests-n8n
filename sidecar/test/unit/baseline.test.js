'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { classifyBaseline } = require('../../rounds');
const { missOutcome } = require('../../rounds');

test('a method with too little mutation surface is settled, not worked on', () => {
  assert.equal(classifyBaseline({ totalMutants: 1 }, 3).kind, 'no_mutants');
  assert.equal(classifyBaseline({ totalMutants: 0 }, 3).kind, 'no_mutants');
});

test('a method with enough mutants is improvable', () => {
  assert.equal(classifyBaseline({ totalMutants: 14 }, 3).kind, 'improvable');
});

test('a result PIT could not measure is never announced as "0 mutants"', () => {
  // runPit returns totalMutants null when it produced no usable result. `?? 0` turned
  // that into a confident "0 mutants (min 3) — no meaningful mutation surface", wrote
  // mutationBefore into the ledger and settled the unit for good.
  const r = classifyBaseline({ totalMutants: null }, 3);
  assert.equal(r.kind, 'unmeasured');
  assert.match(r.reason, /not measured|unmeasured/i);
});

test('a run where no test executed is unmeasured, not a zero', () => {
  const r = classifyBaseline({ totalMutants: null, noTests: true }, 3);
  assert.equal(r.kind, 'unmeasured');
  assert.match(r.reason, /no tests/i);
});

test('an unmeasured unit is never recorded as a measurement', () => {
  assert.equal(classifyBaseline({ totalMutants: null }, 3).recordable, false);
  assert.equal(classifyBaseline({ totalMutants: 0 }, 3).recordable, true);
});

// ── what a missed round decides ───────────────────────────────────────────
test('a miss counts, and the next round runs while misses remain', () => {
  const r = missOutcome({ consecutiveMisses: 0, maxMisses: 3, survivorsLeft: 12 });
  assert.equal(r.consecutiveMisses, 1);
  assert.equal(r.continueRounds, true);
});

test('the miss cap ends the unit', () => {
  const r = missOutcome({ consecutiveMisses: 2, maxMisses: 3, survivorsLeft: 12 });
  assert.equal(r.consecutiveMisses, 3);
  assert.equal(r.continueRounds, false);
});

test('a miss with nothing left to attack ends the unit whatever the count says', () => {
  assert.equal(missOutcome({ consecutiveMisses: 0, maxMisses: 3, survivorsLeft: 0 }).continueRounds, false);
});

test('the decision is made here, never echoed from the last round', () => {
  // /api/round/miss returned f.continueRounds — the PREVIOUS round's answer. When verify
  // failed to measure, that stale `true` sent the workflow back for another round with
  // nothing advancing: the run span forever, one suite + JaCoCo + PIT per cycle.
  const stale = { continueRounds: true };
  const r = missOutcome({ consecutiveMisses: 5, maxMisses: 3, survivorsLeft: 99, previous: stale });
  assert.equal(r.continueRounds, false);
});

test('an uncapped miss budget still stops when the work runs out', () => {
  assert.equal(missOutcome({ consecutiveMisses: 99, maxMisses: 0, survivorsLeft: 5 }).continueRounds, true);
  assert.equal(missOutcome({ consecutiveMisses: 99, maxMisses: 0, survivorsLeft: 0 }).continueRounds, false);
});

test('an unknown survivor count is not treated as "nothing left"', () => {
  assert.equal(missOutcome({ consecutiveMisses: 0, maxMisses: 3, survivorsLeft: null }).continueRounds, true);
});

// ── crediting a round for what it actually did ────────────────────────────
const { targetForRound } = require('../../rounds');

test('a mutant targeted in THIS round is the one the round is judged on', () => {
  const tm = { mutator: 'MathMutator', line: 120, round: 4 };
  assert.deepEqual(targetForRound(tm, 4), tm);
});

test('a target left over from an earlier round is not this round\'s achievement', () => {
  // when the mutation phase skipped, verify still read f.targetMutant from the previous
  // round, found the mutant already dead and announced "targeted mutant MathMutator at
  // line 120: KILLED" for a round that attacked nothing and wrote nothing — and counted
  // a second kill for that mutator in the ranking stats.
  assert.equal(targetForRound({ mutator: 'MathMutator', line: 120, round: 3 }, 4), null);
});

test('an unstamped target is not attributed to any round', () => {
  assert.equal(targetForRound({ mutator: 'MathMutator', line: 120 }, 4), null);
  assert.equal(targetForRound(null, 4), null);
});
