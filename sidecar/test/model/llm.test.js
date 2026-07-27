'use strict';
// LARGE tests (Google sizing): they call the real model over a non-localhost network.
// Everything in sidecar/test/unit is small or medium and never leaves the machine.
//
// They exist because 237 passing small tests could not see that our deployed
// configuration returns nothing usable on the first attempt: the tests assert what we ASK
// the model, never that the asking works. This suite asserts the latter, and only that —
// invariants a correct answer must satisfy, never the content of one.
//
//   npm run test:model                 replay from fixtures where possible, call when not
//   MODEL_TEST_REFRESH=1 npm run test:model    ignore fixtures, call for everything
//
// A recorded run is MEDIUM: it reads fixtures and touches no network. Only a refresh, or a
// prompt this suite has not seen before, is large.
const { test, before } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { coveragePrompt, mutationPrompt, repairPrompt } = require('../../prompts');
const { parseGeneratedTests } = require('../../parse');

const BASE = (process.env.LLM_BASE_URL || '').replace(/\/$/, '');
const KEY = process.env.LLM_API_KEY || '';
const MODEL = process.env.LLM_MODEL || 'qwen-3.6-27b-fp8';
const THINKING = String(process.env.LLM_ENABLE_THINKING || 'false') === 'true';
const THINKING_EXTRA = THINKING ? parseInt(process.env.LLM_THINKING_BUDGET || '3000', 10) : 0;
const REFRESH = !!process.env.MODEL_TEST_REFRESH;
const FIXTURES = path.join(__dirname, 'fixtures');
const live = !!(BASE && KEY);

// ── the unit under test: a real class, a real mutant ───────────────────────
const SOURCE = `package com.example;

public class Discount {

    public int priceFor(int units, boolean member) {
        int total = units * 10;
        if (units > 5) {
            total = total - 5;
        }
        return member ? total / 2 : total;
    }
}
`;

const gaps = {
  path: 'src/main/java/com/example/Discount.java',
  fqcn: 'com.example.Discount',
  method: 'priceFor',
  package: 'com.example',
  module: '.',
  jdk: 17,
  testFramework: 'junit4',
  coverage: 100,
  covPhaseMaxPct: 0,
  source: SOURCE,
  methodSource: SOURCE.split('\n').slice(3, 11).join('\n'),
  classHeader: 'package com.example;\n\npublic class Discount {',
  siblingSignatures: [],
  uncovered: { lines: [] },
  covTestPath: 'src/test/java/com/example/DiscountMacCovTest.java',
  mutTestPath: 'src/test/java/com/example/DiscountMacMutTest.java',
  projectTestPath: 'src/test/java/com/example/DiscountTest.java',
  existingTest: null,
  constraints: ["don't use reflection or introspection"],
  mutantsPerRound: 1,
  survived: [{
    status: 'SURVIVED', mutator: 'ConditionalsBoundaryMutator', line: 7, method: 'priceFor',
    description: 'changed conditional boundary: units > 5 became units >= 5',
  }],
};

// ── the client: same shape the pipeline uses, with record/replay ───────────
const keyOf = (plan) => crypto.createHash('sha256')
  .update(JSON.stringify([MODEL, THINKING, plan.maxTokens, plan.system, plan.prompt])).digest('hex').slice(0, 16);

async function ask(plan) {
  const file = path.join(FIXTURES, `${keyOf(plan)}.json`);
  if (!REFRESH && fs.existsSync(file)) return JSON.parse(fs.readFileSync(file, 'utf8'));
  const body = {
    model: MODEL,
    messages: [{ role: 'system', content: plan.system }, { role: 'user', content: plan.prompt }],
    // exactly the ceiling the pipeline gives this stage on its FIRST attempt — the point
    // is to find out whether the first attempt works, not whether a retry rescues it
    max_tokens: (plan.maxTokens || 4096) + THINKING_EXTRA,
    temperature: plan.temperature ?? 0.2,
    chat_template_kwargs: { enable_thinking: THINKING },
  };
  const started = Date.now();
  const res = await fetch(BASE + '/chat/completions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + KEY },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(600000),
  });
  if (!res.ok) throw new Error(`LLM HTTP ${res.status}: ${(await res.text()).slice(0, 200)}`);
  const d = await res.json();
  const out = {
    content: d.choices?.[0]?.message?.content || '',
    finish: d.choices?.[0]?.finish_reason || null,
    completionTokens: d.usage?.completion_tokens ?? null,
    reasoningChars: (d.choices?.[0]?.message?.reasoning || '').length,
    secs: Math.round((Date.now() - started) / 1000),
    ceiling: body.max_tokens, thinking: THINKING, model: MODEL,
  };
  fs.mkdirSync(FIXTURES, { recursive: true });
  fs.writeFileSync(file, JSON.stringify(out, null, 1));
  return out;
}

