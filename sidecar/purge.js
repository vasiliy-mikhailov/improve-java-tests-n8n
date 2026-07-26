'use strict';
// Starting a repo again from nothing.
//
// The pipeline is deliberately incremental: ledgers remember which units were settled and
// what they measured, so a second batch does not redo the first. When the measurement
// semantics change — or when you simply want to see the whole repo improved from a clean
// slate — that memory has to go, along with the artifacts it points at: the prepared PR
// patches and the per-file branches carrying earlier generated tests.
//
// Deletion is narrow on purpose. Only patches under the prs directory and only branches
// this pipeline creates are ever named; a ledger is data, and data can be wrong.

const path = require('node:path');

const OURS = /^tests\/improve-/;

/**
 * @param {object} ledger  the improvement ledger for ONE repo (unit key → record)
 * @param {string} [prsDir]  where prepared PR artifacts live
 * @returns {{files: string[], branches: string[]}}
 */
function purgePlan(ledger, prsDir = '/data/prs') {
  const files = new Set();
  const branches = new Set();
  for (const record of Object.values(ledger || {})) {
    if (!record) continue;
    if (record.patchPath) {
      const p = String(record.patchPath);
      // never step outside the artifact directory, whatever the ledger says
      if (path.dirname(p) === prsDir) {
        files.add(p);
        files.add(p.replace(/\.patch$/, '.json'));   // the PR payload beside it
      }
    }
    if (record.branch && OURS.test(record.branch)) branches.add(record.branch);
  }
  return { files: [...files], branches: [...branches] };
}

module.exports = { purgePlan, OURS };
