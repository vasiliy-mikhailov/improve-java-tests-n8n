'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { MEASURE_VERSION, stamp, restorable, normalizeUnitKey } = require('../../measure');

test('a measurement records the semantics that produced it', () => {
  const e = stamp({ coverageBefore: 80, mutationBefore: 40, macBefore: 32 });
  assert.equal(e.v, MEASURE_VERSION);
  assert.equal(e.coverageBefore, 80);
});

test('a measurement taken under older semantics is not restored', () => {
  // JSONObject#isRecordStyleAccessor was recorded at mutation 15.79% — a score computed
  // over toString()'s mutants as well. After the scoping fix the same method measures 0%.
  // Replaying the old number would hand the run a baseline it can only "improve" by luck.
  assert.equal(restorable({ coverageBefore: 74, mutationBefore: 15.79, v: MEASURE_VERSION - 1 }), false);
});

test('a measurement from before versioning existed is not restored', () => {
  assert.equal(restorable({ coverageBefore: 83.33, mutationBefore: 0, macBefore: 0, ts: 1785010802817 }), false);
});

test('a current measurement is restored', () => {
  assert.equal(restorable(stamp({ coverageBefore: 80 })), true);
});

test('nothing at all is not restorable', () => {
  assert.equal(restorable(null), false);
  assert.equal(restorable(undefined), false);
  assert.equal(restorable({}), false);
});

test('a unit key is the same key however the method name was escaped', () => {
  // the live ledger holds BOTH of these for one constructor, so its measurement is
  // recorded under one key and looked up under the other
  const escaped = 'src/main/java/org/json/Property.java::&lt;init&gt;';
  const plain = 'src/main/java/org/json/Property.java::<init>';
  assert.equal(normalizeUnitKey(escaped), plain);
  assert.equal(normalizeUnitKey(plain), plain);
  assert.equal(normalizeUnitKey(escaped), normalizeUnitKey(plain));
});

test('normalising an ordinary key changes nothing, and is idempotent', () => {
  const k = 'src/main/java/org/json/XML.java::parse';
  assert.equal(normalizeUnitKey(k), k);
  assert.equal(normalizeUnitKey(normalizeUnitKey(k)), k);
  assert.equal(normalizeUnitKey('&amp;&lt;&gt;&quot;'), '&<>"');
});

test('a file-level key from the old unit model is not a unit and is not restored', () => {
  // before the method-by-method model these were the keys; their numbers describe a
  // whole class, not the method the run now works on
  assert.equal(restorable({ coverageBefore: 83.33, v: MEASURE_VERSION }, 'src/main/java/org/json/XML.java'), false);
  assert.equal(restorable({ coverageBefore: 83.33, v: MEASURE_VERSION }, 'src/main/java/org/json/XML.java::parse'), true);
});

// ── replaying ledgers into a run ──────────────────────────────────────────
const { planReplay } = require('../../measure');

const UNITS = new Set([
  'src/main/java/org/json/XML.java::parse',
  'src/main/java/org/json/XML.java::toString',
  'src/main/java/org/json/Property.java::<init>',
]);
const has = (k) => UNITS.has(k);

test('a settled unit is replayed so the run does not redo it', () => {
  // The replay ran in /api/repo/prepare, which is BEFORE baseline coverage expands
  // classes into method units — so state.files held FILE keys and every unit-keyed
  // ledger entry missed. An already-improved, already-PR'd unit came back as a fresh
  // candidate and was re-measured, re-improved and re-PR'd.
  const plan = planReplay({ 'src/main/java/org/json/XML.java::parse': { state: 'improved' } }, {}, has);
  assert.deepEqual(plan.settle.map((x) => x.key), ['src/main/java/org/json/XML.java::parse']);
});

