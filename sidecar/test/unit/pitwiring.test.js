'use strict';
// Getting PIT into somebody else's build, and finding the report it leaves behind.
//
// This half of pit.js was the least-tested and the most superstitious: every rule in it
// exists because a real repo failed in a way that looked like something else. A wrong
// junit-platform-launcher makes PIT report "no tests" rather than a version conflict. A
// plugin injected into a <profile> is silently ignored, so the run looks like PIT simply
// found nothing to mutate. Reading another module's mutations.xml yields numbers that are
// entirely plausible and describe a different class.
//
// Nothing here is mocked. ensureMavenWiring edits a real pom on disk and the assertions
// read that file back, because the defect being pinned is what ends up in the file.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const DATA = fs.mkdtempSync(path.join(os.tmpdir(), 'ijt-pitwiring-'));
process.env.DATA_DIR = DATA;
const { state } = require('../../state');
const repo = require('../../repo');
const pit = require('../../pit');

// ── junit-platform version matching ───────────────────────────────────────
// Jupiter 5.x rides platform 1.x; JUnit 6 unified them. Get this wrong and PIT's bundled
// launcher mismatches the project's engine and finds no tests at all — which reads as
// "your class has no coverage", not as a dependency problem.
test('jupiter 5.x maps onto the matching platform 1.x', () => {
  assert.equal(pit.platformVersionFor('5.11.3'), '1.11.3');
  assert.equal(pit.platformVersionFor('5.10'), '1.10');
});

test('junit 6 unified the versions, so it maps to itself', () => {
  assert.equal(pit.platformVersionFor('6.1.0'), '6.1.0');
  assert.equal(pit.platformVersionFor('7.0.1'), '7.0.1');
});

test('junit 4 and unparseable versions have no platform at all', () => {
  // Returning a wrong-but-plausible version here would be worse than returning nothing:
  // pitPluginXml omits the launcher dependency entirely when this is null.
  assert.equal(pit.platformVersionFor('4.13.2'), null);
  assert.equal(pit.platformVersionFor('not-a-version'), null);
  assert.equal(pit.platformVersionFor(''), null);
  assert.equal(pit.platformVersionFor(null), null);
  assert.equal(pit.platformVersionFor(undefined), null);
});

// ── the plugin block ──────────────────────────────────────────────────────
test('junit5 gets both the engine bridge and a pinned platform launcher', () => {
  const xml = pit.pitPluginXml('junit5', '5.11.3');
  assert.match(xml, /pitest-junit5-plugin/);
  assert.match(xml, /junit-platform-launcher/);
  assert.match(xml, /<version>1\.11\.3<\/version>/);
});

test('junit5 with an unreadable version still gets the bridge, but no launcher', () => {
  // A launcher pinned to a version we guessed is worse than none: PIT's own default at
  // least matches something.
  const xml = pit.pitPluginXml('junit5', 'wat');
  assert.match(xml, /pitest-junit5-plugin/);
  assert.doesNotMatch(xml, /junit-platform-launcher/);
});

test('testng gets its own bridge and nothing from the junit family', () => {
  const xml = pit.pitPluginXml('testng', null);
  assert.match(xml, /pitest-testng-plugin/);
  assert.doesNotMatch(xml, /junit/);
});

test('junit4 needs no bridge, so no dependencies block is emitted at all', () => {
  // An empty <dependencies/> is not equivalent — it is a valid pom that says something
  // different, and a mutant that turns the guard into "always emit" must not survive.
  const xml = pit.pitPluginXml('junit4', '4.13.2');
  assert.doesNotMatch(xml, /<dependencies>/);
  assert.match(xml, /pitest-maven/);
});

