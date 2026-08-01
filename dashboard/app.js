'use strict';
// Live dashboard: subscribes to the run instead of polling it.
//
// ONLY THE TRANSPORT CHANGED. Every selector, format helper and line of markup below is what
// it was when this page polled `api/metrics` every 2 s and `api/llm/log` every 5 s. What used
// to be two timers is now a STOMP subscription over a WebSocket: `/topic/events` carries the
// event log line by line, `/topic/stage` carries the stage banner including its progress line,
// and the derived numbers are re-read only when something has actually happened. An idle
// dashboard now makes no requests at all.
//
// The STOMP client is written out here rather than pulled in. This directory is the unported
// dashboard and app.js is the only file in it that may change, so there is no <script> tag to
// add and no vendored asset to serve — and a CDN is not an option for something that has to run
// on a laptop with no network. The frames are text (CONNECT, SUBSCRIBE, MESSAGE) and the
// orchestrator registers a plain WebSocket endpoint next to the SockJS one for exactly this
// reason; see orchestrator/…/ws/WebSocketConfig.java.

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
const fmtTok = (n) => !n ? '0' : n >= 1e6 ? (n / 1e6).toFixed(1) + 'M' : n >= 1e3 ? Math.round(n / 1e3) + 'k' : String(n);
const fmtHours = (h) => h >= 8 ? `${Math.round(h / 8 * 10) / 10} d (${Math.round(h * 10) / 10} h)` : `${Math.round(h * 10) / 10} h`;
const fmtDur = (sec) => {
  const d = Math.floor(sec / 86400), h = Math.floor((sec % 86400) / 3600), m = Math.floor((sec % 3600) / 60);
  return d ? `${d}d ${h}h` : h ? `${h}h ${m}m` : `${m}m`;
};

// ── the feed ───────────────────────────────────────────────────────────────
// Event lines now reach this page from three places — the snapshot read on connect, the
// stream while it is up, and whatever `api/metrics` still carries on a refresh — so they are
// MERGED BY `seq` rather than replacing one another. seq is the right key for it: it only ever
// rises, and it deliberately does not reset when a run starts, which is what lets a client that
// reconnects say where it got to. Duplicates collapse, order is by seq, and a line that arrives
// twice by two routes is drawn once.
const FEED_MAX = 400;              // the window the backend itself keeps
const eventsBySeq = new Map();
// The snapshot's seq. A streamed event at or below it is already in hand: this is what closes
// the gap between "subscribed" and "read the snapshot" without showing anything twice.
let streamFloor = 0;

function mergeEvents(list) {
  for (const e of list || []) if (e && e.seq != null) eventsBySeq.set(e.seq, e);
  if (eventsBySeq.size > FEED_MAX) {
    const oldest = [...eventsBySeq.keys()].sort((a, b) => a - b).slice(0, eventsBySeq.size - FEED_MAX);
    for (const k of oldest) eventsBySeq.delete(k);
  }
}

// ── reading what the stream does not carry ─────────────────────────────────
// The cards, the files table and the work accounting are DERIVED — averages, the FTE ratio,
// the ETA — and that derivation lives in the backend, not here. The stream says WHEN to ask
// for them; it does not replace them.

let dialogCache = [];

async function getJson(path) {
  const r = await fetch(path, { cache: 'no-store' });
  if (!r.ok) throw new Error(`${path} → HTTP ${r.status}`);
  return r.json();
}

let refreshedAt = 0;
async function refresh() {
  refreshedAt = Date.now();
  let m;
  try { m = await getJson('api/metrics'); }
  catch { setBanner({ name: 'offline', detail: 'sidecar unreachable' }); return; }
  // The model exchanges belong IN the activity stream: read on their own they are just a pile
  // of prompts with no context, but interleaved with the events they sit between — which
  // mutant was chosen, which test was written, what PIT then said — they explain the run.
  try { dialogCache = (await getJson('api/llm/log')).entries || []; } catch { /* keep the previous copy */ }
  mergeEvents(m.events);
  render(m);
}

