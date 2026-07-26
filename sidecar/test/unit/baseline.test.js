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

test('a covered method PIT could not run tests for is unmeasured, not a zero', () => {
  // `noTests` alone does not decide it — pit.js sets `unmeasured` only when JaCoCo saw
  // coverage, which is what makes "no tests ran" a broken glob rather than an untested
  // method. This fixture originally omitted the flag and so described neither case.
  const r = classifyBaseline({ totalMutants: null, noTests: true, unmeasured: true }, 3);
  assert.equal(r.kind, 'unmeasured');
  assert.match(r.reason, /no tests/i);
});

test('an unmeasured unit is never recorded as a measurement', () => {
  assert.equal(classifyBaseline({ totalMutants: null }, 3).recordable, false);
  assert.equal(classifyBaseline({ totalMutants: 0 }, 3).recordable, true);
});

// ── what a missed round decides ───────────────────────────────────────────
test('the next round runs while misses remain', () => {
  const r = missOutcome({ consecutiveMisses: 1, maxMisses: 3, survivorsLeft: 12 });
  assert.equal(r.continueRounds, true);
});

test('the miss cap ends the unit', () => {
  assert.equal(missOutcome({ consecutiveMisses: 3, maxMisses: 3, survivorsLeft: 12 }).continueRounds, false);
});

test('the count is read, never incremented here — verify already did that', () => {
  // both verify and /api/round/miss incremented, so every missed round counted twice:
  // the log said "miss 2/3" and then "4 in a row — stop", ending units at half the
  // budget the operator configured
  assert.equal(missOutcome({ consecutiveMisses: 2, maxMisses: 3, survivorsLeft: 12 }).consecutiveMisses, 2);
});

test('a miss with nothing left to attack ends the unit whatever the count says', () => {
  assert.equal(missOutcome({ consecutiveMisses: 1, maxMisses: 3, survivorsLeft: 0 }).continueRounds, false);
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

test('no tests ran AND no coverage is an untested method, not a failure to measure', () => {
  // pit.js separates these deliberately: coverage>0 with no tests means our targetTests
  // glob is broken (UNMEASURED); coverage 0 means nothing tests the method yet, which is
  // an ordinary starting point and precisely what the coverage phase is for. Collapsing
  // them marked every untested method `failed`, and ten failures end a run with "the repo
  // or its toolchain is the problem" — on exactly the under-tested repos this tool exists
  // to improve.
  const r = classifyBaseline({ totalMutants: null, noTests: true, score: 0 }, 3);
  assert.equal(r.kind, 'untested');
  assert.equal(r.recordable, false, 'nothing was measured, so nothing is recorded');
  assert.match(r.reason, /no test/i);
});

test('no tests ran but the method IS covered is a broken measurement', () => {
  const r = classifyBaseline({ totalMutants: null, noTests: true, unmeasured: true, score: null }, 3);
  assert.equal(r.kind, 'unmeasured');
});

test('an untested method is still worked on — it is not settled', () => {
  const r = classifyBaseline({ totalMutants: null, noTests: true, score: 0 }, 3);
  assert.notEqual(r.kind, 'no_mutants');
  assert.notEqual(r.kind, 'unmeasured');
});

test("a new attempt at a unit inherits no target from the previous one", () => {
  // The stamp is the round number, and a fresh attempt restarts at round 1 — so attempt
  // 3 round 1 matched the target left by attempt 2 round 1. The log then announced
  // "targeted mutant NullReturnVals at line 416" for a round whose prompt had skipped,
  // and mutatorStats recorded an attempt nobody made.
  assert.equal(targetForRound({ mutator: 'NullReturnValsMutator', line: 416, round: 1, attempt: 2 }, 1, 3), null);
  assert.deepEqual(targetForRound({ mutator: 'NullReturnValsMutator', line: 416, round: 1, attempt: 3 }, 1, 3),
    { mutator: 'NullReturnValsMutator', line: 416, round: 1, attempt: 3 });
});

test('a round that had nothing to ask for ends the unit, it does not count as a miss', () => {
  // XML#isStringAllWhiteSpace is private with no public route, so the mutation prompt
  // correctly skips — but each skip was counted as a MISS, so the unit burned three
  // rounds, settled, and was re-picked twice more: nine empty rounds, each paying for a
  // PIT run and a coverage run. A miss means a test was written and failed to kill;
  // nothing was written here.
  const r = missOutcome({ consecutiveMisses: 1, maxMisses: 3, survivorsLeft: 5, nothingToAsk: true });
  assert.equal(r.continueRounds, false);
  assert.equal(r.settle, true);
  assert.match(r.verdict, /nothing to ask|no work/i);
});

test('an ordinary miss is unaffected', () => {
  const r = missOutcome({ consecutiveMisses: 1, maxMisses: 3, survivorsLeft: 5 });
  assert.equal(r.continueRounds, true);
  assert.notEqual(r.settle, true);
});
