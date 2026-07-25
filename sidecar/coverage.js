'use strict';
// Line coverage via JaCoCo.
//
// Maven: JaCoCo's goals are invoked directly (prepare-agent sets the `argLine` property
// Surefire picks up), so a project without JaCoCo configured needs no pom edit.
// Gradle: an INIT SCRIPT applies the jacoco plugin and forces an XML report, so the
// repo's own build files stay untouched.
//
// Coverage is keyed by fully-qualified class name — that is what JaCoCo reports and
// what maps cleanly onto a Java "file" (one public class per file).
const fs = require('node:fs');
const path = require('node:path');
const { run } = require('./exec');
const { state, event, upsertFile } = require('./state');
const repo = require('./repo');
const { round2 } = require('./util');

const JACOCO_VERSION = process.env.JACOCO_VERSION || '0.8.12';
const INIT_SCRIPT = '.ijt-jacoco.init.gradle';

function gradleInitScript() {
  return `// injected by improve-java-tests-n8n — applies JaCoCo and forces an XML report
allprojects { p ->
  p.plugins.withId('java') {
    p.apply plugin: 'jacoco'
    p.tasks.withType(Test).configureEach { t -> t.ignoreFailures = true; t.finalizedBy(p.tasks.named('jacocoTestReport')) }
    p.tasks.named('jacocoTestReport').configure { r ->
      r.dependsOn p.tasks.withType(Test)
      r.reports { xml.required = true; html.required = false }
    }
  }
}
`;
}

async function runCoverage() {
  const dir = repo.repoDir();
  const build = state.runner;
  if (!build?.tool) throw new Error('build not detected — call /api/repo/prepare first');
  let r;
  if (build.tool === 'maven') {
    const g = `org.jacoco:jacoco-maven-plugin:${JACOCO_VERSION}`;
    // Only supply an agent when the project has none: two -javaagent entries kill the
    // test JVM outright (`java.lang.instrument ASSERTION FAILED`), which is how every
    // Apache Commons repo failed in the first sweep.
    // when deferring, use the PROJECT's plugin version (bare `jacoco:report` prefix)
    // so the report tool matches the agent that wrote the exec data
    const goals = build.hasJacoco
      ? ['test', 'jacoco:report']
      : [`${g}:prepare-agent`, 'test', `${g}:report`];
    r = await run([build.wrapper, '-B', '-ntp', ...goals,
      '-Dmaven.test.failure.ignore=true', '-DfailIfNoTests=false'],
    { cwd: dir, timeoutMs: 3600000, label: 'jacoco', env: repo.buildEnv() });
    // If deferring to the project produced nothing (its agent sits in an inactive
    // profile, say), fall back to supplying ours.
    if (build.hasJacoco && !findReports(dir).length) {
      event('coverage', 'the project’s own JaCoCo produced no report — retrying with our agent');
      r = await run([build.wrapper, '-B', '-ntp', `${g}:prepare-agent`, 'test', `${g}:report`,
        '-Dmaven.test.failure.ignore=true', '-DfailIfNoTests=false'],
      { cwd: dir, timeoutMs: 3600000, label: 'jacoco', env: repo.buildEnv() });
    }
  } else {
    fs.writeFileSync(path.join(dir, INIT_SCRIPT), gradleInitScript());
    r = await run([build.wrapper, '--no-daemon', '--continue', '-I', INIT_SCRIPT, 'test', 'jacocoTestReport'],
      { cwd: dir, timeoutMs: 3600000, label: 'jacoco', env: repo.buildEnv() });
  }
  const reports = findReports(dir);
  if (!reports.length) {
    throw new Error(`JaCoCo produced no XML report (exit ${r.code}): ` + (r.stderr || r.stdout).slice(-700));
  }
  const merged = { classes: {}, missed: 0, covered: 0 };
  for (const rep of reports) mergeReport(merged, fs.readFileSync(rep, 'utf8'));
  const totalPct = merged.missed + merged.covered > 0
    ? round2((merged.covered * 100) / (merged.covered + merged.missed)) : 0;

  // map class coverage onto the units we track (a unit is one METHOD)
  const byPath = {};
  for (const [rel, f] of Object.entries(state.files)) {
    const fq = f.fqcn || repo.fqcnOf(f.path || rel);
    const c = merged.classes[fq];
    if (c && f.method) {
      // a method unit takes its own counters
      const m = c.methods?.[f.method];
      const executable = m ? m.missed + m.covered : 0;
      const pct = executable > 0 ? round2((m.covered * 100) / executable) : 0;
      byPath[rel] = pct;
      upsertFile(rel, {
        coverage: pct, coveredLines: m?.covered ?? 0, missedLines: m?.missed ?? 0,
        executableLines: executable, methodLine: m?.line ?? null,
      });
    } else if (c) {
      const executable = c.missed + c.covered;
      const pct = executable > 0 ? round2((c.covered * 100) / executable) : 0;
      byPath[rel] = pct;
      upsertFile(rel, { coverage: pct, coveredLines: c.covered, missedLines: c.missed, executableLines: executable });
    } else if (f.coverage == null) {
      // Absent from the report entirely. JaCoCo lists every class it saw on the
      // classpath, so absence means there is no executable code at all — an
      // annotation (@interface), a marker interface, a constants holder. Those read
      // as "0 % covered" and used to sort straight to the top of the pick list, where
      // they consumed a whole run each and could never improve.
      upsertFile(rel, { coverage: 0, executableLines: 0 });
      byPath[rel] = 0;
    }
  }
  event('coverage', `total line coverage ${totalPct}% over ${reports.length} JaCoCo report(s) (build exit ${r.code})`);
  return { totalPct, files: byPath, exitCode: r.code, reports: reports.length, classes: merged.classes };
}

