'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { purgePlan } = require('../../purge');

const LEDGER = {
  'src/main/java/org/json/XML.java::parse': { state: 'improved', branch: 'tests/improve-src-main-java-org-json-xml-java', patchPath: '/data/prs/tests-improve-src-main-java-org-json-xml-java.patch' },
  'src/main/java/org/json/XML.java::toString': { state: 'improved', branch: 'tests/improve-src-main-java-org-json-xml-java', patchPath: '/data/prs/tests-improve-src-main-java-org-json-xml-java.patch' },
  'src/main/java/org/json/Cookie.java::<init>': { state: 'no_improvement', branch: 'tests/improve-src-main-java-org-json-cookie-java' },
};

test('every prepared PR artifact of this repo is named for removal', () => {
  const p = purgePlan(LEDGER);
  assert.ok(p.files.includes('/data/prs/tests-improve-src-main-java-org-json-xml-java.patch'));
  assert.ok(p.files.includes('/data/prs/tests-improve-src-main-java-org-json-xml-java.json'),
    'the payload beside the patch goes too');
});

test('a patch shared by two units of one file is removed once', () => {
  const p = purgePlan(LEDGER);
  assert.equal(new Set(p.files).size, p.files.length);
});

test('every branch this repo produced is named, including ones with no patch', () => {
  const p = purgePlan(LEDGER);
  assert.deepEqual(p.branches.sort(), [
    'tests/improve-src-main-java-org-json-cookie-java',
    'tests/improve-src-main-java-org-json-xml-java',
  ]);
});

test('an empty ledger asks for nothing', () => {
  const p = purgePlan({});
  assert.deepEqual(p.files, []);
  assert.deepEqual(p.branches, []);
  assert.deepEqual(purgePlan(null).branches, []);
});

test('only artifacts under the prs directory are ever named', () => {
  const evil = { u: { state: 'improved', branch: 'b', patchPath: '/etc/passwd' } };
  assert.deepEqual(purgePlan(evil).files, [], 'a path outside /data/prs is not ours to delete');
});

test('a branch name that is not one of ours is left alone', () => {
  const p = purgePlan({ u: { state: 'improved', branch: 'master' } });
  assert.deepEqual(p.branches, [], 'only tests/improve-* branches belong to this pipeline');
});