// At most one derived refresh a second, however chatty the run gets. Without this a build
// printing a progress line every 50 ms would put the 2-second poll back, faster.
const REFRESH_MIN_MS = 1000;
let refreshQueued = null;
function scheduleRefresh() {
  if (refreshQueued) return;
  refreshQueued = setTimeout(() => {
    refreshQueued = null;
    refresh();
  }, Math.max(0, REFRESH_MIN_MS - (Date.now() - refreshedAt)));
}

// The snapshot: everything the stream cannot tell a page that has just been opened.
async function loadSnapshot() {
  const s = await getJson('api/state');
  streamFloor = Number(s.seq) || 0;
  mergeEvents(s.events);
  if (s.stage) { setBanner(s.stage); stageMoved(s.stage); }
}

// ── STOMP over a native WebSocket ──────────────────────────────────────────
const NUL = '\u0000';
const SUB_EVENTS = 'ev';
const SUB_STAGE = 'st';
const DEST_EVENTS = '/topic/events';
const DEST_STAGE = '/topic/stage';
// Matches the server's. A unit's PIT measurement can run for forty minutes without producing a
// single frame, and an idle connection through a reverse proxy is reaped long before that —
// silently, leaving a page that looks healthy and is not.
const HEARTBEAT_MS = 10000;
const SILENCE_LIMIT_MS = 4 * HEARTBEAT_MS;

function endpoint() {
  // Resolved against the page, exactly like `fetch('api/metrics')`, so it works at / and behind
  // Caddy's /dashboard prefix without knowing which one it is in.
  const u = new URL('ws', document.baseURI);
  u.protocol = u.protocol === 'https:' ? 'wss:' : 'ws:';
  return u.href;
}

function encodeFrame(command, headers, body) {
  let out = command + '\n';
  for (const [k, v] of Object.entries(headers || {})) out += `${k}:${v}\n`;
  return `${out}\n${body || ''}${NUL}`;
}

function parseFrame(raw) {
  // EOLs between frames are the server's heart-beat, not a frame.
  const text = raw.replace(/^[\r\n]+/, '');
  if (!text) return null;
  const sep = /\r?\n\r?\n/.exec(text);
  const head = sep ? text.slice(0, sep.index) : text;
  const body = sep ? text.slice(sep.index + sep[0].length) : '';
  const lines = head.split(/\r?\n/);
  const command = lines.shift();
  const headers = {};
  // No header unescaping: the only headers read here are `subscription` and `destination`, and
  // neither can contain a character STOMP escapes.
  for (const line of lines) {
    const i = line.indexOf(':');
    if (i > 0) headers[line.slice(0, i)] = line.slice(i + 1);
  }
  return { command, headers, body };
}

let socket = null;
let attempts = 0;
let fallbackTimer = null;
// Frames that arrive before the snapshot has been read. They cannot be applied yet — until
// `streamFloor` is known there is no way to tell which of them the snapshot already contains.
let held = [];
let holding = true;

let lastStageKey = '';
function stageMoved(stage) {
  const key = `${stage.name || ''}\u0000${stage.detail || ''}`;
  if (key === lastStageKey) return false;
  lastStageKey = key;
  return true;
}

function connect() {
  let ws;
  try { ws = new WebSocket(endpoint()); } catch { retry(); return; }
  socket = ws;
  holding = true;
  held = [];

  let buf = '';
  let lastRx = Date.now();
  let beat = null;
  let watchdog = null;
  const stopTimers = () => { clearInterval(beat); clearInterval(watchdog); };

  ws.onopen = () => {
    ws.send(encodeFrame('CONNECT', {
      'accept-version': '1.2',
      host: location.hostname,
      'heart-beat': `${HEARTBEAT_MS},${HEARTBEAT_MS}`,
    }));
  };

  ws.onmessage = (ev) => {
    lastRx = Date.now();
    if (typeof ev.data !== 'string') return;
    buf += ev.data;
    // One WebSocket message is normally one frame, but nothing guarantees it — frames are
    // delimited by NUL, so the buffer is drained by that and never by message boundaries.
    let at;
    while ((at = buf.indexOf(NUL)) >= 0) {
      const frame = parseFrame(buf.slice(0, at));
      buf = buf.slice(at + 1);
      if (frame) onFrame(frame, ws);
    }
    // A heart-beat carries no NUL. Dropping leading EOLs keeps it from accumulating for the
    // lifetime of the connection.
    buf = buf.replace(/^[\r\n]+/, '');
  };

  ws.onerror = () => { try { ws.close(); } catch { /* already gone */ } };
  ws.onclose = () => {
    stopTimers();
    if (socket === ws) { socket = null; retry(); }
  };

  beat = setInterval(() => {
    if (ws.readyState === WebSocket.OPEN) ws.send('\n');
  }, HEARTBEAT_MS);
  watchdog = setInterval(() => {
    // Nothing at all for four beats: the socket believes it is open and the other end is gone.
    // Closing it is what turns a frozen page into a reconnect.
    if (Date.now() - lastRx > SILENCE_LIMIT_MS) { try { ws.close(); } catch { /* already gone */ } }
  }, HEARTBEAT_MS);
}

