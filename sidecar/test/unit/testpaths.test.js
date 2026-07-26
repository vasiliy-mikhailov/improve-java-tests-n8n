'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { generatedTestPaths, existingTestCandidates, SUREFIRE_INCLUDED } = require('../../testpaths');

const SRC = 'src/main/java/org/json/XML.java';
const MODULE = 'core/src/main/java/com/acme/deep/pkg/Thing.java';

test('generated tests land in src/test/java beside the class, package intact', () => {
  const g = generatedTestPaths(SRC, 1);
  assert.equal(g.covPath, 'src/test/java/org/json/XMLMacCovTest.java');
  assert.equal(g.mutPath, 'src/test/java/org/json/XMLMacMutTest.java');
});

test("a module's path prefix survives the mapping", () => {
  const g = generatedTestPaths(MODULE, 1);
  assert.equal(g.mutPath, 'core/src/test/java/com/acme/deep/pkg/ThingMacMutTest.java');
});

test('every generated name is one Surefire actually runs', () => {
  // A class named PricingMacTestCov compiled, was silently skipped by Surefire, left
  // JaCoCo blind, and PIT — which uses its own glob — then reported 100%. The suite was
  // green and every number was a lie.
  for (const round of [1, 2, 3, 11]) {
    for (const p of Object.values(generatedTestPaths(SRC, round))) {
      const cls = p.split('/').pop().replace(/\.java$/, '');
      assert.ok(SUREFIRE_INCLUDED.some((re) => re.test(cls)), `${cls} is not picked up by Surefire`);
    }
  }
});

test('each round gets its own class — Java forbids two public classes of one name', () => {
  const names = new Set();
  for (const round of [1, 2, 3, 4, 5]) {
    const g = generatedTestPaths(SRC, round);
    assert.notEqual(g.covPath, g.mutPath, 'coverage and mutation classes must differ');
    for (const p of [g.covPath, g.mutPath]) {
      assert.ok(!names.has(p), `${p} collides with an earlier round`);
      names.add(p);
    }
  }
  assert.equal(names.size, 10);
});

test("the project's own test is looked for under every convention it might use", () => {
  const c = existingTestCandidates(SRC);
  assert.deepEqual(c, [
    'src/test/java/org/json/XMLTest.java',
    'src/test/java/org/json/XMLTests.java',
    'src/test/java/org/json/TestXML.java',
    'src/test/java/org/json/XMLTestCase.java',
  ]);
});

test('a source path outside src/main/java still yields a test path under src/test/java', () => {
  // some repos keep sources in java/ or src/ — a generated path must never be written
  // next to production code
  for (const src of ['src/java/org/json/XML.java', 'java/org/json/XML.java', 'XML.java']) {
    const g = generatedTestPaths(src, 1);
    assert.match(g.mutPath, /(^|\/)src\/test\/java\//, `${src} → ${g.mutPath}`);
    assert.match(g.mutPath, /XMLMacMutTest\.java$/);
  }
});