/** Every jacoco XML report under the repo (one per module). */
function findReports(dir) {
  const out = [];
  const walk = (d, depth) => {
    if (depth > 7) return;
    let entries;
    try { entries = fs.readdirSync(d, { withFileTypes: true }); } catch { return; }
    for (const ent of entries) {
      const p = path.join(d, ent.name);
      if (ent.isDirectory()) {
        if (ent.name === '.git' || ent.name === 'node_modules') continue;
        walk(p, depth + 1);
      } else if (/^(jacoco|jacocoTestReport)\.xml$/.test(ent.name)) out.push(p);
    }
  };
  walk(dir, 0);
  return out;
}

/**
 * Pull per-class LINE counters and per-sourcefile line hits out of a JaCoCo XML report.
 * Regex-scanned rather than DOM-parsed: the sidecar is dependency-free by design and the
 * report's shape is fixed and flat.
 */
function mergeReport(acc, xml) {
  // <package name="com/x"> … </package>
  const pkgRe = /<package\s+name="([^"]*)"\s*>([\s\S]*?)<\/package>/g;
  let pm;
  while ((pm = pkgRe.exec(xml))) {
    const pkg = pm[1].split('/').join('.');
    const body = pm[2];
    // <class name="com/x/Foo" sourcefilename="Foo.java"> … <counter type="LINE" …/></class>
    //
    // Split on class BOUNDARIES instead of matching <class>…</class> pairs. JaCoCo emits
    // SELF-CLOSING <class …/> for synthetic classes with no methods (`Foo$1`), and a
    // paired regex treats such a tag as an opening tag: `[\s\S]*?</class>` then runs on
    // and swallows the following classes, stealing their counters. Every class after a
    // self-closing sibling silently reported 0 % coverage. Splitting bounds each
    // fragment at the next class, so a self-closing tag simply carries no counter.
    for (const part of body.split(/<class\s+/).slice(1)) {
      const name = part.match(/^name="([^"]+)"/)?.[1];
      if (!name) continue;
      const end = part.indexOf('</class>');
      if (end === -1) continue;                  // self-closing → no counters to read
      const frag = part.slice(0, end);
      const line = lineCounter(frag);
      if (!line) continue;
      const fq = name.split('/').join('.').split('$')[0];   // fold inner classes into the outer one
      const e = (acc.classes[fq] ||= { missed: 0, covered: 0, lines: {}, methods: {} });
      e.missed += line.missed; e.covered += line.covered;
      // Per-METHOD counters, which JaCoCo already reports. They cost nothing extra and
      // are what makes the method the pipeline's unit of work: every method's line
      // coverage is known before a single mutation run.
      for (const mpart of frag.split(/<method\s+/).slice(1)) {
        const mname = mpart.match(/^name="([^"]+)"/)?.[1];
        if (!mname) continue;
        const mline = parseInt(mpart.match(/\sline="(\d+)"/)?.[1] || '0', 10);
        const mend = mpart.indexOf('</method>');
        const mc = lineCounter(mend === -1 ? mpart : mpart.slice(0, mend));
        if (!mc) continue;
        const key = mname;
        const me = (e.methods[key] ||= { missed: 0, covered: 0, line: mline });
        me.missed += mc.missed; me.covered += mc.covered;
        if (mline && (!me.line || mline < me.line)) me.line = mline;
      }
    }
    // <sourcefile name="Foo.java"> <line nr="7" mi="0" ci="2" .../> …
    const srcRe = /<sourcefile\s+name="([^"]+)"\s*>([\s\S]*?)<\/sourcefile>/g;
    let sm;
    while ((sm = srcRe.exec(body))) {
      const fq = pkg ? `${pkg}.${sm[1].replace(/\.java$/, '')}` : sm[1].replace(/\.java$/, '');
      const e = (acc.classes[fq] ||= { missed: 0, covered: 0, lines: {}, methods: {} });
      const lineRe = /<line\s+nr="(\d+)"\s+mi="(\d+)"\s+ci="(\d+)"/g;
      let lm;
      while ((lm = lineRe.exec(sm[2]))) {
        e.lines[lm[1]] = { mi: parseInt(lm[2], 10), ci: parseInt(lm[3], 10) };
      }
    }
  }
  // report-level totals: the LAST top-level LINE counter belongs to <report>
  const totals = [...xml.matchAll(/<counter\s+type="LINE"\s+missed="(\d+)"\s+covered="(\d+)"\s*\/>/g)];
  if (totals.length) {
    const t = totals[totals.length - 1];
    acc.missed += parseInt(t[1], 10);
    acc.covered += parseInt(t[2], 10);
  }
}

