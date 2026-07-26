'use strict';
// Sidecar HTTP API (:3000). Everything the n8n workflow needs, so the workflow
// itself stays 100% native n8n nodes (HTTP Request / Code / IF / SplitInBatches).
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const S = require('./state');
const repo = require('./repo');
const coverage = require('./coverage');
const pit = require('./pit');
const tests = require('./tests');
const rulesMod = require('./rules');
const pr = require('./pr');
const llm = require('./llm');
const timesheet = require('./timesheet');
const roundsMod = require('./rounds');
const { run } = require('./exec');
const { mac, fileSlug, round2, clamp, slugify } = require('./util');

const PORT = parseInt(process.env.SIDECAR_PORT || '3000', 10);
const state = S.state;

// ── helpers ────────────────────────────────────────────────────────────────
function json(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) });
  res.end(body);
}
function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', (c) => {
      data += c;
      if (data.length > 20e6) { req.destroy(); reject(new Error('request body too large')); }
    });
    req.on('end', () => { try { resolve(data ? JSON.parse(data) : {}); } catch (e) { reject(new Error('bad JSON body')); } });
    req.on('error', reject);
  });
}

/** A unit key is `<path>::<method>`; everything git-facing needs the path alone. */
function unitPath(key) { return state.files[key]?.path || String(key).split('::')[0]; }
function unitMethod(key) { return state.files[key]?.method || String(key).split('::')[1] || null; }
/** Human label for events and PR titles: `Class#method`. */
function unitLabel(key) {
  const f = state.files[key] || {};
  const cls = (f.fqcn || unitPath(key)).split(/[./]/).pop().replace(/\.java$/, '');
  return f.method ? `${cls}#${f.method}()` : cls;
}

function needRun() { if (!state.run) throw new Error('no active run — POST /api/run/start first'); }
function ledger() {
  const slug = slugify(state.run.config.repoUrl);
  return (state.improvedLedger[slug] ||= {});
}
/** Per-repo record of every measurement taken, regardless of outcome. */
function measured() {
  const slug = slugify(state.run.config.repoUrl);
  return (state.measureLedger[slug] ||= {});
}
function recordMeasurement(file, patch) {
  const m = measured();
  m[file] = { ...(m[file] || {}), ...patch, ts: Date.now() };
  S.save();
}

/** Close the current attempt's stopwatch into the file's cumulative machine time. */
function accrueSpent(file) {
  const f = state.files[file];
  if (!f || !f.attemptStartedAt) return f?.spentSec || 0;
  const spent = (f.spentSec || 0) + (Math.floor(Date.now() / 1000) - f.attemptStartedAt);
  S.upsertFile(file, { spentSec: spent, attemptStartedAt: null });
  return spent;
}

/**
 * Repo-level mutation/MAC totals = the MEAN over every file whose mutation score
 * we actually measured (the dashboard labels them "picked files"). Reporting the
 * last file's score as the repo's number — as this used to — makes the headline
 * swing wildly with whatever file happened to be measured last.
 */
function refreshTotals() {
  if (!state.run) return;
  const files = Object.values(state.files).filter((f) => f.mutationBefore != null);
  if (!files.length) return;
  const avg = (xs) => round2(xs.reduce((s, x) => s + x, 0) / xs.length);
  const before = avg(files.map((f) => f.mutationBefore));
  const after = avg(files.map((f) => f.mutationAfter ?? f.mutationBefore));
  state.run.baseline.mutationPct = before;
  state.run.baseline.mac = mac(state.run.baseline.coveragePct, before);
  state.run.result.mutationPct = after;
  state.run.result.mac = mac(state.run.result.coveragePct ?? state.run.baseline.coveragePct, after);
  state.run.measuredFiles = files.length;
}

function metricsPayload() {
  refreshTotals();
  const files = Object.values(state.files).sort((a, b) => (a.mac ?? 999) - (b.mac ?? 999));
  const targeted = files.filter((f) => f.macBefore != null);
  const avg = (xs) => xs.length ? round2(xs.reduce((s, x) => s + x, 0) / xs.length) : null;
  // work accounting: human-equivalent hours, machine time, ETA, FTE, progress
  const settled = files.filter((f) => ['improved', 'no_improvement', 'failed'].includes(f.status));
  const now = Math.floor(Date.now() / 1000);
  const humanMin = files.reduce((s, f) => s + (f.timesheet?.totalMin || 0), 0);
  let machineSec = files.reduce((s, f) => s + (f.spentSec || 0), 0);
  // only the file currently being worked on has a live stopwatch; a crashed
  // attempt must not keep accruing time forever
  for (const f of files) if (f.attemptStartedAt && f.status === 'picked') machineSec += now - f.attemptStartedAt;
  // clone + install + baseline measurement are real machine time too, and they
  // dominate short batches — without them the FTE ratio flatters the pipeline
  machineSec += state.overheadLedger?.[slugify(state.run?.config?.repoUrl || '')] || 0;
  const timedSettled = settled.filter((f) => f.spentSec > 0);
  const remaining = files.filter((f) => f.status === 'candidate' || f.status === 'picked').length;
  const avgSecPerFile = timedSettled.length
    ? timedSettled.reduce((s, f) => s + f.spentSec, 0) / timedSettled.length : null;
  // FTE must compare like with like: only files whose machine time we actually
  // measured. (Files improved before the stopwatch existed carry human-equivalent
  // hours but no machine time, and would inflate the ratio.)
  const comparable = files.filter((f) => f.timesheet?.totalMin > 0 && f.spentSec > 0);
  const comparableHumanMin = comparable.reduce((s, f) => s + f.timesheet.totalMin, 0);
  const comparableMachineSec = comparable.reduce((s, f) => s + f.spentSec, 0);
  const tokens = state.run?.tokens || { in: 0, out: 0, calls: 0 };
  const work = {
    tokensIn: tokens.in, tokensOut: tokens.out, llmCalls: tokens.calls,
    tokensPerImproved: settled.filter((f) => f.status === 'improved').length
      ? Math.round((tokens.in + tokens.out) / settled.filter((f) => f.status === 'improved').length) : null,
    humanHours: round2(humanMin / 60),
    machineHours: round2(machineSec / 3600),
    fte: comparableMachineSec > 600 ? round2((comparableHumanMin * 60) / comparableMachineSec) : null,
    fteBasis: comparable.length,
    etaSec: avgSecPerFile != null ? Math.round(remaining * avgSecPerFile) : null,
    settled: settled.length,
    improved: settled.filter((f) => f.status === 'improved').length,
    totalFiles: files.length,
    remaining,
  };
  return {
    work,
    stage: state.stage,
    run: state.run,
    runner: state.runner,
    totals: {
      baseline: state.run?.baseline || {},
      current: state.run?.result || {},
      targetedFiles: targeted.length,
      measuredFiles: state.run?.measuredFiles ?? 0,
      improvedFiles: targeted.filter((f) => f.status === 'improved').length,
      avgMacBefore: avg(targeted.map((f) => f.macBefore).filter((x) => x != null)),
      avgMacAfter: avg(targeted.map((f) => f.macAfter ?? f.macBefore).filter((x) => x != null)),
    },
    // project only what the dashboard renders, and only as many rows as it shows:
    // full records for ~500 files made this a ~130 KB poll every 2 s. Files the
    // pipeline has touched always win a slot; untouched candidates fill the rest.
    files: [...files.filter((f) => f.status !== 'candidate'),
      ...files.filter((f) => f.status === 'candidate')].slice(0, 250).map((f) => ({
      path: f.method ? `${f.path}::${f.method}` : f.path,
      sourcePath: f.path, method: f.method || null,
      status: f.status, attempts: f.attempts, rounds: f.rounds || 0,
      coverage: f.coverage, mutation: f.mutation, mac: f.mac,
      coverageBefore: f.coverageBefore, mutationBefore: f.mutationBefore, macBefore: f.macBefore,
      coverageAfter: f.coverageAfter, mutationAfter: f.mutationAfter, macAfter: f.macAfter,
      // what the best attempt reached even when the result was not kept
      attemptCoverage: f.attemptCoverage, attemptMutation: f.attemptMutation, attemptMac: f.attemptMac,
      failure: f.failure,
      tokensIn: f.tokensIn || 0, tokensOut: f.tokensOut || 0, llmCalls: f.llmCalls || 0,
      prUrl: f.prUrl, prPatch: f.prPatch,
      timesheet: f.timesheet && {
        hours: f.timesheet.hours, totalMin: f.timesheet.totalMin,
        analysisMin: f.timesheet.analysisMin, testsMin: f.timesheet.testsMin,
        mutationMin: f.timesheet.mutationMin, verifyMin: f.timesheet.verifyMin,
      },
    })),
    prs: state.prs,
    decisions: state.decisions,
    events: state.events.slice(-60),
  };
}


