'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { extractCleanedFile, plausibleCleanup, worthCommitting } = require('../../cleanup');

const ORIGINAL = `package org.json;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class JSONArrayMacMutTest {

    // Wait, let me think about this. The mutant replaces + with -, so I need
    // inputs where those differ. Let's try 2 and 3... actually any pair works.
    // Hmm, but does getNumber() unwrap? Let me reason: yes, it calls optNumber.
    @Test
    public void testGetNumberReturnsValue() {
        JSONArray a = new JSONArray("[5]");
        assertEquals(5, a.getNumber(0).intValue());
    }

    @Test
    public void testDoesNotThrow() {
        JSONArray a = new JSONArray("[1]");
        a.getNumber(0);
    }
}
`;

const CLEANED = `package org.json;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class JSONArrayMacMutTest {

    // kills NullReturnVals on getNumber
    @Test
    public void testGetNumberReturnsValue() {
        JSONArray a = new JSONArray("[5]");
        assertEquals(5, a.getNumber(0).intValue());
    }
}
`;

test('a cleaned JUnit file is recognised as Java, not judged by vitest shapes', () => {
  // The gate was /\b(it|test|describe)\s*\(/ — inherited from the JS pipeline. A Java
  // test file matches none of that, so every cleanup was rejected as "implausible
  // output" and D12 could never be demonstrated on a Java repo.
  assert.doesNotMatch(CLEANED, /\b(it|describe)\s*\(/, 'the fixture is genuinely JS-shape-free');
  assert.equal(plausibleCleanup(ORIGINAL, CLEANED).ok, true);
});

test('scratch reasoning removed and one intent comment kept is the point of the pass', () => {
  const r = plausibleCleanup(ORIGINAL, CLEANED);
  assert.equal(r.ok, true);
  assert.doesNotMatch(CLEANED, /Wait, let me think|Hmm, but/);
});

test('an answer that dropped every test is a deletion, not a cleanup', () => {
  const empty = CLEANED.replace(/@Test[\s\S]*?\n    }\n/, '');
  const r = plausibleCleanup(ORIGINAL, empty);
  assert.equal(r.ok, false);
  assert.match(r.reason, /no @Test|no tests/i);
});

test('a truncated answer is rejected rather than committed', () => {
  const r = plausibleCleanup(ORIGINAL, CLEANED.slice(0, 120));
  assert.equal(r.ok, false);
});

test('cleanup may not rename the class — the file would stop compiling', () => {
  const renamed = CLEANED.replace('JSONArrayMacMutTest', 'CleanedTest');
  const r = plausibleCleanup(ORIGINAL, renamed);
  assert.equal(r.ok, false);
  assert.match(r.reason, /class/i);
});

test('cleanup may not add tests it invented', () => {
  const grown = CLEANED.replace('}\n$', '') + `
    @Test
    public void testSomethingNew() {
        assertEquals(1, 1);
    }
}
`;
  const r = plausibleCleanup(ORIGINAL, grown);
  assert.equal(r.ok, false);
  assert.match(r.reason, /more tests|added/i);
});

test('cleanup may not smuggle in reflection', () => {
  const sneaky = CLEANED.replace('JSONArray a = new JSONArray("[5]");',
    'java.lang.reflect.Method m = JSONArray.class.getDeclaredMethod("getNumber"); m.setAccessible(true);');
  const r = plausibleCleanup(ORIGINAL, sneaky);
  assert.equal(r.ok, false);
  assert.match(r.reason, /reflection/i);
});

test('an unchanged answer is not a change', () => {
  assert.equal(plausibleCleanup(ORIGINAL, ORIGINAL).ok, false);
});

test('thinking blocks and markdown fences never reach the file', () => {
  assert.equal(extractCleanedFile(`<think>I should strip the noise</think>\n${CLEANED}`).trim(), CLEANED.trim());
  assert.equal(extractCleanedFile('```java\n' + CLEANED + '```').trim(), CLEANED.trim());
  assert.equal(extractCleanedFile(CLEANED).trim(), CLEANED.trim());
});

test('the cleaned file always ends with exactly one newline', () => {
  assert.match(extractCleanedFile('```java\n' + CLEANED.trimEnd() + '\n```'), /}\n$/);
});

test('an empty or test-less file is never worth committing', () => {
  // A zero-byte XMLMacMutR2Test.java reached a prepared PR: a new file, empty blob, no
  // hunks. Whatever produced it, dead weight in a deliverable is exactly what D12 forbids.
  assert.equal(worthCommitting(''), false);
  assert.equal(worthCommitting('   \n\n  '), false);
  assert.equal(worthCommitting('package org.json;\n\npublic class T { }\n'), false, 'no @Test = nothing tested');
});

test('a real generated test is worth committing', () => {
  assert.equal(worthCommitting(CLEANED), true);
});

test('a parameterised or repeated test counts as a test', () => {
  const p = 'package a;\nimport org.junit.jupiter.params.ParameterizedTest;\npublic class T {\n  @ParameterizedTest\n  void t(int x) { assertEquals(1, x); }\n}\n';
  assert.equal(worthCommitting(p), true);
});

// ── which files a cleanup may touch ───────────────────────────────────────
const { ownedByUnit } = require('../../cleanup');

test('a cleanup only touches the files of the unit it can re-measure', () => {
  // The pass cleaned every changed test file on the branch but re-measured only the
  // CURRENT unit. JSONObjectGetElementTypeMacMutTest.java was stripped again while
  // isNumberSimilar was current, and "kept" on isNumberSimilar's score — the very file
  // whose stripping had already been reverted once for dropping getElementType from
  // 80 to 60. A verification that cannot see the thing it is verifying is not one.
  const changed = [
    'src/test/java/org/json/JSONObjectIsNumberSimilarMacMutTest.java',
    'src/test/java/org/json/JSONObjectIsNumberSimilarMacMutR2Test.java',
    'src/test/java/org/json/JSONObjectGetElementTypeMacMutTest.java',
  ];
  const mine = ownedByUnit(changed, { covPath: 'src/test/java/org/json/JSONObjectIsNumberSimilarMacCovTest.java',
    mutPath: 'src/test/java/org/json/JSONObjectIsNumberSimilarMacMutTest.java' });
  assert.deepEqual(mine, [
    'src/test/java/org/json/JSONObjectIsNumberSimilarMacMutTest.java',
    'src/test/java/org/json/JSONObjectIsNumberSimilarMacMutR2Test.java',
  ]);
});

test('every round of the current unit is included, not just this one', () => {
  const changed = ['src/test/java/a/BRunMacCovTest.java', 'src/test/java/a/BRunMacMutR4Test.java'];
  const mine = ownedByUnit(changed, { covPath: 'src/test/java/a/BRunMacCovR2Test.java', mutPath: 'src/test/java/a/BRunMacMutR2Test.java' });
  assert.deepEqual(mine.sort(), changed.slice().sort());
});

test('a sibling method of the same class is not ours to clean', () => {
  const changed = ['src/test/java/a/BOtherMacMutTest.java'];
  assert.deepEqual(ownedByUnit(changed, { covPath: 'src/test/java/a/BRunMacCovTest.java', mutPath: 'src/test/java/a/BRunMacMutTest.java' }), []);
});

test("the project's own test file is never a cleanup target", () => {
  const changed = ['src/test/java/a/BTest.java'];
  assert.deepEqual(ownedByUnit(changed, { covPath: 'src/test/java/a/BRunMacCovTest.java', mutPath: 'src/test/java/a/BRunMacMutTest.java' }), []);
});
