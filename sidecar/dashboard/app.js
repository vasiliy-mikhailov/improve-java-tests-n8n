'use strict';
// Live dashboard: polls api/metrics (relative path — works at / and behind /dashboard/).

const STAGE_LABELS = {
  idle: 'Idle', starting: 'Starting run', cloning: 'Cloning repository',
  applying_rules: 'Applying team rules', installing: 'Installing dependencies',
  measuring_baseline: 'Measuring baseline', picking_file: 'Picking a file to mutate',
  branching: 'Creating branch', improving_coverage: 'Improving coverage',
  improving_mutation: 'Improving mutation score', improving_mac: 'Improving MAC (verifying)',
  preparing_pr: 'Preparing PR', iteration_done: 'Iteration done', done: 'Done',
  failed: 'Failed', interrupted: 'Interrupted', error: 'Error',
};
const ACTIVE = ['starting', 'cloning', 'applying_rules', 'installing', 'measuring_baseline',
  'picking_file', 'branching', 'improving_coverage', 'improving_mutation', 'improving_mac', 'preparing_pr'];

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const fmt = (x, suf = '%') => (x == null ? '–' : (Math.round(x * 100) / 100) + suf);
const fmtHours = (h) => h >= 8 ? `${Math.round(h / 8 * 10) / 10} d (${Math.round(h * 10) / 10} h)` : `${Math.round(h * 10) / 10} h`;
const fmtDur = (sec) => {
  const d = Math.floor(sec / 86400), h = Math.floor((sec % 86400) / 3600), m = Math.floor((sec % 3600) / 60);
  return d ? `${d}d ${h}h` : h ? `${h}h ${m}m` : `${m}m`;
};

async function tick() {
  let m;
  try {
    const r = await fetch('api/metrics', { cache: 'no-store' });
    m = await r.json();
  } catch { setBanner({ name: 'offline', detail: 'sidecar unreachable' }); return; }
  render(m);
}

function setBanner(stage) {
  const el = $('stage-banner');
  el.className = 'stage ' + (ACTIVE.includes(stage.name) ? 'active' : stage.name === 'failed' ? 'failed' : stage.name === 'done' ? 'done' : 'idle');
  $('stage-name').textContent = STAGE_LABELS[stage.name] || stage.name;
  $('stage-detail').textContent = stage.detail || '';
  const p = stage.progress;
  $('stage-progress').textContent = p && (Date.now() / 1000 - p.ts) < 60
    ? `${p.line}  ·  ${p.elapsed}s` : '';
}