/**
 * Turn the scanned FILES into the pipeline's real unit of work: one entry per METHOD.
 *
 * A class is the wrong granularity for this job. PIT costs (mutants x suite time), so a
 * class-wide run is slow and mostly wasted — the weakness is almost always concentrated
 * in a few methods, and the tests that fix it are method-shaped. JaCoCo already reports
 * per-method counters, so the whole method list, with its line coverage, comes free from
 * the baseline run — no extra measurement to enumerate them.
 *
 * Key: `<path>::<method>`. The workflow passes that string around opaquely, so a "file"
 * everywhere downstream is really a method unit; `f.path` is still the source file it
 * lives in, which is what git, the diff and the PR care about.
 */
function expandFilesIntoMethodUnits(classes) {
  const files = Object.values(state.files).filter((f) => !f.method);
  const minUnitLines = state.run.config.minUnitLines ?? 2;
  let units = 0, skipped = 0, trivial = 0;
  for (const f of files) {
    const fq = f.fqcn || repo.fqcnOf(f.path);
    const c = classes[fq];
    delete state.files[f.path];
    if (!c || !c.methods || !Object.keys(c.methods).length) { skipped += 1; continue; }
    for (const [name, m] of Object.entries(c.methods)) {
      const executable = m.missed + m.covered;
      if (executable <= 0) continue;                 // nothing to test in there
      // Skip units with no behaviour to verify. A one-line private constructor, a
      // getter that returns a field, a static initialiser: PIT finds nothing worth
      // mutating and a run spent there buys nothing. The first Gradle run picked
      // `Assertions#<init>()` — a private utility-class constructor — and failed on it.
      if (name === '<clinit>') continue;
      if (executable < minUnitLines) { trivial += 1; continue; }
      const key = `${f.path}::${name}`;
      state.files[key] = {
        path: f.path, method: name, fqcn: fq, module: f.module, lines: f.lines,
        key, methodLine: m.line || null,
        coverage: executable > 0 ? round2((m.covered * 100) / executable) : 0,
        coveredLines: m.covered, missedLines: m.missed, executableLines: executable,
        mutation: null, mac: null, macBefore: null, macAfter: null,
        status: 'candidate', attempts: 0, branch: null, prUrl: null, prPatch: null,
        updatedAt: Math.floor(Date.now() / 1000),
      };
      units += 1;
    }
  }
  if (units) {
    S.event('measuring_baseline', `unit of work is the METHOD: ${files.length} class(es) → ${units} method(s)`
      + (trivial ? `; skipped ${trivial} trivial method(s) (< ${minUnitLines} executable lines)` : '')
      + (skipped ? `; ${skipped} class(es) had no executable method` : ''));
  }
  S.save();
}

const isCtor = (m) => m === '<init>' || m === '<clinit>';

