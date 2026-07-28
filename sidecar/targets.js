'use strict';
// Turning a PIT report into the work a round should actually do.
//
// The existing loop asks the model about ONE mutant, measures, and repeats — two PIT runs
// per round per method-unit, 609 runs across 22 classes on one JSON-java run, 43% of its
// wall-clock. Most of that re-measures code that did not change.
//
// This models the round differently: every surviving mutant maps to a deterministic test
// NAME, mutants on the same line share it, and any target whose name already appears in
// the test sources is dropped without a model call. What remains is asked for once,
// compiled once, and measured once.
//
// The line-level dedup is worth having and is not the prize: on org.json.XML, 96 surviving
// mutants collapse to 76 targets, 21%. The prize is measuring once per class instead of
// twice per round.

/** `<init>` → Ctor, `lambda$dispatchQueueBatch$2` → lambdaDispatchQueueBatch2. */
function javaSafeMethod(method) {
  const m = String(method || '');
  if (m === '<init>') return 'Ctor';
  if (m === '<clinit>') return 'StaticInit';
  // lambda$foo$2 → lambdaFoo2: keep it readable and legal, never just strip to nothing
  const cleaned = m.replace(/[^A-Za-z0-9]+(.)?/g, (_, c) => (c ? c.toUpperCase() : ''));
  return cleaned || 'Method';
}

/**
 * The name of the test that kills this mutant.
 *
 * Keyed on METHOD and LINE, deliberately not on the mutator kind: `>` becoming `>=`,
 * becoming `<`, and the branch being removed altogether are three mutants of one
 * condition, and the single test that exercises that boundary distinguishes all three.
 * Asking the model three times buys three chances to write the same test.
 */
function targetName(mutant) {
  return `kill_${javaSafeMethod(mutant && mutant.method)}_line${(mutant && mutant.line) || 0}`;
}

/** One target per surviving line, carrying every mutation found on it. */
function groupTargets(survivors) {
  const byName = new Map();
  for (const mu of survivors || []) {
    const name = targetName(mu);
    if (!byName.has(name)) {
      byName.set(name, { name, method: mu.method, line: mu.line, mutants: [] });
    }
    byName.get(name).mutants.push(mu);
  }
  return [...byName.values()];
}

/**
 * Targets with no test of that name yet — the only ones worth a model call.
 *
 * 1000 mutants at ~100 tokens each and 30 tokens/sec is hours of generation; a string
 * check over the test sources costs nothing. The match is anchored on the method
 * declaration so `kill_x_line17` cannot satisfy `kill_x_line170`.
 */
function pendingTargets(targets, testSources) {
  const haystack = (testSources || []).join('\n');
  return (targets || []).filter((t) => !new RegExp(`\\b${t.name}\\s*\\(`).test(haystack));
}

module.exports = { targetName, groupTargets, pendingTargets, javaSafeMethod };
