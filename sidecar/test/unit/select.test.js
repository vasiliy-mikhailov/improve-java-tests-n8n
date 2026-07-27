'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { rankSurvivors, eligible, penalty, rankUnits, exhausted } = require('../../select');

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

test('a unit with no known route sinks to the bottom of the queue', () => {
  // It used to be DROPPED, to save the four PIT runs a genuinely dead private method
  // costs. That traded a bounded cost for an unbounded one: reachability is regex over
  // Java source, and every construct it misreads deleted real, testable work from the run.
  // Last place keeps the saving where the analysis is right and costs only ordering where
  // it is wrong.
  const r = rankUnits([u({ path: 'live', reach: 'route' }), u({ path: 'dead', reach: 'none' })]);
  assert.deepEqual(r.units.map((x) => x.path), ['live', 'dead']);
  assert.equal(r.unreachable, 0);
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

// ── which units count as "targeted" in the headline ───────────────────────
const { isTargeted } = require('../../select');

test('a unit with no mutation surface is not a targeted improvement', () => {
  // the dashboard reported avg MAC after = 13.33 while its one improved unit stood at
  // 66.67: four units settled as "no mutants" were averaged in at 0, understating the
  // work by a factor of five
  assert.equal(isTargeted({ macBefore: 0, status: 'no_mutants' }), false);
  assert.equal(isTargeted({ macBefore: 0, status: 'improved' }), true);
});

test('a unit that was never measured is not counted either way', () => {
  assert.equal(isTargeted({ macBefore: null, status: 'candidate' }), false);
  assert.equal(isTargeted({ status: 'candidate' }), false);
});

test('a measured unit that failed to improve still counts — it was really attempted', () => {
  assert.equal(isTargeted({ macBefore: 40, status: 'no_improvement' }), true);
});

test('a unit the analysis cannot find a route to is ranked last, never dropped', () => {
  // Dropping on the strength of a regex over Java source deletes real work silently:
  // multi-line signatures, callers inside inner classes, `this::foo` method references
  // and overloads all defeated it. Ranking it last keeps the benefit and costs at most
  // some rounds at the very end of a run.
  const r = rankUnits([u({ path: 'unsure', reach: 'none' }), u({ path: 'callable', reach: 'public' })]);
  assert.deepEqual(r.units.map((x) => x.path), ['callable', 'unsure']);
  assert.equal(r.unreachable, 0, 'nothing is discarded');
});

test('behind a private chain still beats no known route at all', () => {
  const r = rankUnits([u({ path: 'none', reach: 'none' }), u({ path: 'route', reach: 'route' })]);
  assert.deepEqual(r.units.map((x) => x.path), ['route', 'none']);
});

test('a unit repeatedly proven unexecutable sinks, on evidence rather than guesswork', () => {
  // JSONObject#isRecordStyleAccessor is guarded by `if (isRecordType && ...)`: reaching it
  // needs a real Java record, which a test compiled at this project's source level cannot
  // declare. Static analysis sees a valid route; only the runs show the truth — three
  // attempts, coverage never off zero. It keeps winning rank 1 on 14 mutants and 0 MAC,
  // burning three rounds each time it is picked.
  const r = rankUnits([
    u({ path: 'proven-dead', reach: 'route', misses: 3, everReached: false }),
    u({ path: 'ordinary', reach: 'route' }),
  ]);
  assert.deepEqual(r.units.map((x) => x.path), ['ordinary', 'proven-dead']);
});

test('one failed attempt is not proof — only a repeated one', () => {
  const r = rankUnits([
    u({ path: 'unlucky', reach: 'public', misses: 1, everReached: false }),
    u({ path: 'ordinary', reach: 'route' }),
  ]);
  assert.deepEqual(r.units.map((x) => x.path), ['unlucky', 'ordinary'], 'still ahead: it is directly callable');
});

test('a unit that HAS been reached is never demoted for missing', () => {
  const r = rankUnits([
    u({ path: 'reached', reach: 'route', misses: 5, everReached: true }),
    u({ path: 'untried', reach: 'none' }),
  ]);
  assert.deepEqual(r.units.map((x) => x.path), ['reached', 'untried']);
});

test('a unit whose every survivor has been attempted is exhausted, not re-picked', () => {
  // getRawType: 3 survivors, all attempted, so the round targets nothing and is discarded
  // — then it is picked again, and again, each time costing a PIT run and a coverage run
  // with no model call and no possible outcome.
  const survivors = [{ mutator: 'NullReturnValsMutator', line: 3414 }, { mutator: 'RemoveConditionalMutator', line: 3413 }];
  const attempted = ['NullReturnValsMutator@3414', 'RemoveConditionalMutator@3413'];
  assert.equal(exhausted({ lastSurvived: survivors, attemptedMutants: attempted }), true);
});

test('a unit with an un-attempted survivor is not exhausted', () => {
  const survivors = [{ mutator: 'NullReturnValsMutator', line: 3414 }, { mutator: 'MathMutator', line: 99 }];
  assert.equal(exhausted({ lastSurvived: survivors, attemptedMutants: ['NullReturnValsMutator@3414'] }), false);
});

test('a unit that was never measured is not exhausted — nothing is known about it', () => {
  assert.equal(exhausted({}), false);
  assert.equal(exhausted({ lastSurvived: null, attemptedMutants: [] }), false);
});

test('a unit measured as having no survivors at all is exhausted', () => {
  assert.equal(exhausted({ lastSurvived: [], attemptedMutants: [] }), true);
});

// ── what a kill is WORTH, not just how easily it comes ────────────────────
// Across 476 targeted mutants the pipeline killed 77, and 63% of those kills came from
// the two cheapest kinds: VoidMethodCall (58% kill rate) and NullReturnVals (26%). The
// mutators that pin actual logic were barely touched — RemoveConditional 4%,
// ConditionalsBoundary 14%, Math 11%.
//
// That is not an accident, it is the ranking. penalty() rewards a kind for its observed
// kill RATE, so the easiest kinds are attacked first and the hardest last. A NullReturnVals
// kill can be bought with assertNotNull — and 48 assertNotNull calls are exactly what the
// generated tests contain. The bonus should go to kinds whose kills prove something.

test('a kind that keeps dying to a weak assertion earns no ranking bonus', () => {
  // returns null → assertNotNull kills it and pins nothing else
  assert.equal(penalty({ tried: 10, killed: 9 }, 'NullReturnValsMutator'), 0);
  assert.equal(penalty({ tried: 10, killed: 9 }, 'EmptyObjectReturnValsMutator'), 0);
});

test('a kind whose kill pins real behaviour still earns one', () => {
  assert.equal(penalty({ tried: 10, killed: 9 }, 'ConditionalsBoundaryMutator'), -1);
  assert.equal(penalty({ tried: 10, killed: 9 }, 'MathMutator'), -1);
  assert.equal(penalty({ tried: 10, killed: 9 }, 'RemoveConditionalMutator_EQUAL_ELSE'), -1);
});

test('a kind that never dies is still demoted whatever it would prove', () => {
  // the original reason penalty exists: stop spending rounds on a kind nothing kills
  assert.equal(penalty({ tried: 6, killed: 0 }, 'ConditionalsBoundaryMutator'), 2);
  assert.equal(penalty({ tried: 6, killed: 0 }, 'NullReturnValsMutator'), 2);
});

test('a mostly-missing kind is demoted, shallow or deep', () => {
  assert.equal(penalty({ tried: 10, killed: 2 }, 'MathMutator'), 1);
  assert.equal(penalty({ tried: 10, killed: 2 }, 'NullReturnValsMutator'), 1);
});

test('one attempt is still not evidence', () => {
  assert.equal(penalty({ tried: 1, killed: 0 }, 'MathMutator'), 0);
  assert.equal(penalty(undefined, 'MathMutator'), 0);
});
