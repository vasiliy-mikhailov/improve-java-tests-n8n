'use strict';
// What actually happened to the mutant this round was aiming at.
//
// The verify route used to answer this with one boolean: is the target still in PIT's
// survivor list? That reads the right data and draws the wrong conclusion, because it
// cannot tell the difference between
//
//   the test ran and does not distinguish the mutation
//   the test never existed when PIT ran
//
// and the second happens often. A generated test that breaks the suite is repaired once;
// if the repair also fails, the workflow deletes the file. Verification then runs against
// a repo with no new test, measures the score it started with, and concludes the mutant
// resisted. On one JSON-java run that conclusion was drawn 41 times across 19 units.
//
// It is not a cosmetic error. The mutant is marked attempted when the test is WRITTEN, so
// select.eligible() strikes it off permanently; mutatorStats books a try with no kill, and
// two of those demote a whole mutator kind for the rest of the run; the round counts as a
// miss, and three misses end the unit. JSONWriter#value was abandoned at 20% on three such
// rounds, then re-picked and taken to 40% by the first round whose test compiled.
//
// So the question needs a third answer, and the caller has to say whether the round's test
// was on disk at measurement time. That is a fact about the file system, not an inference.

/**
 * @param {object} args
 * @param {{mutator:string,line:number}|null} args.targetMutant  what the round aimed at
 * @param {Array<{mutator:string,line:number}>} args.survived   PIT survivors after the round
 * @param {boolean} args.testsPresent  was the round's generated test on disk when PIT ran?
 * @param {boolean} [args.wroteAny]  did the round write a test at all? (default true)
 * @param {number} [args.otherEligible]  un-attempted survivors left besides this one
 * @param {number} [args.brokenBefore]  how many earlier rounds on THIS mutant were broken
 * @returns {{kind:'broken'|'resisted'|'killed', stillAlive:boolean|null, message:string,
 *            countMutatorTry:boolean, unattempt:boolean}|null}
 */
function roundOutcome({
  targetMutant, survived, testsPresent, wroteAny = true, otherEligible = 0, brokenBefore = 0,
}) {
  const tm = targetMutant;
  // no line means PIT output cannot be matched against it — there is nothing to report,
  // and inventing a verdict here is how the false ones started
  if (!tm || !tm.line) return null;
  // The model declines to write a test when it judges the mutation equivalent, and the
  // prompt tells it to. That is a judgement ABOUT the mutant, so the mutant stays
  // attempted — but there is still no test here, and every verdict below describes one.
  if (!wroteAny) return null;

  if (!testsPresent) {
    // Whether the mutant appears in the survivor list is irrelevant: no test of ours ran.
    // stillAlive stays null so no caller can read this as either outcome — in particular
    // an absent mutant here must never be credited as a kill.
    const again = brokenBefore >= 1;
    // Whether to hand the mutant back to the queue is a real trade, and the run settled it.
    // JSONWriter#value broke on NullReturnVals at 360, then 370, then 380, and killed a
    // RemoveConditional mutant on the first round that compiled: at temperature 0.2 the
    // same prompt returns the same uncompilable test, so retrying THIS mutant while others
    // are untried spends a round re-learning that. Move on instead.
    //
    // Unless there is nothing to move on to. Leaving the last eligible mutant marked makes
    // the next round report "no un-attempted survivors left — stop" and abandons the unit
    // on the strength of a round that measured nothing at all. One retry is cheaper than
    // writing the unit off; a second is the loop this guards against.
    const last = otherEligible === 0;
    const unattempt = last && !again;
    return {
      kind: 'broken',
      stillAlive: null,
      message: `${tm.mutator} at line ${tm.line}: the round's test never reached the measurement `
        + '(it broke the suite and was deleted) — the mutant was not challenged'
        + (unattempt ? ', and as the only remaining survivor it goes back in the queue for one more try' : '')
        + (last && again ? ', and that has now happened twice, so it stays marked as attempted' : '')
        + (!last ? ', so the next round takes a different one' : ''),
      // evidence about the generated test, not about this kind of mutation
      countMutatorTry: false,
      unattempt,
    };
  }

  const stillAlive = (survived || []).some((m) => m.line === tm.line && m.mutator === tm.mutator);
  return {
    kind: stillAlive ? 'resisted' : 'killed',
    stillAlive,
    message: `${tm.mutator} at line ${tm.line}: `
      + (stillAlive ? 'STILL ALIVE — the new test does not distinguish it' : 'KILLED'),
    countMutatorTry: true,
    unattempt: false,
  };
}

/**
 * The facts roundOutcome runs on, gathered rather than assumed.
 *
 * These were computed inline in the verify route, so nothing tested them — and every
 * verdict is only as good as they are. `exists` is injected so this stays a pure function
 * over a file-system answer instead of one that goes and looks.
 *
 * @param {object} args
 * @param {string[]} [args.roundTestPaths]  what THIS round wrote
 * @param {(p:string)=>boolean} args.exists  is that path still on disk?
 * @param {Array} [args.survived]  PIT's survivors from the re-measurement
 * @param {string[]} [args.attempted]  attempt keys already spent on this unit
 * @param {string|null} [args.targetKey]  this round's target, excluded from the count
 * @param {number} [args.cap]  how many survivors the next round will be offered
 * @returns {{wroteAny:boolean, testsPresent:boolean, otherEligible:number}}
 */
function roundEvidence({ roundTestPaths, exists, survived, attempted, targetKey, cap = 20 }) {
  const { eligible, attemptKey } = require('./select');
  const wrote = roundTestPaths || [];
  // capped the same way `lastSurvived` is: counting "is there anything else to move on to"
  // over the full report answers yes about mutants the queue will never show
  const offerable = (survived || []).slice(0, Math.max(10, cap));
  return {
    wroteAny: wrote.length > 0,
    // every file, not any: generation writes the test and repair rewrites it, and losing
    // either means the measurement did not see what the round produced
    testsPresent: wrote.every((p) => exists(p)),
    otherEligible: eligible(offerable, attempted || [])
      .filter((m) => attemptKey(m) !== targetKey).length,
  };
}

module.exports = { roundOutcome, roundEvidence };
