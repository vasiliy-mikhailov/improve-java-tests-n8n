'use strict';
// Run the project's test suite (optionally scoped to one test class). No coverage — this
// is the fast green/red gate that guards every round, and PIT refuses to run on red tests.
const { run } = require('./exec');
const { state, event } = require('./state');
const repo = require('./repo');

/**
 * @param scope  repo-relative test file path, a test class name, or null for everything
 */
async function runTests(scope) {
  const dir = repo.repoDir();
  const build = state.runner;
  if (!build?.tool) throw new Error('build not detected');
  const cls = scope ? classNameOf(scope) : null;
  let argv;
  if (build.tool === 'maven') {
    argv = [build.wrapper, '-B', '-ntp', 'test', '-DfailIfNoTests=false'];
    if (cls) argv.push(`-Dtest=${cls}`, '-DfailIfNoTests=false', '-Dsurefire.failIfNoSpecifiedTests=false');
  } else {
    argv = [build.wrapper, '--no-daemon', 'test'];
    if (cls) argv.push('--tests', cls);
  }
  const r = await run(argv, { cwd: dir, timeoutMs: 3600000, label: 'tests', env: repo.buildEnv() });
  const out = r.stdout + '\n' + r.stderr;
  const passed = r.code === 0;
  event('tests', `${scope || 'full suite'}: ${passed ? 'green' : 'RED (exit ' + r.code + ')'}`);
  return { passed, exitCode: r.code, summary: failureSummary(out, passed) };
}

/** Simple class name (or FQCN) from a test file path — what Maven/Gradle filters take. */
function classNameOf(scope) {
  if (!scope.endsWith('.java')) return scope;
  const m = scope.match(/src\/test\/java\/(.+)\.java$/);
  return m ? m[1].split('/').join('.') : scope.split('/').pop().replace(/\.java$/, '');
}

/**
 * The part of a Java build log an LLM can act on: compiler errors and test failures,
 * not the thousand lines of dependency resolution around them.
 */
function failureSummary(out, passed) {
  if (passed) {
    const line = out.split('\n').reverse().find((l) => /Tests run:/.test(l));
    return (line || 'build succeeded').trim().slice(0, 500);
  }
  const lines = out.split('\n');
  const keep = [];
  for (let i = 0; i < lines.length; i++) {
    const l = lines[i];
    if (/^\[ERROR\]|COMPILATION ERROR|error: |Tests run:.*(Failures|Errors): [1-9]|expected:|but was:|^\s+at [\w.$]+\(.*\.java:\d+\)|FAILED|Caused by:/.test(l)) {
      keep.push(l.replace(/^\[ERROR\]\s*/, '').trim());
    }
    if (keep.length > 60) break;
  }
  const text = (keep.length ? keep : lines.slice(-40)).join('\n');
  return text.slice(-4000);
}

module.exports = { runTests, classNameOf };
