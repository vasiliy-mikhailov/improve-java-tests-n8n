'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { staleCoverageArtifacts } = require('../../coverage');

test('the execution data file is cleared before a run, or coverage accumulates', () => {
  // JaCoCo's Maven plugin APPENDS to jacoco.exec, and target/ is gitignored so
  // `git clean -fd` leaves it. On JSON-java the file reached 10 MB, and JSONArray#getNumber
  // read 0% covered in one run and 100% in the next — the 100% came from a generated test
  // that had already been deleted. Every number derived from it was wrong.
  const paths = staleCoverageArtifacts(['.']);
  assert.ok(paths.includes('target/jacoco.exec'), 'Maven exec data');
  assert.ok(paths.includes('build/jacoco/test.exec'), 'Gradle exec data');
});

test('the XML report is cleared too — a stale one reads exactly like a fresh one', () => {
  const paths = staleCoverageArtifacts(['.']);
  assert.ok(paths.includes('target/site/jacoco/jacoco.xml'));
  assert.ok(paths.some((p) => /build\/reports\/jacoco\/.*\.xml$/.test(p)));
});

test('every module is cleared, not just the root', () => {
  const paths = staleCoverageArtifacts(['.', 'core', 'json-path']);
  assert.ok(paths.includes('core/target/jacoco.exec'));
  assert.ok(paths.includes('json-path/target/jacoco.exec'));
  assert.ok(paths.includes('target/jacoco.exec'), 'the root module keeps unprefixed paths');
});

test('nothing outside the repo is ever named for deletion', () => {
  for (const p of staleCoverageArtifacts(['.', '../evil', '/etc'])) {
    assert.doesNotMatch(p, /(^|\/)\.\.(\/|$)/, `${p} escapes the repo`);
    assert.doesNotMatch(p, /^\//, `${p} is absolute`);
  }
});

test('a module list with duplicates yields each path once', () => {
  const paths = staleCoverageArtifacts(['.', '.', 'core', 'core']);
  assert.equal(new Set(paths).size, paths.length);
});

test('no modules means the root module', () => {
  assert.ok(staleCoverageArtifacts([]).includes('target/jacoco.exec'));
  assert.ok(staleCoverageArtifacts().includes('target/jacoco.exec'));
});
