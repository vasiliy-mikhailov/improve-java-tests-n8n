'use strict';
// A round can fail in two completely different ways, and the pipeline reported them as one.
//
// Observed on the live JSON-java run, 41 times across 19 units. The round writes a test,
// the suite goes RED, the repair fails, the workflow DELETES the test — and then /api/verify
// re-measures, finds the score unchanged (of course: there is no test) and announces
//
//   targeted mutant NullReturnValsMutator at line 360: STILL ALIVE — the new test does not
//   distinguish it
//
// about a file that is not on disk. CDL#shouldQuoteValue burned 8 rounds that way,
// JSONTokener#nextSimpleValue 7, and JSONWriter#value was abandoned at 20% after three of
// them — then re-picked and improved to 40% on the first round that actually compiled.
//
// The cost is not only the false sentence. The mutant was marked attempted at write time,
// so select.eligible() strikes it off for good; mutatorStats records a try with no kill,
// which demotes that whole mutator kind after two; and the miss counter ends the unit.
// Every one of those is a conclusion drawn from a measurement that never happened.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { roundOutcome, roundEvidence } = require('../../roundoutcome');

const TARGET = { mutator: 'NullReturnValsMutator', line: 360 };
const survivedWith = (m) => [{ ...m, status: 'SURVIVED' }];

test('a test that never reached the measurement says nothing about the mutant', () => {
  const r = roundOutcome({ targetMutant: TARGET, survived: survivedWith(TARGET), testsPresent: false });
  assert.equal(r.kind, 'broken');
  assert.equal(r.stillAlive, null, 'no claim either way — the test was not there');
  assert.doesNotMatch(r.message, /does not distinguish/,
    'that sentence describes a test that ran; this one did not');
  assert.match(r.message, /never reached the measurement/);
});

test('a broken round does not count as a try against the mutator kind', () => {
  // two tries with no kill demotes a mutator for the rest of the run. Compile failures
  // are evidence about the generated test, not about NullReturnVals mutants.
  const r = roundOutcome({ targetMutant: TARGET, survived: survivedWith(TARGET), testsPresent: false });
  assert.equal(r.countMutatorTry, false);
});

test('with other mutants left to try, a broken round moves on rather than retrying', () => {
  // The run's own evidence: JSONWriter#value broke on NullReturnVals at lines 360, 370 and
  // 380, then killed a RemoveConditional mutant on the first round that compiled. At
  // temperature 0.2 the same prompt yields the same uncompilable test, so handing the
  // mutant back would spend the round re-learning that.
  const r = roundOutcome({
    targetMutant: TARGET, survived: survivedWith(TARGET), testsPresent: false, otherEligible: 3,
  });
  assert.equal(r.unattempt, false);
});

test('but the LAST eligible mutant is handed back — the alternative is abandoning the unit', () => {
  // with nothing else to try, leaving it marked reads as "no un-attempted survivors left"
  // and ends the unit after a single round that measured nothing
  const r = roundOutcome({
    targetMutant: TARGET, survived: survivedWith(TARGET), testsPresent: false, otherEligible: 0,
  });
  assert.equal(r.unattempt, true);
  assert.match(r.message, /only remaining/);
});

test('and only once: a mutant that breaks twice stays marked even as the last one', () => {
  // otherwise the unit loops on it until the miss budget runs out
  const r = roundOutcome({
    targetMutant: TARGET, survived: survivedWith(TARGET), testsPresent: false,
    otherEligible: 0, brokenBefore: 1,
  });
  assert.equal(r.kind, 'broken');
  assert.equal(r.unattempt, false);
  assert.match(r.message, /twice/);
});

test('a test that ran and left the mutant alive is still reported as resisting it', () => {
  const r = roundOutcome({ targetMutant: TARGET, survived: survivedWith(TARGET), testsPresent: true });
  assert.equal(r.kind, 'resisted');
  assert.equal(r.stillAlive, true);
  assert.match(r.message, /STILL ALIVE — the new test does not distinguish it/);
  assert.equal(r.countMutatorTry, true);
  assert.equal(r.unattempt, false);
});

test('a kill is a kill', () => {
  const r = roundOutcome({ targetMutant: TARGET, survived: [], testsPresent: true });
  assert.equal(r.kind, 'killed');
  assert.equal(r.stillAlive, false);
  assert.equal(r.countMutatorTry, true);
});