function render(m) {
  setBanner(m.stage || { name: 'idle' });
  const cfg = m.run?.config;
  $('repo').textContent = cfg ? `${cfg.repoUrl} @ ${cfg.repoBranch} · PR mode: ${cfg.prMode} · run ${m.run.status}` : 'no run yet';

  const b = m.totals?.baseline || {}, c = m.totals?.current || {};
  $('c-cov').textContent = fmt(c.coveragePct ?? b.coveragePct);
  $('c-cov-d').textContent = b.coveragePct != null ? `baseline ${fmt(b.coveragePct)}` : '';
  $('c-mut').textContent = fmt(c.mutationPct ?? b.mutationPct);
  $('c-mut-d').textContent = b.mutationPct != null
    ? `baseline ${fmt(b.mutationPct)} · avg of ${m.totals?.measuredFiles ?? 0} measured file(s)` : '';
  $('c-mac').textContent = fmt(m.totals?.avgMacAfter, '');
  $('c-mac-d').textContent = m.totals?.avgMacBefore != null ? `before ${fmt(m.totals.avgMacBefore, '')} (targeted files)` : '';
  $('c-iter').textContent = m.run ? m.run.iteration : '–';
  $('c-iter-d').textContent = cfg ? `of max ${cfg.maxIterations}` : '';
  $('c-prs').textContent = (m.prs || []).length;
  $('c-prs-d').textContent = `${m.totals?.improvedFiles ?? 0} file(s) improved`;

  const w = m.work || {};
  const pct = w.totalFiles ? Math.round((w.settled / w.totalFiles) * 100) : 0;
  $('c-prog').textContent = w.totalFiles ? `${w.settled} / ${w.totalFiles} files (${pct}%)` : '–';
  $('c-prog-bar').style.width = pct + '%';
  $('c-prog-d').textContent = w.totalFiles ? `${w.improved} improved · ${w.settled - w.improved} no-improvement/failed · ${w.remaining} remaining` : '';
  $('c-human').textContent = w.humanHours != null ? fmtHours(w.humanHours) : '–';
  $('c-machine').textContent = w.machineHours != null ? fmtHours(w.machineHours) : '–';
  $('c-fte').textContent = w.fte != null ? w.fte + '×' : '–';
  const fteCard = $('c-fte').parentElement.querySelector('.d');
  if (fteCard) {
    fteCard.textContent = w.fte != null
      ? `over ${w.fteBasis} file(s) with measured machine time`
      : 'measuring…';
  }
  $('c-eta').textContent = w.etaSec != null ? fmtDur(w.etaSec) : '–';
  $('c-eta-d').textContent = w.etaSec != null ? `at current pace, ${w.remaining} file(s) left` : 'measuring pace…';

  const tb = $('files').querySelector('tbody');
  const files = (m.files || []).filter((f) => f.status !== 'candidate' || f.coverage != null).slice(0, 200);
  const inScope = m.work?.totalFiles ?? (m.files || []).length;
  $('files-count').textContent = `(${inScope} in scope${inScope > files.length ? `, showing ${files.length}` : ''})`;
  tb.innerHTML = files.map((f) => {
    // before = frozen at pick time (falls back to live measurement for candidates);
    // after = frozen at verify/PR time; live values never overwrite these columns
    const covB = f.coverageBefore ?? f.coverage;
    const mutB = f.mutationBefore ?? (f.macBefore != null ? f.mutation : null);
    const macB = f.macBefore ?? (f.mac != null && f.macAfter == null ? f.mac : null);
    const hasAfter = f.macAfter != null;
    // not improved, but we did measure what the attempt reached — show it muted
    // rather than dropping the information on the floor
    const tried = !hasAfter && (f.attemptMac != null || f.attemptMutation != null);
    const cell = (kept, attempt, suffix = '%') => hasAfter ? `<b>${fmt(kept, suffix)}</b>`
      : tried ? `<span class="attempt" title="best attempt — not kept (no net MAC gain)">${fmt(attempt, suffix)}</span>`
        : '–';
    return `<tr class="st-${esc(f.status)}"${f.failure ? ` title="${esc(f.failure)}"` : ''}>
    <td class="path">${esc(f.path)}</td>
    <td>${fmt(covB)}</td><td>${fmt(mutB)}</td><td>${fmt(macB, '')}</td>
    <td>${cell(f.coverageAfter, f.attemptCoverage)}</td>
    <td>${cell(f.mutationAfter, f.attemptMutation)}</td>
    <td>${cell(f.macAfter, f.attemptMac, '')}</td>
    <td title="${f.timesheet ? esc(`analysis ${f.timesheet.analysisMin}m · writing ${f.timesheet.testsMin}m · mutation ${f.timesheet.mutationMin}m · verify ${f.timesheet.verifyMin}m`) : ''}">${f.timesheet ? fmtHours(f.timesheet.hours) : '–'}</td>
    <td><span class="badge b-${esc(f.status)}">${esc(f.status)}</span></td>
    <td>${f.prUrl ? `<a href="${esc(f.prUrl)}" target="_blank">PR ↗</a>` : (f.prPatch ? 'patch' : '')}</td>
  </tr>`;
  }).join('');

  $('prs').innerHTML = (m.prs || []).map((p) => `<li>
    <b>${esc(p.title)}</b> — <code>${esc(p.branch)}</code>
    ${p.url ? ` · <a href="${esc(p.url)}" target="_blank">${esc(p.url)}</a>` : ` · local patch: <code>${esc(p.patchPath || '')}</code>`}
  </li>`).join('') || '<li class="muted">none yet</li>';

  $('decisions').innerHTML = Object.entries(m.decisions || {}).map(([k, v]) => `
    <details><summary><b>${esc(k)}</b> ${v.rule ? '· rule: “' + esc(v.rule).slice(0, 90) + '”' : '· (no rule set)'}</summary>
    <pre>${esc(JSON.stringify(v.result, null, 2)).slice(0, 2000)}</pre></details>`).join('') || '<span class="muted">none yet</span>';

  $('events').innerHTML = (m.events || []).slice().reverse().map((e) => `
    <div class="ev"><span class="ts">${new Date(e.ts * 1000).toLocaleTimeString()}</span>
    <span class="badge b-stage">${esc(e.stage)}</span> ${esc(e.msg)}</div>`).join('');
}

tick();
setInterval(tick, 2000);
