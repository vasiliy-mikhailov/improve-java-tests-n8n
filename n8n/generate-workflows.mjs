// Generates the n8n workflow JSON for improve-java-tests-n8n.
//
// HARD CONSTRAINT: only native n8n nodes — Manual/Webhook triggers, HTTP Request,
// Code (pure JS data transforms: no child_process, no fs, no shell), IF, NoOp.
// Every OS-touching operation (git, Maven/Gradle, JaCoCo, PIT, gh, LLM) goes through
// the sidecar HTTP API on :3000.
//
// Run: node generate-workflows.mjs → writes workflows/Improve-Java-Tests.json

import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomUUID } from 'node:crypto';

const __dirname = dirname(fileURLToPath(import.meta.url));
const OUT = join(__dirname, 'workflows');
mkdirSync(OUT, { recursive: true });

const API = 'http://127.0.0.1:3000';
const WORKFLOW_ID = 'ijtImproveJavaTests1';

// ── tiny builder ───────────────────────────────────────────────────────────
let _x = 0, _y = 0;
const pos = () => { _x += 220; if (_x > 3600) { _x = 220; _y += 200; } return [_x, _y]; };
const w = { name: 'Improve Java Tests', nodes: [], connections: {} };
function add(type, name, parameters = {}, typeVersion = 1) {
  w.nodes.push({ parameters, type, typeVersion, position: pos(), id: randomUUID(), name });
  return name;
}
function link(from, to, out = 0) {
  const conn = (w.connections[from] ||= { main: [] });
  while (conn.main.length <= out) conn.main.push([]);
  if (!conn.main[out].some((c) => c.node === to)) conn.main[out].push({ node: to, type: 'main', index: 0 });
}
function chain(...names) { for (let i = 0; i < names.length - 1; i++) link(names[i], names[i + 1]); }

const NoOp = (name) => add('n8n-nodes-base.noOp', name, {});
const Code = (name, jsCode) => add('n8n-nodes-base.code', name, { jsCode, mode: 'runOnceForAllItems' }, 2);
const IfNum = (name, valueExpr, operation, value2) =>
  add('n8n-nodes-base.if', name, {
    conditions: { number: [{ value1: valueExpr, value2, operation }] },
  }, 1);

function Http(name, { method = 'POST', path, urlExpr, body, timeout = 600000 }) {
  const params = {
    method,
    url: urlExpr || API + path,
    options: { timeout, redirect: { redirect: {} } },
  };
  if (method === 'POST') {
    params.sendBody = true;
    params.specifyBody = 'json';
    params.jsonBody = body || '={{ {} }}';
  }
  return add('n8n-nodes-base.httpRequest', name, params, 4.2);
}

// ── shared prompt fragments (live inside Code nodes) ───────────────────────

// What a generated Java test must satisfy to compile, run and be worth committing.
const COMMON_TEST_RULES = `
- ONE test file only. Its public class name MUST equal the file name, and the package declaration MUST match the directory under src/test/java.
- Use the SAME test framework and assertion library as the style reference (JUnit 5: org.junit.jupiter.api.Test/Assertions; JUnit 4: org.junit.Test/org.junit.Assert). Do NOT introduce a dependency the project does not already have.
- Call only the PUBLIC API of the class under test. Never use reflection, setAccessible, or read source/bytecode.
- Do NOT modify production code, existing tests, or the build files.
- Deterministic: no network, no clock/randomness without fixing them, no reliance on file-system state or test execution order.
- Every test must ASSERT a value or an observable side effect; a test that only checks "does not throw" is worthless.
- The tests MUST compile and PASS against the CURRENT implementation.`;

// The heart of mutation-killing: PIT mutator → the assertion that catches it.
const MUTATOR_PLAYBOOK = `
Mutator → the assertion that detects it:
- ConditionalsBoundary (< ↔ <=, > ↔ >=): exercise the value EXACTLY at the boundary and assert the branch taken there.
- NegateConditionals (== ↔ !=, < ↔ >=): assert behaviour on BOTH sides of the condition.
- Math (+ ↔ -, * ↔ /, %): choose inputs where the two operations differ; assert the exact numeric result.
- Increments (i++ ↔ i--): assert the final accumulated/counted value.
- ReturnVals / NullReturnVals / PrimitiveReturnVals / BooleanTrueReturnVals / EmptyObjectReturnVals: assert the ACTUAL returned value or the returned object's contents (size, a known element) — never merely that it is non-null.
- VoidMethodCall / ConstructorCall: assert the observable SIDE EFFECT of the removed call (state change, collaborator interaction, output).
- RemoveConditional: assert that the guarded behaviour does NOT happen when the condition is false.
- InlineConstant: assert the exact constant-derived value.
- Switch: assert the outcome for each case label, including the default.
NO_COVERAGE survivors are the cheapest kills: the line never runs at all, so simply calling the method with representative inputs and asserting the result usually removes a whole cluster of them. Do those first.`;

