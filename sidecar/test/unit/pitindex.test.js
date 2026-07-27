'use strict';
// PIT's mutant index, parsed from the shape PIT actually writes.
//
// Captured verbatim from /data/repos/.../build/reports/pitest/mutations.xml on the box:
// the index is NOT a bare <index> element, it is nested inside <indexes>, and <block>
// inside <blocks>. A fixture written from memory would have used the flat spelling, passed,
// and left the parser returning null for every mutant — the same mistake as fixtures whose
// braces balanced when the file that broke the parser did not.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { parseReport } = require('../../pit');
const { attemptKey } = require('../../select');

const mutation = (status, line, mutator, index, block) =>
  `<mutation detected='${status !== 'SURVIVED' && status !== 'NO_COVERAGE'}' status='${status}' numberOfTestsRun='0'>`
  + '<sourceFile>JSONObject.java</sourceFile>'
  + '<mutatedClass>org.json.JSONObject</mutatedClass>'
  + '<mutatedMethod>parseJSONObject</mutatedMethod>'
  + '<methodDescription>()V</methodDescription>'
  + `<lineNumber>${line}</lineNumber>`
  + `<mutator>org.pitest.mutationtest.engine.gregor.mutators.${mutator}</mutator>`
  + `<indexes><index>${index}</index></indexes>`
  + `<blocks><block>${block}</block></blocks>`
  + '<killingTest/><description>removed conditional</description></mutation>';

// the real case: three mutants, one kind, one line
const XML = '<?xml version="1.0" encoding="UTF-8"?>\n<mutations partial="true">\n'
  + mutation('SURVIVED', 248, 'RemoveConditionalMutator_EQUAL_ELSE', 11, 0) + '\n'
  + mutation('SURVIVED', 248, 'RemoveConditionalMutator_EQUAL_ELSE', 14, 0) + '\n'
  + mutation('NO_COVERAGE', 248, 'RemoveConditionalMutator_EQUAL_ELSE', 17, 1) + '\n'
  + mutation('KILLED', 250, 'MathMutator', 22, 2) + '\n'
  + '</mutations>\n';

const write = () => {
  const p = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'pitidx-')), 'mutations.xml');
  fs.writeFileSync(p, XML);
  return p;
};

test('the index is read from inside <indexes>, where PIT puts it', () => {
  const r = parseReport(write(), 'src/main/java/org/json/JSONObject.java', 'org.json.JSONObject');
  const alive = r.survived.filter((m) => m.line === 248);
  assert.equal(alive.length, 3);
  assert.deepEqual(alive.map((m) => m.index).sort((a, b) => a - b), [11, 14, 17],
    'a null here means the parser is looking for a flat <index> that PIT never writes');
});

test('so the three of them are three attempt keys', () => {
  const r = parseReport(write(), 'src/main/java/org/json/JSONObject.java', 'org.json.JSONObject');
  const keys = new Set(r.survived.filter((m) => m.line === 248).map(attemptKey));
  assert.equal(keys.size, 3, 'one key for all three is what settled parseJSONObject at 78.57%');
});

test('the block is still read from inside <blocks>', () => {
  const r = parseReport(write(), 'src/main/java/org/json/JSONObject.java', 'org.json.JSONObject');
  assert.equal(r.survived.find((m) => m.index === 17).block, '1');
});
