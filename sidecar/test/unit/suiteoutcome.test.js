'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { suiteOutcome } = require('../../tests');

const MVN_GREEN = `[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running org.json.JSONArrayTest
[INFO] Tests run: 112, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.9 s
[INFO] Results:
[INFO]
[INFO] Tests run: 1163, Failures: 0, Errors: 0, Skipped: 3
[INFO]
[INFO] BUILD SUCCESS
`;

const MVN_FAIL = `[INFO] Running org.json.JSONObjectMacMutTest
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
[ERROR] Failures:
[ERROR]   JSONObjectMacMutTest.testJavaPackageMethodExclusion:14 expected:<false> but was:<true>
[INFO] Results:
[ERROR] Tests run: 1164, Failures: 1, Errors: 0, Skipped: 3
[INFO] BUILD FAILURE
`;

const MVN_COMPILE_ERROR = `[ERROR] COMPILATION ERROR :
[ERROR] /src/test/java/org/json/BMacMutTest.java:[12,9] cannot find symbol
[INFO] BUILD FAILURE
`;

const GRADLE_GREEN = `> Task :test\n> Task :jacocoTestReport\n\nBUILD SUCCESSFUL in 42s\n7 actionable tasks: 7 executed\n`;
const GRADLE_FAIL = `> Task :test FAILED\n\nBMacMutTest > t() FAILED\n    org.opentest4j.AssertionFailedError at BMacMutTest.java:14\n\nFAILURE: Build failed with an exception.\n`;

test('a green Maven run is recognised without running the suite again', () => {
  // verify ran the full suite, then ran it AGAIN under JaCoCo, then PIT ran it once more:
  // three executions of 1163 tests per round, two of which say the same thing.
  const r = suiteOutcome(MVN_GREEN);
  assert.equal(r.passed, true);
  assert.equal(r.tests, 1163);
  assert.equal(r.failures, 0);
});

test('a failing suite is recognised even when the build was told to ignore failures', () => {
  // runCoverage passes -Dmaven.test.failure.ignore=true, so the exit code is always 0 —
  // the counts are the only honest signal
  const r = suiteOutcome(MVN_FAIL);
  assert.equal(r.passed, false);
  assert.equal(r.failures, 1);
  assert.match(r.summary, /expected:<false> but was:<true>/);
});

test('a compilation error is a failure, not an absence of tests', () => {
  const r = suiteOutcome(MVN_COMPILE_ERROR);
  assert.equal(r.passed, false);
  assert.match(r.summary, /cannot find symbol/);
});

test('Gradle output is understood too', () => {
  assert.equal(suiteOutcome(GRADLE_GREEN).passed, true);
  assert.equal(suiteOutcome(GRADLE_FAIL).passed, false);
});

test('an unrecognisable log yields null — "cannot tell", never "green"', () => {
  // the whole point: an unreadable log must send the caller back to a real test run,
  // not let it record a pass nothing established
  assert.equal(suiteOutcome('').passed, null);
  assert.equal(suiteOutcome('Downloading from central: something.jar\n').passed, null);
  assert.equal(suiteOutcome(undefined).passed, null);
});

test('a run that executed no tests at all cannot be called green', () => {
  const noTests = '[INFO] Results:\n[INFO]\n[INFO] Tests run: 0, Failures: 0, Errors: 0, Skipped: 0\n[INFO] BUILD SUCCESS\n';
  assert.equal(suiteOutcome(noTests).passed, null, 'nothing ran, so nothing was proven');
});

test('errors count as failures', () => {
  const errs = '[INFO] Results:\n[ERROR] Tests run: 50, Failures: 0, Errors: 2, Skipped: 0\n[INFO] BUILD FAILURE\n';
  const r = suiteOutcome(errs);
  assert.equal(r.passed, false);
  assert.equal(r.errors, 2);
});
