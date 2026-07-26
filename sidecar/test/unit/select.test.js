'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { rankSurvivors, eligible, penalty } = require('../../select');

const m = (mutator, difficulty, line, extra = {}) => ({ mutator, difficulty, line, status: 'SURVIVED', ...extra });
const order = (list, stats) => rankSurvivors(list, stats).map((x) => `${x.mutator}@${x.line}`);

test('with no evidence, ranking follows PIT difficulty then line', () => {
  const list = [m('RemoveConditional', 2, 40), m('Math', 0, 90), m('NegateConditionals', 1, 10)];
  assert.deepEqual(order(list, {}), ['Math@90', 'NegateConditionals@10', 'RemoveConditional@40']);
});

test('a mutator kind attacked twice with no kills goes last', () => {
  const list = [m('RemoveConditional', 2, 40), m('Math', 0, 90)];
  const stats = { Math: { tried: 2, killed: 0 } };
  assert.deepEqual(order(list, stats), ['RemoveConditional@40', 'Math@90']);
});

test('one attempt is not evidence — a single miss must not demote a kind', () => {
  const stats = { Math: { tried: 1, killed: 0 } };
  assert.equal(penalty(stats.Math), 0);
});

test('a kind that keeps missing is demoted even after an early lucky kill', () => {
  // the run that motivated this: ConditionalsBoundary killed once at line 73, then
  // missed at 164, 191 and 234 — and stayed top-ranked the whole time, because
  // "killed > 0" immunised it permanently.
  const cb = { tried: 4, killed: 1 };
  const fresh = { tried: 2, killed: 1 };
  assert.ok(penalty(cb) > 0, 'a 25% kill rate over 4 tries is evidence of a bad bet');
  assert.equal(penalty(fresh), 0, 'a 50% kill rate is still worth trying');
  const list = [m('ConditionalsBoundary', 0, 164), m('NegateConditionals', 1, 10)];
  assert.deepEqual(order(list, { ConditionalsBoundary: cb }), ['NegateConditionals@10', 'ConditionalsBoundary@164']);
});

test('a productive kind stays in front of an untried one', () => {
  const list = [m('RemoveConditional', 2, 5), m('Math', 2, 90)];
  assert.deepEqual(order(list, { Math: { tried: 3, killed: 3 } }), ['Math@90', 'RemoveConditional@5']);
});

test('never demoted below the never-killed kinds, however good the rest look', () => {
  const list = [m('VoidMethodCall', 0, 5), m('Math', 2, 90)];
  const stats = { VoidMethodCall: { tried: 5, killed: 0 }, Math: { tried: 4, killed: 1 } };
  assert.deepEqual(order(list, stats), ['Math@90', 'VoidMethodCall@5'], 'zero kills is worse than a low rate');
});

test('ranking is deterministic and does not mutate its input', () => {
  const list = [m('Math', 1, 90), m('Math', 1, 10)];
  const copy = list.slice();
  assert.deepEqual(order(list, {}), ['Math@10', 'Math@90']);
  assert.deepEqual(list, copy);
});

test('an already-attempted mutant is never offered again', () => {
  const list = [m('Math', 0, 90), m('NegateConditionals', 1, 10)];
  assert.deepEqual(eligible(list, ['Math@90']).map((x) => x.line), [10]);
  assert.deepEqual(eligible(list, []).length, 2);
  assert.deepEqual(eligible(list, ['Math@90', 'NegateConditionals@10']), []);
});

test('the attempt key pins mutator AND line — same kind elsewhere is fair game', () => {
  const list = [m('Math', 0, 90), m('Math', 0, 91)];
  assert.deepEqual(eligible(list, ['Math@90']).map((x) => x.line), [91]);
});