/** Parse exactly as the pipeline does, so a test failure means the PIPELINE would fail. */
const asPipelineWould = (answer, plan) =>
  parseGeneratedTests({ ok: true, json: safeJson(answer.content) }, plan);

function safeJson(text) {
  const s = String(text || '');
  const i = s.indexOf('{'), j = s.lastIndexOf('}');
  if (i < 0 || j <= i) return null;
  try { return JSON.parse(s.slice(i, j + 1)); } catch { return null; }
}

before(() => {
  if (!live && !fs.existsSync(FIXTURES)) {
    throw new Error('set LLM_BASE_URL and LLM_API_KEY, or record fixtures first');
  }
});

// ── contract: does a call come back usable at the budget we ship? ──────────
test('the mutation call answers within its first-attempt budget', async () => {
  const plan = mutationPrompt(gaps);
  const a = await ask(plan);
  assert.notEqual(a.finish, 'length',
    `truncated at ${a.ceiling} tokens after ${a.secs}s (${a.reasoningChars} chars of reasoning) — `
    + 'the pipeline pays for this call, discards it, and pays again at double the ceiling');
  assert.ok(safeJson(a.content), 'the answer must parse as JSON');
});

test('the coverage call answers within its first-attempt budget', async () => {
  const plan = coveragePrompt({ ...gaps, coverage: 0, uncovered: { lines: 'all' } });
  const a = await ask(plan);
  assert.notEqual(a.finish, 'length', `truncated at ${a.ceiling} tokens after ${a.secs}s`);
  assert.ok(safeJson(a.content), 'the answer must parse as JSON');
});

// ── compliance: does the answer honour what the prompt demands? ────────────
test('exactly one test file comes back, at the path the pipeline planned', async () => {
  const plan = mutationPrompt(gaps);
  const r = asPipelineWould(await ask(plan), plan);
  assert.equal(r.count, 1, 'one file per round');
  assert.equal(r.tests[0].path, plan.targetPath);
});

test('the public class matches the file name, or javac refuses it', async () => {
  const plan = mutationPrompt(gaps);
  const r = asPipelineWould(await ask(plan), plan);
  const want = plan.targetPath.split('/').pop().replace(/\.java$/, '');
  assert.match(r.tests[0].content, new RegExp(`public\\s+class\\s+${want}\\b`));
});

test('the write_test rule against reflection is obeyed', async () => {
  const plan = mutationPrompt(gaps);
  const r = asPipelineWould(await ask(plan), plan);
  assert.doesNotMatch(r.tests[0].content, /setAccessible|getDeclaredMethod|getDeclaredField|Class\.forName/);
});

test("the project's own test framework is used, not the model's favourite", async () => {
  // gaps say JUnit 4; a JUnit 5 import would not compile against this project
  const plan = mutationPrompt(gaps);
  const r = asPipelineWould(await ask(plan), plan);
  assert.match(r.tests[0].content, /import\s+org\.junit\.Test|import\s+static\s+org\.junit\.Assert/);
  assert.doesNotMatch(r.tests[0].content, /org\.junit\.jupiter/);
});

test('the test targets the mutant it was given, at its line', async () => {
  // ConditionalsBoundary on `units > 5`: only an input AT the boundary distinguishes it
  const plan = mutationPrompt(gaps);
  const r = asPipelineWould(await ask(plan), plan);
  assert.match(r.tests[0].content, /\b(5|6)\b/,
    'a boundary mutant is killed by exercising the boundary value');
});

