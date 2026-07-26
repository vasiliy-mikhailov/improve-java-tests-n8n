'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { Budget } = require('../../budget');

const opts = { thinkingReserve: 3000, hardMax: 16000 };

test('the ceiling is the answer budget plus the thinking reserve', () => {
  const b = new Budget(opts);
  assert.equal(b.ceiling('improving_mutation', 2500), 5500);
});

test('with thinking off, the answer budget is the whole ceiling', () => {
  const b = new Budget({ ...opts, thinkingReserve: 0 });
  assert.equal(b.ceiling('improving_mutation', 2500), 2500);
});

test('a truncated answer escalates, and the escalation is capped', () => {
  const b = new Budget(opts);
  assert.equal(b.escalate('improving_mutation', 5500), 11000);
  assert.equal(b.escalate('improving_mutation', 11000), 16000);
  assert.equal(b.escalate('improving_mutation', 16000), 16000);
});

test('a stage that truncated once starts higher next time', () => {
  // 4 of 12 calls in the JSON-java run finished with finish_reason=length, and each
  // re-ran the whole prompt at ~100s. Paying that again for the same stage is the bug.
  const b = new Budget(opts);
  b.record('improving_mutation', { finish: 'length', ceiling: 5500 });
  assert.equal(b.ceiling('improving_mutation', 2500), 11000, 'learned from the truncation');
});

test('what one stage learns does not inflate the others', () => {
  const b = new Budget(opts);
  b.record('improving_mutation', { finish: 'length', ceiling: 5500 });
  assert.equal(b.ceiling('improving_coverage', 3000), 6000);
});

test('a stage that answers comfortably is never inflated', () => {
  const b = new Budget(opts);
  for (let i = 0; i < 5; i++) b.record('improving_coverage', { finish: 'stop', ceiling: 6000, completionTokens: 900 });
  assert.equal(b.ceiling('improving_coverage', 3000), 6000);
});

test('a stage that keeps answering near its ceiling gets headroom before it truncates', () => {
  const b = new Budget(opts);
  // stopping cleanly at 5200 of 5500 twice means the next answer very likely will not fit
  b.record('improving_mutation', { finish: 'stop', ceiling: 5500, completionTokens: 5200 });
  b.record('improving_mutation', { finish: 'stop', ceiling: 5500, completionTokens: 5300 });
  assert.ok(b.ceiling('improving_mutation', 2500) > 5500, 'should widen before hitting the wall');
});

test('the learned ceiling never exceeds the hard maximum', () => {
  const b = new Budget(opts);
  for (let i = 0; i < 10; i++) b.record('improving_mutation', { finish: 'length', ceiling: 16000 });
  assert.equal(b.ceiling('improving_mutation', 2500), 16000);
});

test('learning survives a restart', () => {
  const b = new Budget(opts);
  b.record('improving_mutation', { finish: 'length', ceiling: 5500 });
  const revived = new Budget(opts, b.toJSON());
  assert.equal(revived.ceiling('improving_mutation', 2500), 11000);
});

test('at the hard maximum there is nothing to escalate to, and the call is not repeated', () => {
  // escalate() returns the same ceiling once capped, and llm.js re-issued a byte-identical
  // request: another ~100-200 s for a deterministically identical truncated answer, with
  // an event line claiming a bigger budget.
  const b = new Budget(opts);
  assert.equal(b.grew('improving_mutation', 16000), false, 'already at the cap');
  assert.equal(b.grew('improving_mutation', 5500), true);
});