async function onFrame(frame, ws) {
  if (frame.command === 'CONNECTED') {
    attempts = 0;
    stopFallbackPoll();
    ws.send(encodeFrame('SUBSCRIBE', { id: SUB_STAGE, destination: DEST_STAGE }));
    ws.send(encodeFrame('SUBSCRIBE', { id: SUB_EVENTS, destination: DEST_EVENTS }));
    // SUBSCRIBE first, snapshot second — which is "subscribe from the snapshot's seq" with the
    // gap closed rather than with it left open. Reading the snapshot first would lose whatever
    // was published while that request was in flight, and nothing would ever ask for it again.
    // Anything that arrives early is held here and applied against the snapshot's seq, so it is
    // neither lost nor shown twice.
    try { await loadSnapshot(); } catch { /* the refresh below still paints the page */ }
    holding = false;
    for (const item of held) applyStream(item);
    held = [];
    await refresh();
    return;
  }
  if (frame.command === 'MESSAGE') {
    let payload;
    try { payload = JSON.parse(frame.body); } catch { return; }
    const item = { sub: frame.headers.subscription, dest: frame.headers.destination, payload };
    if (holding) held.push(item); else applyStream(item);
    return;
  }
  // An ERROR frame ends the session at the server; close so the reconnect path runs.
  if (frame.command === 'ERROR') { try { ws.close(); } catch { /* already gone */ } }
}

function applyStream(item) {
  if (item.sub === SUB_STAGE || item.dest === DEST_STAGE) {
    const stage = item.payload || { name: 'idle' };
    setBanner(stage);
    // A progress line moves the banner and nothing else — re-deriving the cards for every line
    // a Maven build prints would be the old poll wearing a new hat. A real stage change is
    // worth a refresh: it is where files, PRs and totals move.
    if (stageMoved(stage)) scheduleRefresh();
    return;
  }
  const e = item.payload;
  if (!e || e.seq == null || e.seq <= streamFloor) return;   // the snapshot already had it
  mergeEvents([e]);
  renderFeed();                 // the line appears at once; the numbers follow
  scheduleRefresh();
}

function retry() {
  attempts += 1;
  // Three failures is not a blip, it is a proxy that will not upgrade or a server without the
  // endpoint. Fall back to the poll this page used to do, so the dashboard keeps working while
  // someone reads the console; the poll stops the moment a session connects.
  if (attempts >= 3) startFallbackPoll();
  setTimeout(connect, Math.min(15000, 1000 * 2 ** Math.min(attempts, 4)));
}

function startFallbackPoll() {
  if (fallbackTimer) return;
  console.warn('dashboard: no websocket session — falling back to polling every 2s');
  fallbackTimer = setInterval(refresh, 2000);
  refresh();
}

function stopFallbackPoll() {
  if (!fallbackTimer) return;
  clearInterval(fallbackTimer);
  fallbackTimer = null;
}

// ── rendering ──────────────────────────────────────────────────────────────

