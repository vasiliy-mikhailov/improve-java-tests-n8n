'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { rankSurvivors, eligible, penalty, rankUnits } = require('../../select');

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

// ── which unit to work on next ────────────────────────────────────────────
const u = (over) => ({ path: 'F.java::m', method: 'm', mac: 0, executableLines: 10, reach: 'public', ...over });

test('the weakest unit comes first — that is where the gap is', () => {
  const r = rankUnits([u({ path: 'a', mac: 40 }), u({ path: 'b', mac: 5 }), u({ path: 'c', mac: 20 })]);
  assert.deepEqual(r.units.map((x) => x.path), ['b', 'c', 'a']);
});

test('a unit no public path reaches is dropped, not queued', () => {
  // the model is told to write a test for it, correctly refuses, the round changes
  // nothing, and the unit is abandoned after 3 misses — four PIT runs to learn what the
  // call graph already said.
  const r = rankUnits([u({ path: 'live', reach: 'route' }), u({ path: 'dead', reach: 'none' })]);
  assert.deepEqual(r.units.map((x) => x.path), ['live']);
  assert.equal(r.unreachable, 1);
});

test('at equal weakness, a method a test can call outright beats one behind private hops', () => {
  const r = rankUnits([u({ path: 'hidden', reach: 'route' }), u({ path: 'callable', reach: 'public' })]);
  assert.deepEqual(r.units.map((x) => x.path), ['callable', 'hidden']);
});

test('constructors still sort last among equals', () => {
  const r = rankUnits([u({ path: 'ctor', method: '<init>' }), u({ path: 'real', method: 'compute' })]);
  assert.deepEqual(r.units.map((x) => x.path), ['real', 'ctor']);
});

test('among equals, the unit with the most code to mutate goes first', () => {
  const r = rankUnits([u({ path: 'small', executableLines: 3 }), u({ path: 'big', executableLines: 40 })]);
  assert.deepEqual(r.units.map((x) => x.path), ['big', 'small']);
});

test('a unit not yet measured is ranked on what is known, not treated as perfect', () => {
  const r = rankUnits([u({ path: 'measured', mac: 50 }), u({ path: 'unknown', mac: null, coverage: 0 })]);
  assert.equal(r.units[0].path, 'unknown');
});

test('ranking is total: the same input always yields the same order', () => {
  const list = [u({ path: 'x' }), u({ path: 'y' }), u({ path: 'z' })];
  assert.deepEqual(rankUnits(list).units.map((x) => x.path), rankUnits(list.slice().reverse()).units.map((x) => x.path));
});
