'use strict';
// JSON-file-backed state store. Single-process, in-memory with debounced flush.
const fs = require('node:fs');
const path = require('node:path');
const { nowSec, redact } = require('./util');

const DATA_DIR = process.env.DATA_DIR || '/data';
const STATE_FILE = path.join(DATA_DIR, 'state.json');
const EVENTS_FILE = path.join(DATA_DIR, 'events.jsonl');
const MAX_EVENTS_IN_STATE = 400;

function envConfig() {
  const e = process.env;
  return {
    repoUrl: e.REPO_URL || '',
    repoBranch: e.REPO_BRANCH || 'main',
    scopeGlob: e.SCOPE_GLOB || '**/src/main/java/**/*.java',
    scopeLimit: parseInt(e.SCOPE_LIMIT || '0', 10),
    maxIterations: parseInt(e.MAX_ITERATIONS || '0', 10), // 0 = unlimited
    // 0 = no cap. Rounds end when a round kills nothing, when every surviving mutant
    // has already been attempted, or when the unit's time budget runs out — a fixed
    // count would stop a unit that is still paying off.
    maxRoundsPerFile: parseInt(e.MAX_ROUNDS_PER_FILE || '0', 10),
    // Mutants shown to the model per round. 1 = write ONE test for the single most
    // promising survivor, re-measure, repeat: tiny prompts, fast answers, and every
    // round is independently verified instead of betting a big test on many mutants.
    mutantsPerRound: parseInt(e.MUTANTS_PER_ROUND || '1', 10),
    // survivors offered to the model when it chooses which mutant to kill this round
    mutantChoices: parseInt(e.MUTANT_CHOICES || '20', 10),
    // rounds are cheap now, but a mutant-dense method still needs a ceiling
    unitBudgetSec: parseInt(e.UNIT_BUDGET_SEC || '3600', 10),
    maxAttemptsPerFile: parseInt(e.MAX_ATTEMPTS_PER_FILE || '3', 10),
    // diminishing-returns stop: a further round is only worth its ~10 min of
    // machine time if the last one closed a real share of the remaining MAC gap
    minRoundGapFrac: parseFloat(e.MIN_ROUND_GAP_FRAC || '0'),
    minRoundGain: parseFloat(e.MIN_ROUND_GAIN || '0'),
    // a class with fewer mutants than this has no mutation surface worth a run
    // (annotations, marker interfaces, constants holders)
    minMutantsPerClass: parseInt(e.MIN_MUTANTS_PER_CLASS || '1', 10),
    // 'method' (default): rounds mutate one method at a time and project the class
    // score, with one class-wide measurement at the end. 'class': mutate everything
    // every round — honest but far slower.
    pitScope: e.PIT_SCOPE || 'method',
    // a method with fewer executable lines than this has no behaviour worth verifying
    // (one-line accessors, private utility-class constructors)
    minUnitLines: parseInt(e.MIN_UNIT_LINES || '1', 10),
    // units we could not measure do not consume the scope quota, but too many of them
    // means the repo/toolchain is broken rather than the units
    maxFailures: parseInt(e.MAX_FAILURES || '10', 10),
    // The coverage phase runs only for a method covered at or below this (default 0:
    // completely unexecuted). Above it, mutation work extends coverage on its own —
    // killing a NO_COVERAGE mutant means executing the line it sits on.
    covPhaseMaxPct: parseFloat(e.COV_PHASE_MAX_PCT || '0'),
    prMode: e.PR_MODE || 'github', // github | local
    prBase: e.PR_BASE || '',       // defaults to repoBranch
    setupScript: e.SETUP_SCRIPT || '', // extra build goal/task to run after compile (e.g. 'shade')
    dryRun: String(e.DRY_RUN || 'false') === 'true',
    rules: {
      post_clone: e.RULES_POST_CLONE || '',
      pre_pick: e.RULES_PRE_PICK || '',
      pick_file: e.RULES_PICK_FILE || '',
      write_test: e.RULES_WRITE_TEST || '',
      check_changes: e.RULES_CHECK_CHANGES || '',
      make_pr: e.RULES_MAKE_PR || '',
    },
  };
}