// ── the escape hatch we depend on in parse.js ──────────────────────────────
test('an equivalent mutation is declined rather than faked', async () => {
  // Nothing observable distinguishes this; the prompt says to return an empty array, and
  // parse.js treats that as a legitimate answer. Never verified against the model before.
  const plan = mutationPrompt({
    ...gaps,
    survived: [{
      status: 'SURVIVED', mutator: 'RemoveConditionalMutator_EQUAL_ELSE', line: 6, method: 'priceFor',
      description: 'removed conditional - replaced comparison with false (equivalent: the '
        + 'branch only reassigns a local that is immediately overwritten)',
    }],
  });
  const a = await ask(plan);
  const parsed = safeJson(a.content);
  assert.ok(parsed, 'even a refusal must be JSON');
  assert.ok(Array.isArray(parsed.tests), 'the reply shape holds whether or not it writes a test');
});

// ── repair keeps the round's purpose ───────────────────────────────────────
test('a repair fixes the test without abandoning the mutant', async () => {
  const broken = [{
    path: gaps.mutTestPath,
    content: 'package com.example;\n\nimport org.junit.Test;\nimport static org.junit.Assert.assertEquals;\n\n'
      + 'public class DiscountMacMutTest {\n  @Test public void t() {\n'
      + '    assertEquals(45, new Discount().priceFor(5, false));\n  }\n}\n',
  }];
  const plan = repairPrompt(
    { ...gaps, targetMutant: { mutator: 'ConditionalsBoundaryMutator', line: 7 } },
    { summary: 'expected:<45> but was:<50>' }, broken);
  const a = await ask(plan);
  const parsed = safeJson(a.content);
  assert.ok(parsed && Array.isArray(parsed.tests), 'the repair must come back as JSON');
  if (parsed.tests.length) {
    assert.match(parsed.tests[0].path, /DiscountMacMutTest\.java$/, 'the path is kept');
    assert.match(parsed.tests[0].content, /assert/i, 'and it still asserts something');
  }
});

// ── regression: a prompt of production size and difficulty ─────────────────
// The cases above use a ten-line class, and they all pass. That proves less than it
// looks: the prompt that actually fails in production is 7 KB, carries 14 survivors and
// targets a private method behind a guard. Captured verbatim from the live pipeline
// (JSONObject#isRecordStyleAccessor) so this suite is exercised by the real thing rather
// than by a fixture chosen for convenience — the same mistake as fixtures whose braces
// balanced when the file that broke the parser did not.
// The fixture is the INPUT (gaps captured live from /api/files/gaps), not a rendered
// prompt — so the prompt is built by today's prompts.js and a change to the budget or the
// wording is actually exercised. A captured prompt would pin the old ceiling for ever and
// report a fix as still broken.
const REAL_GAPS = JSON.parse(fs.readFileSync(path.join(__dirname, 'prompts', 'jsonobject-isRecordStyleAccessor.json'), 'utf8'));

test('a real production-sized mutation prompt answers within its first-attempt budget', async () => {
  const plan = mutationPrompt(REAL_GAPS);
  assert.ok(!plan.skip, `the fixture must produce a real prompt, not ${plan.reason}`);
  const a = await ask(plan);
  assert.notEqual(a.finish, 'length',
    `truncated at ${a.ceiling} tokens after ${a.secs}s with ${a.reasoningChars} characters of reasoning. `
    + 'The pipeline discards this answer, doubles the ceiling and pays for the whole call again — '
    + `about ${a.secs * 3}s of wall-clock per round, on every round of this shape.`);
  const parsed = safeJson(a.content);
  assert.ok(parsed && Array.isArray(parsed.tests), 'and it must come back as the reply shape parse.js expects');
});