// =============================================================================
// SPINE
// =============================================================================
add('n8n-nodes-base.manualTrigger', 'Start (manual)', {});
add('n8n-nodes-base.webhook', 'Start (webhook)', {
  path: 'improve-run', httpMethod: 'POST', responseMode: 'onReceived', options: {},
}, 2);
w.nodes[w.nodes.length - 1].webhookId = 'aa11bb22-ijt-4run-9000-improvejava01';

Http('Start Run', { path: '/api/run/start', body: '={{ $json.body || {} }}' });
Http('Clone Repo', { path: '/api/repo/clone', timeout: 1200000 });
Http('Rules: post-clone', { path: '/api/rules/apply', body: `={{ { stage: 'post_clone' } }}` });
Http('Build & Detect', { path: '/api/repo/prepare', timeout: 3000000 });
Http('Baseline Coverage', { path: '/api/coverage/run', body: `={{ { phase: 'baseline', stage: 'measuring_baseline' } }}`, timeout: 3600000 });
Http('Rules: pre-pick', { path: '/api/rules/apply', body: `={{ { stage: 'pre_pick' } }}` });
Http('Rules: write-test', { path: '/api/rules/apply', body: `={{ { stage: 'write_test' } }}` });

NoOp('Next Iteration');
Http('Get Candidates', { method: 'GET', path: '/api/files/candidates' });
IfNum('More Work?', '={{ $json.done ? 0 : 1 }}', 'equal', 1);
Http('Rules: pick file', { path: '/api/rules/apply', body: `={{ { stage: 'pick_file' } }}` });
IfNum('File Picked?', `={{ $json.result && $json.result.file ? 1 : 0 }}`, 'equal', 1);
// pick failed: transient (bad LLM output) → try again; terminal (rule excludes
// every candidate) → finish. The sidecar caps consecutive transient retries.
IfNum('Pick Retryable?', `={{ ($json.result && $json.result.retry) ? 1 : 0 }}`, 'equal', 1);
Http('Start Iteration', { path: '/api/iteration/start', body: `={{ { file: $('Rules: pick file').first().json.result.file } }}` });
Http('Baseline Mutation', { path: '/api/pit/run', body: `={{ { file: $('Start Iteration').first().json.file, phase: 'baseline', stage: 'improving_mutation' } }}`, timeout: 3600000 });
// a class PIT finds (almost) nothing to mutate in — annotation, marker interface,
// constants holder — cannot be improved, so skip it without spending the round budget
IfNum('Has Mutants?', `={{ $json.skip ? 0 : 1 }}`, 'equal', 1);
Http('Coverage Gaps', { method: 'GET', urlExpr: `=${API}/api/files/gaps?path={{ encodeURIComponent($('Start Iteration').first().json.file) }}` });

chain('Start (manual)', 'Start Run');
chain('Start (webhook)', 'Start Run');
chain('Start Run', 'Clone Repo', 'Rules: post-clone', 'Build & Detect', 'Baseline Coverage',
  'Rules: pre-pick', 'Rules: write-test', 'Next Iteration', 'Get Candidates', 'More Work?');
link('More Work?', 'Rules: pick file', 0);
link('Rules: pick file', 'File Picked?');
link('File Picked?', 'Start Iteration', 0);
chain('Start Iteration', 'Baseline Mutation', 'Has Mutants?');
link('Has Mutants?', 'Coverage Gaps', 0);
link('Has Mutants?', 'Next Iteration', 1);   // nothing to mutate → pick another class