function freshRun(overrides = {}) {
  const cfg = envConfig();
  const o = overrides && typeof overrides === 'object' ? overrides : {};
  for (const k of ['repoUrl', 'repoBranch', 'scopeGlob', 'scopeLimit', 'maxIterations',
    'maxRoundsPerFile', 'maxAttemptsPerFile', 'minRoundGapFrac',
    'minRoundGain', 'mutantsPerRound', 'mutantChoices', 'unitBudgetSec', 'minMutantsPerClass', 'pitScope', 'minUnitLines', 'maxFailures', 'covPhaseMaxPct', 'prMode', 'prBase', 'dryRun', 'setupScript']) {
    if (o[k] !== undefined && o[k] !== null && o[k] !== '') cfg[k] = o[k];
  }
  if (o.rules && typeof o.rules === 'object') {
    for (const k of Object.keys(cfg.rules)) if (o.rules[k] !== undefined) cfg.rules[k] = o.rules[k];
  }
  cfg.scopeLimit = parseInt(cfg.scopeLimit, 10) || 0;
  cfg.maxIterations = Math.max(0, parseInt(cfg.maxIterations, 10) || 0); // 0 = unlimited
  cfg.maxRoundsPerFile = Math.max(0, parseInt(cfg.maxRoundsPerFile, 10) || 0);
  cfg.mutantsPerRound = Math.max(1, parseInt(cfg.mutantsPerRound, 10) || 1);
  cfg.unitBudgetSec = Math.max(0, parseInt(cfg.unitBudgetSec, 10) || 0);
  cfg.maxAttemptsPerFile = parseInt(cfg.maxAttemptsPerFile, 10) || 3;
  cfg.minRoundGapFrac = Math.max(0, parseFloat(cfg.minRoundGapFrac) || 0);
  cfg.minRoundGain = Math.max(0, parseFloat(cfg.minRoundGain) || 0);
  cfg.minMutantsPerClass = Math.max(0, parseInt(cfg.minMutantsPerClass, 10) || 0);
  if (!cfg.prBase) cfg.prBase = cfg.repoBranch;
  return {
    id: 'run-' + Date.now(),
    startedAt: nowSec(),
    finishedAt: null,
    status: 'running',
    config: cfg,
    iteration: 0,
    baseline: { coveragePct: null, mutationPct: null, mac: null },
    result: { coveragePct: null, mutationPct: null, mac: null },
    tokens: { in: 0, out: 0, calls: 0 },
  };
}

const state = {
  run: null,
  stage: { name: 'idle', detail: '', since: nowSec(), progress: null },
  runner: null,      // { tool, wrapper, jdk, testFramework, testFrameworkVersion }
  files: {},         // path -> file record
  decisions: {},     // ruleStage -> last application result
  prs: [],
  events: [],
  seq: 0,
  // per-repo ledger of final file dispositions, SURVIVES run/start — enables
  // batched full-repo runs and crash-restart without redoing finished files.
  // { [repoSlug]: { [path]: {state: 'improved'|'exhausted'|'failed', prUrl?, ts} } }
  improvedLedger: {},
  // per-repo cumulative clone/install/baseline seconds, so the FTE ratio counts
  // run overhead and not just per-file work
  overheadLedger: {},
  // per-repo measurements for EVERY file we ever measured, improved or not:
  // baseline coverage/mutation/MAC plus what the best attempt reached. Kept
  // separate from improvedLedger because these entries say nothing about
  // whether a file is settled — they exist so the numbers survive batches.
  // { [repoSlug]: { [path]: {coverageBefore, mutationBefore, macBefore,
  //                          attemptCoverage, attemptMutation, attemptMac, ts} } }
  measureLedger: {},
  // unit the pipeline is improving right now — token usage is attributed to it
  currentUnit: null,
  // the last few model exchanges, so the dashboard can show what is actually being
  // asked and answered rather than only that a call is in flight
  llmLog: [],
};

