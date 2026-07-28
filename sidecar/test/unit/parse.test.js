'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { parseGeneratedTests, parseRepairedTests } = require('../../parse');

const plan = { targetPath: 'src/test/java/a/BMacMutTest.java', projectTestPath: 'src/test/java/a/BTest.java' };
const ok = (content) => ({ ok: true, json: { tests: [{ path: plan.targetPath, content }] } });
const body = 'package a;\n\npublic class BMacMutTest { @Test public void t() { assertEquals(2, x); } }';

test('a well-formed answer is accepted as-is', () => {
  const r = parseGeneratedTests(ok(body), plan);
  assert.equal(r.count, 1);
  assert.equal(r.tests[0].path, plan.targetPath);
});

test('a path outside src/test/java is replaced with the planned one', () => {
  const r = parseGeneratedTests({ ok: true, json: { tests: [{ path: '../../etc/passwd', content: body }] } }, plan);
  assert.equal(r.tests[0].path, plan.targetPath, 'never write outside the test tree');
});

test("the project's own test file is never overwritten", () => {
  const r = parseGeneratedTests({ ok: true, json: { tests: [{ path: plan.projectTestPath, content: body }] } }, plan);
  assert.equal(r.tests[0].path, plan.targetPath);
});

test('the public class is renamed to match the file, or javac refuses to compile', () => {
  const wrong = 'package a;\n\npublic class SomethingElse { @Test public void t() { assertEquals(2, x); } }';
  const r = parseGeneratedTests({ ok: true, json: { tests: [{ path: plan.targetPath, content: wrong }] } }, plan);
  assert.match(r.tests[0].content, /public class BMacMutTest/);
  assert.doesNotMatch(r.tests[0].content, /SomethingElse/);
});

test('only one test file is taken, however many are offered', () => {
  const r = parseGeneratedTests({ ok: true, json: { tests: [
    { path: plan.targetPath, content: body }, { path: 'src/test/java/a/Other.java', content: body },
  ] } }, plan);
  assert.equal(r.count, 1);
});

test('an empty answer is a legitimate "this mutation changes nothing"', () => {
  const r = parseGeneratedTests({ ok: true, json: { tests: [] } }, plan);
  assert.equal(r.count, 0);
  assert.deepEqual(r.tests, []);
});

test('a stub too short to be a test is rejected', () => {
  const r = parseGeneratedTests({ ok: true, json: { tests: [{ path: plan.targetPath, content: 'class X{}' }] } }, plan);
  assert.equal(r.count, 0);
});

test('a failed or unparseable model call yields nothing, not a crash', () => {
  assert.equal(parseGeneratedTests({ ok: false }, plan).count, 0);
  assert.equal(parseGeneratedTests({ ok: true, json: null }, plan).count, 0);
  assert.equal(parseGeneratedTests(undefined, plan).count, 0);
});

test('the chosen mutant is carried through for the kill check', () => {
  const withOffer = { ...plan, offered: [{ line: 3, mutator: 'MathMutator' }] };
  const r = parseGeneratedTests(ok(body), withOffer);
  assert.deepEqual(r.chosen, { line: 3, mutator: 'MathMutator' });
});

test('repaired tests keep the original paths', () => {
  const prev = { paths: [plan.targetPath] };
  const r = parseRepairedTests({ ok: true, json: { tests: [{ path: 'wherever/It.java', content: body }] } }, prev);
  assert.equal(r.tests[0].path, plan.targetPath);
});

test("no answer may name a test file other than the one this round plans to write", () => {
  // safePath only guarded plan.projectTestPath — the ONE existing test it knew about. Any
  // other real test in the tree ("src/test/java/org/json/StringBuilderWriterTest.java")
  // passed the check and was overwritten with generated content; if the suite then went
  // red, Delete Broken Tests removed the team's file from the repo entirely.
  const victim = 'src/test/java/org/json/StringBuilderWriterTest.java';
  const r = parseGeneratedTests({ ok: true, json: { tests: [{ path: victim, content: body }] } }, plan);
  assert.equal(r.tests[0].path, plan.targetPath);
  assert.notEqual(r.tests[0].path, victim);
});

test('the planned path is the only path, however plausible the alternative looks', () => {
  for (const p of ['src/test/java/a/BMacMutR2Test.java', 'src/test/java/a/Helper.java', plan.targetPath]) {
    const r = parseGeneratedTests({ ok: true, json: { tests: [{ path: p, content: body }] } }, plan);
    assert.equal(r.tests[0].path, plan.targetPath);
  }
});

// ── a batch round aims at many lines, so it aims at no single mutant ──────
// batchPrompt offers `{marker, line, method}`; the single-mutant prompt offers
// `{line, mutator, status}`. parse.js read offered[0] and built `chosen` from it either
// way, so a batch round produced chosen.mutator === undefined — which the workflow printed
// verbatim ("targeting undefined at line 78") and, worse, stored as targetMutant, where
// the kill check matches on mutator AND line and therefore could never match anything.
//
// A batch has no single target. Saying so is the honest answer; roundOutcome already
// returns null for a round with no target mutant.
test('a single-mutant round still reports the mutant it aimed at', () => {
  const r = parseGeneratedTests(
    { ok: true, json: { tests: [{ path: 'src/test/java/a/BMacMutTest.java', content: 'x'.repeat(40) }] } },
    { targetPath: 'src/test/java/a/BMacMutTest.java', offered: [{ line: 42, mutator: 'MathMutator', status: 'SURVIVED' }] });
  assert.deepEqual(r.chosen, { line: 42, mutator: 'MathMutator' });
});

test('a batch round reports no single target rather than an undefined one', () => {
  const r = parseGeneratedTests(
    { ok: true, json: { tests: [{ path: 'src/test/java/a/BMacMutTest.java', content: 'x'.repeat(40) }] } },
    { targetPath: 'src/test/java/a/BMacMutTest.java',
      offered: [{ marker: 'covers m:78', line: 78, method: 'm' }, { marker: 'covers m:90', line: 90, method: 'm' }] });
  assert.equal(r.chosen, null, 'no mutator field means this was never a single-mutant round');
});

test('an offer with no mutator is never dressed up as one', () => {
  const r = parseGeneratedTests(
    { ok: true, json: { tests: [{ path: 'p', content: 'x'.repeat(40) }] } },
    { targetPath: 'p', offered: [{ line: 5 }] });
  assert.equal(r.chosen, null);
});

test('no offers at all means no target', () => {
  const r = parseGeneratedTests({ ok: true, json: { tests: [] } }, { targetPath: 'p' });
  assert.equal(r.chosen, null);
});
