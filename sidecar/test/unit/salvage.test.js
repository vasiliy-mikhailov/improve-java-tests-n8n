'use strict';
// A batch round writes N tests into one file. One bad assertion used to cost all N.
//
// The very first unit of the v2 run proved it: three targets in
// ThreadLocalStatisticsCollectorIncrementBatchLoadExceptionCountMacMutTest, two of them
// asserting wrongly, the whole file deleted for breaking the suite, and the round recorded
// as cov 0→0 mut 0→0. Under the old one-mutant-per-round loop a bad assertion cost one
// test; batching made it cost the batch.
//
// It does not have to. The file COMPILED — the failures are assertion failures, the
// runner names the methods that failed, and those names are the deterministic target names
// this pipeline chose. So the failing methods can be cut out and the rest kept.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { failedTestNames, dropTestMethods } = require('../../salvage');
const { stripNonCode } = require('../../javasrc');

// Braces inside string literals are not structure — the fixture below deliberately
// asserts on "}" — so balance is checked over code only. Counting raw characters made
// the UNMODIFIED fixture look unbalanced (4 vs 6) and failed a correct implementation.
const braces = (s) => {
  const c = stripNonCode(s);
  return [(c.match(/\{/g) || []).length, (c.match(/\}/g) || []).length];
};

// captured verbatim from the live v2 run
const GRADLE = `ThreadLocalStatisticsCollectorIncrementBatchLoadExceptionCountMacMutTest > kill_incrementBatchLoadExceptionCount_line76() FAILED
    at org.hamcrest.MatcherAssert.assertThat(MatcherAssert.java:20)
    at org.dataloader.stats.ThreadLocalStatisticsCollectorIncrementBatchLoadExceptionCountMacMutTest.kill_incrementBatchLoadExceptionCount_line76(ThreadLocalStatisticsCollectorIncrementBatchLoadExceptionCountMacMutTest.java:27)
ThreadLocalStatisticsCollectorIncrementBatchLoadExceptionCountMacMutTest > kill_incrementBatchLoadExceptionCount_line82() FAILED
    at org.hamcrest.MatcherAssert.assertThat(MatcherAssert.java:20)`;

const SUREFIRE = `[ERROR] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0
[ERROR] kill_mustEscape_line170(org.json.XMLMacMutTest)  Time elapsed: 0.01 s  <<< FAILURE!
java.lang.AssertionError: expected:<true> but was:<false>`;

test('the failing method names are read out of a Gradle run', () => {
  const f = failedTestNames(GRADLE);
  assert.deepEqual([...f].sort(), [
    'kill_incrementBatchLoadExceptionCount_line76',
    'kill_incrementBatchLoadExceptionCount_line82',
  ]);
});

test('and out of a Surefire run', () => {
  assert.deepEqual([...failedTestNames(SUREFIRE)], ['kill_mustEscape_line170']);
});

test('a green log names nothing', () => {
  assert.equal(failedTestNames('BUILD SUCCESSFUL in 12s').size, 0);
  assert.equal(failedTestNames('').size, 0);
  assert.equal(failedTestNames(null).size, 0);
});

// ── cutting the bad methods out ───────────────────────────────────────────
const FILE = `package a;

import org.junit.jupiter.api.Test;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.CoreMatchers.equalTo;

class BMacMutTest {

    @Test
    void kill_m_line10() {
        assertThat(new B().m(), equalTo(1));
    }

    @Test
    void kill_m_line20() {
        // this one asserts the wrong thing
        assertThat(new B().m(), equalTo(99));
    }

    @Test
    void kill_m_line30() {
        assertThat(new B().n("}"), equalTo("}"));
    }
}
`;

test('only the named method is removed', () => {
  const out = dropTestMethods(FILE, new Set(['kill_m_line20']));
  assert.doesNotMatch(out, /kill_m_line20/);
  assert.match(out, /kill_m_line10/);
  assert.match(out, /kill_m_line30/);
});

test('the file still has its package, imports and class', () => {
  const out = dropTestMethods(FILE, new Set(['kill_m_line20']));
  assert.match(out, /^package a;/);
  assert.match(out, /import org\.junit\.jupiter\.api\.Test;/);
  assert.match(out, /class BMacMutTest \{/);
  const [o, c] = braces(out);
  assert.equal(o, c, 'braces balance');
});

test('a brace inside a string literal does not end the method early', () => {
  // kill_m_line30 asserts on "}" — counting braces naively would cut the file in half
  const out = dropTestMethods(FILE, new Set(['kill_m_line30']));
  assert.doesNotMatch(out, /kill_m_line30/);
  assert.match(out, /kill_m_line10/);
  assert.match(out, /kill_m_line20/);
  const [o, c] = braces(out);
  assert.equal(o, c, 'braces balance');
});

test('removing every method leaves a compilable empty class, not a broken file', () => {
  const out = dropTestMethods(FILE, new Set(['kill_m_line10', 'kill_m_line20', 'kill_m_line30']));
  assert.match(out, /class BMacMutTest \{/);
  assert.doesNotMatch(out, /@Test/);
  const [o, c] = braces(out);
  assert.equal(o, c);
});

test('naming a method that is not there changes nothing', () => {
  assert.equal(dropTestMethods(FILE, new Set(['kill_m_line999'])), FILE);
  assert.equal(dropTestMethods(FILE, new Set()), FILE);
});

// ── one decision, used by both places that drop tests ─────────────────────
// delete-many learned to cut only the failing methods. The PIT green-suite recovery did
// not, and deleted the whole file — so a batch that survived a red suite by salvage was
// then wiped whole when PIT refused to run against one of its members. Two callers, one
// decision, or they drift.
const { salvageSource } = require('../../salvage');

test('cutting the named methods returns the file when something still runs', () => {
  const out = salvageSource(FILE, new Set(['kill_m_line20']));
  assert.ok(out);
  assert.doesNotMatch(out, /kill_m_line20/);
  assert.match(out, /kill_m_line10/);
});

test('cutting everything returns null — there is nothing worth keeping', () => {
  // a file of one test whose only test failed is a file to delete, not to keep empty
  const out = salvageSource(FILE, new Set(['kill_m_line10', 'kill_m_line20', 'kill_m_line30']));
  assert.equal(out, null);
});

test('naming nothing that is in the file returns null, not a no-op keep', () => {
  // the caller asked us to drop something we cannot find — deleting is the safe answer,
  // since the file demonstrably broke the build and we cannot say which part
  assert.equal(salvageSource(FILE, new Set(['kill_other_line1'])), null);
  assert.equal(salvageSource(FILE, new Set()), null);
});

test('a file we cannot read is not salvageable', () => {
  assert.equal(salvageSource(null, new Set(['kill_m_line10'])), null);
  assert.equal(salvageSource('', new Set(['kill_m_line10'])), null);
});
