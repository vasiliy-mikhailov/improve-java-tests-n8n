'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { resolveBranch } = require('../../branch');

// `git ls-remote --symref <url>` — the symref line names the repo's default branch,
// the rest are its actual refs.
const JSON_JAVA = `ref: refs/heads/master\tHEAD
b3f1a2c\tHEAD
b3f1a2c\trefs/heads/master
9de1122\trefs/heads/gh-pages
44ab001\trefs/tags/20231013
`;
const MODERN = `ref: refs/heads/main\tHEAD
aa11\tHEAD
aa11\trefs/heads/main
bb22\trefs/heads/release/2.x
`;

test('a configured branch that exists is used unchanged', () => {
  const r = resolveBranch('master', JSON_JAVA);
  assert.equal(r.branch, 'master');
  assert.equal(r.fellBack, false);
});

test("a configured branch the remote does not have falls back to its default", () => {
  // JSON-java's default is master; a stale REPO_BRANCH=main killed a whole run at clone
  const r = resolveBranch('main', JSON_JAVA);
  assert.equal(r.branch, 'master');
  assert.equal(r.fellBack, true);
  assert.match(r.reason, /main/);
  assert.match(r.reason, /master/);
});

test('no configured branch means the repo default, whatever it is called', () => {
  assert.equal(resolveBranch('', JSON_JAVA).branch, 'master');
  assert.equal(resolveBranch(null, MODERN).branch, 'main');
  assert.equal(resolveBranch('auto', JSON_JAVA).branch, 'master');
});

test('a branch that is not the default is still honoured', () => {
  assert.equal(resolveBranch('release/2.x', MODERN).branch, 'release/2.x');
  assert.equal(resolveBranch('gh-pages', JSON_JAVA).branch, 'gh-pages');
});

test('a tag is not a branch', () => {
  const r = resolveBranch('20231013', JSON_JAVA);
  assert.equal(r.branch, 'master');
  assert.equal(r.fellBack, true);
});

test('an unreachable remote fails loudly rather than guessing a branch', () => {
  assert.throws(() => resolveBranch('main', ''), /no branches/i);
});

test('without a symref line the branches themselves decide the default', () => {
  const noSymref = `aa11\trefs/heads/trunk\nbb22\trefs/heads/wip\n`;
  assert.equal(resolveBranch('trunk', noSymref).branch, 'trunk');
  const r = resolveBranch('main', noSymref);
  assert.equal(r.fellBack, true);
  assert.match(r.reason, /trunk/);
});
