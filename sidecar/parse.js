'use strict';
// Turning a model answer into files on disk. Everything here exists because the model is
// free-form and the file system is not: a Java file must sit at the path its package and
// class name dictate, and a wrong path either fails to compile or overwrites the team's
// own tests.

const MIN_TEST_BYTES = 20;

const usable = (t) => t && typeof t.content === 'string' && t.content.trim().length > MIN_TEST_BYTES;

/**
 * The round writes exactly one file, at the path the prompt demanded. Anything else is
 * redirected there.
 *
 * The old check merely required the path to be inside src/test/java and not equal to the
 * one existing test it knew about — so the model could name any OTHER real test file in
 * the repo and have it overwritten with generated content. If the suite then went red,
 * the workflow's Delete Broken Tests step removed the team's file from the repo.
 */
function safePath(raw, plan) {
  const p = typeof raw === 'string' ? raw.replace(/^\.?\//, '') : '';
  return p === plan.targetPath ? p : plan.targetPath;
}

/** javac refuses a public class whose name differs from the file name. */
function alignClassName(content, path) {
  const want = path.split('/').pop().replace(/\.java$/, '');
  const declared = (content.match(/public\s+(?:final\s+|abstract\s+)?class\s+(\w+)/) || [])[1];
  if (!declared || declared === want) return content;
  return content.replace(new RegExp('\\b' + declared + '\\b', 'g'), want);
}

/**
 * @param {{ok?:boolean, json?:{tests?:Array}}} resp  raw /api/llm/chat result
 * @param {{targetPath:string, projectTestPath?:string, offered?:Array}} plan
 */
function parseGeneratedTests(resp, plan) {
  const raw = resp && resp.ok && resp.json && Array.isArray(resp.json.tests) ? resp.json.tests : [];
  // One file per round. The prompt asks for one; more than one means the model drifted,
  // and a second file only widens what has to compile.
  const tests = raw.filter(usable).slice(0, 1).map((t) => {
    const path = safePath(t.path, plan);
    return { path, content: alignClassName(t.content, path) };
  });
  const first = (plan.offered || [])[0];
  return {
    tests,
    paths: tests.map((t) => t.path),
    count: tests.length,
    // Carried so the kill check knows which mutant this round was aiming at — read from
    // THIS phase's plan, not from a sibling phase's node.
    //
    // Only when the plan really did aim at one. A batch round offers `{marker, line,
    // method}` for every surviving line, and reading offered[0] out of that produced
    // `{line: 78, mutator: undefined}`: the workflow printed "targeting undefined at line
    // 78", and the kill check — which matches on mutator AND line — could never match
    // anything, so no batch round could report a kill. A round aimed at many lines is
    // aimed at no single mutant, and roundOutcome already handles being told that.
    chosen: first && first.mutator ? { line: first.line, mutator: first.mutator } : null,
  };
}

/** A repair rewrites the same files; a new path here would leave the broken one on disk. */
function parseRepairedTests(resp, prev) {
  const raw = resp && resp.ok && resp.json && Array.isArray(resp.json.tests) ? resp.json.tests : [];
  const paths = (prev && prev.paths) || [];
  const tests = raw.filter(usable).slice(0, paths.length)
    .map((t, i) => ({ path: paths[i], content: alignClassName(t.content, paths[i]) }))
    .filter((t) => t.path);
  return { tests, paths: tests.map((t) => t.path), count: tests.length };
}

module.exports = { parseGeneratedTests, parseRepairedTests };
