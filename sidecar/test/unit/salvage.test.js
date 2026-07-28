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
