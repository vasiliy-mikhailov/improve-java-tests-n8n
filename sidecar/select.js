'use strict';
// Which surviving mutant to attack next.
//
// The order comes from PIT's own data — mutator kind, SURVIVED before NO_COVERAGE, both
// folded into `difficulty` by pit.js — and from what has actually died in THIS run. It
// never comes from asking the model which mutant it fancies: that judgement was
// confidently wrong on four consecutive rounds, every time naming a RemoveConditional
// mutant it then could not distinguish.

const attemptKey = (m) => `${m.mutator}@${m.line}`;

/**
 * How badly this run's evidence argues against a mutator kind. 0 = no objection.
 *
 * The first version was `tried >= 2 && killed === 0 ? 1 : 0`, which let a single early
 * kill immunise a kind permanently: ConditionalsBoundary killed once at line 73, then
 * missed at 164, 191 and 234 while staying at the top of the queue every round. Kill
 * RATE decides now, and a kind that has never killed anything is still the worst bet.
 */
function penalty(st) {
  if (!st || st.tried < 2) return 0;          // one miss is noise, not evidence
  if (st.killed === 0) return 2;              // repeatedly attacked, never once died
  const rate = st.killed / st.tried;
  if (rate < 0.4) return 1;                   // mostly missing → behind the untried kinds
  // evidence cuts both ways: a kind this run keeps killing is a better bet than one we
  // have never tried, even where PIT rates them equally hard
  return rate >= 0.6 ? -1 : 0;
}

/** Survivors we have not already attacked and failed to kill. */
function eligible(list, attempted) {
  const tried = new Set(attempted || []);
  return (list || []).filter((m) => !tried.has(attemptKey(m)));
}

/** Best bet first. Stable and deterministic: same input, same round, same choice. */
function rankSurvivors(list, stats) {
  const st = stats || {};
  return (list || []).slice().sort((a, b) =>
    penalty(st[a.mutator]) - penalty(st[b.mutator])
    || (a.difficulty ?? 1) - (b.difficulty ?? 1)
    || (a.line || 0) - (b.line || 0));
}

const isCtor = (m) => m === '<init>' || m === '<clinit>';

/**
 * How hard it is for a test to get at this unit — a RANKING signal, never a filter.
 *
 * It was a filter, and it deleted real work: reachability is decided by regex over Java
 * source, which missed multi-line signatures, callers inside inner and anonymous classes,
 * `this::foo` method references, and overloads whose first declaration was private. Each
 * miss removed a genuinely testable method from the queue for the whole run, silently and
 * permanently. Ranked last instead, an analysis mistake costs some rounds at the end of a
 * run rather than the method itself.
 */
const REACH_COST = { public: 0, route: 1, none: 2 };

/**
 * Which unit to work on next.
 *
 * Weakest first, because that is where the MAC gap is — but a unit no public path reaches
 * is dropped rather than queued: the prompt correctly refuses to write a test for it, the
 * round changes nothing, and the unit is abandoned after three misses, having spent four
 * PIT runs to learn what the call graph already said. Among equally weak units, one a test
 * can call outright is worth more per round than one behind three private hops.
 */
/**
 * Has the run itself proved it cannot execute this unit? Two or more attempts in which
 * coverage never moved off zero is evidence no static analysis can offer: JSONObject's
 * isRecordStyleAccessor sits behind `if (isRecordType && ...)` and needs a real Java
 * record, which a test compiled at the project's source level cannot declare. The call
 * graph says reachable; the runs say otherwise, and the runs are measurements.
 */
const provenUnexecutable = (x) => (x.misses || 0) >= 2 && x.everReached === false;

function rankUnits(list) {
  const all = list || [];
  const units = all.slice();
  units.sort((a, b) =>
    // evidence of unexecutability outranks everything: it is measured, not guessed
    (provenUnexecutable(a) ? 1 : 0) - (provenUnexecutable(b) ? 1 : 0)
    // a unit with no MAC yet is not a perfect one — rank it on the coverage we do have
    || (a.mac ?? (a.coverage ?? 0) / 2) - (b.mac ?? (b.coverage ?? 0) / 2)
    || (isCtor(a.method) ? 1 : 0) - (isCtor(b.method) ? 1 : 0)
    || REACH_COST[a.reach ?? 'public'] - REACH_COST[b.reach ?? 'public']
    || (b.executableLines ?? b.lines ?? 0) - (a.executableLines ?? a.lines ?? 0)
    || String(a.path).localeCompare(String(b.path)));
  return { units, unreachable: 0 };
}

/**
 * Did this run actually take this unit on? Only those belong in the headline averages.
 *
 * A unit settled as having no mutation surface records a macBefore of 0 and never gets an
 * "after", so averaging it in reported avg MAC after = 13.33 for a batch whose single
 * improved unit stood at 66.67 — understating the work fivefold with units that cannot be
 * improved at all.
 */
function isTargeted(f) {
  return !!f && f.macBefore != null && f.status !== 'no_mutants';
}

/**
 * Is there anything left to attack? A unit that has been measured and whose every
 * surviving mutant has already been attempted can produce nothing: the prompt skips, the
 * round targets nothing, and it is discarded — then picked again, up to its attempt limit,
 * costing a PIT run and a coverage run each time for an outcome that cannot change.
 *
 * Never measured is NOT exhausted: nothing is known about it yet.
 */
function exhausted(f) {
  if (!f || !Array.isArray(f.lastSurvived)) return false;
  return eligible(f.lastSurvived, f.attemptedMutants || []).length === 0;
}

module.exports = { rankSurvivors, eligible, penalty, attemptKey, rankUnits, isTargeted, exhausted };