// ── injecting into a real pom ─────────────────────────────────────────────
function fixture(pomXml, runner = { testFramework: 'junit5', testFrameworkVersion: '5.11.3' }) {
  state.run = { id: 'run-pitwiring', config: { repoUrl: 'file:///tmp/x', repoBranch: 'main' } };
  state.runner = runner;
  const dir = repo.repoDir();
  fs.rmSync(dir, { recursive: true, force: true });
  fs.mkdirSync(dir, { recursive: true });
  if (pomXml !== null) fs.writeFileSync(path.join(dir, 'pom.xml'), pomXml);
  return dir;
}
const pomOf = (dir) => fs.readFileSync(path.join(dir, 'pom.xml'), 'utf8');

const PLAIN = `<project>
  <artifactId>a</artifactId>
  <build>
    <plugins>
      <plugin><artifactId>maven-surefire-plugin</artifactId></plugin>
    </plugins>
  </build>
</project>
`;

test('a module with no pom is reported, not guessed at', () => {
  const dir = fixture(null);
  assert.equal(pit.ensureMavenWiring('.'), 'no-pom');
  assert.equal(fs.existsSync(path.join(dir, 'pom.xml')), false, 'must not create one');
});

test('PIT is injected into the existing top-level <plugins>', () => {
  const dir = fixture(PLAIN);
  assert.equal(pit.ensureMavenWiring('.'), 'injected');
  const pom = pomOf(dir);
  assert.match(pom, /pitest-maven/);
  // and it landed inside build/plugins, not appended after </project>
  assert.ok(pom.indexOf('pitest-maven') < pom.indexOf('</plugins>'), pom);
});

test('a <build> without <plugins> gets one created', () => {
  const dir = fixture(`<project><build></build></project>\n`);
  assert.equal(pit.ensureMavenWiring('.'), 'injected');
  assert.match(pomOf(dir), /<plugins>[\s\S]*pitest-maven[\s\S]*<\/plugins>/);
});

test('a pom with no <build> at all gets one before </project>', () => {
  const dir = fixture(`<project>\n  <artifactId>a</artifactId>\n</project>\n`);
  assert.equal(pit.ensureMavenWiring('.'), 'injected');
  const pom = pomOf(dir);
  assert.match(pom, /<build>[\s\S]*pitest-maven[\s\S]*<\/build>/);
  assert.match(pom, /<\/project>\s*$/);
});

test('a <build> that lives inside <profiles> is NOT where PIT goes', () => {
  // This is the whole reason buildIsTopLevel exists. A plugin declared inside a profile
  // that is not activated is silently ignored: the run completes, PIT never executes, and
  // the failure surfaces as "no mutations found" — which reads like a property of the
  // class under test rather than a wiring mistake.
  const dir = fixture(`<project>
  <profiles>
    <profile>
      <id>release</id>
      <build><plugins><plugin><artifactId>other</artifactId></plugin></plugins></build>
    </profile>
  </profiles>
</project>
`);
  assert.equal(pit.ensureMavenWiring('.'), 'injected');
  const pom = pomOf(dir);
  const inProfile = pom.indexOf('pitest-maven') > pom.indexOf('<profiles>')
    && pom.indexOf('pitest-maven') < pom.indexOf('</profiles>');
  assert.equal(inProfile, false, `PIT was injected into the profile:\n${pom}`);
});

test("a project that already wires PIT properly is left alone", () => {
  const dir = fixture(`<project><build><plugins>
    <plugin><artifactId>pitest-maven</artifactId>
      <dependencies><dependency><artifactId>pitest-junit5-plugin</artifactId></dependency></dependencies>
    </plugin>
  </plugins></build></project>
`);
  const before = pomOf(dir);
  assert.equal(pit.ensureMavenWiring('.'), 'present');
  assert.equal(pomOf(dir), before, 'the pom must be byte-identical — we defer to the project');
});

test('a project declaring PIT without the engine bridge gets just the bridge', () => {
  const dir = fixture(`<project><build><plugins>
    <plugin><artifactId>pitest-maven</artifactId><version>1.20.0</version></plugin>
  </plugins></build></project>
`);
  assert.equal(pit.ensureMavenWiring('.'), 'patched');
  const pom = pomOf(dir);
  assert.match(pom, /pitest-junit5-plugin/);
  // their version is theirs; we add a dependency, we do not take the plugin over
  assert.match(pom, /<version>1\.20\.0<\/version>/);
});

