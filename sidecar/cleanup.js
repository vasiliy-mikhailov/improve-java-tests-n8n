'use strict';
// Deciding whether a model's "cleaned" test file may replace the one on disk.
//
// The cleanup pass strips chain-of-thought scratch comments and vacuous tests (D11/D12).
// Its gate was inherited from the JavaScript pipeline and asked whether the result
// contained `it(`, `test(` or `describe(` — shapes no Java test file has. Every cleanup
// on a Java repo was therefore rejected as "implausible cleanup output", silently, so the
// pass has never actually run on the repos this pipeline targets.
//
// The gate below asks Java questions, and each one is a way the pass could damage a PR:
// a file that no longer compiles, tests deleted wholesale, invented tests, or reflection
// smuggled in past the write_test rules.

const TEST_ANNOTATION = /@(?:Test|ParameterizedTest|RepeatedTest|TestFactory)\b/g;
const REFLECTION = /\b(?:setAccessible|getDeclaredMethod|getDeclaredField|Class\.forName|MethodHandles)\b/;

/** The file content out of a raw completion: no thinking block, no markdown fence. */
function extractCleanedFile(text) {
  const body = String(text || '')
    .replace(/^[\s\S]*?<\/think>/, '')
    .replace(/^\s*```[a-z]*\s*\n?/m, '')
    .replace(/```\s*$/m, '')
    .trim();
  return body ? body + '\n' : '';
}

const publicClassOf = (src) => (String(src).match(/public\s+(?:final\s+|abstract\s+)?class\s+(\w+)/) || [])[1] || null;
const countTests = (src) => (String(src).match(TEST_ANNOTATION) || []).length;

/** Names of the annotated test methods — cleanup may drop these, never invent one. */
function testNames(src) {
  const names = new Set();
  const re = /@(?:Test|ParameterizedTest|RepeatedTest|TestFactory)\b[\s\S]{0,400}?\b(\w+)\s*\(/g;
  let m;
  while ((m = re.exec(String(src)))) names.add(m[1]);
  return names;
}

/**
 * @returns {{ok: boolean, reason?: string}}
 */
function plausibleCleanup(original, cleaned) {
  const before = String(original || ''), after = String(cleaned || '');
  if (!after.trim()) return { ok: false, reason: 'empty result' };
  if (after.trim() === before.trim()) return { ok: false, reason: 'no changes' };

  // the class name is the file name; renaming it stops the file compiling
  const wantClass = publicClassOf(before);
  if (wantClass && publicClassOf(after) !== wantClass) {
    return { ok: false, reason: `public class changed (${wantClass} → ${publicClassOf(after) || 'none'})` };
  }
  if (!/\bpackage\s+[\w.]+\s*;/.test(after) && /\bpackage\s+[\w.]+\s*;/.test(before)) {
    return { ok: false, reason: 'package declaration lost' };
  }

  const testsBefore = countTests(before), testsAfter = countTests(after);
  // pruning a vacuous test is the job; pruning ALL of them is a decision to delete the
  // file, which this pass is not allowed to make silently
  if (testsBefore && !testsAfter) return { ok: false, reason: 'result has no @Test methods left' };
  // counting is not enough: dropping one test and inventing another keeps the total.
  // An invented test has never been run against a mutant and earns nothing.
  const known = testNames(before);
  const invented = [...testNames(after)].filter((n) => !known.has(n));
  if (invented.length) return { ok: false, reason: `cleanup added tests it invented (${invented.join(', ')}); it may only remove` };

  if (REFLECTION.test(after) && !REFLECTION.test(before)) {
    return { ok: false, reason: 'cleanup introduced reflection, which the write_test rules forbid' };
  }
  // a truncated completion looks like a small tidy-up; require the result to still be a
  // recognisable file, and never much LONGER than what it was cleaning
  if (after.length < before.length * 0.2) return { ok: false, reason: 'result far too short — likely truncated' };
  if (after.length > before.length * 1.2) return { ok: false, reason: 'result longer than the original — not a cleanup' };

  return { ok: true, testsBefore, testsAfter };
}

/**
 * Is this file worth putting in a pull request?
 *
 * A zero-byte XMLMacMutR2Test.java reached a prepared PR — a new file with an empty blob
 * and no hunks. Dead weight in a deliverable is precisely what D12 forbids, and an empty
 * or test-less class earns nothing wherever it came from.
 */
function worthCommitting(content) {
  const src = String(content || '');
  if (!src.trim()) return false;
  return countTests(src) > 0;
}

module.exports = { extractCleanedFile, plausibleCleanup, worthCommitting };
