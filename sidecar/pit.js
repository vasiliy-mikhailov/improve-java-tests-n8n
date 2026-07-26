'use strict';
// PIT mutation testing, scoped to ONE class per run (whole-repo mutation is far too slow).
//
// Wiring is the hard part and it depends on the test framework (RESEARCH.md §1):
//   JUnit 4  — works with a bare `pitest-maven` invocation.
//   JUnit 5+ — needs `pitest-junit5-plugin` as a plugin DEPENDENCY, which a `-D` cannot add,
//              so the plugin block is injected into the module's pom (main <build>, never a
//              <profile>: a plugin inside an inactive profile is silently ignored).
//   JUnit 6  — additionally needs junit-platform-launcher pinned to the project's platform
//              version, or PIT's bundled launcher mismatches the engine.
//   TestNG   — needs `pitest-testng-plugin`.
// Injected build config is never committed (pr.js commits test sources only) and is
// re-applied before every run, since round bookkeeping discards uncommitted changes.
const fs = require('node:fs');
const path = require('node:path');
const { run } = require('./exec');
const { state, event } = require('./state');
const repo = require('./repo');
const { round2, slugify } = require('./util');
const { DATA_DIR } = require('./state');

// PIT must be CURRENT: its JUnit bridge has to understand the project's junit-platform,
// and an old PIT against a modern JUnit fails with the unhelpful "Please check you have
// correctly installed the pitest plugin for your project's test library".
const PIT_VERSION = process.env.PIT_VERSION || '1.25.8';
const PIT_JUNIT5_VERSION = process.env.PIT_JUNIT5_VERSION || '1.2.3';
const PIT_TESTNG_VERSION = process.env.PIT_TESTNG_VERSION || '1.0.0';
const MUTATORS = process.env.PIT_MUTATORS || 'DEFAULTS';
// gradle-pitest-plugin must match the project's Gradle: 1.15.0 reads
// `reporting.baseDir`, which Gradle 9 removed, so applying it there fails outright with
// "Could not get unknown property 'baseDir'" — that is why every Gradle repo produced
// nothing. 1.19.0 works on 8/9; older projects keep the older plugin.
function gradlePitestPluginVersion() {
  if (process.env.GRADLE_PITEST_PLUGIN_VERSION) return process.env.GRADLE_PITEST_PLUGIN_VERSION;
  const major = parseInt(String(state.runner?.gradleVersion || '').split('.')[0], 10);
  return Number.isFinite(major) && major < 8 ? '1.15.0' : '1.19.0';
}
const INIT_SCRIPT = '.ijt-pitest.init.gradle';

// ── Maven wiring ───────────────────────────────────────────────────────────

/** junit-platform version matching a jupiter version: 5.11.3 → 1.11.3, 6.1.0 → 6.1.0. */
function platformVersionFor(jupiterVersion) {
  const m = String(jupiterVersion || '').match(/^(\d+)\.(\d+)(?:\.(\d+))?/);
  if (!m) return null;
  const major = parseInt(m[1], 10);
  if (major >= 6) return jupiterVersion;
  if (major === 5) return `1.${m[2]}${m[3] ? '.' + m[3] : ''}`;
  return null;
}

function pitPluginXml(framework, frameworkVersion) {
  const deps = [];
  if (framework === 'junit5') {
    deps.push(`      <dependency><groupId>org.pitest</groupId><artifactId>pitest-junit5-plugin</artifactId><version>${PIT_JUNIT5_VERSION}</version></dependency>`);
    // Pin junit-platform-launcher to the platform the project's engine belongs to, or
    // PIT's bundled launcher mismatches it and finds no tests at all. Jupiter 5.x rides
    // platform 1.x; JUnit 6 unified the two onto one version.
    const platform = platformVersionFor(frameworkVersion);
    if (platform) {
      deps.push(`      <dependency><groupId>org.junit.platform</groupId><artifactId>junit-platform-launcher</artifactId><version>${platform}</version></dependency>`);
    }
  } else if (framework === 'testng') {
    deps.push(`      <dependency><groupId>org.pitest</groupId><artifactId>pitest-testng-plugin</artifactId><version>${PIT_TESTNG_VERSION}</version></dependency>`);
  }
  return `    <plugin>
      <groupId>org.pitest</groupId>
      <artifactId>pitest-maven</artifactId>
      <version>${PIT_VERSION}</version>${deps.length ? `
      <dependencies>
${deps.join('\n')}
      </dependencies>` : ''}
    </plugin>`;
}

