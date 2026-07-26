'use strict';
// Repo lifecycle for JAVA projects: clone, detect build tool / JDK / test framework,
// compile, branches, source+test file access.
//
// Java-specific realities this module encodes (see RESEARCH.md §1):
//   - Maven or Gradle, wrapper preferred (./mvnw, ./gradlew) — it pins the build version.
//   - The JDK a project needs to BUILD can exceed its declared bytecode target, and PIT's
//     forked minion dies under the wrong JDK, so we detect a JDK and use it everywhere.
//   - Multi-module repos: every command must run in the module that owns the class.
//   - Tests live in src/test/java and must be named so Surefire/Gradle pick them up.
const fs = require('node:fs');
const path = require('node:path');
const { run } = require('./exec');
const { state, event, upsertFile, DATA_DIR } = require('./state');
const { slugify, globsToMatcher } = require('./util');
const { resolveBranch, branchAction } = require('./branch');
const { generatedTestPaths, existingTestCandidates } = require('./testpaths');

function repoDir() {
  const cfg = state.run?.config;
  if (!cfg?.repoUrl) throw new Error('no run started / REPO_URL missing');
  return path.join(DATA_DIR, 'repos', slugify(cfg.repoUrl));
}

// Credentials are deliberately NOT inlined into the remote URL — git echoes the full
// URL in failure messages, which would put the token in the event log and dashboard.
// Auth comes from the gh credential helper entrypoint.sh installs.
async function clone() {
  const cfg = state.run.config;
  const dir = repoDir();
  fs.mkdirSync(path.dirname(dir), { recursive: true });
  // Ask the remote which branches it actually has before naming one. A configured branch
  // the repo does not have used to end the run here; now it falls back to the repo's own
  // default and says so.
  const ls = await run(['git', 'ls-remote', '--symref', cfg.repoUrl], { timeoutMs: 120000, label: 'git ls-remote' });
  if (ls.code !== 0) throw new Error('cannot reach ' + cfg.repoUrl + ': ' + ls.stderr.slice(-300));
  const resolved = resolveBranch(cfg.repoBranch, ls.stdout);
  if (resolved.fellBack) event('cloning', resolved.reason);
  // the PR base has to follow: it was frozen at the UNRESOLVED branch name, so on a repo
  // whose default is not `main` every `gh pr create --base main` failed after the work
  // had already been measured and committed
  if (!cfg.prBase || cfg.prBase === cfg.repoBranch) cfg.prBase = resolved.branch;
  cfg.repoBranch = resolved.branch;
  if (fs.existsSync(path.join(dir, '.git'))) {
    event('cloning', 'repo exists, fetching latest');
    const remote = await run(['git', 'remote', 'get-url', 'origin'], { cwd: dir, timeoutMs: 10000 });
    if (/\/\/[^/@\s]+:[^/@\s]+@/.test(remote.stdout)) {
      const clean = remote.stdout.trim().replace(/\/\/[^/@\s]+:[^/@\s]+@/, '//');
      await run(['git', 'remote', 'set-url', 'origin', clean], { cwd: dir, timeoutMs: 10000 });
      event('cloning', 'removed inlined credentials from the git remote');
    }
    const r = await run(['git', 'fetch', 'origin', cfg.repoBranch], { cwd: dir, timeoutMs: 300000, label: 'git fetch' });
    if (r.code !== 0) throw new Error('git fetch failed: ' + r.stderr.slice(-400));
    await run(['git', 'checkout', '-f', cfg.repoBranch], { cwd: dir, timeoutMs: 60000 });
    await run(['git', 'reset', '--hard', `origin/${cfg.repoBranch}`], { cwd: dir, timeoutMs: 60000 });
    await run(['git', 'clean', '-fd'], { cwd: dir, timeoutMs: 60000 });
  } else {
    event('cloning', `cloning ${cfg.repoUrl} (branch ${cfg.repoBranch})`);
    const r = await run(['git', 'clone', '--branch', cfg.repoBranch, '--single-branch',
      cfg.repoUrl, dir], { timeoutMs: 900000, label: 'git clone' });
    if (r.code !== 0) throw new Error('git clone failed: ' + r.stderr.slice(-400));
  }
  const head = await run(['git', 'rev-parse', 'HEAD'], { cwd: dir, timeoutMs: 10000 });
  event('cloning', 'repo ready at ' + head.stdout.trim().slice(0, 12));
  return { dir, head: head.stdout.trim() };
}