function candidates() {
  const cfg = state.run.config;
  const maxAttempts = cfg.maxAttemptsPerFile || 3;
  const all = Object.values(state.files);
  // ledger-replayed units were settled in PREVIOUS batches — they must not consume this
  // batch's quota. Neither do FAILURES: a unit we could not even measure says nothing
  // about the repo, and letting one broken measurement spend the whole quota is how
  // java-dataloader produced 0 PRs from its first pick. Failures are bounded separately
  // so a repo that fails on everything still terminates.
  const processed = all.filter((f) => ['improved', 'no_improvement'].includes(f.status) && !f.fromLedger).length;
  const failed = all.filter((f) => f.status === 'failed' && !f.fromLedger).length;
  const maxFailures = cfg.maxFailures || 10;
  const list = all
    .filter((f) => f.status === 'candidate' && f.attempts < maxAttempts)
    // a class JaCoCo reports no executable lines for cannot be tested or mutated
    .filter((f) => f.executableLines == null || f.executableLines > 0)
    // `path` here is the UNIT KEY (`<file>::<method>`) — it is what every downstream
    // endpoint expects back from the pick. The source file and method are carried
    // alongside so the pick prompt can show something a human (or an LLM) can reason about.
    .map((f) => ({
      path: f.key || f.path, file: f.path, method: f.method || null,
      coverage: f.coverage, mutation: f.mutation, mac: f.mac,
      attempts: f.attempts, lines: f.lines ?? null, mutants: f.totalMutants ?? null,
      executableLines: f.executableLines ?? null,
    }))
    // Weakest first; among equally weak units the one with the MOST executable code,
    // because that is where the mutants — and the achievable gain — are. Constructors
    // sort last within a tie: they are usually field assignment with nothing to assert,
    // and they kept winning the pick on real repos (Cookie#<init>, Assertions#<init>).
    // Not excluded, though — a constructor with validation logic is a fair target once
    // the genuinely behavioural methods are done.
    .sort((a, b) => (a.mac ?? (a.coverage ?? 0) / 2) - (b.mac ?? (b.coverage ?? 0) / 2)
      || (isCtor(a.method) ? 1 : 0) - (isCtor(b.method) ? 1 : 0)
      || (b.executableLines ?? b.lines ?? 0) - (a.executableLines ?? a.lines ?? 0));
  let done = false, reason = '';
  if (cfg.maxIterations > 0 && state.run.iteration >= cfg.maxIterations) { done = true; reason = `max iterations (${cfg.maxIterations}) reached`; }
  else if (cfg.scopeLimit > 0 && processed >= cfg.scopeLimit) { done = true; reason = `scope limit (${cfg.scopeLimit} units) reached`; }
  else if (failed >= maxFailures) { done = true; reason = `${failed} units failed to measure (limit ${maxFailures}) — the repo or its toolchain is the problem, not the units`; }
  else if (!list.length) { done = true; reason = 'no remaining candidate files'; }
  return { done, reason, iteration: state.run.iteration, processed, failed, candidates: list.slice(0, 100) };
}