/**
 * The class-level LINE counter — which is the LAST one in the element, not the first.
 * A <class> contains a <method> per method, each with its own counters, and only then
 * its own totals. Reading the first match returned whichever method happened to come
 * first in the file: a class whose first method was uncovered reported 0 % coverage
 * while the repo total said 98 %.
 */
function lineCounter(fragment) {
  const all = [...fragment.matchAll(/<counter\s+type="LINE"\s+missed="(\d+)"\s+covered="(\d+)"\s*\/>/g)];
  if (!all.length) return null;
  const m = all[all.length - 1];
  return { missed: parseInt(m[1], 10), covered: parseInt(m[2], 10) };
}

/** Uncovered lines of one source file, for the coverage-improvement prompt. */
function uncoveredLines(rel) {
  const dir = repo.repoDir();
  const unit = state.files[rel] || {};
  const srcPath = unit.path || String(rel).split('::')[0];
  const fq = unit.fqcn || repo.fqcnOf(srcPath);
  const acc = { classes: {}, missed: 0, covered: 0 };
  for (const rep of findReports(dir)) {
    try { mergeReport(acc, fs.readFileSync(rep, 'utf8')); } catch { }
  }
  const entry = acc.classes[fq];
  if (!entry || !Object.keys(entry.lines).length) {
    return { lines: 'all', note: 'class never executed by any test — 0% coverage' };
  }
  const uncovered = Object.entries(entry.lines)
    .filter(([, v]) => v.ci === 0 && v.mi > 0)
    .map(([nr]) => parseInt(nr, 10))
    .sort((a, b) => a - b);
  return {
    lines: uncovered.slice(0, 200),
    coveredLines: entry.covered,
    missedLines: entry.missed,
  };
}

module.exports = { runCoverage, uncoveredLines, JACOCO_VERSION, mergeReport, lineCounter };