/**
 * Make sure the module's pom carries a usable PIT plugin. Returns 'present' when the
 * project already declares one (we defer to it), 'injected' when we added ours.
 */
function ensureMavenWiring(moduleRel) {
  const dir = repo.repoDir();
  const pomRel = path.join(moduleRel === '.' ? '' : moduleRel, 'pom.xml');
  const pomAbs = path.join(dir, pomRel);
  if (!fs.existsSync(pomAbs)) return 'no-pom';
  let pom = fs.readFileSync(pomAbs, 'utf8');
  if (/<artifactId>\s*pitest-maven\s*<\/artifactId>/.test(pom)) {
    const framework = state.runner?.testFramework;
    const needsJunit5 = framework === 'junit5' && !/pitest-junit5-plugin/.test(pom);
    const needsTestng = framework === 'testng' && !/pitest-testng-plugin/.test(pom);
    if (!needsJunit5 && !needsTestng) return 'present';
    // project declares PIT but not the engine bridge it needs — add just the dependency
    const dep = needsJunit5
      ? `<dependencies><dependency><groupId>org.pitest</groupId><artifactId>pitest-junit5-plugin</artifactId><version>${PIT_JUNIT5_VERSION}</version></dependency></dependencies>`
      : `<dependencies><dependency><groupId>org.pitest</groupId><artifactId>pitest-testng-plugin</artifactId><version>${PIT_TESTNG_VERSION}</version></dependency></dependencies>`;
    pom = pom.replace(/(<artifactId>\s*pitest-maven\s*<\/artifactId>\s*(?:<version>[^<]*<\/version>\s*)?)/,
      `$1${dep}\n      `);
    fs.writeFileSync(pomAbs, pom);
    event('pit', `added the missing PIT ${needsJunit5 ? 'JUnit 5' : 'TestNG'} bridge to ${pomRel}`);
    return 'patched';
  }
  const block = pitPluginXml(state.runner?.testFramework, state.runner?.testFrameworkVersion);
  const profilesAt = pom.indexOf('<profiles>');
  const buildAt = pom.indexOf('<build>');
  const buildIsTopLevel = buildAt !== -1 && (profilesAt === -1 || buildAt < profilesAt);
  if (buildIsTopLevel) {
    const pluginsAt = pom.indexOf('<plugins>', buildAt);
    const buildEnd = pom.indexOf('</build>', buildAt);
    if (pluginsAt !== -1 && pluginsAt < buildEnd) {
      pom = pom.slice(0, pluginsAt + '<plugins>'.length) + '\n' + block + pom.slice(pluginsAt + '<plugins>'.length);
    } else {
      pom = pom.slice(0, buildAt + '<build>'.length) + `\n  <plugins>\n${block}\n  </plugins>` + pom.slice(buildAt + '<build>'.length);
    }
  } else {
    pom = pom.replace(/<\/project>\s*$/, `  <build>\n    <plugins>\n${block}\n    </plugins>\n  </build>\n</project>\n`);
  }
  fs.writeFileSync(pomAbs, pom);
  event('pit', `injected PIT ${PIT_VERSION} into ${pomRel} (${state.runner?.testFramework})`);
  return 'injected';
}

function gradleInitScript(targetClasses, targetTests, excluded = []) {
  return `// injected by improve-java-tests-n8n — applies gradle-pitest-plugin without touching the repo
initscript {
  // allowInsecureProtocol: the on-host Nexus is plain http, and Gradle 7+ refuses
  // http repositories without it — that rejection killed the Gradle PIT path.
  repositories {
    maven { url = uri('${process.env.MAVEN_MIRROR_URL || 'https://plugins.gradle.org/m2'}'); allowInsecureProtocol = true }
    gradlePluginPortal()
    mavenCentral()
  }
  dependencies { classpath 'info.solidsoft.gradle.pitest:gradle-pitest-plugin:${gradlePitestPluginVersion()}' }
}
allprojects { p ->
  p.plugins.withId('java') {
    p.apply plugin: info.solidsoft.gradle.pitest.PitestPlugin
    p.pitest {
      pitestVersion = '${PIT_VERSION}'
      ${state.runner?.testFramework === 'junit5' ? "junit5PluginVersion = '" + PIT_JUNIT5_VERSION + "'" : ''}
      targetClasses = ['${targetClasses}']
      ${targetTests ? `targetTests = ['${targetTests}']` : ''}
      mutators = ['${MUTATORS}']
      ${excluded.length ? `excludedMethods = [${excluded.map((m) => `'${m}'`).join(', ')}]` : ''}
      outputFormats = ['XML']
      timestampedReports = false
      failWhenNoMutations = false
    }
  }
}
`;
}