test('a measurement of a unit in scope is restored, one from old semantics is not', () => {
  const m = {
    'src/main/java/org/json/XML.java::toString': stamp({ coverageBefore: 80 }),
    'src/main/java/org/json/XML.java::parse': { coverageBefore: 88, v: MEASURE_VERSION - 1 },
  };
  const plan = planReplay({}, m, has);
  assert.deepEqual(plan.restore.map((x) => x.key), ['src/main/java/org/json/XML.java::toString']);
  assert.equal(plan.stale, 1);
});

test('a file-keyed entry from before the unit model never matches a unit', () => {
  const plan = planReplay({ 'src/main/java/org/json/XML.java': { state: 'improved' } }, {}, has);
  assert.deepEqual(plan.settle, []);
  assert.equal(plan.unknown, 1);
});

test('an entry for a unit outside this run scope is simply not applicable', () => {
  const plan = planReplay({ 'src/main/java/org/json/Gone.java::method': { state: 'improved' } }, {}, has);
  assert.deepEqual(plan.settle, []);
  assert.equal(plan.unknown, 1);
});

test('an escaped constructor key still finds its unit', () => {
  const plan = planReplay({ 'src/main/java/org/json/Property.java::&lt;init&gt;': { state: 'improved' } }, {}, has);
  assert.deepEqual(plan.settle.map((x) => x.key), ['src/main/java/org/json/Property.java::<init>']);
});

test("a settled unit's status is replayed but its stale metrics are not", () => {
  // planReplay gated the MEASUREMENT ledger on version and left the IMPROVEMENT ledger
  // ungated — and its records carry the same numbers. isRecordStyleAccessor's 15.79%
  // (three kills that all belonged to toString) was rejected through one door and
  // admitted through the other, into the run's headline mutation score.
  const improved = { 'src/main/java/org/json/XML.java::parse': { state: 'improved', metrics: { mutationBefore: 15.79, macBefore: 12.6 } } };
  const plan = planReplay(improved, {}, has);
  assert.equal(plan.settle.length, 1, 'the unit is still settled — it was really improved');
  assert.deepEqual(plan.settle[0].metrics, null, 'but numbers from older semantics are not restored');
});

test('metrics stamped with the current semantics are replayed', () => {
  const improved = { 'src/main/java/org/json/XML.java::parse': { state: 'improved', metrics: stamp({ mutationBefore: 70 }) } };
  const plan = planReplay(improved, {}, has);
  assert.equal(plan.settle[0].metrics.mutationBefore, 70);
});

test('a unit that once failed to measure is offered again, not blacklisted for ever', () => {
  const plan = planReplay({ 'src/main/java/org/json/XML.java::parse': { state: 'failed' } }, {}, has);
  assert.equal(plan.settle.length, 0, 'a failure measured nothing, so it settles nothing');
  assert.equal(plan.retryable, 1);
});

test('what the runs learned about reaching a unit survives a restart', () => {
  // The demotion for "proved unexecutable" needs two observed misses. That evidence lived
  // only in run state, so every restart forgot it and the unit went back to rank 1 to
  // burn the same three rounds again — on a run that restarts whenever anything ships.
  const m = { 'src/main/java/org/json/XML.java::parse': stamp({ coverageBefore: 0, everReached: false, missesEver: 3 }) };
  const plan = planReplay({}, m, has);
  assert.equal(plan.restore[0].entry.everReached, false);
  assert.equal(plan.restore[0].entry.missesEver, 3);
});

test('which mutants were already attacked survives a restart', () => {
  // Within a run the register is intact — but it lived only in run state, so a restart
  // forgot it and a unit could spend a fresh round on a mutant it had already failed to
  // kill. "Do not try to kill the same mutant twice" has to hold across runs too.
  const m = { 'src/main/java/org/json/XML.java::parse': stamp({ attemptedMutants: ['MathMutator@10', 'NullReturnVals@20'] }) };
  const plan = planReplay({}, m, has);
  assert.deepEqual(plan.restore[0].entry.attemptedMutants, ['MathMutator@10', 'NullReturnVals@20']);
});