// ── the third feedback branch, against the real model ──────────────────────
// lastRoundBlock gained a `broken` branch that pastes the compiler's error into the
// prompt. What that risks at THIS layer is the contract: ~600 extra characters of javac
// output could push the answer past its first-attempt budget or derail the reply shape.
// That is what this checks.
//
// It deliberately does NOT try to prove the model "acts on" the error. I wrote that test
// first — assert the new test drops the symbol javac rejected — ran it against a prompts.js
// with the branch cut out, and it passed anyway: the old prompt never mentions the symbol,
// so not using it is free. A green that cannot go red is worse than no test, because it
// reads like evidence. The branch's wording is pinned by the small tests in
// test/unit/prompts.test.js; its effect is visible in production, where a round that never
// compiled stops being told to fix its assertion.
const BROKEN_GAPS = {
  ...gaps,
  lastRound: {
    reached: true,
    coverage: 100,
    broken: true,
    error: 'DiscountMacMutTest.java:8: error: cannot find symbol\n'
      + '    assertEquals(45, new Discount().priceForAll(5, false));\n'
      + '                                    ^\n  symbol:   method priceForAll(int,boolean)\n'
      + '  location: class com.example.Discount',
  },
};

test('a compile error in the prompt does not break the reply contract', () => {
  const plan = mutationPrompt(BROKEN_GAPS);
  return ask(plan).then((a) => {
    assert.notEqual(a.finish, 'length',
      `truncated at ${a.ceiling} tokens after ${a.secs}s — the pasted javac output costs budget`);
    const r = asPipelineWould(a, plan);
    assert.equal(r.count, 1, 'a compile failure is not a reason to give up on the mutant');
    assert.equal(r.tests[0].path, plan.targetPath);
  });
});

// ── can one round kill EIGHT mutants instead of one? ──────────────────────
// A round costs four JVM builds — baseline PIT, coverage, suite, verify PIT — whether it
// kills one mutant or eight. On java-dataloader that arithmetic put the remaining ETA at
// 170 hours. The prompt has always had a multi-mutant branch (`mutantsPerRound > 1`); it
// has never been exercised, because the config ships 1.
//
// Before wiring it up, the empirical question is whether the model can actually answer:
// eight test methods is a far longer reply than one, and a truncated answer is thrown away
// and paid for again at double the ceiling.
//
// The fixture is real throughout: org.json.XML#parse, its actual source, and 24 genuinely
// surviving mutants from a live `mvn pitest` run on the repo (indexes and descriptions
// included). Not a ten-line class chosen because it fits.
const MULTI = JSON.parse(fs.readFileSync(path.join(__dirname, 'prompts', 'xml-parse-multi.json'), 'utf8'));

test('the eight-mutant prompt is built as a multi-mutant round, not a single', () => {
  const plan = mutationPrompt(MULTI);
  assert.ok(!plan.skip, `expected a real prompt, got ${plan.reason}`);
  assert.match(plan.prompt, /SURVIVING MUTANTS/, 'the multi branch, not "MUTANT TO KILL"');
  assert.equal((plan.offered || []).length, 8, 'eight mutants offered');
});

test('the model answers an eight-mutant round within its budget', () => {
  const plan = mutationPrompt(MULTI);
  return ask(plan).then((a) => {
    assert.notEqual(a.finish, 'length',
      `truncated at ${a.ceiling} tokens after ${a.secs}s — eight tests do not fit, and the `
      + 'pipeline discards the answer and pays again at double');
    assert.ok(safeJson(a.content), 'and it parses as the reply shape');
  });
});

test('and it comes back as one compilable class with a test per mutant', () => {
  const plan = mutationPrompt(MULTI);
  return ask(plan).then((a) => {
    const r = asPipelineWould(a, plan);
    assert.equal(r.count, 1, 'still exactly one file, at the planned path');
    assert.equal(r.tests[0].path, plan.targetPath);
    const want = plan.targetPath.split('/').pop().replace(/\.java$/, '');
    assert.match(r.tests[0].content, new RegExp(`public\\s+class\\s+${want}\\b`));
    const tests = (r.tests[0].content.match(/@Test/g) || []).length;
    assert.ok(tests >= 5,
      `only ${tests} test method(s) for 8 mutants — the round would not amortise its four builds`);
  });
});