// ── build tool ─────────────────────────────────────────────────────────────

function readTextSafe(abs, max = 400000) {
  try { return fs.readFileSync(abs, 'utf8').slice(0, max); } catch { return ''; }
}

/** Module directory that owns a repo-relative path: nearest ancestor with a build file. */
function moduleOf(rel) {
  const dir = repoDir();
  let d = path.dirname(rel);
  while (d && d !== '.' && d !== path.sep) {
    if (['pom.xml', 'build.gradle', 'build.gradle.kts'].some((f) => fs.existsSync(path.join(dir, d, f)))) {
      return d.split(path.sep).join('/');
    }
    d = path.dirname(d);
  }
  return '.';
}

/** Maven artifactId of a module dir — needed for `-pl` in multi-module builds. */
function moduleSelector(moduleRel) {
  return moduleRel && moduleRel !== '.' ? moduleRel : null;
}

function detectBuild() {
  const dir = repoDir();
  const hasPom = fs.existsSync(path.join(dir, 'pom.xml'));
  const hasGradle = ['build.gradle', 'build.gradle.kts', 'settings.gradle', 'settings.gradle.kts']
    .some((f) => fs.existsSync(path.join(dir, f)));
  const tool = hasPom ? 'maven' : hasGradle ? 'gradle' : null;
  // the wrapper pins the Gradle version, and the PIT plugin has to match its API
  let gradleVersion = null;
  if (tool === 'gradle') {
    const props = readTextSafe(path.join(dir, 'gradle', 'wrapper', 'gradle-wrapper.properties'), 20000);
    gradleVersion = props.match(/gradle-([\d.]+)-(?:bin|all)/)?.[1] || null;
  }
  const wrapper = tool === 'maven'
    ? (fs.existsSync(path.join(dir, 'mvnw')) ? './mvnw' : 'mvn')
    : (fs.existsSync(path.join(dir, 'gradlew')) ? './gradlew' : 'gradle');
  return { tool, wrapper, hasPom, hasGradle, gradleVersion };
}

/**
 * JDK the project needs to BUILD. Declared bytecode target is a floor, not the answer:
 * plugins/annotation processors often need newer. We take the max of what is declared
 * and clamp to a JDK that is actually installed (JAVA_HOME_<major> env, set by the image).
 */