// ── running ────────────────────────────────────────────────────────────────

/**
 * Run PIT against one source file (= one class) and return its mutation score.
 * `targetTests` scopes which tests PIT runs — the class's own tests plus ours — so an
 * unrelated broken test elsewhere in the module cannot sink the measurement.
 */
async function runPit(fileRel, { onlyMethod = null } = {}) {
  const dir = repo.repoDir();
  if (!state.runner?.tool) throw new Error('build not detected — call /api/repo/prepare first');
  const f = state.files[fileRel] || {};
  const srcPath = f.path || String(fileRel).split('::')[0];
  const fqcn = f.fqcn || repo.fqcnOf(srcPath);
  const moduleRel = f.module || repo.moduleOf(srcPath);
  // the unit IS a method: mutate only it unless explicitly asked for the whole class
  if (onlyMethod === undefined || onlyMethod === null) onlyMethod = f.method || null;
  const pkg = fqcn.includes('.') ? fqcn.slice(0, fqcn.lastIndexOf('.')) : '';
  const cls = fqcn.slice(fqcn.lastIndexOf('.') + 1);
  // the class's own tests, ours, and anything else named after it
  // Leading wildcard on purpose: a project's tests frequently sit in a sibling package
  // (org.json.junit.XMLTest for org.json.XML), and a package-anchored glob matches none
  // of them.
  const targetTests = `*${cls}*Test*`;
  const targetClasses = `${fqcn}*`;   // includes inner classes

  // Method scoping. PIT's cost is (mutants × suite time) and a class-wide run re-mutates
  // everything on every round, which dominates wall-clock. PIT has no "target method"
  // option, but it has excludedMethods — so mutating one method means excluding all the
  // others. The method list comes from the class's own baseline report (bytecode truth,
  // including constructors as <init>), not from parsing source.
  // Everything except the target method. The method list comes from the class's sibling
  // units (JaCoCo enumerated every method at baseline) or, failing that, from a previous
  // PIT report — never from parsing source.
  const siblings = Object.values(state.files)
    .filter((u) => u.path === srcPath && u.method)
    .map((u) => u.method);
  const known = siblings.length ? siblings : Object.keys(f.methodStats?.byMethod || {});
  const excluded = onlyMethod ? [...new Set(known)].filter((m) => m !== onlyMethod) : [];

  // Delete any previous report FIRST. findReport() falls back to "newest mutations.xml
  // anywhere", so when a run produces nothing it used to return the PREVIOUS unit's
  // report; parseReport then filtered it by this class, found none of its mutants and
  // reported "0 mutants" — a real method with 25 mutants was written off as having no
  // mutation surface and skipped. A missing report must read as a failure, never as zero.
  for (const stale of findAllReports(dir)) { try { fs.unlinkSync(stale); } catch { } }

  let r;
  if (state.runner.tool === 'maven') {
    ensureMavenWiring(moduleRel);
    // Compile FIRST. `mvn <plugin>:<goal>` runs the goal alone — no lifecycle phase, no
    // javac — and branch creation wipes target/ with `git clean -fd`, so a bare PIT
    // invocation finds no classes and reports "no mutations exercised", i.e. score 0.
    // That silently made every file's BASELINE 0 and flattered every improvement.
    const compileArgv = [state.runner.wrapper, '-B', '-ntp', 'test-compile'];
    if (moduleRel && moduleRel !== '.') compileArgv.push('-pl', moduleRel, '-am');
    const c = await run(compileArgv, { cwd: dir, timeoutMs: 1800000, label: 'compile', env: repo.buildEnv() });
    if (c.code !== 0) throw new Error('test-compile before PIT failed: ' + (c.stderr || c.stdout).slice(-600));
    // Incremental analysis would be ideal here — rounds re-measure the same method over
    // and over — but PIT 1.25 moved history behind the commercial arcmutate plugin:
    // passing historyInputFile/historyOutputFile without it fails the whole run with
    // "History has been enabled but no history plugin has been installed/activated".
    // Off unless a deployment actually has that plugin.
    const useHistory = String(process.env.PIT_HISTORY || 'false') === 'true';
    let histFile = null;
    if (useHistory) {
      const histDir = path.join(DATA_DIR, 'pit-history', slugify(state.run.config.repoUrl));
      fs.mkdirSync(histDir, { recursive: true });
      histFile = path.join(histDir, fqcn.replace(/[^\w.]/g, '_') + '.txt');
    }
    const argv = [state.runner.wrapper, '-B', '-ntp',
      `org.pitest:pitest-maven:${PIT_VERSION}:mutationCoverage`,
      `-DtargetClasses=${targetClasses}`, `-DtargetTests=${targetTests}`,
      `-Dmutators=${MUTATORS}`, '-DoutputFormats=XML', '-DtimestampedReports=false',
      '-DfailWhenNoMutations=false', '-DtimeoutConstant=8000'];
    if (histFile) argv.push(`-DhistoryInputFile=${histFile}`, `-DhistoryOutputFile=${histFile}`);
    if (excluded.length) argv.push(`-DexcludedMethods=${excluded.join(',')}`);
    // no -am here: dependencies are built by the compile step above, and -DskipTests
    // (which -am would need) makes PIT skip its own run
    if (moduleRel && moduleRel !== '.') argv.push('-pl', moduleRel);
    r = await run(argv, { cwd: dir, timeoutMs: 3600000, label: 'pit', env: repo.buildEnv() });
  } else {
    fs.writeFileSync(path.join(dir, INIT_SCRIPT), gradleInitScript(targetClasses, targetTests, excluded));
    const task = moduleRel && moduleRel !== '.' ? `:${moduleRel.split('/').join(':')}:pitest` : 'pitest';
    r = await run([state.runner.wrapper, '--no-daemon', '-I', INIT_SCRIPT, task],
      { cwd: dir, timeoutMs: 3600000, label: 'pit', env: repo.buildEnv() });
  }

  const reportAbs = findReport(dir, moduleRel);
  if (!reportAbs) {
    const out = r.stderr + r.stdout;
    if (/No mutations found|no tests to run|0 tests/i.test(out)) {
      // Sanity check on our own measurement: a class the suite demonstrably executes
      // cannot legitimately have "no mutations exercised". When those two disagree the
      // measurement is broken (missing classes, a test-name glob that matches nothing),
      // not the project — say so instead of quietly recording a 0 baseline.
      const cov = state.files[fileRel]?.coverage;
      if (cov > 0) {
        event('pit', `${fileRel}: PIT found no tests to run although JaCoCo measured ${cov}% line `
          + `coverage — treating the mutation score as UNMEASURED, not 0 (targetTests glob or `
          + `compiled classes are wrong)`);
        return { file: fileRel, fqcn, totalMutants: null, killed: 0, score: null, survived: [], noTests: true, unmeasured: true };
      }
      event('pit', `${fileRel}: no mutations exercised — mutation score 0 (nothing kills mutants yet)`);
      return { file: fileRel, fqcn, totalMutants: null, killed: 0, score: 0, survived: [], noTests: true };
    }
    throw new Error(`PIT produced no report (exit ${r.code}): ` + out.slice(-800));
  }
  const parsed = parseReport(reportAbs, fileRel, fqcn);
  parsed.scope = onlyMethod || 'class';
  // PIT prints how many tests it ran; if our freshly written test is not in that number
  // the round cannot possibly kill anything, and we should say so rather than report an
  // unchanged score as if the test had simply been ineffective
  parsed.testsRun = parseInt((r.stdout + r.stderr).match(/Ran (\d+) tests?/)?.[1] || '0', 10) || null;
  event('pit', `${fileRel}${onlyMethod ? ' [method ' + onlyMethod + '()]' : ''}: `
    + `${parsed.totalMutants} mutants, ${parsed.killed} killed, `
    + `${parsed.survived.length} survived+nocov, score ${parsed.score}%`
    + (parsed.testsRun ? ` (${parsed.testsRun} tests ran)` : ''));
  return parsed;
}

