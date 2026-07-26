// Generates the n8n workflow JSON for improve-java-tests-n8n.
//
// HARD CONSTRAINT: only native n8n nodes — Manual/Webhook triggers, HTTP Request,
// Code (pure JS data transforms: no child_process, no fs, no shell), IF, NoOp.
// Every OS-touching operation (git, Maven/Gradle, JaCoCo, PIT, gh, LLM) goes through
// the sidecar HTTP API on :3000.
//
// Run: node generate-workflows.mjs → writes workflows/Improve-Java-Tests.json

import { writeFileSync, mkdirSync, readFileSync } from 'node:fs';
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
function phase(prefix, promptEndpoint, entryNode) {
  const B = (n) => `${prefix}: ${n}`;
  const stage = prefix === 'Cov' ? 'improving_coverage' : 'improving_mutation';
  // The prompt is built by the sidecar (sidecar/prompts.js) from the freshest state, and
  // the answer is parsed there too (sidecar/parse.js). Both are covered by unit tests;
  // the same logic as JS strings inside Code nodes had neither a compiler nor a test, and
  // shipped an undefined identifier and a silently-unapplied edit to production.
  Http(B('Build Prompt'), { path: promptEndpoint, body: `={{ { path: $('Start Iteration').first().json.file } }}` });
  IfNum(B('Has Work?'), `={{ $json.skip ? 0 : 1 }}`, 'equal', 1);
  Http(B('LLM Write Tests'), { path: '/api/llm/chat', body: '={{ $json }}', timeout: 900000 });
  Http(B('Parse Tests'), { path: '/api/tests/parse', body: `={{ { resp: $json, plan: $('${B('Build Prompt')}').first().json } }}` });
  Http(B('Write Tests'), { path: '/api/test/write-many', body: `={{ { tests: $json.tests, stage: '${stage}', note: $json.chosen ? ('targeting ' + $json.chosen.mutator + ' at line ' + $json.chosen.line) : null, targetMutant: $json.chosen || null } }}` });
  Http(B('Run Tests'), { path: '/api/test/run', body: `={{ { stage: '${stage}' } }}`, timeout: 3600000 });
  IfNum(B('Green?'), '={{ $json.passed ? 1 : 0 }}', 'equal', 1);
  IfNum(B('Wrote Any?'), `={{ $('${B('Parse Tests')}').first().json.count }}`, 'larger', 0);
  Http(B('Build Repair'), {
    path: '/api/prompt/repair',
    body: `={{ { path: $('Start Iteration').first().json.file, stage: '${stage}', summary: $json.summary, tests: $('${B('Parse Tests')}').first().json.tests } }}`,
  });
  Http(B('LLM Repair'), { path: '/api/llm/chat', body: '={{ $json }}', timeout: 900000 });
  Http(B('Parse Repair'), { path: '/api/tests/parse-repair', body: `={{ { resp: $json, prev: $('${B('Parse Tests')}').first().json } }}` });
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
// Only for a method NOTHING executes: once anything runs it, killing NO_COVERAGE mutants
// drags coverage along anyway (sidecar/prompts.js decides and says so).
const covDone = phase('Cov', '/api/prompt/coverage', 'Coverage Gaps');

// ── mutation phase ─────────────────────────────────────────────────────────
// ONE mutant per round, chosen from PIT's data and this run's kill record — not from the
// model's opinion about what it can kill, which was confidently wrong four rounds running.
const mutDone = phase('Mut', '/api/prompt/mutation', covDone);

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
// a round that failed to kill its mutant: bin the test, keep the unit
Http('Round Missed', { path: '/api/round/miss', body: `={{ { file: $('Start Iteration').first().json.file } }}` });
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
link('Keep Round?', 'Round Missed', 1);          // no kill → drop the test, not the unit
link('Accept Round', 'Another Round?');
link('Round Missed', 'Another Round?');
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
// Every node is now an HTTP call into the sidecar, so the build-time check that matters
// is whether those endpoints exist. A path typo used to surface as a 404 an hour into a
// run; here it fails the build. (The logic those nodes used to carry inline now lives in
// sidecar/*.js under `npm test`.)
const routes = new Set(
  [...readFileSync(join(__dirname, '..', 'sidecar', 'server.js'), 'utf8')
    .matchAll(/'(GET|POST) (\/api\/[^']+)'/g)].map((m) => `${m[1]} ${m[2]}`));
const badUrls = [];
for (const n of w.nodes.filter((x) => x.type === 'n8n-nodes-base.httpRequest')) {
  const url = String(n.parameters.url || '');
  const path = (url.replace(/^=?http:\/\/127\.0\.0\.1:3000/, '').split('?')[0]) || '';
  const key = `${n.parameters.method || 'GET'} ${path}`;
  if (!routes.has(key)) badUrls.push(`${n.name}: ${key}`);
}
if (badUrls.length) {
  console.error('HTTP NODES POINTING AT NON-EXISTENT SIDECAR ROUTES:\n  ' + badUrls.join('\n  '));
  process.exit(1);
}
console.log(`\u2713 all ${w.nodes.filter((x) => x.type === 'n8n-nodes-base.httpRequest').length} HTTP nodes hit a real sidecar route`);

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
