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

module.exports = { MEASURE_VERSION, stamp, restorable, normalizeUnitKey };
