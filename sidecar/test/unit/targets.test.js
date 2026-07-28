'use strict';
// A different shape for a round: ask once per surviving LINE, measure once per class.
//
// The current loop pays two PIT runs per round per method-unit — 609 runs across 22
// classes on JSON-java, 43% of the run's wall-clock — and asks the model for one mutant at
// a time. Most of that is re-measurement of things that did not change.
//
// The alternative: derive a deterministic test name from each surviving mutant, skip every
// mutant whose name is already present in the test sources (a string check, no model call),
// ask for the rest, compile once, drop what does not build, and measure once. A mutant that
// survives that is left alone rather than retried.
//
// The dedup is worth having but it is not the prize: on org.json.XML, 96 surviving mutants
// collapse to 76 distinct method@line targets — 21%. The prize is 609 PIT runs becoming
// ~44.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { targetMarker, groupTargets, pendingTargets } = require('../../targets');

const m = (mutator, line, method, index = 0, status = 'SURVIVED') =>
  ({ mutator, line, method, index, status });

// ── the marker, not the name ──────────────────────────────────────────────
// The first version made the model name every test kill_<method>_line<N>. That is not how
// these repos name tests — java-dataloader has clearCacheOnError and disableCache,
// JSON-java has emptyStringCookieList and malFormedCookieListException — and a reviewer
// reading the PR sees the generated ones as machine output. It is also a lie the moment
// the source shifts a line.
//
// So the two jobs are separated: the model names the test the way the repo does, and a
// one-line comment carries the machine-readable link to the target. The rules already
// allow exactly one short comment per test, so the marker costs nothing extra.
test('a mutant maps to a stable marker naming its method and line', () => {
  const k = targetMarker(m('ConditionalsBoundaryMutator', 170, 'mustEscape'));
  assert.match(k, /mustEscape/);
  assert.match(k, /170/);
  assert.equal(k, targetMarker(m('RemoveConditionalMutator_EQUAL_ELSE', 170, 'mustEscape', 9)), 'stable across mutators');
});

test('every mutation of the SAME line shares one marker', () => {
  // > became >=, > became <, the branch was removed — one test that exercises the boundary
  // distinguishes all three, so asking three times is three wasted calls
  const a = targetMarker(m('ConditionalsBoundaryMutator', 170, 'mustEscape', 3));
  const b = targetMarker(m('RemoveConditionalMutator_EQUAL_ELSE', 170, 'mustEscape', 7));
  assert.equal(a, b);
});

test('different lines and methods stay distinct', () => {
  assert.notEqual(targetMarker(m('MathMutator', 170, 'a')), targetMarker(m('MathMutator', 171, 'a')));
  assert.notEqual(targetMarker(m('MathMutator', 170, 'a')), targetMarker(m('MathMutator', 170, 'b')));
});

test('constructors and lambdas produce a marker a comment can carry', () => {
  for (const meth of ['<init>', 'lambda$dispatchQueueBatch$2']) {
    const k = targetMarker(m('NullReturnValsMutator', 42, meth));
    assert.doesNotMatch(k, /[\r\n]/, 'a marker lives on one comment line');
    assert.ok(k.length > 0);
  }
});

// ── grouping ──────────────────────────────────────────────────────────────
test('survivors collapse to one target per line, carrying every mutation on it', () => {
  const g = groupTargets([
    m('ConditionalsBoundaryMutator', 170, 'mustEscape', 3),
    m('RemoveConditionalMutator_EQUAL_ELSE', 170, 'mustEscape', 7),
    m('MathMutator', 205, 'mustEscape', 1),
  ]);
  assert.equal(g.length, 2, '170 collapses, 205 is its own');
  const first = g.find((t) => t.line === 170);
  assert.equal(first.mutants.length, 2, 'the prompt still names both mutations');
  assert.equal(first.method, 'mustEscape');
});

test('a NO_COVERAGE line is grouped too — it still needs a test', () => {
  const g = groupTargets([m('NullReturnValsMutator', 12, 'x', 0, 'NO_COVERAGE')]);
  assert.equal(g.length, 1);
  assert.equal(g[0].mutants[0].status, 'NO_COVERAGE');
});

test('no survivors, no targets', () => {
  assert.deepEqual(groupTargets([]), []);
  assert.deepEqual(groupTargets(null), []);
});

// ── the fast filter: never pay the model for a test that exists ───────────
test('a target whose test is already written is not asked for again', () => {
  // 1000 mutants at ~100 tokens and 30 tokens/sec is hours of generation. A string check
  // over the test sources costs nothing. The test is found by its MARKER, so it can be
  // called whatever the repo's convention calls for.
  const targets = groupTargets([m('MathMutator', 170, 'mustEscape'), m('MathMutator', 205, 'mustEscape')]);
  const existing = `class XMustEscapeMacMutTest {
  @Test void escapesAmpersandAtBoundary() {
    // ${targetMarker(m('MathMutator', 170, 'mustEscape'))}
  }
}`;
  const pending = pendingTargets(targets, [existing]);
  assert.equal(pending.length, 1);
  assert.equal(pending[0].line, 205);
});

test('the filter reads every test source it is given', () => {
  const targets = groupTargets([m('MathMutator', 170, 'a'), m('MathMutator', 205, 'a')]);
  const s1 = `// ${targetMarker(m('MathMutator', 170, 'a'))}`;
  const s2 = `// ${targetMarker(m('MathMutator', 205, 'a'))}`;
  assert.equal(pendingTargets(targets, [s1, s2]).length, 0);
});

test('a marker for a nearby line does not count as covered', () => {
  // the marker for line 17 must not satisfy line 170
  const targets = groupTargets([m('MathMutator', 170, 'mustEscape')]);
  const near = `// ${targetMarker(m('MathMutator', 17, 'mustEscape'))}`;
  assert.equal(pendingTargets(targets, [near]).length, 1, 'line 17 is not line 170');
});

test('no sources means everything is pending', () => {
  const targets = groupTargets([m('MathMutator', 170, 'a')]);
  assert.equal(pendingTargets(targets, []).length, 1);
  assert.equal(pendingTargets(targets, null).length, 1);
});

// ── what the route has to assemble ────────────────────────────────────────
// The filter is only as good as the sources it reads. Reading the generated test file for
// THIS unit but not the project's own tests would re-ask for lines the project already
// covers; reading none at all makes the filter a no-op and the whole design pointless.
const { targetsFor } = require('../../targets');

test('targets come from the survivors, minus anything already named in the sources', () => {
  const survivors = [
    m('MathMutator', 170, 'mustEscape'),
    m('MathMutator', 205, 'mustEscape'),
    m('NullReturnValsMutator', 205, 'mustEscape', 2),
  ];
  const written = `// ${targetMarker(m('MathMutator', 170, 'mustEscape'))}`;
  const r = targetsFor(survivors, [written]);
  assert.equal(r.all.length, 2, '170 and 205');
  assert.equal(r.pending.length, 1);
  assert.equal(r.pending[0].line, 205);
  assert.equal(r.covered, 1, 'and it reports what the filter saved');
});

test('with nothing written yet, every target is pending', () => {
  const r = targetsFor([m('MathMutator', 170, 'a')], []);
  assert.equal(r.pending.length, 1);
  assert.equal(r.covered, 0);
});

test('no survivors is not an error, it is a finished class', () => {
  const r = targetsFor([], ['whatever']);
  assert.deepEqual(r.all, []);
  assert.deepEqual(r.pending, []);
  assert.equal(r.covered, 0);
});
