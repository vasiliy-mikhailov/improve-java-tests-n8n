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
function gradlePitestPluginVersion(gradleVersion = state.runner?.gradleVersion) {
  if (process.env.GRADLE_PITEST_PLUGIN_VERSION) return process.env.GRADLE_PITEST_PLUGIN_VERSION;
  const major = parseInt(String(gradleVersion || '').split('.')[0], 10);
  // an unrecognised Gradle gets the modern plugin: the old one cannot even load on 9
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

/**
 * @param {object} [opts]  defaults read from run state; passed explicitly by the tests
 */
function gradleInitScript(targetClasses, targetTests, excluded = [], opts = {}) {
  const gradleVersion = opts.gradleVersion ?? state.runner?.gradleVersion;
  const testFramework = opts.testFramework ?? state.runner?.testFramework;
  const pitVersion = opts.pitVersion ?? PIT_VERSION;
  const junit5Plugin = opts.junit5PluginVersion ?? PIT_JUNIT5_VERSION;
  const mutators = opts.mutators ?? MUTATORS;
  const mirrorUrl = opts.mirrorUrl ?? process.env.MAVEN_MIRROR_URL ?? 'https://plugins.gradle.org/m2';
  return `// injected by improve-java-tests-n8n — applies gradle-pitest-plugin without touching the repo
initscript {
  // allowInsecureProtocol: the on-host Nexus is plain http, and Gradle 7+ refuses
  // http repositories without it — that rejection killed the Gradle PIT path.
  repositories {
    maven { url = uri('${mirrorUrl}'); allowInsecureProtocol = true }
    gradlePluginPortal()
    mavenCentral()
  }
  dependencies { classpath 'info.solidsoft.gradle.pitest:gradle-pitest-plugin:${gradlePitestPluginVersion(gradleVersion)}' }
}
allprojects { p ->
  p.plugins.withId('java') {
    p.apply plugin: info.solidsoft.gradle.pitest.PitestPlugin
    p.pitest {
      pitestVersion = '${pitVersion}'
      ${testFramework === 'junit5' ? `junit5PluginVersion = '${junit5Plugin}'` : ''}
      targetClasses = ['${targetClasses}']
      ${targetTests ? `targetTests = ['${targetTests}']` : ''}
      mutators = ['${mutators}']
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
  // excludedMethods is best-effort — when it misses one, another method's mutants come
  // back in the report and would be scored and attacked as if they were this unit's
  const whole = parseReport(reportAbs, fileRel, fqcn);
  const parsed = scopeToMethod(whole, onlyMethod);
  parsed.scope = onlyMethod || 'class';
  const strays = whole.totalMutants - parsed.totalMutants;
  if (onlyMethod && strays > 0) {
    // excludedMethods let another method through; say so rather than quietly dropping it
    event('pit', `${strays} mutant(s) from other methods came back in the report and were `
      + `excluded from ${onlyMethod}()'s score and target list`);
  }
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

/**
 * How hard a mutant is to kill, from ITS OWN PROPERTIES — not from anyone's opinion.
 * Lower is easier, and the order is what the pipeline attacks first.
 *
 * The ranking is empirical. Watching JSON-java, four consecutive rounds picked
 * RemoveConditionalMutator_EQUAL_ELSE on the model's confident reasoning and every one
 * survived: removing an equality guard usually leaves behaviour that a short test cannot
 * distinguish, and often nothing can (an equivalent mutant). Value-returning and
 * arithmetic mutants are the opposite — the method returns a different value, so one
 * assertion on the return value settles it.
 *
 * SURVIVED beats NO_COVERAGE at every difficulty: the line already executes, so only an
 * assertion is missing rather than a whole path to reach.
 */
function killDifficulty(m) {
  const k = String(m.mutator || '');
  const easy = /(ReturnVals|PrimitiveReturns|BooleanTrueReturn|BooleanFalseReturn|EmptyObjectReturn|NullReturn|Math|Increments|InlineConstant|ConditionalsBoundary|NegateConditionals|Switch)/;
  const hard = /(RemoveConditional|VoidMethodCall|ConstructorCall|NonVoidMethodCall|MemberVariable|RemoveIncrements)/;
  let d = easy.test(k) ? 0 : hard.test(k) ? 2 : 1;
  if (m.status !== 'SURVIVED') d += 3;      // NO_COVERAGE needs the path reached first
  return d;
}

const MUTATION_RE = /<mutation\b([^>]*)>([\s\S]*?)<\/mutation>/g;

/**
 * XML entity decoding for values read out of the report.
 *
 * PIT escapes method names, so a constructor arrives as `&lt;init&gt;` (or `&#60;init&#62;`
 * from older versions). Unit method names come from JaCoCo, which coverage.js already
 * unescapes — so the two spellings never matched, and every constructor unit scoped to
 * zero mutants and was written off as having no mutation surface.
 */
function unescapeXml(v) {
  return String(v)
    .replace(/&#(\d+);/g, (_, d) => String.fromCharCode(parseInt(d, 10)))
    .replace(/&#x([0-9a-f]+);/gi, (_, h) => String.fromCharCode(parseInt(h, 16)))
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"').replace(/&apos;/g, "'")
    .replace(/&amp;/g, '&');
}

const TAG = (body, name) => unescapeXml(body.match(new RegExp(`<${name}>([\\s\\S]*?)</${name}>`))?.[1]?.trim() || '');

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
    // Inner and anonymous classes (a.B$Inner, a.B$1) belong to the target; a DIFFERENT
    // class whose name merely begins with it (a.BFoo) does not. `startsWith` accepted
    // both, and with the method filter matching on name alone, a.BFoo#run's kills were
    // credited to a.B#run.
    if (mutatedClass && mutatedClass !== fqcn && !mutatedClass.startsWith(fqcn + '$')) continue;
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
  const survived = scored.filter((x) => !x.detected)
    .map((m) => ({ ...m, difficulty: killDifficulty(m) }))
    .sort((a, b) => a.difficulty - b.difficulty || (a.line || 0) - (b.line || 0));
  return { file: fileRel, fqcn, totalMutants: total, killed, score, survived, byMethod,
    all: scored, report: path.basename(reportAbs) };
}

/**
 * Narrow a class-wide report to ONE method — the unit of work.
 *
 * PIT has no "mutate only this method" switch; we approximate it with excludedMethods,
 * built from the methods we know about. When that list is incomplete, mutants from other
 * methods come back in the report, and the consequences were not subtle: a SURVIVED
 * MathMutator in toString() outranked every NO_COVERAGE mutant of the actual unit, so a
 * whole round wrote a toString test that was then scored against isRecordStyleAccessor —
 * a guaranteed miss — while the unit's "mutation score" was computed over another
 * method's mutants as well. Excluding is best-effort; this filter is not.
 */
function scopeToMethod(parsed, method) {
  if (!method) return parsed;
  const mine = (parsed.all || []).filter((m) => m.method === method);
  const killed = mine.filter((m) => m.detected).length;
  return {
    ...parsed,
    totalMutants: mine.length,
    killed,
    // no mutants for this method is 0, never 100: nothing was proven about it
    score: mine.length ? round2((killed * 100) / mine.length) : 0,
    empty: mine.length === 0,
    survived: (parsed.survived || []).filter((m) => m.method === method),
    all: mine,
    // byMethod stays whole — it is bytecode truth about the class, and the exclusion
    // list for the next run is built from it
    byMethod: parsed.byMethod,
  };
}

module.exports = { runPit, ensureMavenWiring, parseReport, scopeToMethod, platformVersionFor,
  gradlePitestPluginVersion, gradleInitScript, killDifficulty, PIT_VERSION, MUTATORS };
