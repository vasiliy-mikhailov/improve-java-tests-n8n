'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { scopeToMethod } = require('../../pit');

// shape parseReport returns
const report = () => ({
  file: 'src/main/java/org/json/JSONObject.java',
  fqcn: 'org.json.JSONObject',
  totalMutants: 4,
  killed: 1,
  score: 25,
  survived: [
    { mutator: 'MathMutator', line: 3005, method: 'toString', status: 'SURVIVED', difficulty: 0, detected: false },
    { mutator: 'BooleanTrueReturnValsMutator', line: 2072, method: 'isRecordStyleAccessor', status: 'NO_COVERAGE', difficulty: 3, detected: false },
    { mutator: 'RemoveConditionalMutator', line: 2071, method: 'isRecordStyleAccessor', status: 'NO_COVERAGE', difficulty: 5, detected: false },
  ],
  all: [
    { mutator: 'MathMutator', line: 3005, method: 'toString', detected: false },
    { mutator: 'BooleanTrueReturnValsMutator', line: 2072, method: 'isRecordStyleAccessor', detected: false },
    { mutator: 'RemoveConditionalMutator', line: 2071, method: 'isRecordStyleAccessor', detected: false },
    { mutator: 'NegateConditionalsMutator', line: 2077, method: 'isRecordStyleAccessor', detected: true },
  ],
  byMethod: { toString: { total: 1, killed: 0, survived: 1 }, isRecordStyleAccessor: { total: 3, killed: 1, survived: 2 } },
});

test('survivors from other methods never reach the unit', () => {
  // A MathMutator in toString() led this unit's queue because it was SURVIVED while the
  // unit's own mutants were NO_COVERAGE. A whole round was spent writing a toString test,
  // then scored against isRecordStyleAccessor — a guaranteed miss.
  const r = scopeToMethod(report(), 'isRecordStyleAccessor');
  assert.deepEqual(r.survived.map((m) => m.method), ['isRecordStyleAccessor', 'isRecordStyleAccessor']);
});

test('the score is recomputed over the method alone', () => {
  const r = scopeToMethod(report(), 'isRecordStyleAccessor');
  assert.equal(r.totalMutants, 3);
  assert.equal(r.killed, 1);
  assert.equal(r.score, 33.33, '1 of 3, not 1 of 4 — the unit is the method');
});

test('the full method list survives scoping — exclusions are built from it', () => {
  const r = scopeToMethod(report(), 'isRecordStyleAccessor');
  assert.deepEqual(Object.keys(r.byMethod).sort(), ['isRecordStyleAccessor', 'toString']);
});

test('overloads share a name and stay in the same unit', () => {
  const rep = report();
  // toString() as well as toString(int): one killed, one surviving. `survived` is always
  // a subset of `all`, so a new survivor goes into both.
  rep.all.push({ mutator: 'MathMutator', line: 2960, method: 'toString', detected: true });
  const extra = { mutator: 'VoidMethodCallMutator', line: 2971, method: 'toString', status: 'NO_COVERAGE', difficulty: 4, detected: false };
  rep.all.push(extra);
  rep.survived.push(extra);
  const r = scopeToMethod(rep, 'toString');
  assert.equal(r.totalMutants, 3, 'both toString(int) and toString() count');
  assert.equal(r.killed, 1);
  assert.equal(r.survived.length, 2);
});

test('a class-scoped run is left exactly as it is', () => {
  const rep = report();
  assert.deepEqual(scopeToMethod(rep, null), rep);
  assert.deepEqual(scopeToMethod(rep, undefined), rep);
});

test('a method PIT found nothing for scores 0 mutants, not 100%', () => {
  const r = scopeToMethod(report(), 'noSuchMethod');
  assert.equal(r.totalMutants, 0);
  assert.equal(r.survived.length, 0);
  assert.equal(r.score, 0, 'an unmutated method is not a perfect one');
  assert.equal(r.empty, true, 'and the caller must be able to tell');
});