function setBanner(stage) {
  const el = $('stage-banner');
  el.className = 'stage ' + (ACTIVE.includes(stage.name) ? 'active' : stage.name === 'failed' ? 'failed' : stage.name === 'done' ? 'done' : 'idle');
  $('stage-name').textContent = STAGE_LABELS[stage.name] || stage.name;
  $('stage-detail').textContent = stage.detail || '';
  const p = stage.progress;
  $('stage-progress').textContent = p && (Date.now() / 1000 - p.ts) < 60
    ? `${p.line}  ·  ${p.elapsed}s` : '';
}

/** `a/b/Class.java::method` → dimmed package, class, highlighted #method. */
function unitCell(f) {
  const src = f.sourcePath || String(f.path).split('::')[0];
  const method = f.method || (String(f.path).split('::')[1] || '');
  const short = src.replace(/^.*?src\/main\/java\//, '');
  const i = short.lastIndexOf('/');
  const dir = i === -1 ? '' : short.slice(0, i + 1);
  const cls = i === -1 ? short : short.slice(i + 1);
  return `<span class="dir">${esc(dir)}</span><span class="cls">${esc(cls)}</span>`
    + (method ? `<span class="meth"> #${esc(method)}</span>` : '');
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
  const tin = w.tokensIn || 0, tout = w.tokensOut || 0;
  $('c-tokens').textContent = (tin || tout) ? `${fmtTok(tin)} / ${fmtTok(tout)}` : '–';
  $('c-tokens-d').textContent = (tin || tout)
    ? `in / out · ${w.llmCalls || 0} calls` + (w.tokensPerImproved ? ` · ${fmtTok(w.tokensPerImproved)} per improved unit` : '')
    : 'in / out';

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
    <td class="path" title="${esc(f.path)}">${unitCell(f)}</td>
    <td class="num">${fmt(covB)}</td><td class="num">${fmt(mutB)}</td><td class="num">${fmt(macB, '')}</td>
    <td class="num">${cell(f.coverageAfter, f.attemptCoverage)}</td>
    <td class="num">${cell(f.mutationAfter, f.attemptMutation)}</td>
    <td class="num">${cell(f.macAfter, f.attemptMac, '')}</td>
    <td title="${f.timesheet ? esc(`analysis ${f.timesheet.analysisMin}m · writing ${f.timesheet.testsMin}m · mutation ${f.timesheet.mutationMin}m · verify ${f.timesheet.verifyMin}m`) : ''}">${f.timesheet ? fmtHours(f.timesheet.hours) : '–'}</td>
    <td class="num" title="${f.llmCalls || 0} LLM call(s)">${(f.tokensIn || f.tokensOut) ? fmtTok(f.tokensIn) + ' / ' + fmtTok(f.tokensOut) : '–'}</td>
    <td><span class="badge b-${esc(f.status)}">${esc(f.status)}</span></td>
    <td>${f.prUrl ? `<a href="${esc(f.prUrl)}" target="_blank">PR ↗</a>` : (f.prPatch ? 'patch' : '')}</td>
    <td>${f.status === 'candidate' ? '' : `<button class="say" data-file="${esc(f.path)}" title="tell the pipeline what you think of these tests">💬${FB_COUNTS[f.path] ? ` ${FB_COUNTS[f.path]}` : ''}</button>`}</td>
  </tr>`;
  }).join('');

  $('prs').innerHTML = (m.prs || []).map((p) => `<li>
    <b>${esc(p.title)}</b> — <code>${esc(p.branch)}</code>
    ${p.url ? ` · <a href="${esc(p.url)}" target="_blank">${esc(p.url)}</a>` : ` · local patch: <code>${esc(p.patchPath || '')}</code>`}
  </li>`).join('') || '<li class="muted">none yet</li>';

  $('decisions').innerHTML = Object.entries(m.decisions || {}).map(([k, v]) => `
    <details><summary><b>${esc(k)}</b> ${v.rule ? '· rule: “' + esc(v.rule).slice(0, 90) + '”' : '· (no rule set)'}</summary>
    <pre>${esc(JSON.stringify(v.result, null, 2)).slice(0, 2000)}</pre></details>`).join('') || '<span class="muted">none yet</span>';

  renderFeed();
}

// One chronological stream: plain events plus the model exchanges, newest first.
//
// Its own function now, because it is drawn on two different beats — the moment an event frame
// lands, and again with everything else when the derived numbers are re-read. The markup is
// unchanged; only the source of the events is (the merged store rather than the poll's copy).
function renderFeed() {
  const evs = [...eventsBySeq.values()].map((e) => ({ kind: 'ev', ts: e.ts, seq: e.seq, stage: e.stage, msg: e.msg }));
  const dias = dialogCache.map((d, i) => ({ kind: 'llm', ts: d.ts, seq: 1e9 + i, d }));
  const feed = evs.concat(dias).sort((a, b) => (b.ts - a.ts) || (b.seq - a.seq)).slice(0, 120);
  $('llm-count').textContent = dialogCache.length ? `· ${dialogCache.length} model exchanges inline` : '';
  const openKeys = new Set([...$('events').querySelectorAll('details[open]')].map((n) => n.dataset.k));
  $('events').innerHTML = feed.map((it) => {
    const when = new Date(it.ts * 1000).toLocaleTimeString();
    if (it.kind === 'ev') {
      return `<div class="ev"><span class="ts">${when}</span>
        <span class="badge b-stage">${esc(it.stage)}</span> ${esc(it.msg)}</div>`;
    }
    const e = it.d;
    const k = 'llm' + e.ts + (e.detail || '');
    const unit = e.unit ? String(e.unit).replace(/^.*java\//, '') : '';
    return `<details class="ev llm" data-k="${esc(k)}"${openKeys.has(k) ? ' open' : ''}>
      <summary><span class="ts">${when}</span>
        <span class="badge b-llm">model</span>
        <span class="who">${esc(e.detail || e.stage || 'call')}</span>
        <span class="meta">${e.secs}s${e.finish && e.finish !== 'stop' ? ' · ' + esc(e.finish) : ''}${unit ? ' · ' + esc(unit) : ''}</span></summary>
      <div class="body">
        <h4>system</h4><pre>${esc(e.system || '')}</pre>
        <h4>prompt</h4><pre>${esc(e.prompt || '')}</pre>
        <h4>response</h4><pre>${esc(e.response || '')}</pre>
      </div></details>`;
  }).join('');
}

// A first paint over HTTP so the page is never blank while the handshake runs, and then the
// stream. connect() reads the snapshot itself, after its SUBSCRIBE frames are on the wire.
refresh();
connect();


// ── leaving a comment on the tests written for a unit ─────────────────────────
//
// The corpus is only worth having if a judgement can be given from the place a person is
// already looking. Everything else about a record — the prompt rule, the source, the tests,
// the score — is captured automatically; this is the one part that needs a human.
//
// Ported from the JavaScript pipeline's dashboard, deliberately the same interaction: a 💬 per
// row, a dialog, one POST. The only addition is `apply`, which decides whether the sentence
// also goes into the next round's prompt or is only kept for the optimiser to reflect on.

let FB_COUNTS = {};

// Everything a person has said about this repo, and what it is doing to the prompts.
// Kept in one poll because the three consumers — the badge, the guidance list and the
// dialog's history — all read the same document.
let FB_BY_TARGET = {};
let FB_RECORD = {};

async function pollFeedbackCounts() {
  try {
    const r = await fetch('api/feedback', { cache: 'no-store' });
    if (!r.ok) return;
    const d = await r.json();
    FB_COUNTS = d.counts || {};
    renderGuidance(d.guidance || []);
    // per-unit comments, so the dialog can show what has already been said rather than
    // asking someone to repeat themselves
    FB_BY_TARGET = {};
    FB_RECORD = {};
    const seenFb = new Set();
    for (const rec of (d.records || [])) {
      for (const fb of (rec.feedback || [])) {
        // ONE comment is one comment. The join attaches a unit-targeted comment to EVERY record
        // for that unit, so a unit with two attempts showed the same sentence twice — which
        // reads as two people saying it, and makes a single complaint look like a chorus.
        const k = fb.target || rec.unit;
        const id = (fb.id || fb.ts) + '|' + k;
        if (seenFb.has(id)) continue;
        seenFb.add(id);
        (FB_BY_TARGET[k] = FB_BY_TARGET[k] || []).push(fb);
      }
      // newest wins: a unit is retried, and the last thing written for it is the one in the PR
      const prev = FB_RECORD[rec.unit];
      if (!prev || (rec.ts || 0) >= (prev.ts || 0)) FB_RECORD[rec.unit] = rec;
    }
  } catch (err) { /* the badge is not worth an error banner */ }
}

// THE READ PATH. Guidance that nothing displays is a write-only feature: these four sentences
// are in the prompt for the next test the pipeline writes, and until now the only way to see
// them was to curl the API.
function renderGuidance(items) {
  const sec = document.getElementById('guidance-section');
  const list = document.getElementById('guidance');
  if (!sec || !list) return;
  sec.hidden = items.length === 0;
  document.getElementById('guidance-count').textContent =
    items.length ? `${items.length} in every prompt` : '';
  list.innerHTML = items.map((g) => `<li>${esc(g)}</li>`).join('');
}
pollFeedbackCounts();
setInterval(pollFeedbackCounts, 30000);

let fbFile = null;
document.addEventListener('click', (e) => {
  const b = e.target.closest('button.say');
  if (!b) return;
  fbFile = b.dataset.file;
  document.getElementById('fb-title').textContent = 'Feedback on tests for ' + fbFile;
  document.getElementById('fb-text').value = '';
  document.getElementById('fb-status').textContent = '';
  document.getElementById('fb-apply').checked = false;
  // THE THING BEING JUDGED. Asking someone to comment on a test without showing them the test
  // is asking them to remember it; the record has been in the API all along and nothing read it.
  const pane = document.getElementById('fb-record');
  const rec = FB_RECORD[fbFile];
  if (!rec) {
    pane.hidden = false;
    pane.innerHTML = '<p class="none">No generated test recorded for this unit yet.</p>';
  } else {
    pane.hidden = false;
    const o = rec.outcome || {};
    const before = (o.before || {}).mac, after = (o.after || {}).mac;
    const score = o.verdict
      ? `${esc(o.verdict)}${before != null ? ` · MAC ${esc(before)} → ${esc(after)}` : ''}`
      : 'not yet measured';
    const src = (rec.code || {}).methodSource || '(source not captured)';
    const tests = (rec.tests || []).map((t) => `<h4>${esc((t.path || '').split('/').pop())}</h4>`
      + `<pre>${esc(t.content || '')}</pre>`).join('');
    pane.innerHTML = `<div class="score">${score}</div>`
      + `<h4>method under test</h4><pre>${esc(src)}</pre>` + tests;
  }
  // what has already been said about this unit
  const box = document.getElementById('fb-existing');
  const said = FB_BY_TARGET[fbFile] || [];
  box.hidden = said.length === 0;
  box.innerHTML = said.map((f) => {
    const who = f.author ? esc(f.author) : 'someone';
    const when = f.ts ? new Date(f.ts * 1000).toISOString().slice(0, 10) : '';
    return `<div>${esc(f.text)}<span class="who"> — ${who} ${when}</span></div>`;
  }).join('');
  document.getElementById('fb').showModal();
  document.getElementById('fb-text').focus();
});

document.getElementById('fb-form').addEventListener('submit', async (e) => {
  const save = e.submitter && e.submitter.value === 'save';
  if (!save || !fbFile) return;
  const text = document.getElementById('fb-text').value.trim();
  const author = document.getElementById('fb-author').value.trim() || null;
  const apply = document.getElementById('fb-apply').checked;
  if (!text) return;
  const status = document.getElementById('fb-status');
  try {
    const r = await fetch('api/feedback', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ file: fbFile, text, author, apply }),
    });
    const d = await r.json();
    if (!d.ok) { status.textContent = d.error || 'could not save'; return; }
    // "attached: 0" is a real answer, not a failure: nothing has been generated for that unit
    // yet, so there is no record for the judgement to hang on.
    status.textContent = d.attached
      ? `saved (${d.attached} record${d.attached > 1 ? 's' : ''})`
      : 'saved — nothing generated for it yet to attach to';
    pollFeedbackCounts();
  } catch (err) { status.textContent = 'could not save: ' + err.message; }
});