// ── route table ────────────────────────────────────────────────────────────
const routes = {
  'GET /api/health': async () => ({ ok: true, service: 'ijt-sidecar', stage: state.stage.name, ts: Date.now() }),
  'GET /api/state': async () => state,
  'GET /api/metrics': async () => metricsPayload(),
  // the live model dialog, served separately so the 2-second metrics poll stays small
  'GET /api/llm/log': async () => ({ entries: (state.llmLog || []).slice().reverse() }),
  'GET /api/rules': async () => ({ rules: state.run?.config?.rules || S.envConfig().rules, decisions: state.decisions }),
  'GET /api/events': async (q) => {
    const after = parseInt(q.get('after') || '0', 10);
    return { events: state.events.filter((e) => e.seq > after) };
  },

  'POST /api/stage': async (q, body) => {
    S.setStage(String(body.stage || 'idle'), String(body.detail || ''));
    return { ok: true, stage: state.stage };
  },

  'POST /api/run/start': async (q, body) => {
    // one git worktree + one run state: overlapping executions would corrupt both.
    // `force` (used by the batch driver, which owns execution lifecycle) and a
    // staleness window let a genuinely dead run be taken over.
    // 'interrupted' means the sidecar restarted: whatever execution owned that
    // run is gone, so it can never be a real concurrency conflict.
    const active = state.run && state.run.status === 'running' && state.stage?.name !== 'interrupted';
    const idleSec = Math.floor(Date.now() / 1000) - (state.stage?.since || 0);
    if (active && !body.force && idleSec < 900) {
      const err = new Error(`a run is already active (${state.run.id}, stage ${state.stage.name}, `
        + `${idleSec}s since last stage change) — stop it first or pass force:true`);
      err.statusCode = 409;
      throw err;
    }
    if (active) S.event('starting', `taking over stale/forced run ${state.run.id} (idle ${idleSec}s)`);
    state.run = S.freshRun(body);
    state.files = {};
    state.decisions = {};
    state.prs = [];
    state.pickFailures = 0;
    if (body.clearLedger) delete state.improvedLedger[slugify(state.run.config.repoUrl)];
    S.setStage('starting', `run ${state.run.id} on ${state.run.config.repoUrl}#${state.run.config.repoBranch}`);
    S.save();
    return { ok: true, run: state.run };
  },

  'POST /api/run/finish': async (q, body) => {
    needRun();
    state.run.status = body.status || 'done';
    state.run.finishedAt = Math.floor(Date.now() / 1000);
    S.setStage('done', `run finished: ${state.run.status}; ${state.prs.length} PR(s)`);
    await repo.resetToBase().catch(() => { });
    return { ok: true, run: state.run, prs: state.prs };
  },

  'POST /api/repo/clone': async () => {
    needRun();
    S.setStage('cloning', state.run.config.repoUrl);
    return { ok: true, ...(await repo.clone()) };
  },

  'POST /api/repo/prepare': async () => {
    needRun();
    S.setStage('installing', 'installing dependencies + tooling');
    const det = await repo.install();
    const files = await repo.listScopeFiles();
    // replay measurements first: every file we ever measured keeps its numbers,
    // whether or not it was ever improved (status is untouched here)
    let remeasured = 0;
    for (const [p, m] of Object.entries(measured())) {
      if (!state.files[p]) continue;
      S.upsertFile(p, {
        coverageBefore: m.coverageBefore, mutationBefore: m.mutationBefore, macBefore: m.macBefore,
        attemptCoverage: m.attemptCoverage, attemptMutation: m.attemptMutation, attemptMac: m.attemptMac,
        failure: m.failure,
      });
      remeasured += 1;
    }
    // replay the persistent ledger so finished files are not re-picked
    let replayed = 0;
    for (const [p, rec] of Object.entries(ledger())) {
      if (!state.files[p]) continue;
      S.upsertFile(p, rec.state === 'improved'
        ? { status: 'improved', fromLedger: true, prUrl: rec.prUrl || null, prPatch: rec.patchPath || null, ...(rec.metrics || {}) }
        : { status: rec.state === 'failed' ? 'failed' : 'no_improvement', fromLedger: true, attempts: state.run.config.maxAttemptsPerFile || 3, ...(rec.metrics || {}) });
      replayed += 1;
    }
    if (replayed || remeasured) {
      S.event('installing', `ledger: ${replayed} file(s) already settled in previous runs — skipping them; `
        + `${remeasured} file(s) restored earlier measurements`);
    }
    return { ok: true, runner: det, scopeFiles: files.length, settledFromLedger: replayed, measurementsRestored: remeasured };
  },

  'POST /api/coverage/run': async (q, body) => {
    needRun();
    S.setStage(body.stage || 'improving_coverage', 'running test suite with coverage');
    const r = await coverage.runCoverage();
    if (body.phase === 'baseline') {
      state.run.baseline.coveragePct = r.totalPct;
      expandFilesIntoMethodUnits(r.classes || {});
      // per-unit mac recompute
      for (const f of Object.values(state.files)) f.mac = mac(f.coverage, f.mutation);
    }
    state.run.result.coveragePct = r.totalPct;
    S.save();
    return { ok: true, totalPct: r.totalPct, exitCode: r.exitCode };
  },

  'GET /api/files/candidates': async () => { needRun(); S.setStage('picking_file', 'evaluating candidate files'); return candidates(); },

  'POST /api/rules/apply': async (q, body) => {
    needRun();
    const stage = body.stage;
    const stageNames = {
      post_clone: ['applying_rules', 'post-clone rules'],
      pre_pick: ['applying_rules', 'pre-pick rules'],
      pick_file: ['picking_file', 'picking a file to mutate'],
      write_test: ['improving_coverage', 'assembling test-writing constraints'],
      check_changes: ['improving_mac', 'checking whether changes are good'],
      make_pr: ['preparing_pr', 'composing PR per team rules'],
    };
    const [sn, sd] = stageNames[stage] || ['applying_rules', stage];
    S.setStage(sn, sd);
    let context = body.context || {};
    if (stage === 'pick_file' && !context.candidates) context = { candidates: candidates().candidates };
    const result = await rulesMod.apply(stage, context);
    return { ok: true, stage, result };
  },

  'POST /api/iteration/start': async (q, body) => {
    needRun();
    const file = body.file;
    if (!file || !state.files[file]) throw new Error('unknown file: ' + file);
    if (state.run.iteration === 0) {
      // first pick of this batch: everything before it was setup overhead
      const slug = slugify(state.run.config.repoUrl);
      state.overheadLedger[slug] = (state.overheadLedger[slug] || 0)
        + Math.max(0, Math.floor(Date.now() / 1000) - state.run.startedAt);
    }
    // close any stopwatch left running by a previous, abandoned attempt
    for (const other of Object.values(state.files)) {
      if (other.attemptStartedAt && other.path !== file) accrueSpent(other.path);
    }
    state.run.iteration += 1;
    const template = state.decisions.pre_pick?.result?.branchTemplate || 'tests/improve-{file}';
    // branch (and therefore the PR) is per SOURCE FILE — the brief asks for a PR per
    // file — while the unit of work below it is the method
    const branch = template.replace('{file}', fileSlug(unitPath(file)));
    S.setStage('picking_file', `iteration ${state.run.iteration}: picked ${file}`);
    await repo.createBranch(branch);
    state.currentUnit = file;      // token usage is attributed to this unit
    S.upsertFile(file, {
      status: 'picked', branch, attempts: state.files[file].attempts + 1,
      rounds: 0, roundBase: null, lastSurvived: null,
      attemptStartedAt: Math.floor(Date.now() / 1000),
    });
    S.save();
    return { ok: true, file, branch, iteration: state.run.iteration };
  },

  'POST /api/pit/run': async (q, body) => {
    needRun();
    const file = body.file;
    if (!file) throw new Error('file required');
    S.setStage(body.stage || 'improving_mutation', `mutation testing ${unitLabel(file)}`);
    let r;
    try {
      r = await pit.runPit(file);
    } catch (e) {
      if (body.phase === 'baseline') {
        // one broken file must not sink a full-repo run: record and move on
        S.event('improving_mutation', `PIT failed on ${file} — marking file failed: ${e.message.slice(0, 250)}`);
        const spentSec = accrueSpent(file);
        const cov = state.files[file]?.coverage ?? null;
        S.upsertFile(file, { status: 'failed', coverageBefore: cov, failure: e.message.slice(0, 200) });
        recordMeasurement(file, { coverageBefore: cov, failure: e.message.slice(0, 200) });
        ledger()[file] = {
          state: 'failed', ts: Date.now(),
          metrics: { spentSec, coverageBefore: cov, failure: e.message.slice(0, 200) },
        };
        S.save();
        return { ok: false, failed: true, score: 0, survived: [], totalMutants: 0, error: e.message.slice(0, 500) };
      }
      throw e;
    }
    const f = state.files[file] || S.upsertFile(file, {});
    const fileMac = mac(f.coverage, r.score);
    // keep as many survivors as the model is offered to choose from, or it silently
    // picks from a shorter list than MUTANT_CHOICES promises
    const keepSurvivors = Math.max(10, state.run.config.mutantChoices ?? 20);
    S.upsertFile(file, { mutation: r.score, mac: fileMac, totalMutants: r.totalMutants, lastSurvived: (r.survived || []).slice(0, keepSurvivors) });
    // Nothing to mutate → nothing to improve, and MAC is pinned at 0 for ever. Constants
    // holders, marker interfaces and annotations land here. Settle the file immediately
    // WITHOUT charging it to the batch's file quota, and let the workflow pick another:
    // spending a whole run on such a class is how the first real-repo sweep produced
    // zero PRs across ten repositories.
    const minMutants = state.run.config.minMutantsPerClass ?? 3;
    if (body.phase === 'baseline' && (r.totalMutants ?? 0) < minMutants) {
      const spentSec = accrueSpent(file);
      // Skipping is FREE in both budgets. /api/iteration/start already charged an
      // iteration for this pick; give it back, or a couple of duds in a row end the run
      // before a single improvable class is ever attempted (which is exactly what
      // happened to jackson-core, json-path and concurrency-limits: two skips, done).
      state.run.iteration = Math.max(0, state.run.iteration - 1);
      S.upsertFile(file, { status: 'no_mutants', coverageBefore: f.coverage, mutationBefore: r.score, macBefore: fileMac });
      recordMeasurement(file, { coverageBefore: f.coverage, mutationBefore: r.score, macBefore: fileMac, noMutants: true });
      ledger()[file] = { state: 'no_mutants', ts: Date.now(), metrics: { spentSec, totalMutants: r.totalMutants ?? 0 } };
      S.event('improving_mutation', `${unitLabel(file)}: ${r.totalMutants ?? 0} mutants (min ${minMutants}) — `
        + 'no meaningful mutation surface, skipping to the next method');
      S.save();
      return { ok: true, ...r, skip: true, reason: 'too few mutants to improve' };
    }
    if (body.phase === 'baseline') {
      S.upsertFile(file, {
        macBefore: fileMac, coverageBefore: f.coverage, mutationBefore: r.score,
      });
      // survives batches even if this unit is never improved
      recordMeasurement(file, { coverageBefore: f.coverage, mutationBefore: r.score, macBefore: fileMac });
      refreshTotals();
      S.save();
    }
    return { ok: true, ...r };
  },

  'GET /api/files/gaps': async (q) => {
    needRun();
    const p = q.get('path');
    if (!p) throw new Error('path required');
    S.setStage('improving_coverage', `analysing coverage gaps in ${p}`);
    const srcPath = unitPath(p);
    const source = repo.readFileSafe(srcPath, 24000);
    const f = state.files[p] || {};
    const round = (f.rounds || 0) + 1;
    // each round writes its OWN test classes — Java forbids two public classes of the
    // same name, and append-only additions keep earlier rounds' commits intact
    const guess = repo.guessTestPath(srcPath, round);
    let existingTest = guess.exists ? repo.readFileSafe(guess.path, 12000) : null;
    if (!existingTest) {
      // no test for this class yet → hand the LLM a sibling test to imitate
      // (assertion library, base classes, naming and package conventions)
      const ref = repo.findStyleReference(p);
      if (ref) existingTest = `// STYLE REFERENCE — an existing test from this repo (${ref.path}).\n// Imitate its imports, assertion style and conventions. Do not modify it.\n${ref.content}`;
    }
    const fqcn = f.fqcn || repo.fqcnOf(srcPath);
    // only the part of the class the model needs for THIS method
    const mctx = f.method ? repo.methodContext(srcPath, f.method, f.methodLine) : null;
    return {
      ok: true, path: srcPath, unit: p, source, sourceLines: source ? source.split('\n').length : 0,
      methodSource: mctx ? mctx.body : null,
      classHeader: mctx ? mctx.header : null,
      siblingSignatures: mctx ? mctx.signatures : null,
      uncovered: coverage.uncoveredLines(p),
      coverage: f.coverage ?? null,
      missedLines: f.missedLines ?? null,
      executableLines: f.executableLines ?? null,
      covPhaseMaxPct: state.run.config.covPhaseMaxPct ?? 0,
      mutantsPerRound: state.run.config.mutantsPerRound ?? 1,
      mutantChoices: state.run.config.mutantChoices ?? 20,
      totalMutants: f.totalMutants ?? null,
      // the method this unit is about: the tests must concentrate here
      method: f.method || null,
      methodLine: f.methodLine || null,
      rounds: f.rounds || 0,
      // Never offer a mutant we have already tried and failed to kill. Each round
      // re-measures, so the list is fresh — a kill often takes neighbours with it and
      // those simply disappear — but a survivor we already attacked and left alive is a
      // known dead end for this approach, and re-picking it burns the round.
      survived: (f.lastSurvived || []).filter((m) =>
        !(f.attemptedMutants || []).includes(`${m.mutator}@${m.line}`)),
      attemptedMutants: (f.attemptedMutants || []).length,
      targetMethod: f.targetMethod || null,
      fqcn,
      package: fqcn.includes('.') ? fqcn.slice(0, fqcn.lastIndexOf('.')) : '',
      className: fqcn.slice(fqcn.lastIndexOf('.') + 1),
      module: f.module || repo.moduleOf(srcPath),
      // both generated class names for THIS round, already Surefire/Gradle-includable
      covTestPath: guess.covPath,
      mutTestPath: guess.mutPath,
      projectTestPath: guess.exists ? guess.path : null,
      testExists: guess.exists,
      round,
      existingTest,
      buildTool: state.runner?.tool,
      testFramework: state.runner?.testFramework,
      testFrameworkVersion: state.runner?.testFrameworkVersion,
      jdk: state.runner?.jdk?.chosen,
      constraints: rulesMod.testWritingConstraints(),
    };
  },

  'POST /api/test/write': async (q, body) => {
    needRun();
    try {
      const r = repo.writeTestFile(body.path, String(body.content || ''));
      S.event('improving_coverage', 'wrote ' + r.path + ' (' + r.bytes + ' bytes)');
      return { ok: true, ...r };
    } catch (e) { return { ok: false, error: e.message }; }
  },

  'POST /api/test/delete': async (q, body) => {
    needRun();
    return { ok: repo.deleteTestFile(body.path) };
  },

  'POST /api/test/write-many': async (q, body) => {
    needRun();
    if (body.stage) S.setStage(body.stage, 'writing generated tests');
    // the model's own account of which mutant it chose and why — worth seeing on the
    // dashboard, since the choice drives the whole round
    if (body.note) S.event(body.stage || 'improving_mutation', String(body.note).slice(0, 300));
    // remember WHICH mutant this round is trying to kill, so the next measurement can
    // report whether it actually died instead of leaving us to infer it from a score
    if (body.targetMutant && state.currentUnit) {
      const u = state.files[state.currentUnit] || {};
      const tried = (u.attemptedMutants || []).slice();
      const key = `${body.targetMutant.mutator}@${body.targetMutant.line}`;
      if (body.targetMutant.line && !tried.includes(key)) tried.push(key);
      S.upsertFile(state.currentUnit, { targetMutant: body.targetMutant, attemptedMutants: tried });
    }
    const written = [], errors = [];
    for (const t of (body.tests || []).slice(0, 5)) {
      try {
        const r = repo.writeTestFile(t.path, String(t.content || ''));
        written.push(r.path);
        S.event(body.stage || 'improving_coverage', 'wrote ' + r.path + ' (' + r.bytes + ' bytes)');
      } catch (e) { errors.push({ path: t.path, error: e.message }); }
    }
    return { ok: true, written, errors };
  },

  'POST /api/test/delete-many': async (q, body) => {
    needRun();
    const deleted = [];
    for (const p of [...new Set(body.paths || [])]) {
      if (repo.deleteTestFile(p)) deleted.push(p);
    }
    S.event(body.stage || 'improving_coverage', 'deleted generated tests that broke the suite: ' + deleted.join(', '));
    return { ok: true, deleted };
  },

  'POST /api/test/run': async (q, body) => {
    needRun();
    if (body.stage) S.setStage(body.stage, 'running tests ' + (body.path || '(full suite)'));
    try {
      const r = await tests.runTests(body.path || null);
      return { ok: true, ...r };
    } catch (e) { return { ok: false, passed: false, error: e.message }; }
  },

  'POST /api/llm/chat': async (q, body) => {
    if (body.stage) S.setStage(body.stage, body.stageDetail || 'consulting LLM');
    try {
      const r = await llm.chat({
        system: body.system, prompt: body.prompt, messages: body.messages,
        stage: body.stage, stageDetail: body.stageDetail,
        maxTokens: clamp(parseInt(body.maxTokens || '4096', 10), 64, 12000),
        temperature: body.temperature, json: !!body.json,
      });
      return { ok: true, text: r.text, json: r.json ?? null };
    } catch (e) { S.event('llm', 'LLM error: ' + e.message); return { ok: false, error: e.message }; }
  },

  'POST /api/test/cleanup': async (q, body) => {
    needRun();
    const file = body.file;
    const f = state.files[file] || {};
    S.setStage('preparing_pr', `cleaning up generated tests for ${file}`);
    // Accepted rounds are already COMMITTED, so the working tree is usually clean
    // here — select against the base branch, not `git status`.
    const changed = await pr.changedTestFiles();
    if (!changed.length) S.event('preparing_pr', `cleanup: no generated test files found for ${file}`);
    const results = [];
    for (const p of changed.slice(0, 5)) {
      const original = repo.readFileSafe(p, 100000);
      if (!original) continue;
      try {
        const r = await llm.chat({
          system: 'You are a strict test-code editor. Clean this generated test file: (1) REMOVE all scratch/chain-of-thought comments — anything reasoning aloud ("Wait", "Let\'s try", exploratory strategy essays, self-corrections). Keep at most ONE short comment per test stating which mutant/behavior it verifies. (2) REMOVE tests that are vacuous (cannot fail, assert nothing meaningful, or admit in comments they kill nothing). (3) Do NOT change, weaken, or reorder any remaining test logic, imports, or setup. Reply with ONLY the complete cleaned file content — no markdown fences, no explanation.',
          prompt: original,
          maxTokens: 9000, temperature: 0.1,
        });
        const cleaned = (r.text || '').replace(/^[\s\S]*?<\/think>/, '')
          .replace(/^```[a-z]*\s*\n?/m, '').replace(/```\s*$/m, '').trim() + '\n';
        const plausible = cleaned.length > 200 && /\b(it|test|describe)\s*\(/.test(cleaned)
          && cleaned.length >= original.length * 0.2 && cleaned.length <= original.length * 1.2;
        if (!plausible || cleaned === original) {
          results.push({ path: p, kept: 'original', reason: !plausible ? 'implausible cleanup output' : 'no changes' });
          continue;
        }
        repo.writeTestFile(p, cleaned);
        results.push({ path: p, kept: 'cleaned', bytesBefore: original.length, bytesAfter: cleaned.length, _original: original });
      } catch (e) { results.push({ path: p, kept: 'original', reason: e.message.slice(0, 200) }); }
    }
    const touched = results.filter((r) => r.kept === 'cleaned');
    const publicResults = results.map(({ _original, ...r }) => r);
    if (!touched.length) return { ok: true, cleaned: 0, results: publicResults };
    // verified cleanup: suite must stay green AND mutation score must not drop
    const suite = await tests.runTests(null);
    let newScore = null, scoreOk = false;
    if (suite.passed) {
      try {
        const st = await pit.runPit(file);
        newScore = st.score;
        scoreOk = newScore >= (f.mutationAfter ?? f.mutation ?? 0);
      } catch { scoreOk = false; }
    }
    if (!suite.passed || !scoreOk) {
      for (const t of touched) repo.writeTestFile(t.path, t._original);
      S.event('preparing_pr', `cleanup reverted: ${!suite.passed ? 'suite went red' : 'mutation score dropped to ' + newScore}`);
      return { ok: true, cleaned: 0, reverted: true, results: publicResults };
    }
    const cov = f.coverageAfter ?? f.coverage;
    S.upsertFile(file, { mutation: newScore, mutationAfter: newScore, mac: mac(cov, newScore), macAfter: mac(cov, newScore) });
    // cleaned files are edits on top of committed rounds — commit them so the PR carries them
    try { await pr.commit(`test: tidy generated tests for ${file}`); }
    catch (e) { S.event('preparing_pr', 'cleanup commit note: ' + e.message.slice(0, 160)); }
    S.event('preparing_pr', 'cleanup kept: ' + touched.map((t) => `${t.path} ${t.bytesBefore}→${t.bytesAfter}B`).join(', '));
    return { ok: true, cleaned: touched.length, mutationAfter: newScore, results: publicResults };
  },

  'POST /api/verify': async (q, body) => {
    needRun();
    const file = body.file;
    if (!file || !state.files[file]) throw new Error('unknown file: ' + file);
    const f = state.files[file];
    S.setStage('improving_mac', `verifying MAC improvement for ${file}`);
    try {
      const suite = await tests.runTests(null);
      if (!suite.passed) {
        return { ok: true, improved: false, testsGreen: false, reason: 'full suite red', summary: suite.summary, file };
      }
      const cov = await coverage.runCoverage();
      // The unit IS a method, so PIT measures exactly it: the score needs no projection
      // and no class-wide reconciliation. MAC for the unit = method coverage x method
      // mutation score, both measured on the same method.
      S.setStage('improving_mac', `re-measuring mutation score of ${unitLabel(file)}`);
      const st = await pit.runPit(file);
      // Did the mutant this round aimed at actually die? An unchanged score says the
      // round achieved nothing but not WHY; this distinguishes "the test never ran",
      // "the test ran but does not distinguish the mutation" and "it worked".
      const tm = f.targetMutant;
      if (tm && tm.line) {
        const stillAlive = (st.survived || []).some((m) => m.line === tm.line && m.mutator === tm.mutator);
        const killedNow = (st.killed ?? 0) - (f.mutation != null && f.totalMutants
          ? Math.round((f.mutation / 100) * f.totalMutants) : 0);
        S.event('improving_mac', `targeted mutant ${tm.mutator} at line ${tm.line}: `
          + (stillAlive ? 'STILL ALIVE — the new test does not distinguish it' : 'KILLED')
          + (killedNow > 1 ? ` — and took ${killedNow - 1} other mutant(s) with it` : '')
          + (st.testsRun ? ` (${st.testsRun} tests ran)` : ''));
        S.upsertFile(file, { lastTargetKilled: !stillAlive });
      }
      const coverageAfter = state.files[file].coverage;
      const macAfter = mac(coverageAfter, st.score);
      S.upsertFile(file, {
        mutation: st.score, mac: macAfter, macAfter, coverageAfter, mutationAfter: st.score,
        lastSurvived: (st.survived || []).slice(0, Math.max(10, state.run.config.mutantChoices ?? 20)),
      });
      state.run.result.coveragePct = cov.totalPct;
      refreshTotals();
      S.save();
      const diff = await pr.diffAgainstBase();
      const changed = await pr.changedFiles();
      // round criterion: keep the round iff ≥1 metric improves AND none degrades
      const rb = f.roundBase || { coverage: f.coverageBefore, mutation: f.mutationBefore, mac: f.macBefore };
      const improvedAny = changed.length > 0 && ((coverageAfter ?? 0) > (rb.coverage ?? 0)
        || (st.score ?? 0) > (rb.mutation ?? 0) || (macAfter ?? 0) > (rb.mac ?? 0));
      const degradedAny = (coverageAfter ?? 0) < (rb.coverage ?? 0)
        || (st.score ?? 0) < (rb.mutation ?? 0) || (macAfter ?? 0) < (rb.mac ?? 0);
      // no changed files → any measured delta is PIT run-to-run noise, not improvement
      const improved = (macAfter ?? 0) > (f.macBefore ?? 0) && changed.length > 0;
      // remember the best result any attempt reached, even if it is not kept —
      // "we tried and got this far" is information worth showing
      const prev = measured()[file] || {};
      if ((macAfter ?? 0) >= (prev.attemptMac ?? -1)) {
        recordMeasurement(file, { attemptCoverage: coverageAfter, attemptMutation: st.score, attemptMac: macAfter });
      }
      const rounds = f.rounds || 0;
      const maxRounds = state.run.config.maxRoundsPerFile || 5;
      const elapsedSec = (f.spentSec || 0)
        + (f.attemptStartedAt ? Math.floor(Date.now() / 1000) - f.attemptStartedAt : 0);
      const { keepRound, continueRounds, gain, gapClosed, verdict } = roundsMod.decide({
        macBase: rb.mac, macAfter, improvedAny, degradedAny, rounds, maxRounds,
        minGapFrac: state.run.config.minRoundGapFrac,
        minGain: state.run.config.minRoundGain,
        // granularity: one mutant of N is the smallest move a round can make, and the
        // floors must never sit above it
        totalMutants: st.totalMutants ?? f.totalMutants ?? null,
        coverage: coverageAfter,
        elapsedSec, budgetSec: state.run.config.unitBudgetSec || 0,
      });
      S.event('improving_mac', `round ${rounds + 1} of ${file}: cov ${rb.coverage}→${coverageAfter}, mut ${rb.mutation}→${st.score}, mac ${rb.mac}→${macAfter} — ${verdict}`);
      // remembered so /api/round/accept can echo it: the loop's IF then reads the
      // verdict off its direct input instead of reaching back to a node that has
      // run several times in this execution
      S.upsertFile(file, { continueRounds });
      return {
        ok: true, file, testsGreen: true,
        coverageBefore: f.coverageBefore, coverageAfter,
        mutationBefore: f.mutationBefore, mutationAfter: st.score,
        macBefore: f.macBefore, macAfter,
        improved,
        improvedAny, degradedAny,
        keepRound, continueRounds, roundGain: gain, roundGapClosed: gapClosed,
        rounds,
        maxRounds,
        totalCoverage: cov.totalPct,
        changedFiles: changed,
        diff: diff.slice(0, 30000),
        branch: f.branch,
      };
    } catch (e) {
      S.event('improving_mac', 'verification failed: ' + e.message.slice(0, 300));
      return { ok: true, improved: false, testsGreen: false, error: e.message, file };
    }
  },

  'POST /api/round/accept': async (q, body) => {
    needRun();
    const file = body.file;
    const f = state.files[file];
    if (!f) throw new Error('unknown file: ' + file);
    const rounds = (f.rounds || 0) + 1;
    // commit this round's tests so a later degrading round can be dropped alone
    try { await pr.commit(`test: improve MAC of ${file} (round ${rounds})`); }
    catch (e) { S.event('improving_mac', `round ${rounds} commit note: ${e.message}`); }
    S.upsertFile(file, {
      rounds,
      roundBase: { coverage: f.coverageAfter, mutation: f.mutationAfter, mac: f.macAfter },
    });
    S.event('improving_mac', `round ${rounds} accepted for ${file} (mac now ${f.macAfter})`);
    return { ok: true, file, rounds, continueRounds: !!f.continueRounds };
  },

  'POST /api/round/drop': async (q, body) => {
    needRun();
    const file = body.file;
    const f = state.files[file];
    if (!f) throw new Error('unknown file: ' + file);
    S.setStage('improving_mac', `finalizing ${file} after ${f.rounds || 0} accepted round(s)`);
    // drop the last (stale/degraded) round: uncommitted changes only
    await repo.discardUncommitted();
    let rb = f.roundBase || { coverage: f.coverageBefore, mutation: f.mutationBefore, mac: f.macBefore };
    const keptRounds = (f.rounds || 0) > 0;
    S.upsertFile(file, keptRounds
      ? { coverage: rb.coverage, mutation: rb.mutation, mac: rb.mac,
        coverageAfter: rb.coverage, mutationAfter: rb.mutation, macAfter: rb.mac }
      // nothing was kept: leave the "after" columns empty rather than echoing
      // "before" values, which read as a measured (null) improvement
      : { coverage: rb.coverage, mutation: rb.mutation, mac: rb.mac,
        coverageAfter: null, mutationAfter: null, macAfter: null });
    const diff = await pr.diffAgainstBase();
    const changed = diff.match(/^\+\+\+ b\/(.+)$/gm)?.map((l) => l.slice(6)) || [];
    const improved = (f.rounds || 0) > 0 && (rb.mac ?? 0) > (f.macBefore ?? 0);
    return {
      ok: true, file, testsGreen: true,
      coverageBefore: f.coverageBefore, coverageAfter: rb.coverage,
      mutationBefore: f.mutationBefore, mutationAfter: rb.mutation,
      macBefore: f.macBefore, macAfter: rb.mac,
      improved, rounds: f.rounds || 0,
      changedFiles: changed,
      diff: diff.slice(0, 30000),
      branch: f.branch,
    };
  },

  'POST /api/pr/create': async (q, body) => {
    needRun();
    const file = body.file;
    const f = state.files[file];
    if (!f) throw new Error('unknown file: ' + file);
    S.setStage('preparing_pr', `preparing PR for ${file}`);
    // human-equivalent timesheet from the cumulative diff (before commit resets nothing:
    // diff is vs base and includes committed rounds)
    let ts = null;
    try {
      const diffText = await pr.diffAgainstBase();
      const stats = timesheet.diffStats(diffText);
      const src = repo.readFileSafe(unitPath(file), 500000);
      const mutantsKilled = (f.totalMutants && f.mutationAfter != null && f.mutationBefore != null)
        ? Math.max(0, Math.round((f.mutationAfter - f.mutationBefore) * f.totalMutants / 100))
        : Math.ceil(stats.testCases / 2);
      ts = timesheet.estimate({
        sourceLines: src ? src.split('\n').length : 0,
        testCases: stats.testCases, addedTestLines: stats.addedTestLines,
        mutantsKilled, rounds: f.rounds || 1,
      });
      S.event('preparing_pr', `human-equivalent timesheet for ${file}: ${ts.hours} h (analysis ${ts.analysisMin}m + writing ${ts.testsMin}m + mutation ${ts.mutationMin}m + verify ${ts.verifyMin}m)`);
    } catch (e) { S.event('preparing_pr', 'timesheet estimate skipped: ' + e.message.slice(0, 120)); }
    try {
      await pr.commit(body.title || `test: improve MAC of ${file}`);
    } catch (e) {
      if (/no changed test files/.test(e.message)) {
        await repo.resetToBase();
        S.upsertFile(file, { status: 'no_improvement' });
        S.event('preparing_pr', `skipping PR for ${file}: ${e.message}`);
        return { ok: true, pr: null, skipped: e.message };
      }
      throw e;
    }
    const rec = await pr.createPr({
      file, branch: f.branch, title: body.title, body: body.body || '', labels: body.labels || [],
    });
    const spentSec = accrueSpent(file);
    S.upsertFile(file, { status: 'improved', prUrl: rec.url, prPatch: rec.patchPath, timesheet: ts });
    ledger()[file] = {
      state: 'improved', prUrl: rec.url, patchPath: rec.patchPath, branch: f.branch, ts: Date.now(),
      metrics: {
        coverageBefore: f.coverageBefore, coverageAfter: f.coverageAfter,
        mutationBefore: f.mutationBefore, mutationAfter: f.mutationAfter,
        macBefore: f.macBefore, macAfter: f.macAfter,
        timesheet: ts, spentSec,
      },
    };
    S.save();
    await repo.resetToBase();
    return { ok: true, pr: rec };
  },

  'POST /api/iteration/discard': async (q, body) => {
    needRun();
    const file = body.file;
    S.setStage('improving_mac', `no improvement for ${file} — discarding changes`);
    await repo.resetToBase();
    if (file && state.files[file]) {
      const f = state.files[file];
      const spentSec = accrueSpent(file);
      if (f.status !== 'failed') {
        const maxAttempts = state.run.config.maxAttemptsPerFile || 3;
        S.upsertFile(file, { status: f.attempts >= maxAttempts ? 'no_improvement' : 'candidate' });
        if (f.attempts >= maxAttempts) {
          // keep the numbers: a file we could not improve is still a file we measured
          const m = measured()[file] || {};
          ledger()[file] = {
            state: 'exhausted', ts: Date.now(),
            metrics: {
              spentSec, attempts: f.attempts,
              coverageBefore: f.coverageBefore ?? m.coverageBefore,
              mutationBefore: f.mutationBefore ?? m.mutationBefore,
              macBefore: f.macBefore ?? m.macBefore,
              attemptCoverage: m.attemptCoverage, attemptMutation: m.attemptMutation, attemptMac: m.attemptMac,
            },
          };
          S.save();
        }
      }
    }
    S.event('improving_mac', `discarded changes for ${file}: ${body.reason || 'no MAC improvement'}`);
    return { ok: true };
  },

  'POST /api/admin/reset': async () => {
    state.run = null; state.files = {}; state.decisions = {}; state.prs = []; state.events = []; state.seq = 0;
    S.setStage('idle', 'state reset');
    return { ok: true };
  },
};

// ── static dashboard ───────────────────────────────────────────────────────
const DASH = path.join(__dirname, 'dashboard');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.json': 'application/json' };
function serveStatic(req, res, rel) {
  if (!rel || rel === '/') rel = '/index.html';
  const abs = path.join(DASH, path.normalize(rel));
  if (!abs.startsWith(DASH)) { res.writeHead(403); return res.end(); }
  fs.readFile(abs, (err, buf) => {
    if (err) { res.writeHead(404); return res.end('not found'); }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(abs)] || 'application/octet-stream' });
    res.end(buf);
  });
}

