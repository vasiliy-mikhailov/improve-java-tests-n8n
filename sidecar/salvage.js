'use strict';
// Keeping the good tests when one of a batch goes bad.
//
// A batch round writes N tests into one file, and until now one bad assertion cost all N:
// the suite went red, the file was deleted, and the round recorded cov 0→0 mut 0→0. That
// happened on the very first unit of the v2 run — three targets, two asserting wrongly,
// three tests lost.
//
// It does not have to. The file COMPILED; the failures were assertion failures; the runner
// names the methods that failed; and those names are the deterministic target names this
// pipeline chose (targets.js). So the failing methods can be cut out and the rest kept —
// which is only possible because the names were decided up front rather than left to the
// model.

const { stripNonCode } = require('./javasrc');

/**
 * Test method names the runner reported as failing.
 *
 *   Gradle:   `SomeTest > methodName() FAILED`
 *   Surefire: `methodName(pkg.SomeTest)  Time elapsed: 0.01 s  <<< FAILURE!`
 */
function failedTestNames(log) {
  const out = new Set();
  const s = String(log || '');
  for (const m of s.matchAll(/>\s*([A-Za-z_$][\w$]*)\s*\([^)]*\)\s*FAILED/g)) out.add(m[1]);
  for (const m of s.matchAll(/^\s*(?:\[ERROR\]\s*)?([A-Za-z_$][\w$]*)\([\w.$]+\).*<<<\s*(?:FAILURE|ERROR)!/gm)) out.add(m[1]);
  return out;
}

/** Where the method's body ends, counting braces over code only. */
function bodyEnd(src, openIdx) {
  // Braces inside string literals and comments are not structure: a test asserting on "}"
  // would otherwise appear to end halfway through, and the cut would take the rest of the
  // class with it. stripNonCode blanks those out while preserving offsets.
  const code = stripNonCode(src);
  let depth = 0;
  for (let i = openIdx; i < code.length; i += 1) {
    if (code[i] === '{') depth += 1;
    else if (code[i] === '}') {
      depth -= 1;
      if (depth === 0) return i + 1;
    }
  }
  return -1;
}

/**
 * Remove the named test methods, with their annotations, leaving everything else intact.
 * Unknown names are ignored — the caller passes whatever the runner said, and the runner
 * also names tests from other classes.
 */
function dropTestMethods(source, names) {
  let src = String(source || '');
  if (!names || !names.size) return src;
  for (const name of names) {
    const decl = new RegExp(`(^[ \\t]*(?:@[\\w.]+(?:\\([^)]*\\))?[ \\t]*\\r?\\n[ \\t]*)*)`
      + `[\\w<>\\[\\], ]*?\\b${name}\\s*\\([^)]*\\)[^{;]*\\{`, 'm');
    const m = decl.exec(src);
    if (!m) continue;
    const open = src.indexOf('{', m.index + m[0].length - 1);
    const end = bodyEnd(src, open);
    if (end < 0) continue;
    // take the trailing newline too, so removals do not leave a widening gap
    let stop = end;
    while (stop < src.length && (src[stop] === '\n' || src[stop] === '\r')) stop += 1;
    src = src.slice(0, m.index) + src.slice(stop);
  }
  return src;
}

module.exports = { failedTestNames, dropTestMethods, bodyEnd };
