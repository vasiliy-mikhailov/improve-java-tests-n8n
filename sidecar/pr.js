'use strict';
// Commit, push and PR creation. Two modes:
//   github — push branch + `gh pr create` (repos the team owns)
//   local  — record branch + patch + PR payload under /data/prs (third-party repos)
const fs = require('node:fs');
const path = require('node:path');
const { run } = require('./exec');
const { state, event, DATA_DIR } = require('./state');
const { repoDir } = require('./repo');

async function changedFiles() {
  const dir = repoDir();
  // -z + --porcelain: NUL-separated, unquoted paths (survives spaces); renames
  // emit "R  new\0old\0" — we keep the new path and skip the old one.
  const r = await run(['git', 'status', '--porcelain', '-z'], { cwd: dir, timeoutMs: 30000 });
  const parts = r.stdout.split('\0');
  const out = [];
  for (let i = 0; i < parts.length; i++) {
    const rec = parts[i];
    if (!rec) continue;
    const xy = rec.slice(0, 2);
    out.push(rec.slice(3));
    if (xy[0] === 'R' || xy[0] === 'C') i += 1; // consume the source path
  }
  return out.filter(Boolean);
}

/** Test files this branch touched — committed rounds AND uncommitted edits. */
async function changedTestFiles() {
  const dir = repoDir();
  const base = state.run.config.prBase;
  const committed = await run(['git', 'diff', '--name-only', `${base}...HEAD`], { cwd: dir, timeoutMs: 30000 });
  const all = new Set([
    ...committed.stdout.split('\n').map((s) => s.trim()),
    ...(await changedFiles()),
  ].filter(Boolean));
  return [...all].filter(isCommittableTest);
}

async function diffAgainstBase() {
  const dir = repoDir();
  const base = state.run.config.prBase;
  // intent-to-add new test files so they show up in the diff
  const newTests = (await changedFiles()).filter(isCommittableTest);
  if (newTests.length) await run(['git', 'add', '-N', '--', ...newTests], { cwd: dir, timeoutMs: 30000 });
  const r = await run(['git', 'diff', base, '--', ':!target', ':!build', ':!.gradle', ':!.ijt-*', ':!pom.xml', ':!**/pom.xml'], { cwd: dir, timeoutMs: 60000 });
  return r.stdout;
}

// Only Java test sources are ever committed: never the PIT/JaCoCo wiring we inject into
// pom.xml or the init scripts, and never build output.
const TEST_PATH_RE = /(^|\/)src\/test\/java\/.+\.java$/;
const ARTIFACT_RE = /(^|\/)(target|build|\.gradle|\.ijt-)/;
const isCommittableTest = (p) => TEST_PATH_RE.test(p) && !ARTIFACT_RE.test(p);

async function commit(message) {
  const dir = repoDir();
  // Commit ONLY test sources — never the injected PIT plugin block in pom.xml, the
  // .ijt-* init scripts or target/build output; committing those poisons the base branch.
  const changed = await changedFiles();
  const testish = changed.filter(isCommittableTest);
  if (!testish.length) {
    // rounds may already be committed on this branch — that's fine for PR creation
    const ahead = await run(['git', 'rev-list', '--count', `${state.run.config.prBase}..HEAD`], { cwd: dir, timeoutMs: 30000 });
    if (parseInt(ahead.stdout.trim(), 10) > 0) {
      const sha = (await run(['git', 'rev-parse', 'HEAD'], { cwd: dir, timeoutMs: 10000 })).stdout.trim();
      return { sha };
    }
    throw new Error('no changed test files to commit');
  }
  await run(['git', 'add', '--', ...testish], { cwd: dir, timeoutMs: 30000 });
  // --no-verify: repo pre-commit hooks (husky lint etc.) must not abort the
  // pipeline mid-run — the PR's own CI still judges the final result
  const r = await run(['git', 'commit', '--no-verify', '-m', message], { cwd: dir, timeoutMs: 60000 });
  if (r.code !== 0 && !/nothing to commit/.test(r.stdout + r.stderr)) {
    throw new Error('git commit failed: ' + (r.stderr || r.stdout).slice(-300));
  }
  const sha = (await run(['git', 'rev-parse', 'HEAD'], { cwd: dir, timeoutMs: 10000 })).stdout.trim();
  return { sha };
}

async function createPr({ file, branch, title, body, labels = [] }) {
  const dir = repoDir();
  const cfg = state.run.config;
  const record = {
    file, branch, title, body, labels,
    mode: cfg.prMode, createdAt: Date.now(), url: null, patchPath: null,
  };
  if (cfg.prMode === 'github' && !cfg.dryRun) {
    let r = await run(['git', 'push', '--force', '--set-upstream', 'origin', branch, '--no-verify'],
      { cwd: dir, timeoutMs: 120000, label: 'git push' });
    if (r.code !== 0) throw new Error('git push failed: ' + (r.stderr || r.stdout).slice(-400));
    const args = ['gh', 'pr', 'create', '--base', cfg.prBase, '--head', branch, '--title', title, '--body', body];
    r = await run(args, { cwd: dir, timeoutMs: 60000, label: 'gh pr create' });
    let url = (r.stdout + r.stderr).match(/https:\/\/github\.com\/[^\s"]+\/pull\/\d+/)?.[0] || null;
    if (!url && /already exists/i.test(r.stdout + r.stderr)) {
      const v = await run(['gh', 'pr', 'view', branch, '--json', 'url', '-q', '.url'], { cwd: dir, timeoutMs: 30000 });
      url = v.stdout.trim() || null;
      // keep PR fresh
      await run(['gh', 'pr', 'edit', branch, '--title', title, '--body', body], { cwd: dir, timeoutMs: 30000 });
    }
    if (!url) throw new Error('gh pr create failed: ' + (r.stderr || r.stdout).slice(-400));
    for (const label of labels) {
      await run(['gh', 'pr', 'edit', branch, '--add-label', label], { cwd: dir, timeoutMs: 30000 });
    }
    record.url = url;
  } else {
    // local mode: keep the branch, save patch + PR payload as the deliverable
    const prDir = path.join(DATA_DIR, 'prs');
    fs.mkdirSync(prDir, { recursive: true });
    const slug = branch.replace(/[^a-zA-Z0-9._-]+/g, '-');
    const patch = await diffAgainstBase();
    const patchPath = path.join(prDir, slug + '.patch');
    fs.writeFileSync(patchPath, patch);
    fs.writeFileSync(path.join(prDir, slug + '.json'), JSON.stringify(record, null, 2));
    record.patchPath = patchPath;
  }
  state.prs.push(record);
  event('preparing_pr', record.url ? 'PR created: ' + record.url : 'PR prepared locally: ' + record.patchPath);
  return record;
}

module.exports = { commit, createPr, changedFiles, changedTestFiles, diffAgainstBase };