const server = http.createServer(async (req, res) => {
  const u = new URL(req.url, 'http://x');
  const key = req.method + ' ' + u.pathname;
  try {
    if (routes[key]) {
      const body = req.method === 'POST' ? await readBody(req) : {};
      const out = await routes[key](u.searchParams, body);
      return json(res, 200, out);
    }
    if (u.pathname.startsWith('/api/')) return json(res, 404, { error: 'no such endpoint: ' + key });
    // dashboard: served at / and /dashboard (Caddy strips the /dashboard prefix)
    let rel = u.pathname;
    if (rel.startsWith('/dashboard')) rel = rel.slice('/dashboard'.length) || '/';
    return serveStatic(req, res, rel);
  } catch (e) {
    S.event('error', `${key}: ${e.message}`);
    // a rejected concurrent start is a guard, not a run failure
    if (e.statusCode === 409) return json(res, 409, { ok: false, error: e.message });
    // a hard error aborts the n8n execution → the run is dead; reflect that
    if (state.run && state.run.status === 'running' && req.method === 'POST') {
      state.run.status = 'failed';
      state.run.finishedAt = Math.floor(Date.now() / 1000);
      S.setStage('failed', e.message.slice(0, 200));
    }
    return json(res, 500, { ok: false, error: require('./util').redact(e.message) });
  }
});

S.load();
server.listen(PORT, () => console.log(`sidecar listening on :${PORT}`));
