'use strict';
// PIT refused to run because one of OUR OWN generated tests fails on unmutated code.
//
// PIT computes line coverage first, running every targetTests class against the original
// bytecode, and aborts if any fails: "Mutation testing requires a green suite." Round 2 of
// ThreadLocalStatisticsCollector#incrementLoadErrorCount sent two test classes to the
// minion — round 1's MacMutTest and round 2's MacMutR2Test — and round 1's failed.
//
// Each passes alone, and `gradle test` is green, so nothing upstream noticed. They collide
// only in PIT's single minion JVM, on a class whose whole purpose is thread-local state.
// So the pipeline is shipping order-dependent tests, in PRs, and calling the result
// UNMEASURED.
//
// The message names the class and the method. The code threw that away and reported
// "targetTests glob or compiled classes are wrong" — a guess, and the wrong one; it cost
// three rounds of diagnosis.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { pitGreenSuiteFailure } = require('../../pit');

// captured verbatim from /data/pit-unmeasured.log on the live run
const REAL = `8:53:33?AM PIT >> INFO : Sending 2 test classes to minion
8:53:33?AM PIT >> INFO : Sent tests to minion
8:53:33?AM PIT >> SEVERE : Description [testClass=org.dataloader.stats.ThreadLocalStatisticsCollectorIncrementLoadErrorCountMacMutTest, name=[engine:junit-jupiter]/[class:org.dataloader.stats.ThreadLocalStatisticsCollectorIncrementLoadErrorCountMacMutTest]/[method:incrementLoadErrorCount_delegates_to_overall_and_thread_local_collectors()]] did not pass without mutation.
8:53:33?AM PIT >> SEVERE : Tests failing without mutation:
Exception in thread "main" org.pitest.help.PitHelpError: 1 tests did not pass without mutation when calculating line coverage. Mutation testing requires a green suite.
* FAILURE - org.gradle.internal.exceptions.LocationAwareException: Execution failed for task ':pitest'.`;

test('the failing test class and method are recovered from PIT output', () => {
  const f = pitGreenSuiteFailure(REAL);
  assert.ok(f, 'this output plainly says which test failed');
  assert.equal(f.testClass, 'org.dataloader.stats.ThreadLocalStatisticsCollectorIncrementLoadErrorCountMacMutTest');
  assert.equal(f.method, 'incrementLoadErrorCount_delegates_to_overall_and_thread_local_collectors');
});

test('a generated test is recognised as ours', () => {
  const f = pitGreenSuiteFailure(REAL);
  assert.equal(f.ours, true, 'MacMutTest is a name this pipeline produces — we can delete it');
});

test("someone else's failing test is not ours to delete", () => {
  // the project's own suite failing under PIT is a different problem and not one to
  // "fix" by deleting their test
  const out = REAL.replace(/ThreadLocalStatisticsCollectorIncrementLoadErrorCountMacMutTest/g,
    'StatisticsCollectorTest');
  const f = pitGreenSuiteFailure(out);
  assert.equal(f.testClass, 'org.dataloader.stats.StatisticsCollectorTest');
  assert.equal(f.ours, false);
});

test('output without that failure yields nothing', () => {
  assert.equal(pitGreenSuiteFailure('BUILD SUCCESSFUL in 12s'), null);
  assert.equal(pitGreenSuiteFailure(''), null);
  assert.equal(pitGreenSuiteFailure(null), null);
});

test('the round-2 class is recognised as ours too', () => {
  const out = REAL.replace(/MacMutTest/g, 'MacMutR2Test');
  assert.equal(pitGreenSuiteFailure(out).ours, true);
});

// ── a salvage that is never re-measured is a salvage wasted ───────────────
// The clean v2 run: PIT refused because one generated test fails on unmutated code, the
// recovery cut that method and kept the other, and then the round reported UNMEASURED
// anyway — the cut was applied and immediately thrown away. Having removed the thing that
// blocked PIT, the run has to ask PIT again.
//
// Bounded, because each retry can uncover another offender and an unbounded loop over a
// class of bad tests would spend the unit's whole budget discovering them one at a time.
const { greenSuiteRetries } = require('../../pit');

test('a cut earns one more measurement', () => {
  assert.equal(greenSuiteRetries(0, true), 1, 'first cut: try again');
});

test('but the retries run out', () => {
  assert.equal(greenSuiteRetries(1, true), 1, 'second cut: still worth one more');
  assert.equal(greenSuiteRetries(2, true), 0, 'third: stop, this class of test is the problem');
});

test('a failure we could not cut earns nothing', () => {
  // the offending test belongs to the project, or nothing was salvageable — re-running PIT
  // would fail identically
  assert.equal(greenSuiteRetries(0, false), 0);
  assert.equal(greenSuiteRetries(1, false), 0);
});
