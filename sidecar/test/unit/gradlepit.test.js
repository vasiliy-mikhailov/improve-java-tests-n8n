'use strict';
// Gradle decided PIT had already run, and the round measured nothing.
//
// Round 1 of ThreadLocalStatisticsCollector#incrementLoadErrorCount measured 66.67%.
// Round 2 produced NO report at all, and the diagnostic showed why:
//
//   PIT said: ... 5 actionable tasks: 3 executed
//
// Two of the five were up-to-date. From Gradle's point of view the two verify runs have
// identical `pitest` inputs — same class, same target method, same excludedMethods — and
// java-dataloader sets org.gradle.caching=true, so the second is skipped and no
// mutations.xml appears. The sidecar deletes stale reports before each run precisely so a
// missing report cannot read as zero, which turned the skip into UNMEASURED: correct, and
// a whole round wasted. It hit round 2 of every multi-round unit on this repo.
//
// `--rerun` (Gradle 7.6+) forces just the named task, unlike `--rerun-tasks` which would
// also recompile everything.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { gradlePitArgv, escapeMethodForPit } = require('../../pit');

test('the pitest task is forced to rerun, or Gradle skips it as up-to-date', () => {
  const argv = gradlePitArgv({ wrapper: './gradlew', scanArgs: [], initScript: 'pit-init.gradle', task: 'pitest' });
  assert.ok(argv.includes('--rerun'), `no --rerun in ${argv.join(' ')}`);
  assert.ok(argv.indexOf('--rerun') > argv.indexOf('pitest'),
    '--rerun applies to the task that precedes it');
});

test('it still carries the scan args and the init script', () => {
  const argv = gradlePitArgv({
    wrapper: './gradlew', scanArgs: ['--no-scan'], initScript: 'pit-init.gradle', task: ':core:pitest',
  });
  assert.deepEqual(argv, ['./gradlew', '--no-daemon', '--no-scan', '-I', 'pit-init.gradle', ':core:pitest', '--rerun']);
});

test('a module-scoped task keeps its path', () => {
  const argv = gradlePitArgv({ wrapper: './gradlew', scanArgs: [], initScript: 'i.gradle', task: ':a:b:pitest' });
  assert.ok(argv.includes(':a:b:pitest'));
});

// ── the second thing the diagnostic showed ────────────────────────────────
test('a constructor reaches excludedMethods as <init>, not as an XML entity', () => {
  // the live list read: excludedMethods=[&lt;init&gt;, resetThread, incrementLoadCount, ...]
  // PIT matches method names, and no method is called "&lt;init&gt;" — so the constructor
  // was never excluded and its mutants came back as strays in every scoped run
  assert.equal(escapeMethodForPit('&lt;init&gt;'), '<init>');
  assert.equal(escapeMethodForPit('&#60;init&#62;'), '<init>');
  assert.equal(escapeMethodForPit('<init>'), '<init>');
  assert.equal(escapeMethodForPit('getStatistics'), 'getStatistics');
});