test('the mutant is matched on kind AND line, not either alone', () => {
  // a different mutator at the same line is a different mutant
  const other = roundOutcome({
    targetMutant: TARGET, survived: survivedWith({ mutator: 'VoidMethodCallMutator', line: 360 }), testsPresent: true,
  });
  assert.equal(other.kind, 'killed', 'ours is gone from the survivor list');
  const sameKindElsewhere = roundOutcome({
    targetMutant: TARGET, survived: survivedWith({ mutator: 'NullReturnValsMutator', line: 999 }), testsPresent: true,
  });
  assert.equal(sameKindElsewhere.kind, 'killed');
});

test('no target mutant means no verdict to report', () => {
  assert.equal(roundOutcome({ targetMutant: null, survived: [], testsPresent: true }), null);
  assert.equal(roundOutcome({ targetMutant: { mutator: 'X' }, survived: [], testsPresent: true }), null,
    'a target without a line cannot be matched against PIT output');
});

test('a round that wrote no test at all makes no claim about the mutant either', () => {
  // The model declines when it judges the mutation equivalent, and the prompt tells it to.
  // That is a deliberate judgement about the mutant, so the mutant stays attempted — but
  // "the new test does not distinguish it" is still a sentence about a test that is not
  // there, and "broken" would be wrong too: nothing broke.
  const r = roundOutcome({ targetMutant: TARGET, survived: survivedWith(TARGET), wroteAny: false, testsPresent: false });
  assert.equal(r, null);
});

test('present-ness is only consulted when the round actually wrote something', () => {
  const r = roundOutcome({ targetMutant: TARGET, survived: survivedWith(TARGET), wroteAny: true, testsPresent: false });
  assert.equal(r.kind, 'broken');
});

test('a broken round is never credited with a kill it did not earn', () => {
  // the survivor list will not contain the mutant if PIT could not run the class at all;
  // absent a test, that must not read as success
  const r = roundOutcome({ targetMutant: TARGET, survived: [], testsPresent: false });
  assert.equal(r.kind, 'broken');
  assert.notEqual(r.stillAlive, false, 'silence is not a kill');
});

// ── the inputs roundOutcome is given: gathered, not assumed ───────────────
// The verify route computed these inline, so nothing tested them — and every verdict
// above is only as good as they are.
const { attemptKey } = require('../../select');

const surv = (mutator, line, index) => ({ mutator, line, index, status: 'SURVIVED' });

test('a round is "present" only when every file it wrote is still there', () => {
  const on = new Set(['a/X.java']);
  const e = roundEvidence({ roundTestPaths: ['a/X.java'], exists: (p) => on.has(p) });
  assert.equal(e.wroteAny, true);
  assert.equal(e.testsPresent, true);
});

test('one deleted file out of two is not "present"', () => {
  // generation writes the mut test, repair rewrites it; if the deleter takes either, the
  // measurement did not see what the round produced
  const on = new Set(['a/X.java']);
  const e = roundEvidence({ roundTestPaths: ['a/X.java', 'a/Y.java'], exists: (p) => on.has(p) });
  assert.equal(e.testsPresent, false);
});

test('a round that wrote nothing is not a broken round', () => {
  const e = roundEvidence({ roundTestPaths: [], exists: () => false });
  assert.equal(e.wroteAny, false);
});

test('other-eligible excludes the target itself and everything already attempted', () => {
  const target = surv('RemoveConditionalMutator_EQUAL_ELSE', 248, 11);
  const e = roundEvidence({
    roundTestPaths: [], exists: () => false,
    survived: [target, surv('RemoveConditionalMutator_EQUAL_ELSE', 248, 14), surv('MathMutator', 90, 3)],
    attempted: [attemptKey(target)],
    targetKey: attemptKey(target),
  });
  assert.equal(e.otherEligible, 2, 'the sibling at index 14 and the Math mutant both remain');
});

test('other-eligible counts only mutants the next round will actually be offered', () => {
  // lastSurvived is capped, so a survivor past the cap is not something to "move on to"
  const target = surv('A', 1, 0);
  const many = [target, ...Array.from({ length: 40 }, (_, i) => surv('B', i + 2, i))];
  const e = roundEvidence({
    roundTestPaths: [], exists: () => false,
    survived: many, attempted: [attemptKey(target)], targetKey: attemptKey(target), cap: 10,
  });
  assert.equal(e.otherEligible, 9, 'ten offerable, minus the attempted target');
});

test('with no survivor list there is nothing else to move on to', () => {
  const e = roundEvidence({ roundTestPaths: [], exists: () => false });
  assert.equal(e.otherEligible, 0);
});
