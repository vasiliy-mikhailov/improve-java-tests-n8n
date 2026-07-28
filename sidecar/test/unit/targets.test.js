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
const { targetName, groupTargets, pendingTargets } = require('../../targets');

const m = (mutator, line, method, index = 0, status = 'SURVIVED') =>
  ({ mutator, line, method, index, status });

// ── naming ────────────────────────────────────────────────────────────────
test('a mutant maps to a deterministic, javac-legal test name', () => {
  const n = targetName(m('ConditionalsBoundaryMutator', 170, 'mustEscape'));
  assert.match(n, /^[a-zA-Z_][a-zA-Z0-9_]*$/, 'must be a Java identifier');
  assert.match(n, /mustEscape/);
  assert.match(n, /170/);
  assert.equal(n, targetName(m('ConditionalsBoundaryMutator', 170, 'mustEscape')), 'stable');
});

test('every mutation of the SAME line shares one name', () => {
  // > became >=, > became <, the branch was removed — one test that exercises the boundary
  // distinguishes all three, so asking three times is three wasted calls
  const a = targetName(m('ConditionalsBoundaryMutator', 170, 'mustEscape', 3));
  const b = targetName(m('RemoveConditionalMutator_EQUAL_ELSE', 170, 'mustEscape', 7));
  const c = targetName(m('NegateConditionalsMutator', 170, 'mustEscape', 9));
  assert.equal(a, b);
  assert.equal(b, c);
});

test('different lines and different methods stay distinct', () => {
  assert.notEqual(targetName(m('MathMutator', 170, 'a')), targetName(m('MathMutator', 171, 'a')));
  assert.notEqual(targetName(m('MathMutator', 170, 'a')), targetName(m('MathMutator', 170, 'b')));
});

test('a constructor gets a legal name, not <init>', () => {
  const n = targetName(m('NullReturnValsMutator', 42, '<init>'));
  assert.match(n, /^[a-zA-Z_][a-zA-Z0-9_]*$/);
  assert.doesNotMatch(n, /[<>]/);
});

test('a lambda gets a legal name too', () => {
  // PIT reports synthetic methods as lambda$dispatchQueueBatch$2
  const n = targetName(m('VoidMethodCallMutator', 337, 'lambda$dispatchQueueBatch$2'));
  assert.match(n, /^[a-zA-Z_][a-zA-Z0-9_]*$/);
  assert.doesNotMatch(n, /\$/);
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
  // over the test sources costs nothing.
  const targets = groupTargets([m('MathMutator', 170, 'mustEscape'), m('MathMutator', 205, 'mustEscape')]);
  const existing = `class XMustEscapeMacMutTest {\n  @Test void ${targetName(m('MathMutator', 170, 'mustEscape'))}() {}\n}`;
  const pending = pendingTargets(targets, [existing]);
  assert.equal(pending.length, 1);
  assert.equal(pending[0].line, 205);
});

test('the filter reads every test source it is given', () => {
  const targets = groupTargets([m('MathMutator', 170, 'a'), m('MathMutator', 205, 'a')]);
  const s1 = `void ${targetName(m('MathMutator', 170, 'a'))}() {}`;
  const s2 = `void ${targetName(m('MathMutator', 205, 'a'))}() {}`;
  assert.equal(pendingTargets(targets, [s1, s2]).length, 0);
});

test('a name that merely appears as a substring does not count as covered', () => {
  // `void killMustEscapeLine17() {}` must not satisfy the target for line 170
  const targets = groupTargets([m('MathMutator', 170, 'mustEscape')]);
  const near = `void ${targetName(m('MathMutator', 17, 'mustEscape'))}() {}`;
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
  const written = `void ${targetName(m('MathMutator', 170, 'mustEscape'))}() {}`;
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
