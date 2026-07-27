'use strict';
// PIT emits several DISTINCT mutants that share a mutator kind and a line, and the attempt
// register keyed them as one.
//
// `JSONObject#parseJSONObject` measured 14 mutants, 11 killed, 3 alive — and all three
// alive were `RemoveConditionalMutator_EQUAL_ELSE` at line 248 (two SURVIVED, one
// NO_COVERAGE; the line holds more than one conditional). One round attacked "the" mutant
// at that key, and every one of the three was then struck off. The unit settled at 78.57%
// reporting "no un-attempted survivors left — stop" with two survivors never attempted.
//
// Across the run this masked 37 survivors in 18 units, every one of them reported as
// nothing left to attack. PIT's <index> is the per-mutant identifier that separates them.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { attemptKey, eligible, exhausted } = require('../../select');

const at248 = (index) => ({
  mutator: 'RemoveConditionalMutator_EQUAL_ELSE', line: 248, status: 'SURVIVED', index,
});

test('two distinct mutants of one kind on one line are two keys, not one', () => {
  assert.notEqual(attemptKey(at248(11)), attemptKey(at248(14)));
});

test('attacking one of them leaves the others eligible', () => {
  const survived = [at248(11), at248(14), { ...at248(17), status: 'NO_COVERAGE' }];
  const left = eligible(survived, [attemptKey(at248(11))]);
  assert.equal(left.length, 2, 'two survivors were never attempted and must stay in the queue');
  assert.deepEqual(left.map((x) => x.index), [14, 17]);
});

test('and the unit is not declared exhausted while they remain', () => {
  const f = {
    lastSurvived: [at248(11), at248(14)],
    attemptedMutants: [attemptKey(at248(11))],
  };
  assert.equal(exhausted(f), false);
});

test('the same mutant is still recognised as attempted', () => {
  assert.equal(eligible([at248(11)], [attemptKey(at248(11))]).length, 0);
});

test('a key recorded before indexes existed still matches its whole line-and-kind group', () => {
  // Attempt registers persist across runs. A legacy `Mutator@248` entry cannot say WHICH
  // of the three it meant, so it must keep its old, broad meaning rather than silently
  // matching none of them and re-offering work already known to have failed.
  const survived = [at248(11), at248(14)];
  assert.equal(eligible(survived, ['RemoveConditionalMutator_EQUAL_ELSE@248']).length, 0);
});

test('a mutant PIT gave no index keeps the plain key', () => {
  const noIndex = { mutator: 'MathMutator', line: 90, status: 'SURVIVED' };
  assert.equal(attemptKey(noIndex), 'MathMutator@90');
});