// =============================================================================
// IMPROVEMENT PHASE (generic: coverage / mutation)
// =============================================================================
function phase(prefix, buildPromptCode, entryNode) {
  const B = (n) => `${prefix}: ${n}`;
  const stage = prefix === 'Cov' ? 'improving_coverage' : 'improving_mutation';
  Code(B('Build Prompt'), buildPromptCode);
  IfNum(B('Has Work?'), `={{ $json.skip ? 0 : 1 }}`, 'equal', 1);
  Http(B('LLM Write Tests'), { path: '/api/llm/chat', body: '={{ $json }}', timeout: 900000 });
  Code(B('Parse Tests'), `
const resp = $json;
const plan = $('${B('Build Prompt')}').first().json;
let tests = (resp.ok && resp.json && Array.isArray(resp.json.tests)) ? resp.json.tests : [];
tests = tests
  .filter(t => t && typeof t.content === 'string' && t.content.trim().length > 20)
  .slice(0, 1)
  .map((t) => {
    let p = typeof t.path === 'string' ? t.path.replace(/^\\.?\\//, '') : '';
    // a Java file must live at the path its package + class name dictate, so trust our
    // computed target unless the model produced exactly that
    const safe = /(^|\\/)src\\/test\\/java\\/.+\\.java$/.test(p) && !p.includes('..');
    if (!safe || p === plan.projectTestPath) p = plan.targetPath;
    // the public class name has to match the file name or javac refuses to compile
    const want = p.split('/').pop().replace(/\\.java$/, '');
    let content = t.content;
    const declared = (content.match(/public\\s+(?:final\\s+|abstract\\s+)?class\\s+(\\w+)/) || [])[1];
    if (declared && declared !== want) {
      content = content.replace(new RegExp('\\\\b' + declared + '\\\\b', 'g'), want);
    }
    return { path: p, content };
  });
return [{ json: { tests, paths: tests.map(t => t.path), count: tests.length } }];`);
  Http(B('Write Tests'), { path: '/api/test/write-many', body: `={{ { tests: $json.tests, stage: '${stage}' } }}` });
  Http(B('Run Tests'), { path: '/api/test/run', body: `={{ { stage: '${stage}' } }}`, timeout: 3600000 });
  IfNum(B('Green?'), '={{ $json.passed ? 1 : 0 }}', 'equal', 1);
  IfNum(B('Wrote Any?'), `={{ $('${B('Parse Tests')}').first().json.count }}`, 'larger', 0);
  Code(B('Build Repair'), `
const fail = $json;
const parsed = $('${B('Parse Tests')}').first().json;
const gaps = $('Coverage Gaps').first().json;
const filesTxt = parsed.tests.map(t => 'PATH: ' + t.path + '\\n' + t.content.slice(0, 6000)).join('\\n\\n---\\n\\n');
const system = 'You are an expert Java test engineer. Tests you previously wrote FAIL to compile or fail against the current implementation. Fix them. Keep the SAME file path and class name. Reply ONLY with JSON: {"tests":[{"path":"...","content":"full corrected file content"}]}. Compilation errors: fix imports, types, visibility and constructor/method signatures against the source shown. Assertion failures: correct the EXPECTED values to match the real behaviour of the source — never weaken an assertion to make it pass trivially. If a test cannot be fixed, drop it from the output.';
const prompt = 'BUILD / TEST OUTPUT (failures):\\n' + String(fail.summary || '').slice(0, 4000)
  + '\\n\\nYOUR TEST FILE(S):\\n' + filesTxt
  + '\\n\\nCLASS UNDER TEST ' + gaps.fqcn + ' (' + gaps.path + '):\\n' + String(gaps.source || '').slice(0, 10000)
  + '\\n\\nReply with corrected JSON now.';
return [{ json: { system, prompt, json: true, maxTokens: 7000, temperature: 0.2, stage: '${stage}', stageDetail: 'repairing failing generated tests' } }];`);
  Http(B('LLM Repair'), { path: '/api/llm/chat', body: '={{ $json }}', timeout: 900000 });
  Code(B('Parse Repair'), `
const resp = $json;
const prev = $('${B('Parse Tests')}').first().json;
let tests = (resp.ok && resp.json && Array.isArray(resp.json.tests)) ? resp.json.tests : [];
tests = tests
  .filter(t => t && typeof t.content === 'string' && t.content.trim().length > 20)
  .slice(0, prev.paths.length)
  .map((t, i) => ({ path: prev.paths[i], content: t.content }))
  .filter(t => t.path);
return [{ json: { tests, paths: tests.map(t => t.path), count: tests.length } }];`);
  Http(B('Write Repair'), { path: '/api/test/write-many', body: `={{ { tests: $json.tests } }}` });
  Http(B('Re-run Tests'), { path: '/api/test/run', body: `={{ {} }}`, timeout: 3600000 });
  IfNum(B('Green After Repair?'), '={{ $json.passed ? 1 : 0 }}', 'equal', 1);
  Http(B('Delete Broken Tests'), {
    path: '/api/test/delete-many',
    body: `={{ { paths: ($('${B('Parse Tests')}').first().json.paths || []).concat($('${B('Parse Repair')}').first().json.paths || []) } }}`,
  });
  NoOp(B('Done'));

  chain(entryNode, B('Build Prompt'), B('Has Work?'));
  link(B('Has Work?'), B('LLM Write Tests'), 0);
  link(B('Has Work?'), B('Done'), 1);
  chain(B('LLM Write Tests'), B('Parse Tests'), B('Write Tests'), B('Run Tests'), B('Green?'));
  link(B('Green?'), B('Done'), 0);
  link(B('Green?'), B('Wrote Any?'), 1);
  link(B('Wrote Any?'), B('Build Repair'), 0);
  link(B('Wrote Any?'), B('Done'), 1);
  chain(B('Build Repair'), B('LLM Repair'), B('Parse Repair'), B('Write Repair'), B('Re-run Tests'), B('Green After Repair?'));
  link(B('Green After Repair?'), B('Done'), 0);
  link(B('Green After Repair?'), B('Delete Broken Tests'), 1);
  link(B('Delete Broken Tests'), B('Done'));
  return B('Done');
}

