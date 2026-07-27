'use strict';
// TDD for the logic that used to live as JavaScript strings inside the workflow JSON.
// Every case here is a defect that actually shipped, because a string in a JSON file has
// no compiler, no linter and no tests: a `str.replace` that silently matched nothing left
// the deployed prompt still asking the model to choose a mutant days after that decision
// had moved into the pipeline.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { coveragePrompt, mutationPrompt, repairPrompt } = require('../../prompts');

const gaps = {
  path: 'src/main/java/a/B.java', fqcn: 'a.B', method: 'calc', package: 'a', module: '.',
  jdk: 17, testFramework: 'junit5', coverage: 90, covPhaseMaxPct: 0,
  source: 'package a;\nclass B { int calc(int x){ return x + 1; } int other(){ return 2; } }',
  methodSource: '  int calc(int x){\n    return x + 1;\n  }',
  classHeader: 'package a;\nclass B {',
  siblingSignatures: ['int other();'],
  uncovered: { lines: [4] },
  covTestPath: 'src/test/java/a/BMacCovTest.java',
  mutTestPath: 'src/test/java/a/BMacMutTest.java',
  projectTestPath: null, existingTest: null, constraints: [], mutantsPerRound: 1,
  survived: [{ status: 'SURVIVED', mutator: 'MathMutator', line: 3, method: 'calc', description: 'Replaced integer addition with subtraction' }],
};

// ── coverage phase ──────────────────────────────────────────────────────────

test('the coverage phase is skipped for a method that already executes', () => {
  const r = coveragePrompt(gaps);
  assert.equal(r.skip, true);
  assert.match(r.reason, /already executed/);
});

test('the coverage phase runs for a method nothing executes', () => {
  const r = coveragePrompt({ ...gaps, coverage: 0, uncovered: { lines: 'all' } });
  assert.equal(r.skip, undefined);
  assert.match(r.system, /FIRST test/);
  assert.equal(r.targetPath, gaps.covTestPath);
  assert.ok(r.maxTokens <= 3000, 'the first test is meant to be small');
});

test('a fully covered method needs no coverage work', () => {
  const r = coveragePrompt({ ...gaps, coverage: 0, uncovered: { lines: [] } });
  assert.equal(r.skip, true);
  assert.match(r.reason, /fully covered/);
});

// ── mutation phase ──────────────────────────────────────────────────────────

test('the mutation prompt names the mutant to kill and does not ask the model to choose', () => {
  const r = mutationPrompt(gaps);
  assert.match(r.prompt, /MUTANT TO KILL/);
  assert.match(r.prompt, /MathMutator at line 3/);
  assert.doesNotMatch(r.prompt, /CHOOSE/i, 'selection is the pipeline’s job, not the model’s');
  assert.doesNotMatch(r.system, /"mutant"/, 'no mutant-number field to invent');
});

test('the prompt carries the target METHOD, not the whole class', () => {
  const r = mutationPrompt(gaps);
  assert.match(r.prompt, /int calc\(int x\)/);
  assert.doesNotMatch(r.prompt, /int other\(\)\{ return 2; \}/, 'other method bodies are noise');
  assert.match(r.prompt, /int other\(\);/, 'but their signatures help build inputs');
});

test('exactly one mutant is offered, and it is the first (best-ranked) survivor', () => {
  const many = [...gaps.survived, { status: 'SURVIVED', mutator: 'VoidMethodCallMutator', line: 9 }];
  const r = mutationPrompt({ ...gaps, survived: many });
  assert.equal(r.offered.length, 1);
  assert.equal(r.offered[0].mutator, 'MathMutator');
  assert.doesNotMatch(r.prompt, /VoidMethodCall/);
});

test('no survivors left ends the unit, and says whether any were attempted', () => {
  assert.match(mutationPrompt({ ...gaps, survived: [] }).reason, /no surviving mutants/);
  assert.match(mutationPrompt({ ...gaps, survived: [], attemptedMutants: 7 }).reason, /already been attempted/);
});

test('the model may decline when a mutation changes nothing observable', () => {
  assert.match(mutationPrompt(gaps).prompt, /empty tests array/);
});

// ── repair ──────────────────────────────────────────────────────────────────

test('the repair prompt shows the failure and the method, and pins the path', () => {
  const r = repairPrompt(gaps, { summary: 'expected:<2> but was:<3>' },
    [{ path: gaps.mutTestPath, content: 'class BMacMutTest {}' }]);
  assert.match(r.prompt, /expected:<2> but was:<3>/);
  assert.match(r.prompt, /int calc\(int x\)/);
  assert.match(r.system, /SAME file path/);
});

