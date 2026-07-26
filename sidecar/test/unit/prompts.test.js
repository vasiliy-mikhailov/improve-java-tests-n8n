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