function detectJdk() {
  const dir = repoDir();
  const texts = [];
  for (const f of ['pom.xml', 'build.gradle', 'build.gradle.kts', 'gradle.properties']) {
    const t = readTextSafe(path.join(dir, f));
    if (t) texts.push(t);
  }
  const blob = texts.join('\n');
  const versions = [];
  const push = (v) => {
    if (!v) return;
    let n = parseInt(String(v).replace(/^1\./, ''), 10); // 1.8 → 8
    if (Number.isFinite(n) && n >= 6 && n <= 25) versions.push(n);
  };
  for (const re of [
    /<maven\.compiler\.(?:release|source|target)>\s*([\d.]+)\s*</g,
    /<(?:release|source|target)>\s*([\d.]+)\s*</g,
    /<java\.version>\s*([\d.]+)\s*</g,
    /sourceCompatibility\s*=?\s*['"]?(?:JavaVersion\.VERSION_)?([\d._]+)/g,
    /targetCompatibility\s*=?\s*['"]?(?:JavaVersion\.VERSION_)?([\d._]+)/g,
    /languageVersion\s*=\s*JavaLanguageVersion\.of\((\d+)\)/g,
  ]) {
    let m;
    while ((m = re.exec(blob))) push(m[1].replace(/_/g, '.'));
  }
  const declared = versions.length ? Math.max(...versions) : null;
  const installed = availableJdks();
  const order = jdkPreference(declared, installed);
  return { declared, chosen: order[0], order, javaHome: javaHomeFor(order[0]), installed };
}

/**
 * JDKs to TRY, best first. The declared bytecode target is a floor, not an answer:
 * Apache Commons and friends declare `maven.compiler.source=1.8` while their plugin
 * stack (japicmp, animal-sniffer, modern Surefire) refuses to run on JDK 8. Building
 * those under the declared 8 is what killed half the first eval sweep. So prefer a
 * modern JDK that can still emit the declared target, and fall back downwards only if
 * the build actually fails (install() walks this list).
 */
function jdkPreference(declared, installed) {
  const d = declared || 8;
  let wish;
  if (d <= 8) wish = [17, 11, 21, 8];
  else if (d <= 11) wish = [17, 11, 21];
  else if (d <= 17) wish = [17, 21];
  else wish = [d, 21];
  const order = wish.filter((v) => installed.includes(v) && v >= d);
  // never return empty: fall back to anything installed that can build the target
  return order.length ? order : installed.filter((v) => v >= d).concat(installed).slice(0, 2);
}

function availableJdks() {
  return Object.keys(process.env)
    .map((k) => k.match(/^JAVA_HOME_(\d+)$/))
    .filter(Boolean)
    .map((m) => parseInt(m[1], 10))
    .filter((v) => fs.existsSync(process.env['JAVA_HOME_' + v]))
    .sort((a, b) => a - b);
}

function javaHomeFor(major) {
  return process.env['JAVA_HOME_' + major] || process.env.JAVA_HOME || '';
}

/** Env every build/test/PIT command runs under. */
function buildEnv() {
  const jdk = state.runner?.jdk;
  const home = jdk?.javaHome || process.env.JAVA_HOME || '';
  return home ? { JAVA_HOME: home, PATH: path.join(home, 'bin') + ':' + process.env.PATH } : {};
}

/**
 * Unit-test framework — decides how PIT must be wired (junit5 needs pitest-junit5-plugin,
 * testng needs pitest-testng-plugin) and how generated tests are written.
 */
function detectTestFramework() {
  const dir = repoDir();
  let blob = '';
  for (const f of ['pom.xml', 'build.gradle', 'build.gradle.kts', 'gradle/libs.versions.toml']) {
    blob += readTextSafe(path.join(dir, f)) + '\n';
  }
  // module build files matter in multi-module repos
  for (const m of listModules().slice(0, 30)) {
    for (const f of ['pom.xml', 'build.gradle', 'build.gradle.kts']) {
      blob += readTextSafe(path.join(dir, m, f), 120000) + '\n';
    }
  }
  const jupiter = /junit-jupiter|org\.junit\.jupiter/.test(blob);
  const junit4 = /<artifactId>junit<\/artifactId>|junit:junit|junit:junit:4|org\.junit\.Test/.test(blob);
  const testng = /testng/i.test(blob);
  // an existing test file is the most reliable signal
  const sample = findAnyTestSource();
  const sampleText = sample ? readTextSafe(sample.abs, 40000) : '';
  if (/org\.junit\.jupiter\.api/.test(sampleText)) return { framework: 'junit5', version: jupiterVersion(blob) };
  if (/^import\s+org\.junit\.Test;/m.test(sampleText)) return { framework: 'junit4', version: null };
  if (/org\.testng/.test(sampleText)) return { framework: 'testng', version: null };
  if (jupiter) return { framework: 'junit5', version: jupiterVersion(blob) };
  if (junit4) return { framework: 'junit4', version: null };
  if (testng) return { framework: 'testng', version: null };
  return { framework: 'junit5', version: null }; // sane default for new tests
}

function jupiterVersion(blob) {
  const m = blob.match(/junit-jupiter(?:-api|-engine)?[^<\n]*?[:>]\s*(\d+\.\d+\.\d+)/)
    || blob.match(/<junit[.-]?jupiter[.-]?version>\s*(\d+\.\d+\.\d+)/i)
    || blob.match(/junit-bom[^\n]*?(\d+\.\d+\.\d+)/);
  return m ? m[1] : null;
}

/** All module directories (dirs holding a build file), repo-relative, '.' first. */
function listModules() {
  const dir = repoDir();
  const out = ['.'];
  const walk = (d, depth) => {
    if (depth > 4) return;
    let entries;
    try { entries = fs.readdirSync(d, { withFileTypes: true }); } catch { return; }
    for (const ent of entries) {
      if (!ent.isDirectory() || ent.name.startsWith('.') || ['target', 'build', 'node_modules'].includes(ent.name)) continue;
      const p = path.join(d, ent.name);
      if (['pom.xml', 'build.gradle', 'build.gradle.kts'].some((f) => fs.existsSync(path.join(p, f)))) {
        out.push(path.relative(dir, p).split(path.sep).join('/'));
      }
      walk(p, depth + 1);
    }
  };
  walk(dir, 0);
  return out;
}

function findAnyTestSource() {
  const dir = repoDir();
  let found = null;
  const walk = (d, depth) => {
    if (found || depth > 8) return;
    let entries;
    try { entries = fs.readdirSync(d, { withFileTypes: true }); } catch { return; }
    for (const ent of entries) {
      if (found) return;
      if (ent.name.startsWith('.') || ['target', 'build', 'node_modules'].includes(ent.name)) continue;
      const p = path.join(d, ent.name);
      if (ent.isDirectory()) walk(p, depth + 1);
      else if (/Tests?\.java$/.test(ent.name) && p.includes(`${path.sep}src${path.sep}test${path.sep}`)) {
        found = { abs: p, rel: path.relative(dir, p).split(path.sep).join('/') };
      }
    }
  };
  walk(dir, 0);
  return found;
}

// ── compile / prepare ──────────────────────────────────────────────────────

async function install() {
  const dir = repoDir();
  const build = detectBuild();
  if (!build.tool) throw new Error('no Maven pom.xml or Gradle build file found — not a supported Java repo');
  const jdk = detectJdk();
  if (!jdk.javaHome) throw new Error('no JDK available in the image');
  state.runner = { ...build, jdk, testFramework: null };
  event('installing', `build=${build.tool} (${build.wrapper}), declared JDK ${jdk.declared ?? '?'}, `
    + `trying ${jdk.order.join(' → ')} (available ${jdk.installed.join('/')})`);

  // Compile main + test sources under each candidate JDK until one works: this both
  // warms the Nexus-mirrored dependency cache and DISCOVERS the JDK the project really
  // needs, which is the only reliable way to know it.
  const argv = build.tool === 'maven'
    ? [build.wrapper, '-B', '-ntp', '-DskipTests', 'test-compile']
    : [build.wrapper, '--no-daemon', '-q', 'testClasses'];
  let r = null, lastErr = '';
  for (const major of jdk.order) {
    state.runner.jdk = { ...jdk, chosen: major, javaHome: javaHomeFor(major) };
    r = await run(argv, { cwd: dir, timeoutMs: 2400000, label: `compile (JDK ${major})`, env: buildEnv() });
    if (r.code === 0) {
      if (major !== jdk.order[0]) event('installing', `JDK ${jdk.order[0]} could not build this project — JDK ${major} can`);
      break;
    }
    lastErr = (r.stderr || r.stdout).slice(-700);
    event('installing', `build failed under JDK ${major}${major === jdk.order[jdk.order.length - 1] ? '' : ' — trying the next JDK'}`);
  }
  if (!r || r.code !== 0) throw new Error(`project build failed under every candidate JDK (${jdk.order.join('/')}): ` + lastErr);

  const tf = detectTestFramework();
  state.runner = { ...state.runner, testFramework: tf.framework, testFrameworkVersion: tf.version };
  state.runner.hasJacoco = await detectJacoco();
  event('installing', `ready (build=${build.tool}, JDK ${jdk.chosen}, tests=${tf.framework}${tf.version ? ' ' + tf.version : ''})`);
  return state.runner;
}

/**
 * Does the project already wire JaCoCo itself?
 *
 * It matters because adding our own `prepare-agent` on top of an existing one puts TWO
 * -javaagent entries on the test JVM, and the JVM dies with
 * `java.lang.instrument ASSERTION FAILED ... invokeJavaAgentMainMethod` before a single
 * test runs. Apache's commons-parent is the common case, and it is invisible in the
 * repo's own pom.xml — only the EFFECTIVE pom shows it, so that is what we ask for.
 * (Cheap: resolves the parent chain, runs no tests.)
 */
async function detectJacoco() {
  const dir = repoDir();
  if (state.runner?.tool !== 'maven') {
    // Gradle: our init script owns the wiring, and applying the plugin twice is a no-op
    const blob = ['build.gradle', 'build.gradle.kts'].map((f) => readTextSafe(path.join(dir, f))).join('\n');
    return /jacoco/i.test(blob);
  }
  // -Doutput, not stdout: help:effective-pom prints at INFO level, so `-q` silently
  // yields an empty POM — which made this return false for every Apache project and
  // put a second agent on the JVM anyway.
  const out = path.join(dir, '.ijt-effective-pom.xml');
  const r = await run([state.runner.wrapper, '-B', '-ntp', 'help:effective-pom', `-Doutput=${out}`],
    { cwd: dir, timeoutMs: 600000, label: 'effective-pom', env: buildEnv() });
  let text = '';
  try { text = fs.readFileSync(out, 'utf8'); } catch { text = r.stdout; }
  try { fs.unlinkSync(out); } catch { }
  if (r.code !== 0 && !text) return false;   // unknown → assume not, and inject our own
  const has = /jacoco-maven-plugin/.test(text);
  event('installing', has
    ? 'project configures JaCoCo itself — using its agent (a second one would crash the test JVM)'
    : 'project has no JaCoCo — the pipeline supplies its own agent');
  return has;
}

// ── source files ───────────────────────────────────────────────────────────

const MAIN_SRC = /(^|\/)src\/main\/java\//;
const TEST_SRC = /(^|\/)src\/test\/java\//;

async function listScopeFiles() {
  const dir = repoDir();
  const cfg = state.run.config;
  const r = await run(['git', 'ls-files'], { cwd: dir, timeoutMs: 30000 });
  if (r.code !== 0) throw new Error('git ls-files failed');
  const match = globsToMatcher(cfg.scopeGlob);
  const files = r.stdout.split('\n').map((s) => s.trim()).filter(Boolean)
    .filter((p) => p.endsWith('.java'))
    .filter((p) => MAIN_SRC.test(p) && !TEST_SRC.test(p))
    .filter((p) => !/(^|\/)(target|build|generated|generated-sources)\//.test(p))
    .filter((p) => !/(package-info|module-info)\.java$/.test(p))
    .filter(match);
  // line count is the cheapest pre-measurement signal of mutant surface: the pick
  // stage uses it to prefer logic-dense classes over huge generated/DTO files
  for (const p of files) {
    let lines = null;
    try { lines = fs.readFileSync(path.join(dir, p), 'utf8').split('\n').length; } catch { }
    upsertFile(p, { lines, fqcn: fqcnOf(p), module: moduleOf(p) });
  }
  return files;
}

/** Fully-qualified class name for a repo-relative source path. */
function fqcnOf(rel) {
  const m = rel.match(/src\/main\/java\/(.+)\.java$/);
  if (m) return m[1].split('/').join('.');
  // non-standard layout: fall back to the package declaration
  const src = readFileSafe(rel, 8000) || '';
  const pkg = src.match(/^\s*package\s+([\w.]+)\s*;/m)?.[1];
  const cls = path.basename(rel, '.java');
  return pkg ? `${pkg}.${cls}` : cls;
}

async function createBranch(name) {
  const dir = repoDir();
  const cfg = state.run.config;
  // A branch is per FILE — that is what a PR is — while a unit is a METHOD, so the second
  // method of a file arrives at a branch already carrying the first method's committed
  // (and possibly already PR'd) rounds. Resetting it there loses that work.
  const owners = (state.run.branchOwners ||= {});
  const decision = branchAction({ branch: name, owner: owners[name], runId: state.run.id });
  await run(['git', 'checkout', '-f', cfg.repoBranch], { cwd: dir, timeoutMs: 60000 });
  await run(['git', 'clean', '-fd'], { cwd: dir, timeoutMs: 60000 });
  const argv = decision.action === 'reuse'
    ? ['git', 'checkout', '-f', name]                       // continue on top of it
    : ['git', 'checkout', '-B', name, cfg.repoBranch];      // start again from the base
  const r = await run(argv, { cwd: dir, timeoutMs: 60000 });
  if (r.code !== 0) throw new Error('branch create failed: ' + r.stderr.slice(-300));
  owners[name] = state.run.id;
  event('branching', `working on branch ${name} (${decision.action === 'reuse' ? 'continuing this run\'s work on it' : decision.reason})`);
  return name;
}

/** Discard uncommitted changes on the current branch, keeping its commits. */
async function discardUncommitted() {
  const dir = repoDir();
  await run(['git', 'checkout', '--', '.'], { cwd: dir, timeoutMs: 60000 });
  await run(['git', 'clean', '-fd'], { cwd: dir, timeoutMs: 60000 });
}

async function resetToBase() {
  const dir = repoDir();
  const cfg = state.run.config;
  await run(['git', 'checkout', '-f', cfg.repoBranch], { cwd: dir, timeoutMs: 60000 });
  await run(['git', 'clean', '-fd'], { cwd: dir, timeoutMs: 60000 });
}

function readFileSafe(rel, maxLen = 200000) {
  const dir = repoDir();
  const abs = path.resolve(dir, rel);
  if (!abs.startsWith(dir + path.sep)) throw new Error('path escapes repo');
  try { return fs.readFileSync(abs, 'utf8').slice(0, maxLen); } catch { return null; }
}

function writeTestFile(rel, content) {
  if (!TEST_SRC.test(rel)) throw new Error('refusing to write outside src/test/java: ' + rel);
  if (!rel.endsWith('.java')) throw new Error('test file must be a .java file');
  const dir = repoDir();
  const abs = path.resolve(dir, rel);
  if (!abs.startsWith(dir + path.sep)) throw new Error('path escapes repo');
  fs.mkdirSync(path.dirname(abs), { recursive: true });
  fs.writeFileSync(abs, content);
  return { path: rel, bytes: Buffer.byteLength(content) };
}

function deleteTestFile(rel) {
  if (!TEST_SRC.test(rel)) throw new Error('refusing to delete outside src/test/java');
  const dir = repoDir();
  const abs = path.resolve(dir, rel);
  if (!abs.startsWith(dir + path.sep)) throw new Error('path escapes repo');
  try { fs.unlinkSync(abs); return true; } catch { return false; }
}

/**
 * Where the generated tests for `srcRel` go: same module, same package, under
 * src/test/java — and named so the BUILD actually runs them.
 *
 * That last part is not cosmetic. Surefire's default includes are `Test*.java`,
 * `*Test.java`, `*Tests.java`, `*TestCase.java` (Gradle's are equivalent), so a class
 * called `PricingMacTestCov` is compiled and then never executed: `mvn test` stays
 * green and JaCoCo reports nothing, while PIT — which selects tests by its own glob —
 * happily runs it and reports a mutation score. That mismatch produced a file measured
 * at coverage 0 % / mutation 100 %. Every generated name therefore ENDS with `Test`.
 *
 * @returns {{path:string, exists:boolean, covPath:string, mutPath:string}}
 *   `path`/`exists` describe the project's OWN test for the class (the style
 *   reference); covPath/mutPath are this round's generated classes.
 */
function guessTestPath(srcRel, round = 1) {
  const dir = repoDir();
  // naming rules (Surefire-includable, one class per round) live in testpaths.js under test
  const candidates = existingTestCandidates(srcRel);
  const own = candidates.find((c) => fs.existsSync(path.join(dir, c)));
  return { path: own || candidates[0], exists: !!own, ...generatedTestPaths(srcRel, round) };
}

/**
 * When the target class has no test, hand the LLM a sibling test to imitate: it shows
 * the repo's assertion library (JUnit 5 vs 4, AssertJ, Hamcrest), test base classes and
 * naming conventions. Prefers a test from the same module.
 */
function findStyleReference(srcRel) {
  const dir = repoDir();
  const wantModule = moduleOf(srcRel);
  const matches = [];
  const walk = (d, depth) => {
    if (depth > 9 || matches.length > 400) return;
    let entries;
    try { entries = fs.readdirSync(d, { withFileTypes: true }); } catch { return; }
    for (const ent of entries) {
      if (ent.name.startsWith('.') || ['target', 'build', 'node_modules'].includes(ent.name)) continue;
      const p = path.join(d, ent.name);
      if (ent.isDirectory()) walk(p, depth + 1);
      else if (/Tests?\.java$/.test(ent.name) && p.includes(`${path.sep}src${path.sep}test${path.sep}java${path.sep}`)) matches.push(p);
    }
  };
  walk(dir, 0);
  if (!matches.length) return null;
  const rels = matches.map((p) => path.relative(dir, p).split(path.sep).join('/'));
  const sameModule = rels.filter((p) => moduleOf(p) === wantModule);
  const pool = sameModule.length ? sameModule : rels;
  // smallest non-trivial test → most digestible reference
  let best = null, bestSize = Infinity;
  for (const p of pool) {
    try {
      const size = fs.statSync(path.join(dir, p)).size;
      if (size > 400 && size < bestSize) { best = p; bestSize = size; }
    } catch { }
  }
  if (!best) return null;
  return { path: best, content: readFileSafe(best, 8000) };
}

/**
 * The source the model actually needs to test ONE method: the file's package + imports,
 * the class declaration, the sibling method signatures (so it knows the available API),
 * and the target method's full body.
 *
 * Sending the whole class instead is what made each call slow: XML.java is ~1500 lines,
 * 12k characters of prompt to produce a 400-byte test for one method. Prompt size drives
 * both latency and cost, and most of that class is irrelevant to the mutant at hand.
 */
function methodContext(rel, methodName, methodLine) {
  const src = readFileSafe(rel, 500000);
  if (!src) return null;
  const lines = src.split('\n');
  const header = [];
  for (const l of lines) {
    if (/^\s*(package|import)\s/.test(l)) header.push(l);
    if (/\b(class|interface|enum|record)\s+\w/.test(l)) { header.push(l.replace(/\s*\{\s*$/, ' {')); break; }
  }
  // sibling signatures: one line each, no bodies
  const signatures = lines
    .filter((l) => /^\s{2,6}(public|protected|private|static)[\w\s<>\[\],.]*\([^)]*\)\s*(\{|throws)/.test(l))
    .map((l) => l.replace(/\s*\{\s*$/, ';').trim())
    .slice(0, 60);
  // the target method: from its declaration to the matching closing brace
  let body = null;
  const startAt = Math.max(0, (methodLine || 1) - 1);
  let start = startAt;
  // the JaCoCo line points at the first executable line, so walk back to the signature
  for (let i = startAt; i >= Math.max(0, startAt - 12); i--) {
    if (new RegExp('\\b' + methodName.replace(/[^\w]/g, '') + '\\s*\\(').test(lines[i] || '')) { start = i; break; }
  }
  let depth = 0, opened = false;
  for (let i = start; i < Math.min(lines.length, start + 600); i++) {
    for (const ch of lines[i]) {
      if (ch === '{') { depth += 1; opened = true; } else if (ch === '}') depth -= 1;
    }
    if (opened && depth <= 0) { body = lines.slice(start, i + 1).join('\n'); break; }
  }
  if (!body) body = lines.slice(start, Math.min(lines.length, start + 120)).join('\n');
  return {
    header: header.join('\n'),
    signatures,
    body,
    startLine: start + 1,
  };
}

module.exports = {
  repoDir, clone, install, detectBuild, detectJdk, detectTestFramework, buildEnv, methodContext,
  listScopeFiles, listModules, moduleOf, moduleSelector, fqcnOf,
  createBranch, resetToBase, discardUncommitted,
  readFileSafe, writeTestFile, deleteTestFile, guessTestPath, findStyleReference,
};