/** Every mutations.xml under the repo — used to clear stale reports before a run. */
function findAllReports(dir) {
  const out = [];
  const walk = (d, depth) => {
    if (depth > 7) return;
    let entries;
    try { entries = fs.readdirSync(d, { withFileTypes: true }); } catch { return; }
    for (const ent of entries) {
      const p = path.join(d, ent.name);
      if (ent.isDirectory()) { if (ent.name !== '.git') walk(p, depth + 1); }
      else if (ent.name === 'mutations.xml') out.push(p);
    }
  };
  walk(dir, 0);
  return out;
}

function findReport(dir, moduleRel) {
  const candidates = [
    path.join(dir, moduleRel === '.' ? '' : moduleRel, 'target', 'pit-reports', 'mutations.xml'),
    path.join(dir, moduleRel === '.' ? '' : moduleRel, 'build', 'reports', 'pitest', 'mutations.xml'),
    path.join(dir, 'target', 'pit-reports', 'mutations.xml'),
    path.join(dir, 'build', 'reports', 'pitest', 'mutations.xml'),
  ];
  const found = candidates.find((p) => fs.existsSync(p));
  if (found) return found;
  // multi-module fallback: newest mutations.xml anywhere
  const all = [];
  const walk = (d, depth) => {
    if (depth > 7) return;
    let entries;
    try { entries = fs.readdirSync(d, { withFileTypes: true }); } catch { return; }
    for (const ent of entries) {
      const p = path.join(d, ent.name);
      if (ent.isDirectory()) { if (ent.name !== '.git') walk(p, depth + 1); }
      else if (ent.name === 'mutations.xml') all.push(p);
    }
  };
  walk(dir, 0);
  if (!all.length) return null;
  return all.map((p) => ({ p, m: fs.statSync(p).mtimeMs })).sort((a, b) => b.m - a.m)[0].p;
}