// ── coverage phase ─────────────────────────────────────────────────────────
const covDone = phase('Cov', `
const gaps = $json; // response of Coverage Gaps
const u = gaps.uncovered || {};
const fullyUncovered = u.lines === 'all';
const missed = fullyUncovered ? 9999 : (u.lines || []).length;
const targetPath = gaps.covTestPath;
// The coverage phase exists to get the method EXECUTED at all. Once anything executes it,
// the mutation phase takes over and drags coverage along with it: a NO_COVERAGE mutant is
// by definition on a line nothing runs, and killing it requires calling that code. So this
// phase runs only when the method is completely unexecuted; otherwise it burns a
// multi-minute call and thousands of tokens to duplicate what mutation work does anyway.
const anyCoverage = !fullyUncovered && gaps.coverage != null && gaps.coverage > (gaps.covPhaseMaxPct ?? 0);
if (missed === 0) return [{ json: { skip: true, reason: 'method fully covered', targetPath, projectTestPath: gaps.projectTestPath } }];
if (anyCoverage) {
  return [{ json: { skip: true, reason: 'method already executed (' + gaps.coverage + '% covered) — mutation tests will extend coverage as they kill NO_COVERAGE mutants', targetPath, projectTestPath: gaps.projectTestPath } }];
}
const constraints = (gaps.constraints || []).map(c => '- ' + c).join('\\n');
const testClass = targetPath.split('/').pop().replace(/\\.java$/, '');
const RULES = ${JSON.stringify(COMMON_TEST_RULES)};
const system = 'You are an expert Java test engineer writing the FIRST test for ONE METHOD that no test executes yet' + (gaps.method ? ' (' + gaps.method + '())' : '') + '. Reply ONLY with JSON: {"tests":[{"path":"...","content":"full test file content"}]}. Create a NEW test class only — never modify existing files. Required file path: ' + targetPath + ' (package ' + gaps.package + ', public class ' + testClass + '). Rules:' + RULES
  + (constraints ? '\\nTeam constraints:\\n' + constraints : '');
const prompt = 'CLASS UNDER TEST: ' + gaps.fqcn + '  (file ' + gaps.path + ', module ' + gaps.module + ', JDK ' + gaps.jdk + ')\\n'
  + (gaps.method ? 'TARGET METHOD: ' + gaps.method + '()' + (gaps.methodLine ? ' (declared around line ' + gaps.methodLine + ')' : '') + ' — the tests must exercise THIS method; coverage and mutation score are measured on it alone.\\n' : '')
  + String(gaps.source || '').slice(0, 14000)
  + '\\n\\nUNCOVERED: ' + (fullyUncovered ? 'THE ENTIRE CLASS (no test executes it at all)' : 'source lines ' + JSON.stringify((u.lines || []).slice(0, 140)))
  + '\\n\\nEXISTING TEST (style reference — imports, assertion library, conventions; do NOT rewrite it):\\n'
  + String(gaps.existingTest || '(none)').slice(0, 6000)
  + '\\n\\nWrite one test class that executes the uncovered lines OF THE TARGET METHOD and asserts real behaviour. JSON only.';
// a deliberately small budget for the first pass: it caps both the wait and the size of
// what comes back, and the rounds provide the depth
return [{ json: { system, prompt, json: true, maxTokens: 3000, temperature: 0.2, stage: 'improving_coverage', stageDetail: 'writing a first, simple test', targetPath, projectTestPath: gaps.projectTestPath } }];`,
  'Coverage Gaps');

