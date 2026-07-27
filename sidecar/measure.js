'use strict';
// Measurements persist across runs so a repo is not re-measured from scratch every time.
// That is only safe while the numbers still mean what they meant when they were written.
//
// They have twice stopped meaning it. The unit of work moved from a FILE to a
// `path::method`, so old entries describe a whole class. And PIT reports were only scoped
// to the target method later, so before that a method's "mutation score" could include
// another method's mutants — JSONObject#isRecordStyleAccessor was recorded at 15.79% with
// 3 kills that all belonged to toString(); scoped properly, the same method measures 0%.
// Replaying either kind hands the run a baseline that no longer describes anything.
//
// So every measurement carries the version of the semantics that produced it, and this
// number goes up whenever those semantics change.
//
//   1 — per-file coverage/mutation
//   2 — per-method units (`path::method`)
//   3 — PIT reports scoped to the unit's own method before scoring
const MEASURE_VERSION = 3;

/** Entities leak in from JaCoCo/PIT XML, where a constructor is `&lt;init&gt;`. */
const ENTITIES = { '&lt;': '<', '&gt;': '>', '&amp;': '&', '&quot;': '"', '&apos;': "'" };

/**
 * One canonical spelling per unit. The live ledger holds both
 * `Property.java::&lt;init&gt;` and `Property.java::<init>` for a single constructor, so a
 * measurement written under one is invisible under the other.
 */
function normalizeUnitKey(key) {
  return String(key || '').replace(/&(lt|gt|amp|quot|apos);/g, (m) => ENTITIES[m]);
}

/** Tag a measurement with the semantics that produced it. */
function stamp(patch) {
  return { ...patch, v: MEASURE_VERSION };
}

/** May this stored measurement be replayed into a fresh run? */
function restorable(entry, key) {
  if (!entry || entry.v !== MEASURE_VERSION) return false;
  // the current unit model is `path::method`; a bare file key predates it
  if (key !== undefined && !String(key).includes('::')) return false;
  return true;
}

/**
 * The effort accounting inside a metrics object: what the work COST, not what it measured.
 *
 * Only the fields that are actually present — a missing timesheet must read as absent, not
 * as zero hours, or the headline understates the work instead of admitting it does not
 * know.
 */
function effortOf(metrics) {
  const out = {};
  if (metrics && metrics.timesheet) out.timesheet = metrics.timesheet;
  if (metrics && typeof metrics.spentSec === 'number') out.spentSec = metrics.spentSec;
  return out;
}

/**
 * What of the persisted ledgers applies to the units this run actually has.
 *
 * This must be decided AFTER classes are expanded into `path::method` units. It used to
 * run in /api/repo/prepare, before that expansion, when `state.files` still held FILE
 * keys — so every unit-keyed entry missed, and a unit that had already been improved and
 * PR'd in an earlier batch came back as a fresh candidate to be measured, improved and
 * PR'd all over again.
 *
 * @param {object} improved  the improvement ledger (unit key → record)
 * @param {object} measurements  the measurement ledger (unit key → measurement)
 * @param {(key:string)=>boolean} hasUnit  does this run have that unit?
 */
function planReplay(improved, measurements, hasUnit) {
  const settle = [], restore = [];
  let stale = 0, unknown = 0;
  let retryable = 0;
  for (const [rawKey, record] of Object.entries(improved || {})) {
    const key = normalizeUnitKey(rawKey);
    if (!hasUnit(key)) { unknown += 1; continue; }
    // A unit we could not MEASURE settles nothing: blacklisting it for the lifetime of
    // the ledger writes off improvable work on the strength of a measurement that, by the
    // pipeline's own definition, measured nothing.
    if (record && record.state === 'failed') { retryable += 1; continue; }
    // The status is a record of what happened and stands. Its measurements are gated:
    // the same numbers the measurement ledger rejects as stale were being admitted
    // through this door and averaged into the headline.
    //
    // But the gate was applied to the WHOLE metrics object, and these are written
    // unstamped — so it rejected every one of them, always, and a replayed run showed
    // "Human-equivalent work 0" next to "2 file(s) improved" with FTE and ETA blank while
    // the ledger held spentSec 1389 and a full timesheet.
    //
    // Effort is not a measurement of the code. Human minutes and machine seconds mean the
    // same thing under per-file, per-method and method-scoped semantics alike, which is
    // the only thing the version stamp tracks. So they come back regardless; coverage,
    // mutation and MAC stay gated.
    const m = (record && record.metrics) || {};
    settle.push({
      key,
      record,
      metrics: restorable(m, key) ? record.metrics : null,
      effort: effortOf(m),
    });
  }
  for (const [rawKey, entry] of Object.entries(measurements || {})) {
    const key = normalizeUnitKey(rawKey);
    if (!hasUnit(key)) { unknown += 1; continue; }
    if (!restorable(entry, key)) { stale += 1; continue; }
    restore.push({ key, entry });
  }
  return { settle, restore, stale, unknown, retryable };
}

module.exports = { MEASURE_VERSION, stamp, restorable, normalizeUnitKey, planReplay, effortOf };
