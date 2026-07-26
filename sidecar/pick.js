'use strict';
// Choosing the next unit of work.
//
// The model used to pick, with the mechanical ranking only as a fallback — and it picked
// the fifth-ranked unit (a private method three hops from any public entry) over three
// directly callable ones, on the strength of "11 lines of reflective logic … high
// potential". That is the same mistake as letting it choose which mutant to attack, where
// its judgement was wrong four rounds running.
//
// So the split is: **the model applies the team rule, the measurements choose.** The rule
// is an exclusion ("don't touch ui") — a question the model genuinely answers better than
// a glob — and among whatever survives, the ranking decides.

const norm = (s) => String(s || '').trim();

/**
 * @param {Array<{path:string,file:string,method:string}>} ranked  weakest-first (select.rankUnits)
 * @param {string[]} excluded  unit keys or bare file paths the team rule rules out
 */
function choosePick(ranked, excluded) {
  const list = ranked || [];
  if (!list.length) return { file: null, retry: false, reason: 'no candidates left to work on' };

  const out = new Set((excluded || []).map(norm).filter(Boolean));
  // a rule speaks about files ("don't touch ui"), so excluding a file excludes its methods
  const isExcluded = (c) => out.has(norm(c.path)) || out.has(norm(c.file));

  const pick = list.find((c) => !isExcluded(c));
  if (!pick) {
    return {
      file: null,
      retry: false,                       // the rule itself excludes everything: terminal
      reason: `every remaining candidate is excluded by the team rule (${out.size} exclusion(s))`,
    };
  }
  const skipped = list.indexOf(pick);
  return {
    file: pick.path,
    reason: skipped
      ? `weakest unit the team rule allows (${skipped} higher-ranked unit(s) excluded)`
      : 'weakest unit by measured coverage x mutation (rank 1)',
  };
}

module.exports = { choosePick };