// ── reaching a method a test cannot call directly ──────────────────────────
const PRIVATE_GAPS = {
  ...gaps,
  method: 'isRecordStyleAccessor',
  visibility: 'private',
  routes: [[
    { method: 'JSONObject', visibility: 'public' },
    { method: 'populateMap', visibility: 'private' },
    { method: 'isRecordStyleAccessor', visibility: 'private' },
  ]],
  survived: [{ status: 'NO_COVERAGE', mutator: 'BooleanTrueReturnValsMutator', line: 2072, method: 'isRecordStyleAccessor' }],
  uncovered: { lines: 'all' },
};

test('a private target is named as private, with the routes that reach it', () => {
  // The model wrote a compiling, passing test for this private method that executed none
  // of it: cov 0 → 0, all 14 mutants alive, one round gone. It was never told it could
  // not call the method.
  const r = mutationPrompt(PRIVATE_GAPS);
  assert.match(r.prompt, /private/i);
  assert.match(r.prompt, /JSONObject/, 'the public route must be named');
  assert.match(r.prompt, /populateMap/);
});

test('the coverage phase gets the same routes', () => {
  const r = coveragePrompt(PRIVATE_GAPS);
  assert.equal(r.skip, undefined);
  assert.match(r.prompt, /private/i);
  assert.match(r.prompt, /JSONObject/);
});

test('reflection stays forbidden — the route is public API, not a back door', () => {
  const r = mutationPrompt(PRIVATE_GAPS);
  assert.match(r.system, /never use reflection/i);
  assert.doesNotMatch(r.prompt, /setAccessible/i);
});

test('a public target gets no routing section to wade through', () => {
  const r = mutationPrompt({ ...PRIVATE_GAPS, visibility: 'public', routes: [] });
  assert.doesNotMatch(r.prompt, /REACHED VIA|cannot be called directly/i);
});

test('a private method nothing calls is reported as unreachable, not attempted', () => {
  // dead private code: no test can execute it, so the round can only waste a call
  const r = mutationPrompt({ ...PRIVATE_GAPS, routes: [] });
  assert.equal(r.skip, true);
  assert.match(r.reason, /no public method|unreachable/i);
});

// ── repair must not abandon the mutant ────────────────────────────────────
const FAILING = [{ path: 'src/test/java/a/BMacMutTest.java', content: 'assertFalse(jo.has("x"));' }];

test('a repair is told which mutant the test still has to distinguish', () => {
  // The round targeted BooleanTrueReturnVals at line 2072. The test asserted the mutant's
  // absence, failed against real behaviour, and the repair simply flipped assertFalse to
  // assertTrue — a passing test that no longer distinguishes anything. The round missed.
  const r = repairPrompt({ ...gaps, targetMutant: { mutator: 'BooleanTrueReturnValsMutator', line: 2072 } },
    { summary: 'expected:<false> but was:<true>' }, FAILING);
  assert.match(r.prompt, /BooleanTrueReturnValsMutator/);
  assert.match(r.prompt, /2072/);
});

test('flipping an assertion to match the mutation is called out as worthless', () => {
  const r = repairPrompt({ ...gaps, targetMutant: { mutator: 'BooleanTrueReturnValsMutator', line: 2072 } },
    { summary: 'boom' }, FAILING);
  assert.match(r.system + r.prompt, /still fail on the mutated code|must still distinguish/i);
  assert.match(r.system + r.prompt, /drop the test|return an empty/i,
    'if it cannot be both correct and distinguishing, dropping it is the honest answer');
});

test('correcting a genuinely wrong expected value is still allowed', () => {
  const r = repairPrompt({ ...gaps, targetMutant: { mutator: 'MathMutator', line: 3 } }, { summary: 'boom' }, FAILING);
  assert.match(r.system, /correct the EXPECTED values/i);
});

test('a coverage-phase repair has no mutant to protect and says nothing about one', () => {
  const r = repairPrompt(gaps, { summary: 'boom' }, FAILING, 'improving_coverage');
  assert.doesNotMatch(r.prompt, /MUTANT THIS TEST MUST STILL KILL/);
});

// ── telling the model its last test never reached the method ──────────────
test('a test that never executed the method is fed back as exactly that', () => {
  // JSONObject#isRecordStyleAccessor is called under `if (isRecordType && ...)`. The model
  // built a plain bean, the guard was false, the method never ran — and the pipeline
  // reported "the new test does not distinguish it", which is a different problem with a
  // different fix. Round after round it rewrote assertions for code it never reached.
  const r = mutationPrompt({ ...gaps, lastRound: { reached: false, coverage: 0 } });
  assert.match(r.prompt, /never executed|did not reach/i);
  assert.match(r.prompt, /guard|condition|path/i);
});

