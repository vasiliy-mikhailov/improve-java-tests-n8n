// Scoring per RESEARCH.md — run inside the container or anywhere with results/:
//   node score.mjs [resultsDir] [dodFile]
// reward = DoD_score × implementation_performance
import { readFileSync, readdirSync, existsSync } from 'node:fs';

const dir = process.argv[2] || '/data/eval/results';
const dodFile = process.argv[3] || '/data/eval/dod.json';

const clamp = (x, lo, hi) => Math.max(lo, Math.min(hi, x));
const results = readdirSync(dir).filter((f) => f.endsWith('.json'))
  .map((f) => JSON.parse(readFileSync(`${dir}/${f}`, 'utf8')));

const rows = [];
for (const r of results) {
  const targeted = r.targetedFiles || [];
  const improvedFiles = targeted.filter((f) => f.status === 'improved');
  const prsOk = improvedFiles.every((f) => f.prUrl || f.prPatch);
  let completion = 0;
  if (r.finishedStatus === 'done' && prsOk) completion = 1;
  else if (r.baseline?.coveragePct != null) completion = 0.5;

  const gaps = targeted
    .filter((f) => f.macBefore != null && f.macBefore < 100)
    .map((f) => clamp(((f.macAfter ?? f.macBefore) - f.macBefore) / (100 - f.macBefore), 0, 1));
  const improvement = gaps.length ? gaps.reduce((s, x) => s + x, 0) / gaps.length : 0;
  const score = 0.4 * completion + 0.6 * improvement;
  rows.push({ name: r.name, completion, improvement: +improvement.toFixed(3), score: +score.toFixed(3), targeted: targeted.length, prs: (r.prs || []).length, status: r.finishedStatus, durationSec: r.durationSec });
}

rows.sort((a, b) => a.name.localeCompare(b.name));
const perf = rows.length ? rows.reduce((s, r) => s + r.score, 0) / rows.length : 0;

let dodScore = null, reward = null, dod = null;
if (existsSync(dodFile)) {
  dod = JSON.parse(readFileSync(dodFile, 'utf8'));
  const vals = Object.values(dod.items || {});
  dodScore = vals.length ? vals.reduce((s, x) => s + x, 0) / vals.length : 0;
  reward = dodScore * perf;
}

console.log(JSON.stringify({
  repos: rows,
  implementation_performance: +perf.toFixed(4),
  dod_score: dodScore != null ? +dodScore.toFixed(4) : 'provide dod.json',
  reward: reward != null ? +reward.toFixed(4) : null,
}, null, 2));