test('a testng project declaring PIT gets the testng bridge, not the junit5 one', () => {
  const dir = fixture(`<project><build><plugins>
    <plugin><artifactId>pitest-maven</artifactId></plugin>
  </plugins></build></project>
`, { testFramework: 'testng' });
  assert.equal(pit.ensureMavenWiring('.'), 'patched');
  assert.match(pomOf(dir), /pitest-testng-plugin/);
});

test('wiring targets the module pom, not the aggregator at the root', () => {
  const dir = fixture(`<project><artifactId>root</artifactId></project>\n`);
  fs.mkdirSync(path.join(dir, 'mod'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'mod', 'pom.xml'), PLAIN);
  assert.equal(pit.ensureMavenWiring('mod'), 'injected');
  assert.doesNotMatch(pomOf(dir), /pitest-maven/, 'the root pom must be untouched');
  assert.match(fs.readFileSync(path.join(dir, 'mod', 'pom.xml'), 'utf8'), /pitest-maven/);
});

// ── where PIT is pointed ──────────────────────────────────────────────────
// Every case here failed silently in production: not as an error, but as "this method has
// no mutation surface", which the pipeline recorded as a finished unit.
test('targetTests carries a leading wildcard, because tests live in sibling packages', () => {
  // org.json.junit.XMLTest tests org.json.XML. A package-anchored glob matched none of
  // them and PIT reported the class as having no coverage.
  const s = pit.pitScope({ fqcn: 'org.json.XML', onlyMethod: null });
  assert.equal(s.targetTests, '*XML*Test*');
  assert.ok(s.targetTests.startsWith('*'), 'a leading wildcard is the entire point');
});

test('targetClasses carries a trailing wildcard, so inner classes come along', () => {
  const s = pit.pitScope({ fqcn: 'org.json.XML', onlyMethod: null });
  assert.equal(s.targetClasses, 'org.json.XML*');
});

test('a class in the default package still yields a usable glob', () => {
  const s = pit.pitScope({ fqcn: 'Widget', onlyMethod: null });
  assert.equal(s.targetTests, '*Widget*Test*');
  assert.equal(s.targetClasses, 'Widget*');
});

test('a class-wide run excludes nothing', () => {
  const s = pit.pitScope({ fqcn: 'a.B', onlyMethod: null, siblingMethods: ['x', 'y'] });
  assert.deepEqual(s.excluded, [], 'no target method means mutate the whole class');
});

test('targeting one method excludes its siblings but never itself', () => {
  const s = pit.pitScope({ fqcn: 'a.B', onlyMethod: 'load', siblingMethods: ['load', 'prime', 'clear'] });
  assert.deepEqual(s.excluded.sort(), ['clear', 'prime']);
});

test('a constructor recognises itself despite arriving XML-escaped', () => {
  // THE bug this function was extracted for. The sibling list spells constructors
  // `&lt;init&gt;`; onlyMethod is `<init>`. Without normalising both sides the constructor
  // failed to remove itself from its own exclusion list, so PIT was told to mutate <init>
  // AND to exclude <init> — leaving nothing to mutate, reported as zero mutants.
  const s = pit.pitScope({
    fqcn: 'a.B',
    onlyMethod: '<init>',
    siblingMethods: ['&lt;init&gt;', 'load'],
  });
  assert.ok(!s.excluded.includes('<init>'), `the constructor excluded itself: ${JSON.stringify(s.excluded)}`);
  assert.deepEqual(s.excluded, ['load']);
});

test('sibling units are preferred over a previous report', () => {
  const s = pit.pitScope({
    fqcn: 'a.B', onlyMethod: 'load',
    siblingMethods: ['load', 'prime'],
    statMethods: ['stale', 'gone'],
  });
  assert.deepEqual(s.excluded, ['prime'], 'the live unit list wins');
});

