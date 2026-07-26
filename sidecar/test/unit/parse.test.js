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