const MUTATION_RE = /<mutation\b([^>]*)>([\s\S]*?)<\/mutation>/g;
const TAG = (body, name) => body.match(new RegExp(`<${name}>([\\s\\S]*?)</${name}>`))?.[1]?.trim() || '';

function parseReport(reportAbs, fileRel, fqcn) {
  const xml = fs.readFileSync(reportAbs, 'utf8');
  const mutants = [];
  let m;
  MUTATION_RE.lastIndex = 0;
  while ((m = MUTATION_RE.exec(xml))) {
    const attrs = m[1], body = m[2];
    const status = attrs.match(/status=['"]([^'"]+)['"]/)?.[1] || 'UNKNOWN';
    const detected = /detected=['"]true['"]/.test(attrs);
    const mutatedClass = TAG(body, 'mutatedClass');
    // a PIT run can pull in inner classes; keep everything under the target class
    if (mutatedClass && !mutatedClass.startsWith(fqcn)) continue;
    mutants.push({
      status,
      detected,
      line: parseInt(TAG(body, 'lineNumber'), 10) || null,
      mutator: TAG(body, 'mutator').split('.').pop(),
      method: TAG(body, 'mutatedMethod'),
      description: TAG(body, 'description'),
      block: TAG(body, 'block'),
    });
  }
  // NON_VIABLE mutants cannot be killed by any test — excluding them keeps the score honest
  const scored = mutants.filter((x) => x.status !== 'NON_VIABLE');
  const killed = scored.filter((x) => x.detected).length;
  const total = scored.length;
  const score = total ? round2((killed * 100) / total) : 0;
  // per-method breakdown: the unit of work for rounds, and the list of method names
  // (bytecode truth) that method scoping excludes against
  const byMethod = {};
  for (const m of scored) {
    const e = (byMethod[m.method || '?'] ||= { total: 0, killed: 0, survived: 0 });
    e.total += 1;
    if (m.detected) e.killed += 1; else e.survived += 1;
  }
  // SURVIVED first, NO_COVERAGE last — the opposite of the intuition, and of what this
  // code used to do. A SURVIVED mutant sits on a line the suite ALREADY executes: only an
  // assertion is missing, which a short test can supply. A NO_COVERAGE mutant sits on a
  // line nothing reaches, and on a method that is already well covered those are the hard
  // leftovers — error paths and edge branches needing elaborate inputs. Offering those
  // first is why four consecutive rounds on JSON-java killed nothing: the model dutifully
  // picked NO_COVERAGE every time and could not reach the code.
  const survived = scored.filter((x) => !x.detected)
    .sort((a, b) => (a.status === 'SURVIVED' ? 0 : 1) - (b.status === 'SURVIVED' ? 0 : 1)
      || (a.line || 0) - (b.line || 0));
  return { file: fileRel, fqcn, totalMutants: total, killed, score, survived, byMethod, report: path.basename(reportAbs) };
}

module.exports = { runPit, ensureMavenWiring, parseReport, platformVersionFor, PIT_VERSION, MUTATORS };
