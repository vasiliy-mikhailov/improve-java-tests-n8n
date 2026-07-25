// Eval runner — executes INSIDE the ijst-n8n container:
//   docker exec ijst-n8n node /data/eval/run-eval.mjs <name|synth>
// Triggers the n8n webhook (so the real workflow runs), polls the sidecar,
// writes /data/eval/results/<name>.json
import { readFileSync, writeFileSync, mkdirSync, rmSync, cpSync, existsSync } from 'node:fs';
import { execSync } from 'node:child_process';

const N8N = 'http://127.0.0.1:5678';
const API = 'http://127.0.0.1:3000';
const EVAL_DIR = '/data/eval';

const arg = process.argv[2];
if (!arg) { console.error('usage: run-eval.mjs <repo-name|synth>'); process.exit(2); }

const cfgFile = JSON.parse(readFileSync(`${EVAL_DIR}/repos.json`, 'utf8'));
const all = [cfgFile.synth, ...cfgFile.candidates];
const cfg = all.find((c) => c.name === arg);
if (!cfg) { console.error('unknown repo: ' + arg); process.exit(2); }

if (cfg.synth) setupSynthOrigin();

function setupSynthOrigin() {
  const origin = `${EVAL_DIR}/synth-origin`;
  rmSync(origin, { recursive: true, force: true });
  cpSync(`${EVAL_DIR}/synth-repo`, origin, { recursive: true });
  const g = (cmd) => execSync(`git ${cmd}`, { cwd: origin, stdio: 'pipe' });
  g('init -b main');
  g('add -A');
  g('-c user.name=synth -c user.email=synth@local commit -m "synth repo baseline"');
  console.log('synth origin ready at ' + origin);
}

const body = {
  repoUrl: cfg.repoUrl, repoBranch: cfg.repoBranch, scopeGlob: cfg.scopeGlob,
  scopeLimit: cfg.scopeLimit, maxIterations: cfg.maxIterations, prMode: cfg.prMode,
  clearLedger: true, // evals are independent measurements
};
if (cfg.setupScript) body.setupScript = cfg.setupScript;
if (cfg.maxMutantsPerFile) body.maxMutantsPerFile = cfg.maxMutantsPerFile;
// explicit rules; real-repo evals default to empty rules → mechanical behavior,
// no env-default leakage (rule adherence is exercised by the synth repo)
body.rules = cfg.rules || { post_clone: '', pre_pick: '', pick_file: '', write_test: '', check_changes: '', make_pr: '' };

// stop any lingering execution from a previous (failed) run before triggering
try {
  const tok = readFileSync('/data/n8n-auth-token.txt', 'utf8').trim();
  const hdrs = { Cookie: 'n8n-auth=' + tok };
  const running = await (await fetch(`${N8N}/rest/executions?filter=${encodeURIComponent('{"status":["running"]}')}`, { headers: hdrs })).json();
  for (const ex of running?.data?.results || []) {
    console.log('stopping lingering execution ' + ex.id);
    await fetch(`${N8N}/rest/executions/${ex.id}/stop`, { method: 'POST', headers: hdrs });
  }
} catch (e) { console.log('execution cleanup skipped: ' + e.message); }

const t0 = Date.now();
console.log(`triggering run for ${cfg.name}: ${JSON.stringify(body)}`);
const trig = await fetch(`${N8N}/webhook/improve-run`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
});
console.log('webhook response: ' + trig.status);
if (trig.status >= 400) process.exit(1);

const timeoutMs = (cfg.timeoutMin || 45) * 60000;
let last = '';
let m = null;
for (; ;) {
  await new Promise((r) => setTimeout(r, 10000));
  try { m = await (await fetch(`${API}/api/metrics`)).json(); } catch { continue; }
  const line = `[${Math.round((Date.now() - t0) / 1000)}s] stage=${m.stage?.name} iter=${m.run?.iteration} status=${m.run?.status} prs=${(m.prs || []).length}`;
  if (line !== last) { console.log(line); last = line; }
  if (m.run && m.run.status !== 'running') break;
  if (m.stage?.name === 'failed') break;
  if (Date.now() - t0 > timeoutMs) { console.log('TIMEOUT'); break; }
}

const stateRes = await (await fetch(`${API}/api/state`)).json();
mkdirSync(`${EVAL_DIR}/results`, { recursive: true });
const targeted = Object.values(stateRes.files || {}).filter((f) => f.macBefore != null);
const result = {
  name: cfg.name,
  config: body,
  finishedStatus: stateRes.run?.status,
  stage: stateRes.stage?.name,
  durationSec: Math.round((Date.now() - t0) / 1000),
  baseline: stateRes.run?.baseline,
  result: stateRes.run?.result,
  targetedFiles: targeted.map((f) => ({
    path: f.path, status: f.status,
    coverageBefore: f.coverageBefore, coverageAfter: f.coverageAfter,
    mutationBefore: f.mutationBefore, mutationAfter: f.mutationAfter,
    macBefore: f.macBefore, macAfter: f.macAfter,
    prUrl: f.prUrl, prPatch: f.prPatch,
  })),
  prs: stateRes.prs,
  decisions: Object.fromEntries(Object.entries(stateRes.decisions || {}).map(([k, v]) => [k, v.result])),
  lastEvents: (stateRes.events || []).slice(-30),
  ranAt: new Date().toISOString(),
};
writeFileSync(`${EVAL_DIR}/results/${cfg.name}.json`, JSON.stringify(result, null, 2));
console.log(`result written: ${EVAL_DIR}/results/${cfg.name}.json`);
console.log(JSON.stringify({ status: result.finishedStatus, targeted: result.targetedFiles, prs: (result.prs || []).length }, null, 2));
