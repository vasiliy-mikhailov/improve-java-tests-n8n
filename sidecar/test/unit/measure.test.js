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
