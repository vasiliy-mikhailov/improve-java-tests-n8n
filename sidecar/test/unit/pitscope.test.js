'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { scopeToMethod } = require('../../pit');

// shape parseReport returns
const report = () => ({
  file: 'src/main/java/org/json/JSONObject.java',
  fqcn: 'org.json.JSONObject',
  totalMutants: 4,
  killed: 1,
  score: 25,
  survived: [
    { mutator: 'MathMutator', line: 3005, method: 'toString', status: 'SURVIVED', difficulty: 0, detected: false },
    { mutator: 'BooleanTrueReturnValsMutator', line: 2072, method: 'isRecordStyleAccessor', status: 'NO_COVERAGE', difficulty: 3, detected: false },
    { mutator: 'RemoveConditionalMutator', line: 2071, method: 'isRecordStyleAccessor', status: 'NO_COVERAGE', difficulty: 5, detected: false },
  ],
  all: [
    { mutator: 'MathMutator', line: 3005, method: 'toString', detected: false },
    { mutator: 'BooleanTrueReturnValsMutator', line: 2072, method: 'isRecordStyleAccessor', detected: false },
    { mutator: 'RemoveConditionalMutator', line: 2071, method: 'isRecordStyleAccessor', detected: false },
    { mutator: 'NegateConditionalsMutator', line: 2077, method: 'isRecordStyleAccessor', detected: true },
  ],
  byMethod: { toString: { total: 1, killed: 0, survived: 1 }, isRecordStyleAccessor: { total: 3, killed: 1, survived: 2 } },
});

test('survivors from other methods never reach the unit', () => {
  // A MathMutator in toString() led this unit's queue because it was SURVIVED while the
  // unit's own mutants were NO_COVERAGE. A whole round was spent writing a toString test,
  // then scored against isRecordStyleAccessor — a guaranteed miss.
  const r = scopeToMethod(report(), 'isRecordStyleAccessor');
  assert.deepEqual(r.survived.map((m) => m.method), ['isRecordStyleAccessor', 'isRecordStyleAccessor']);
});

test('the score is recomputed over the method alone', () => {
  const r = scopeToMethod(report(), 'isRecordStyleAccessor');
  assert.equal(r.totalMutants, 3);
  assert.equal(r.killed, 1);
  assert.equal(r.score, 33.33, '1 of 3, not 1 of 4 — the unit is the method');
});

test('the full method list survives scoping — exclusions are built from it', () => {
  const r = scopeToMethod(report(), 'isRecordStyleAccessor');
  assert.deepEqual(Object.keys(r.byMethod).sort(), ['isRecordStyleAccessor', 'toString']);
});

test('overloads share a name and stay in the same unit', () => {
  const rep = report();
  // toString() as well as toString(int): one killed, one surviving. `survived` is always
  // a subset of `all`, so a new survivor goes into both.
  rep.all.push({ mutator: 'MathMutator', line: 2960, method: 'toString', detected: true });
  const extra = { mutator: 'VoidMethodCallMutator', line: 2971, method: 'toString', status: 'NO_COVERAGE', difficulty: 4, detected: false };
  rep.all.push(extra);
  rep.survived.push(extra);
  const r = scopeToMethod(rep, 'toString');
  assert.equal(r.totalMutants, 3, 'both toString(int) and toString() count');
  assert.equal(r.killed, 1);
  assert.equal(r.survived.length, 2);
});

test('a class-scoped run is left exactly as it is', () => {
  const rep = report();
  assert.deepEqual(scopeToMethod(rep, null), rep);
  assert.deepEqual(scopeToMethod(rep, undefined), rep);
});

test('a method PIT found nothing for scores 0 mutants, not 100%', () => {
  const r = scopeToMethod(report(), 'noSuchMethod');
  assert.equal(r.totalMutants, 0);
  assert.equal(r.survived.length, 0);
  assert.equal(r.score, 0, 'an unmutated method is not a perfect one');
  assert.equal(r.empty, true, 'and the caller must be able to tell');
});

test('constructors are addressable like any other method', () => {
  const rep = report();
  rep.all.push({ mutator: 'MathMutator', line: 100, method: '<init>', detected: false });
  rep.survived.push({ mutator: 'MathMutator', line: 100, method: '<init>', status: 'SURVIVED', difficulty: 0, detected: false });
  const r = scopeToMethod(rep, '<init>');
  assert.equal(r.totalMutants, 1);
  assert.equal(r.survived.length, 1);
});