function load() {
  try {
    const raw = JSON.parse(fs.readFileSync(STATE_FILE, 'utf8'));
    Object.assign(state, raw);
    if (state.run && state.run.status === 'running') {
      // process restarted mid-run
      state.stage = { name: 'interrupted', detail: 'sidecar restarted', since: nowSec(), progress: null };
    }
  } catch { /* first boot */ }
}

let flushTimer = null;
function save() {
  if (flushTimer) return;
  flushTimer = setTimeout(() => {
    flushTimer = null;
    try {
      fs.mkdirSync(DATA_DIR, { recursive: true });
      const tmp = STATE_FILE + '.tmp';
      fs.writeFileSync(tmp, JSON.stringify(state));
      fs.renameSync(tmp, STATE_FILE);
    } catch (e) { console.error('state save failed:', e.message); }
  }, 150);
}

function event(stage, msg) {
  state.seq += 1;
  const entry = { seq: state.seq, ts: nowSec(), stage, msg: redact(msg).slice(0, 500) };
  state.events.push(entry);
  if (state.events.length > MAX_EVENTS_IN_STATE) state.events.splice(0, state.events.length - MAX_EVENTS_IN_STATE);
  try { fs.appendFileSync(EVENTS_FILE, JSON.stringify(entry) + '\n'); } catch { }
  save();
  console.log(`[${stage}] ${msg}`);
}

function setStage(name, rawDetail = '') {
  const detail = redact(rawDetail);
  if (state.stage.name !== name || state.stage.detail !== detail) {
    state.stage = { name, detail, since: nowSec(), progress: null };
    event(name, detail || name);
  }
  save();
}

function setProgress(line, elapsed) {
  // child-process output: may echo credentials we passed in
  state.stage.progress = { line: redact(line).slice(0, 200), elapsed, ts: nowSec() };
  save();
}

/**
 * Accumulate LLM token usage: on the run total and on the unit currently being improved.
 * Called from llm.js for every completion, including retries and repairs — the cost of a
 * unit is everything spent getting there, not just the successful call.
 */
const LLM_LOG_MAX = 12;
/** Record one model exchange for the dashboard's live dialog. */
function addLlmExchange(entry) {
  state.llmLog.push({ ...entry, ts: nowSec(), unit: state.currentUnit || null });
  if (state.llmLog.length > LLM_LOG_MAX) state.llmLog.splice(0, state.llmLog.length - LLM_LOG_MAX);
  save();
}

function addTokens(inTok, outTok) {
  if (!state.run) return;
  const t = (state.run.tokens ||= { in: 0, out: 0, calls: 0 });
  t.in += inTok; t.out += outTok; t.calls += 1;
  const key = state.currentUnit;
  if (key && state.files[key]) {
    const f = state.files[key];
    f.tokensIn = (f.tokensIn || 0) + inTok;
    f.tokensOut = (f.tokensOut || 0) + outTok;
    f.llmCalls = (f.llmCalls || 0) + 1;
  }
  save();
}

function upsertFile(p, patch) {
  const f = state.files[p] || {
    path: p, coverage: null, mutation: null, mac: null,
    macBefore: null, macAfter: null, status: 'candidate',
    attempts: 0, branch: null, prUrl: null, prPatch: null, updatedAt: nowSec(),
  };
  Object.assign(f, patch, { updatedAt: nowSec() });
  state.files[p] = f;
  save();
  return f;
}

module.exports = { state, load, save, event, setStage, setProgress, upsertFile, addTokens, addLlmExchange, freshRun, envConfig, DATA_DIR };