// ── mutation phase ─────────────────────────────────────────────────────────
const mutDone = phase('Mut', `
const gaps = $('Coverage Gaps').first().json;
const cfg = $('Start Run').first().json.run.config;
const targetPath = gaps.mutTestPath;
// freshest survivors: the sidecar tracks the last PIT run of this class (any round)
const allSurvived = (gaps.survived && gaps.survived.length) ? gaps.survived : ($('Baseline Mutation').first().json.survived || []);
const survived = allSurvived.slice(0, cfg.maxMutantsPerFile || 8);
if (!survived.length) return [{ json: { skip: true, reason: 'no surviving mutants', targetPath, projectTestPath: gaps.projectTestPath } }];
const constraints = (gaps.constraints || []).map(c => '- ' + c).join('\\n');
const testClass = targetPath.split('/').pop().replace(/\\.java$/, '');
const mutantsTxt = survived.map((m, i) =>
  '#' + (i + 1) + ' [' + m.status + '] ' + m.mutator + ' at line ' + m.line + ' in method ' + (m.method || '?') + '() — ' + (m.description || '')).join('\\n');
const RULES = ${JSON.stringify(COMMON_TEST_RULES)};
const PLAYBOOK = ${JSON.stringify(MUTATOR_PLAYBOOK)};
const system = 'You are an expert Java test engineer writing ' + (gaps.testFramework === 'junit4' ? 'JUnit 4' : gaps.testFramework === 'testng' ? 'TestNG' : 'JUnit 5') + ' tests that KILL surviving PIT mutants of one class. A mutant is killed when at least one test FAILS on the mutated code while PASSING on the real code — so each test must assert something that DISTINGUISHES the two. Reply ONLY with JSON: {"tests":[{"path":"...","content":"full test file content"}]}. Create a NEW test class only. Required file path: ' + targetPath + ' (package ' + gaps.package + ', public class ' + testClass + '). Rules:' + RULES + PLAYBOOK
  + (constraints ? '\\nTeam constraints:\\n' + constraints : '');
const prompt = 'CLASS UNDER TEST: ' + gaps.fqcn + '  (file ' + gaps.path + ', module ' + gaps.module + ')\\n'
  + String(gaps.source || '').slice(0, 12000)
  + (gaps.method ? '\\n\\nFOCUS: this unit of work IS the method ' + gaps.method + '() — every mutant below is inside it, and the score is measured on it alone.' : '')
  + '\\n\\nSURVIVING MUTANTS TO KILL (status SURVIVED = line runs but nothing asserts on it; NO_COVERAGE = line never runs):\\n' + mutantsTxt
  + '\\n\\nEXISTING TEST (style reference — do NOT rewrite it):\\n'
  + String(gaps.existingTest || '(none)').slice(0, 4000)
  + '\\n\\nWrite one test class killing as many of these mutants as possible. JSON only.';
// bounded per round: each round targets a handful of mutants, and a smaller answer
// arrives sooner and compiles more often than a sprawling one
return [{ json: { system, prompt, json: true, maxTokens: 5000, temperature: 0.25, stage: 'improving_mutation', stageDetail: 'writing mutant-killing tests', targetPath, projectTestPath: gaps.projectTestPath } }];`,
  covDone);

