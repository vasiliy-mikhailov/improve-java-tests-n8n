'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { branchAction } = require('../../branch');

const RUN = 'run-1785086035026';

test('the first unit of a file starts its branch from the base', () => {
  assert.equal(branchAction({ branch: 'tests/improve-xml', owner: null, runId: RUN }).action, 'reset');
});

test('a second unit of the SAME file continues on the branch, it does not reset it', () => {
  // XML::parse improved, was committed and PR'd on tests/improve-...xml-java. XML::format
  // was then picked, `checkout -B <branch> master` moved the branch back to master, and
  // the force-push rewrote the PR to contain only format's test — parse's work vanished
  // from a PR that had already been opened.
  const r = branchAction({ branch: 'tests/improve-xml', owner: RUN, runId: RUN });
  assert.equal(r.action, 'reuse');
  assert.match(r.reason, /this run/i);
});

test("a branch left by an earlier run is reset — its commits are not this run's", () => {
  const r = branchAction({ branch: 'tests/improve-xml', owner: 'run-1784000000000', runId: RUN });
  assert.equal(r.action, 'reset');
  assert.match(r.reason, /earlier run/i);
});

test('reset is the safe default when ownership is unknown', () => {
  assert.equal(branchAction({ branch: 'tests/improve-xml', runId: RUN }).action, 'reset');
  assert.equal(branchAction({ branch: 'tests/improve-xml', owner: undefined, runId: undefined }).action, 'reset');
});
