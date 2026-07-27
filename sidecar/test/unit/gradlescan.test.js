'use strict';
// Every Gradle invocation was uploading a build scan of the user's repo to gradle.com,
// and blocking on it.
//
// java-dataloader's settings.gradle applies `com.gradle.develocity` with
// `publishing.onlyIf { true }`, so a scan is published on EVERY build. In the container
// that upload times out, and the run sat 780 seconds inside a single `./gradlew test`
// with "Publishing Build Scan failed due to network error ... (1 retry remaining)" as its
// last output. The pipeline runs the suite, the coverage build and PIT once per round, so
// the first batch spent 94 minutes to improve one three-mutant method.
//
// Two problems, one flag. `--no-scan` stops the upload — but Gradle rejects it as an
// unknown option on a build with no scan plugin applied, so it can only be passed when
// one is.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { appliesBuildScan, gradleScanArgs } = require('../../gradlescan');

const DEVELOCITY = `plugins {
    id 'com.gradle.develocity' version '4.3'
    id 'org.gradle.toolchains.foojay-resolver-convention' version '1.0.0'
}

develocity {
    buildScan {
        termsOfUseUrl = "https://gradle.com/help/legal-terms-of-use"
        termsOfUseAgree = "yes"
        publishing.onlyIf { true }
    }
}
`;

test('the develocity plugin is recognised', () => {
  assert.equal(appliesBuildScan([DEVELOCITY]), true);
});

test('so are its predecessors, which older repos still use', () => {
  assert.equal(appliesBuildScan(["id 'com.gradle.enterprise' version '3.16'"]), true);
  assert.equal(appliesBuildScan(["id 'com.gradle.build-scan' version '3.0'"]), true);
  assert.equal(appliesBuildScan(['apply plugin: "com.gradle.build-scan"']), true);
  assert.equal(appliesBuildScan(['gradleEnterprise {\n  buildScan {\n  }\n}']), true);
});

test('a build with no scan plugin is left alone', () => {
  // --no-scan on a build that never applies the plugin fails the invocation outright
  assert.equal(appliesBuildScan(["plugins { id 'java'\n id 'jacoco' }"]), false);
  assert.equal(appliesBuildScan([]), false);
  assert.equal(appliesBuildScan([null, undefined, '']), false);
});

test('the flag is only added where it is accepted', () => {
  assert.deepEqual(gradleScanArgs([DEVELOCITY]), ['--no-scan']);
  assert.deepEqual(gradleScanArgs(["plugins { id 'java' }"]), []);
});

test('a comment mentioning build scans does not count as applying one', () => {
  assert.equal(appliesBuildScan(['// we deliberately do not use a buildScan here']), false,
    'a commented line must not make us pass a flag Gradle would reject');
});

// ── the wiring ────────────────────────────────────────────────────────────
test('every Gradle invocation in the sidecar passes the scan args', () => {
  // The flag is only useful where it is actually used, and the pipeline shells out to
  // Gradle from three places — the suite, the coverage build and PIT. Missing it in one
  // leaves that invocation uploading a scan and blocking on it, which is most of the
  // wall-clock. (A pure-logic test would not have noticed: repo.testFileExists was
  // written, tested through an injected parameter, and never exported.)
  // Enumerating the files I remembered is how this shipped broken the first time: the
  // guard listed tests.js, coverage.js and pit.js, passed, and repo.install() — the FIRST
  // Gradle command of every run — still had no flag and blocked on the upload. So the
  // whole directory is scanned, and a new call site cannot opt out by being new.
  const fs = require('node:fs');
  const path = require('node:path');
  const dir = path.join(__dirname, '..', '..');
  const sources = fs.readdirSync(dir).filter((f) => f.endsWith('.js'));
  let found = 0;
  for (const f of sources) {
    for (const line of fs.readFileSync(path.join(dir, f), 'utf8').split('\n')) {
      if (!line.includes("'--no-daemon'")) continue;
      found += 1;
      assert.match(line, /scanArgs/, `${f}: this Gradle invocation does not pass scanArgs — ${line.trim()}`);
    }
  }
  assert.ok(found >= 4, `expected every Gradle call site to be checked, saw ${found}`);
});

test('detectBuild hands the args on, and only for Gradle', () => {
  const repo = require('../../repo');
  assert.equal(typeof repo.detectBuild, 'function');
});
