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
 * The machine-readable link between a test and the mutant it kills.
 *
 * The first version of this made the model NAME each test `kill_<method>_line<N>`, which
 * is not how these repos name tests — java-dataloader has clearCacheOnError and
 * disableCache, JSON-java has emptyStringCookieList and malFormedCookieListException — and
 * it reads as machine output in a PR a human is asked to review. It is also wrong the
 * moment the source shifts a line.
 *
 * So the two jobs are separated. The model names the test the way the repo does, and
 * carries this marker in the one short comment the rules already allow it. The filter
 * greps the marker; the reviewer reads the name.
 *
 * Keyed on METHOD and LINE, deliberately not on mutator kind: `>` becoming `>=`, becoming
 * `<`, and the branch being removed are three mutants of one condition, and the single
 * test that exercises that boundary distinguishes all three.
 *
 * A marker that no longer matches a freshly measured line means the code moved, and
 * re-asking is then the correct answer rather than a miss.
 */
function targetMarker(mutant) {
  return `covers ${javaSafeMethod(mutant && mutant.method)}:${(mutant && mutant.line) || 0}`;
}

/** One target per surviving line, carrying every mutation found on it. */
function groupTargets(survivors) {
  const byName = new Map();
  for (const mu of survivors || []) {
    const marker = targetMarker(mu);
    if (!byName.has(marker)) {
      byName.set(marker, { marker, method: mu.method, line: mu.line, mutants: [] });
    }
    byName.get(marker).mutants.push(mu);
  }
  return [...byName.values()];
}

/**
 * Targets with no test of that name yet — the only ones worth a model call.
 *
 * 1000 mutants at ~100 tokens each and 30 tokens/sec is hours of generation; a string
 * check over the test sources costs nothing. The match is anchored so the marker for line
 * 17 cannot satisfy line 170.
 */
function pendingTargets(targets, testSources) {
  const haystack = (testSources || []).join('\n');
  return (targets || []).filter((t) => !new RegExp(`${t.marker}(?![0-9])`).test(haystack));
}

/**
 * Everything a batch round needs to know, in one place.
 *
 * `covered` is reported rather than inferred: it is the number of targets the filter
 * spared a model call, and if it is always zero the filter is a no-op and the design
 * bought nothing.
 */
function targetsFor(survivors, testSources) {
  const all = groupTargets(survivors);
  const pending = pendingTargets(all, testSources);
  return { all, pending, covered: all.length - pending.length };
}

module.exports = { targetsFor, targetMarker, groupTargets, pendingTargets, javaSafeMethod };
