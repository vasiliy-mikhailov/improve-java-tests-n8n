'use strict';
// Human-equivalent timesheet: how long would a mid-level developer have taken
// to deliver the same test work? Deterministic, itemized, auditable rates.
//
//   analysis   30 min base to open & understand the module, +1 min per 20 source
//              lines (capped at 90 min)
//   writing    12 min per test case that ended up committed
//   mutation   10 min per mutant killed (finding the weakness + crafting the
//              distinguishing assertion — the human equivalent of mutation analysis)
//   verify     15 min base (run suite/coverage, self-review) + 5 min per
//              improvement round (each round = one measure-adjust cycle)
const { round2 } = require('./util');

const RATES = {
  analysisBaseMin: 30, analysisLinesPerMin: 20, analysisCapMin: 90,
  perTestCaseMin: 12, perMutantKilledMin: 10,
  verifyBaseMin: 15, perRoundMin: 5,
};

function estimate({ sourceLines = 0, testCases = 0, addedTestLines = 0, mutantsKilled = 0, rounds = 1 }) {
  const analysisMin = Math.min(RATES.analysisCapMin, RATES.analysisBaseMin + sourceLines / RATES.analysisLinesPerMin);
  const testsMin = testCases * RATES.perTestCaseMin;
  const mutationMin = mutantsKilled * RATES.perMutantKilledMin;
  const verifyMin = RATES.verifyBaseMin + Math.max(1, rounds) * RATES.perRoundMin;
  const totalMin = Math.round(analysisMin + testsMin + mutationMin + verifyMin);
  return {
    analysisMin: Math.round(analysisMin), testsMin, mutationMin, verifyMin,
    totalMin, hours: round2(totalMin / 60),
    inputs: { sourceLines, testCases, addedTestLines, mutantsKilled, rounds },
  };
}

/** Count committed test cases / added lines from a unified diff. */
function diffStats(diff) {
  const testCases = (diff.match(/^\+[^+].*\b(?:it|test)\s*\(/gm) || []).length;
  const addedTestLines = (diff.match(/^\+[^+]/gm) || []).length;
  return { testCases, addedTestLines };
}

module.exports = { estimate, diffStats, RATES };