test('a test that reached the method but missed is told the other thing', () => {
  const r = mutationPrompt({ ...gaps, lastRound: { reached: true, mutator: 'MathMutator', line: 3 } });
  assert.match(r.prompt, /reached|executed/i);
  assert.doesNotMatch(r.prompt, /never executed/i);
});

test('the first round on a unit has nothing to feed back', () => {
  const r = mutationPrompt(gaps);
  assert.doesNotMatch(r.prompt, /YOUR PREVIOUS ATTEMPT/);
});

// ── and the third case: the last test never compiled ──────────────────────
test('a test that broke the build is told THAT, not that its assertion was wrong', () => {
  // Three outcomes need three different fixes, and the block had two branches. A test
  // deleted for breaking the suite came back to the model as "the path is right; the
  // assertion is not" — advice about an assertion in a file that never compiled. On the
  // live run CDL#getValue and JSONObject#parseEndOfKeyValuePair each burned their whole
  // miss budget this way.
  const r = mutationPrompt({
    ...gaps,
    lastRound: { reached: true, coverage: 100, broken: true, error: 'cannot find symbol: method nextEntity()' },
  });
  assert.match(r.prompt, /did not compile|broke the build|broke the suite/i);
  assert.doesNotMatch(r.prompt, /The path is right; the assertion is not/);
});

test('and it is shown the actual failure, not asked to guess', () => {
  const r = mutationPrompt({
    ...gaps,
    lastRound: { reached: true, coverage: 100, broken: true, error: 'cannot find symbol: method nextEntity()' },
  });
  assert.match(r.prompt, /cannot find symbol: method nextEntity\(\)/);
});

test('a broken round with no captured error still says the test did not survive', () => {
  const r = mutationPrompt({ ...gaps, lastRound: { reached: true, coverage: 100, broken: true } });
  assert.match(r.prompt, /did not compile|broke the build|broke the suite/i);
});

// ── the coverage phase must run when nothing executes the survivors ────────
// DataLoaderFactory#newPublisherDataLoaderWithTry and its mapped sibling each measured 6
// mutants, ALL of them NO_COVERAGE, at 16.67% line coverage. The coverage phase skipped
// both — covPhaseMaxPct is 0, so it only fires at exactly 0% — on the stated grounds that
// "mutation tests will extend coverage as they kill NO_COVERAGE mutants".
//
// That is plausible and it was measured false: seven rounds per unit, coverage never
// moved off 16.67, mutation never left 0, ~15 minutes each. Killing a NO_COVERAGE mutant
// does require executing its line, which is precisely why asking for a distinguishing
// assertion is the wrong instruction — the test has to REACH the code first. Same
// distinction the round feedback already makes between "never executed it" and "executed
// it but did not distinguish the mutation".
const allNoCoverage = [
  { status: 'NO_COVERAGE', mutator: 'RemoveConditionalMutator_EQUAL_ELSE', line: 120 },
  { status: 'NO_COVERAGE', mutator: 'NullReturnValsMutator', line: 121 },
];

test('a partly covered method whose every survivor is NO_COVERAGE still gets a coverage test', () => {
  const r = coveragePrompt({
    ...gaps, coverage: 16.67, covPhaseMaxPct: 0,
    uncovered: { lines: [120, 121] }, survived: allNoCoverage,
  });
  assert.ok(!r.skip, `skipped with: ${r.reason}`);
});

test('but a method whose survivors actually run is left to the mutation phase', () => {
  // these lines execute; nothing asserts on them. That is the mutation phase's job.
  const r = coveragePrompt({
    ...gaps, coverage: 16.67, covPhaseMaxPct: 0,
    uncovered: { lines: [120] },
    survived: [{ status: 'SURVIVED', mutator: 'MathMutator', line: 120 }],
  });
  assert.ok(r.skip);
  assert.match(r.reason, /already executed/);
});

test('a mixed survivor list is left to the mutation phase too', () => {
  // one reachable survivor means a mutation test has something to bite on
  const r = coveragePrompt({
    ...gaps, coverage: 16.67, covPhaseMaxPct: 0,
    uncovered: { lines: [120, 121] },
    survived: [...allNoCoverage, { status: 'SURVIVED', mutator: 'MathMutator', line: 122 }],
  });
  assert.ok(r.skip);
});

test('a fully covered method is still skipped whatever its survivors say', () => {
  const r = coveragePrompt({
    ...gaps, coverage: 100, covPhaseMaxPct: 0, uncovered: { lines: [] }, survived: allNoCoverage,
  });
  assert.ok(r.skip);
  assert.match(r.reason, /fully covered/);
});
