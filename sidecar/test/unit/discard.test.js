'use strict';
// Exercises the real git commands against a throwaway repository: the defect this pins
// is entirely in how git behaves, so mocking it would pin the wrong thing.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

const DATA = fs.mkdtempSync(path.join(os.tmpdir(), 'ijt-data-'));
process.env.DATA_DIR = DATA;
const { state } = require('../../state');
const repo = require('../../repo');
const pr = require('../../pr');

const REPO_URL = 'file:///tmp/fixture-repo';
const git = (dir, ...args) => execFileSync('git', args, { cwd: dir, encoding: 'utf8' });

function fixture() {
  state.run = { id: 'run-test', config: { repoUrl: REPO_URL, repoBranch: 'master', prBase: 'master' } };
  const dir = repo.repoDir();
  fs.rmSync(dir, { recursive: true, force: true });
  fs.mkdirSync(path.join(dir, 'src/test/java/a'), { recursive: true });
  git(dir, 'init', '-q', '-b', 'master');
  git(dir, 'config', 'user.email', 't@example.com');
  git(dir, 'config', 'user.name', 'test');
  fs.writeFileSync(path.join(dir, 'pom.xml'), '<project/>\n');
  git(dir, 'add', '-A');
  git(dir, 'commit', '-q', '-m', 'base');
  return dir;
}

const TEST_REL = 'src/test/java/a/BMacMutTest.java';
const TEST_SRC = 'package a;\nimport org.junit.Test;\npublic class BMacMutTest {\n  @Test public void t() { assertEquals(1, 1); }\n}\n';

test("a missed round leaves no trace of its test — not even an empty file", async () => {
  // verify() calls diffAgainstBase(), which runs `git add -N` on the new test so it shows
  // up in the diff. A missed round then ran `git checkout -- .`, which MATERIALISES that
  // intent-to-add entry as an empty blob, and `git clean -fd` skipped it because it was
  // by then tracked. A zero-byte JSONArrayMacMutR2Test.java reached a prepared PR.
  const dir = fixture();
  fs.writeFileSync(path.join(dir, TEST_REL), TEST_SRC);
  await pr.diffAgainstBase();                     // stages intent-to-add
  await repo.discardUncommitted();                // the round missed
  assert.equal(fs.existsSync(path.join(dir, TEST_REL)), false, 'the file must be gone, not emptied');
  assert.equal(git(dir, 'status', '--porcelain').trim(), '', 'and the tree must be clean');
});

test('an accepted round already committed survives a later discard', async () => {
  const dir = fixture();
  fs.writeFileSync(path.join(dir, TEST_REL), TEST_SRC);
  git(dir, 'add', '--', TEST_REL);
  git(dir, 'commit', '-q', '-m', 'kept round');
  fs.writeFileSync(path.join(dir, 'src/test/java/a/Other.java'), TEST_SRC);
  await pr.diffAgainstBase();
  await repo.discardUncommitted();
  assert.equal(fs.readFileSync(path.join(dir, TEST_REL), 'utf8'), TEST_SRC, 'committed work is untouched');
  assert.equal(fs.existsSync(path.join(dir, 'src/test/java/a/Other.java')), false);
});

test('a modified tracked file is restored, not truncated', async () => {
  const dir = fixture();
  fs.writeFileSync(path.join(dir, TEST_REL), TEST_SRC);
  git(dir, 'add', '--', TEST_REL);
  git(dir, 'commit', '-q', '-m', 'round 1');
  fs.writeFileSync(path.join(dir, TEST_REL), TEST_SRC + '// round 2 scribble\n');
  await repo.discardUncommitted();
  assert.equal(fs.readFileSync(path.join(dir, TEST_REL), 'utf8'), TEST_SRC);
});
