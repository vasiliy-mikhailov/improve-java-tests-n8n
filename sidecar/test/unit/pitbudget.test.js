'use strict';
// One PIT invocation could outlive the whole unit budget four times over.
//
// DataLoader#getValueCache() sat 984 seconds inside a single `pitest` task, emitting
// "PIT >> WARNING : Minion exited abnormally due to TIMED_OUT" — the class is
// CompletableFuture-heavy, so mutants hang and PIT waits out each one, and its test glob
// matches a large async suite that reruns per mutant. The run was configured with
// unitBudgetSec 900, but that is only consulted by decide() AFTER a round returns, while
// the subprocess itself was given timeoutMs 3600000. So the budget could not stop it: a
// single unit is free to spend an hour of a run whose remaining ETA is already 170 hours.
//
// The budget has to reach the subprocess.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { pitTimeoutMs } = require('../../pit');

const MIN = 120000;
const MAX = 3600000;

test('a fresh unit gets its whole budget', () => {
  assert.equal(pitTimeoutMs({ unitBudgetSec: 900, spentSec: 0, now: 1000 }), 900000);
});

test('a unit that has already spent some of it gets the rest', () => {
  assert.equal(pitTimeoutMs({ unitBudgetSec: 900, spentSec: 300, now: 1000 }), 600000);
});

test('time on the current attempt counts too', () => {
  // attemptStartedAt is a live stopwatch; ignoring it lets a unit restart its budget
  // every round
  assert.equal(
    pitTimeoutMs({ unitBudgetSec: 900, spentSec: 100, attemptStartedAt: 900, now: 1000 }),
    700000, 'spent 100s + 100s on this attempt = 200s of 900s gone');
});

test('a nearly exhausted budget still gets a floor, not a doomed run', () => {
  // a 3-second timeout guarantees failure and teaches nothing; the round should get a
  // real chance and then be stopped by decide()
  assert.equal(pitTimeoutMs({ unitBudgetSec: 900, spentSec: 899, now: 1000 }), MIN);
  assert.equal(pitTimeoutMs({ unitBudgetSec: 900, spentSec: 5000, now: 1000 }), MIN);
});

test('no budget configured means the old ceiling, not zero', () => {
  // unitBudgetSec 0 documents "uncapped" — reading it as a 0ms timeout would fail every
  // PIT run instantly, which is how `|| 5` once turned uncapped rounds into a cap of five
  assert.equal(pitTimeoutMs({ unitBudgetSec: 0, spentSec: 0, now: 1000 }), MAX);
  assert.equal(pitTimeoutMs({ spentSec: 0, now: 1000 }), MAX);
});

test('an absurd budget is still capped at the hard ceiling', () => {
  assert.equal(pitTimeoutMs({ unitBudgetSec: 99999, spentSec: 0, now: 1000 }), MAX);
});