// =============================================================================
// VERIFY → CHECK RULES → PR / DISCARD → LOOP
// =============================================================================
Http('Verify', { path: '/api/verify', body: `={{ { file: $('Start Iteration').first().json.file } }}`, timeout: 5400000 });
// multi-round: keep the round iff ≥1 of coverage/mutation/MAC improved AND none degraded…
IfNum('Keep Round?', `={{ $json.keepRound ? 1 : 0 }}`, 'equal', 1);
// …then decide separately whether a FURTHER round is worth its machine time (round
// budget left AND the last round closed a real share of the remaining MAC gap).
// Keeping these apart means a kept-but-final round is committed, never dropped.
IfNum('Another Round?', `={{ $json.continueRounds ? 1 : 0 }}`, 'equal', 1);
Http('Accept Round', { path: '/api/round/accept', body: `={{ { file: $('Start Iteration').first().json.file } }}` });
Http('Drop Last Round', { path: '/api/round/drop', body: `={{ { file: $('Start Iteration').first().json.file } }}` });
Http('Cleanup Tests', { path: '/api/test/cleanup', body: `={{ { file: $('Start Iteration').first().json.file } }}`, timeout: 5400000 });
Http('Rules: check changes', { path: '/api/rules/apply', body: `={{ { stage: 'check_changes', context: $('Drop Last Round').first().json } }}`, timeout: 600000 });
IfNum('Approved?', `={{ ($json.result && $json.result.approved && $('Drop Last Round').first().json.improved) ? 1 : 0 }}`, 'equal', 1);
Http('Rules: make PR', { path: '/api/rules/apply', body: `={{ { stage: 'make_pr', context: $('Drop Last Round').first().json } }}`, timeout: 600000 });
Http('Create PR', {
  path: '/api/pr/create',
  body: `={{ { file: $('Start Iteration').first().json.file, title: $json.result.title, body: $json.result.body, labels: $json.result.labels } }}`,
  timeout: 300000,
});
Http('Discard Changes', { path: '/api/iteration/discard', body: `={{ { file: $('Start Iteration').first().json.file, reason: $('Rules: check changes').first().json.result.reason || 'not approved' } }}` });
NoOp('Iteration Done');
Http('Finish Run', { path: '/api/run/finish', body: '={{ {} }}' });
NoOp('End');

chain(mutDone, 'Verify', 'Keep Round?');
link('Keep Round?', 'Accept Round', 0);          // round earned its place → commit it
link('Keep Round?', 'Drop Last Round', 1);       // stale/degraded → discard it
link('Accept Round', 'Another Round?');
link('Another Round?', 'Coverage Gaps', 0);      // next round on the same class
link('Another Round?', 'Drop Last Round', 1);    // done: nothing uncommitted left to drop
chain('Drop Last Round', 'Rules: check changes', 'Approved?');
link('Approved?', 'Cleanup Tests', 0);
link('Cleanup Tests', 'Rules: make PR');
link('Approved?', 'Discard Changes', 1);
chain('Rules: make PR', 'Create PR', 'Iteration Done');
link('Discard Changes', 'Iteration Done');
link('Iteration Done', 'Next Iteration');
link('More Work?', 'Finish Run', 1);
link('File Picked?', 'Pick Retryable?', 1);
link('Pick Retryable?', 'Next Iteration', 0);   // transient → pick again
link('Pick Retryable?', 'Finish Run', 1);       // terminal → done
link('Finish Run', 'End');

// =============================================================================
const out = {
  id: WORKFLOW_ID,
  name: w.name,
  nodes: w.nodes,
  connections: w.connections,
  active: true,
  settings: { executionOrder: 'v1', timezone: 'UTC' },
  tags: [],
};
writeFileSync(join(OUT, 'Improve-Java-Tests.json'), JSON.stringify(out, null, 2));
console.log(`✓ ${w.name}: ${w.nodes.length} nodes`);

// Every Code node must PARSE. A prompt fragment carrying an apostrophe used to
// terminate the single-quoted string it was interpolated into, and the workflow
// only failed hours later, mid-run, inside n8n. Fragments are injected as JSON
// now — this check is what keeps it that way.
const broken = [];
for (const n of w.nodes.filter((x) => x.type === 'n8n-nodes-base.code')) {
  try { new Function(n.parameters.jsCode); }
  catch (e) { broken.push(`${n.name}: ${e.message}`); }
}
if (broken.length) {
  console.error('CODE NODE SYNTAX ERRORS:\n  ' + broken.join('\n  '));
  process.exit(1);
}
console.log(`✓ all ${w.nodes.filter((x) => x.type === 'n8n-nodes-base.code').length} Code nodes parse`);

// static safety scan: forbid non-native/system node types
const allowed = ['n8n-nodes-base.manualTrigger', 'n8n-nodes-base.webhook', 'n8n-nodes-base.httpRequest',
  'n8n-nodes-base.code', 'n8n-nodes-base.if', 'n8n-nodes-base.noOp', 'n8n-nodes-base.splitInBatches'];
const bad = w.nodes.filter((n) => !allowed.includes(n.type));
const shellish = w.nodes.filter((n) => n.type === 'n8n-nodes-base.code' && /child_process|execSync|spawn|require\(['"]fs['"]\)|readFileSync|writeFileSync/.test(n.parameters.jsCode || ''));
if (bad.length || shellish.length) {
  console.error('CONSTRAINT VIOLATION', { bad: bad.map((n) => n.name), shellish: shellish.map((n) => n.name) });
  process.exit(1);
}
console.log('✓ native-nodes-only constraint satisfied');