test("a previous report's methods are the fallback, not an error", () => {
  const s = pit.pitScope({
    fqcn: 'a.B', onlyMethod: 'load',
    siblingMethods: [],
    statMethods: ['load', 'prime'],
  });
  assert.deepEqual(s.excluded, ['prime']);
});

test('nothing known about the class excludes nothing', () => {
  const s = pit.pitScope({ fqcn: 'a.B', onlyMethod: 'load' });
  assert.deepEqual(s.excluded, [], 'better to mutate too much than to exclude everything');
});

test('a name repeated across sources is excluded once', () => {
  // overloads share a name; PIT takes names, not signatures, so a duplicate would be
  // passed twice in a comma-joined -DexcludedMethods
  const s = pit.pitScope({
    fqcn: 'a.B', onlyMethod: 'load',
    siblingMethods: ['prime', 'prime', 'prime'],
  });
  assert.deepEqual(s.excluded, ['prime']);
});

// ── finding the report PIT left behind ────────────────────────────────────
const REPORT = '<mutations></mutations>';
function withReports(rel) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ijt-report-'));
  for (const r of rel) {
    fs.mkdirSync(path.join(dir, path.dirname(r)), { recursive: true });
    fs.writeFileSync(path.join(dir, r), REPORT);
  }
  return dir;
}

test('the maven layout is found', () => {
  const dir = withReports(['target/pit-reports/mutations.xml']);
  assert.equal(pit.findReport(dir, '.'), path.join(dir, 'target/pit-reports/mutations.xml'));
});

test('the gradle layout is found', () => {
  const dir = withReports(['build/reports/pitest/mutations.xml']);
  assert.equal(pit.findReport(dir, '.'), path.join(dir, 'build/reports/pitest/mutations.xml'));
});

test("a module's own report wins over one at the repo root", () => {
  // Both exist after a multi-module run. Preferring the root report would score the wrong
  // class with numbers that look entirely reasonable.
  const dir = withReports(['target/pit-reports/mutations.xml', 'mod/target/pit-reports/mutations.xml']);
  assert.equal(pit.findReport(dir, 'mod'), path.join(dir, 'mod/target/pit-reports/mutations.xml'));
});

test('an unconventional location is found by search, newest first', () => {
  const dir = withReports(['deep/nested/out/mutations.xml', 'other/place/mutations.xml']);
  const newer = path.join(dir, 'other/place/mutations.xml');
  const old = new Date(Date.now() - 60_000);
  fs.utimesSync(path.join(dir, 'deep/nested/out/mutations.xml'), old, old);
  assert.equal(pit.findReport(dir, '.'), newer);
});

test('no report anywhere is null, not a path that does not exist', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ijt-report-empty-'));
  assert.equal(pit.findReport(dir, '.'), null);
});

test('every report is collected for cleanup, and .git is never walked', () => {
  // runPit deletes these before a run; a stale report left behind is read as this run's
  // result. Descending into .git would be slow and could match a blob by name.
  const dir = withReports(['target/pit-reports/mutations.xml', 'mod/build/reports/pitest/mutations.xml']);
  fs.mkdirSync(path.join(dir, '.git'), { recursive: true });
  fs.writeFileSync(path.join(dir, '.git', 'mutations.xml'), REPORT);
  const found = pit.findAllReports(dir);
  assert.equal(found.length, 2, found.join(' | '));
  assert.ok(!found.some((p) => p.includes('.git')), found.join(' | '));
});

test('an unreadable directory is skipped rather than crashing the walk', () => {
  const dir = withReports(['a/mutations.xml']);
  const locked = path.join(dir, 'locked');
  fs.mkdirSync(locked, { recursive: true });
  fs.chmodSync(locked, 0o000);
  try {
    assert.deepEqual(pit.findAllReports(dir), [path.join(dir, 'a/mutations.xml')]);
  } finally {
    fs.chmodSync(locked, 0o755);       // or the temp dir cannot be cleaned up
  }
});
