'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { extractCleanedFile, plausibleCleanup } = require('../../cleanup');

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
