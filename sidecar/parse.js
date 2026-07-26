'use strict';
// Turning a model answer into files on disk. Everything here exists because the model is
// free-form and the file system is not: a Java file must sit at the path its package and
// class name dictate, and a wrong path either fails to compile or overwrites the team's
// own tests.

const MIN_TEST_BYTES = 20;

const usable = (t) => t && typeof t.content === 'string' && t.content.trim().length > MIN_TEST_BYTES;

/** A generated file may only land inside the test tree, and never on the project's own test. */
function safePath(raw, plan) {
  const p = typeof raw === 'string' ? raw.replace(/^\.?\//, '') : '';
  const inTestTree = /(^|\/)src\/test\/java\/.+\.java$/.test(p) && !p.includes('..');
  return !inTestTree || p === plan.projectTestPath ? plan.targetPath : p;
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
    // carried so the kill check knows which mutant this round was aiming at —
    // read from THIS phase's plan, not from a sibling phase's node
    chosen: first ? { line: first.line, mutator: first.mutator } : null,
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
