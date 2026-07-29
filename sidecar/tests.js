'use strict';
// Run the project's test suite (optionally scoped to one test class). No coverage — this
// is the fast green/red gate that guards every round, and PIT refuses to run on red tests.
const { run } = require('./exec');
const { state, event } = require('./state');
const repo = require('./repo');
// Shared with the workflow generator: the n8n node calling this route derives its own
// timeout from this number, and must outlive it. See sidecar/timeouts.js.
const { SUITE_RUN_MS } = require('./timeouts');

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
    argv = [build.wrapper, '--no-daemon', ...(build.scanArgs || []), 'test'];
    if (cls) argv.push('--tests', cls);
  }
  const r = await run(argv, { cwd: dir, timeoutMs: SUITE_RUN_MS, label: 'tests', env: repo.buildEnv() });
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

/**
 * Did the suite pass, judged from the build log alone?
 *
 * verify used to run the full suite, then run it AGAIN under the JaCoCo agent, then let
 * PIT run it a third time — three executions of 1163 tests per round on JSON-java, two of
 * them saying the same thing. The coverage run passes
 * `-Dmaven.test.failure.ignore=true` so its exit code is always 0; the reported counts are
 * the only honest signal in it.
 *
 * `passed` is deliberately three-valued. NULL means "this log does not say", and the
 * caller must then run the suite properly — reading an unclear log as green is exactly the
 * kind of unmeasured claim this project keeps paying for.
 *
 * @returns {{passed: boolean|null, tests: number|null, failures: number|null, errors: number|null, summary: string}}
 */
function suiteOutcome(log) {
  const out = String(log || '');
  if (!out.trim()) return { passed: null, tests: null, failures: null, errors: null, summary: '' };

  // Maven: the LAST "Tests run:" line is the aggregate across modules
  const totals = [...out.matchAll(/Tests run:\s*(\d+),\s*Failures:\s*(\d+),\s*Errors:\s*(\d+)/g)];
  if (totals.length) {
    const [, t, f, e] = totals[totals.length - 1].map(Number.isFinite ? String : String);
    const tests = parseInt(t, 10), failures = parseInt(f, 10), errors = parseInt(e, 10);
    // nothing ran → nothing was proven, whatever the build says
    if (!tests) return { passed: null, tests, failures, errors, summary: failureSummary(out, false) };
    const passed = failures === 0 && errors === 0 && !/COMPILATION ERROR/.test(out);
    return { passed, tests, failures, errors, summary: failureSummary(out, passed) };
  }
  if (/COMPILATION ERROR|cannot find symbol/.test(out)) {
    return { passed: false, tests: null, failures: null, errors: null, summary: failureSummary(out, false) };
  }
  // Gradle prints no counts on success
  if (/^BUILD SUCCESSFUL/m.test(out)) return { passed: true, tests: null, failures: null, errors: null, summary: '' };
  if (/^FAILURE: Build failed|Task :test FAILED/m.test(out)) {
    return { passed: false, tests: null, failures: null, errors: null, summary: failureSummary(out, false) };
  }
  return { passed: null, tests: null, failures: null, errors: null, summary: '' };
}

module.exports = {
  suiteOutcome, runTests, classNameOf };
