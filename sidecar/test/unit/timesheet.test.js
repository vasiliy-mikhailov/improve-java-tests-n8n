'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { estimate, diffStats, RATES } = require('../../timesheet');

test('estimate itemizes and sums the four buckets', () => {
  const t = estimate({ sourceLines: 200, testCases: 5, mutantsKilled: 4, rounds: 2 });
  assert.equal(t.analysisMin, RATES.analysisBaseMin + 200 / RATES.analysisLinesPerMin); // 40
  assert.equal(t.testsMin, 5 * RATES.perTestCaseMin);                                    // 60
  assert.equal(t.mutationMin, 4 * RATES.perMutantKilledMin);                             // 40
  assert.equal(t.verifyMin, RATES.verifyBaseMin + 2 * RATES.perRoundMin);                // 25
  assert.equal(t.totalMin, 40 + 60 + 40 + 25);
  assert.equal(t.hours, Math.round(165 / 60 * 100) / 100);
});

test('analysis time is capped for very large files', () => {
  const t = estimate({ sourceLines: 100000, testCases: 0, mutantsKilled: 0, rounds: 1 });
  assert.equal(t.analysisMin, RATES.analysisCapMin);
});

test('estimate keeps its inputs for auditability', () => {
  const t = estimate({ sourceLines: 10, testCases: 1, addedTestLines: 30, mutantsKilled: 1, rounds: 1 });
  assert.deepEqual(t.inputs, { sourceLines: 10, testCases: 1, addedTestLines: 30, mutantsKilled: 1, rounds: 1 });
});

test('estimate treats a zero-round file as one verification cycle', () => {
  const a = estimate({ rounds: 0 });
  const b = estimate({ rounds: 1 });
  assert.equal(a.verifyMin, b.verifyMin);
});

test('diffStats counts added JUnit test cases, not removed or context lines', () => {
  const diff = [
    'diff --git a/src/test/java/com/example/core/ValidatorMacCovTest.java b/src/test/java/com/example/core/ValidatorMacCovTest.java',
    '+++ b/src/test/java/com/example/core/ValidatorMacCovTest.java',
    '+    @Test',
    '+    void isValidUsername_nullReturnsFalse() {',
    '+        assertFalse(Validator.isValidUsername(null));',
    '+    }',
    '+    @ParameterizedTest',
    '+    @ValueSource(strings = {"ab", "a"})',
    '+    void tooShort(String s) { assertFalse(Validator.isValidUsername(s)); }',
    '-    @Test',
    '-    void removed() {}',
    '     @Test',
  ].join('\n');
  const s = diffStats(diff);
  assert.equal(s.testCases, 2, '@Test and @ParameterizedTest, not the removed or context ones');
  assert.equal(s.addedTestLines, 7);
});

test('diffStats does not count JS-style test calls in a Java project', () => {
  const s = diffStats("+  it('does a thing', () => {});");
  assert.equal(s.testCases, 0);
});

test('diffStats on an empty diff yields zeroes', () => {
  assert.deepEqual(diffStats(''), { testCases: 0, addedTestLines: 0 });
});