test('constructors are addressable like any other method', () => {
  const rep = report();
  rep.all.push({ mutator: 'MathMutator', line: 100, method: '<init>', detected: false });
  rep.survived.push({ mutator: 'MathMutator', line: 100, method: '<init>', status: 'SURVIVED', difficulty: 0, detected: false });
  const r = scopeToMethod(rep, '<init>');
  assert.equal(r.totalMutants, 1);
  assert.equal(r.survived.length, 1);
});

// ── parsing a real PIT report ─────────────────────────────────────────────
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { parseReport } = require('../../pit');

function writeReport(mutations) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ijt-pit-'));
  const file = path.join(dir, 'mutations.xml');
  fs.writeFileSync(file, `<?xml version="1.0" encoding="UTF-8"?>\n<mutations>\n${mutations}\n</mutations>\n`);
  return file;
}
const mutation = ({ cls = 'a.B', method = 'run', line = 10, detected = false, status = 'SURVIVED', mutator = 'MathMutator' }) =>
  `<mutation detected='${detected}' status='${status}'>`
  + `<sourceFile>B.java</sourceFile><mutatedClass>${cls}</mutatedClass><mutatedMethod>${method}</mutatedMethod>`
  + `<lineNumber>${line}</lineNumber><mutator>org.pitest.mutationtest.engine.gregor.mutators.${mutator}</mutator>`
  + `<description>mutated</description></mutation>`;

test('a constructor arrives XML-escaped from PIT and must still be its own unit', () => {
  // JaCoCo names give us "<init>"; PIT's report writer escapes it. Comparing the two
  // matches nothing, so scopeToMethod returned 0 mutants and the unit was written off as
  // having no mutation surface — for a constructor PIT had actually mutated.
  const file = writeReport([
    mutation({ method: '&lt;init&gt;', line: 5 }),
    mutation({ method: '&lt;init&gt;', line: 6, detected: true, status: 'KILLED' }),
    mutation({ method: 'run', line: 10 }),
  ].join('\n'));
  const parsed = parseReport(file, 'src/main/java/a/B.java', 'a.B');
  assert.deepEqual([...new Set(parsed.all.map((m) => m.method))].sort(), ['<init>', 'run']);
  const scoped = scopeToMethod(parsed, '<init>');
  assert.equal(scoped.totalMutants, 2);
  assert.equal(scoped.killed, 1);
  assert.equal(scoped.empty, undefined === scoped.empty ? undefined : false);
});

test('the numeric escaping older PIT emits decodes too', () => {
  const file = writeReport(mutation({ method: '&#60;init&#62;', line: 5 }));
  const parsed = parseReport(file, 'src/main/java/a/B.java', 'a.B');
  assert.equal(parsed.all[0].method, '<init>');
});

test('another class whose name merely starts with ours is not scored into the unit', () => {
  // the filter was mutatedClass.startsWith(fqcn), so a.BFoo counted as a.B — and with a
  // method filter by NAME only, a.BFoo#run was scored into a.B#run's result
  const file = writeReport([
    mutation({ cls: 'a.B', method: 'run', line: 10 }),
    mutation({ cls: 'a.BFoo', method: 'run', line: 99, detected: true, status: 'KILLED' }),
  ].join('\n'));
  const parsed = parseReport(file, 'src/main/java/a/B.java', 'a.B');
  assert.deepEqual(parsed.all.map((m) => m.line), [10], 'a.BFoo is a different class');
  assert.equal(scopeToMethod(parsed, 'run').killed, 0, 'its kill must not be credited here');
});

test('an inner or anonymous class of the target still belongs to it', () => {
  const file = writeReport([
    mutation({ cls: 'a.B', method: 'run', line: 10 }),
    mutation({ cls: 'a.B$Inner', method: 'run', line: 20 }),
    mutation({ cls: 'a.B$1', method: 'run', line: 30 }),
  ].join('\n'));
  const parsed = parseReport(file, 'src/main/java/a/B.java', 'a.B');
  assert.equal(parsed.all.length, 3);
});
