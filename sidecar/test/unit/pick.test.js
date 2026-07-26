'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { choosePick } = require('../../pick');

// already ranked weakest-first by select.rankUnits
const RANKED = [
  { path: 'src/main/java/a/JSONArray.java::getNumber', file: 'src/main/java/a/JSONArray.java', method: 'getNumber' },
  { path: 'src/main/java/ui/Widget.java::render', file: 'src/main/java/ui/Widget.java', method: 'render' },
  { path: 'src/main/java/a/JSONObject.java::hashCode', file: 'src/main/java/a/JSONObject.java', method: 'hashCode' },
];

test('with nothing excluded, the pick is the top-ranked unit', () => {
  // the model used to CHOOSE, and chose the 5th-ranked private method over three
  // directly callable ones. Its job is the team rule; the ranking is measurement.
  const r = choosePick(RANKED, []);
  assert.equal(r.file, 'src/main/java/a/JSONArray.java::getNumber');
  assert.match(r.reason, /rank|weakest/i);
});

test('an excluded unit is skipped and the next survivor taken', () => {
  const r = choosePick(RANKED, ['src/main/java/a/JSONArray.java::getNumber']);
  assert.equal(r.file, 'src/main/java/ui/Widget.java::render');
});

test('excluding a FILE excludes every method in it', () => {
  // "don't touch ui" is a statement about files, and the model answers in those terms
  const r = choosePick(RANKED, ['src/main/java/ui/Widget.java', 'src/main/java/a/JSONArray.java']);
  assert.equal(r.file, 'src/main/java/a/JSONObject.java::hashCode');
});

test('a rule that excludes everything ends the batch and is not retried', () => {
  const r = choosePick(RANKED, RANKED.map((c) => c.path));
  assert.equal(r.file, null);
  assert.equal(r.retry, false);
  assert.match(r.reason, /excluded/i);
});

test('an exclusion naming something not on the list is ignored', () => {
  const r = choosePick(RANKED, ['src/main/java/nowhere/Ghost.java::gone']);
  assert.equal(r.file, 'src/main/java/a/JSONArray.java::getNumber');
});

test('no candidates at all is not a rule decision', () => {
  const r = choosePick([], []);
  assert.equal(r.file, null);
  assert.equal(r.retry, false);
  assert.match(r.reason, /no candidates/i);
});

test('the choice is a function of the ranking alone — same input, same unit', () => {
  assert.equal(choosePick(RANKED, []).file, choosePick(RANKED, []).file);
});
