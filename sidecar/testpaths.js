'use strict';
// Where a generated test file goes, and what its class is called.
//
// This is not cosmetic. Surefire only runs classes matching its default includes, and a
// generated class named `PricingMacTestCov` compiled fine, was silently skipped by the
// suite, left JaCoCo with nothing to record — while PIT, which selects tests by its own
// glob, ran it anyway and reported 100% mutation on a class with 0% coverage. Green
// suite, confident numbers, all of them false. Hence SUREFIRE_INCLUDED below, asserted
// against every name this module can produce.

/** Surefire/Gradle default test-class includes. */
const SUREFIRE_INCLUDED = [/^Test[A-Z0-9_].*$/, /Test$/, /Tests$/, /TestCase$/];

/** src/main/java/... → src/test/java/..., preserving any module prefix and the package. */
function toTestRoot(srcRel) {
  if (/(^|\/)src\/main\/java\//.test(srcRel)) return srcRel.replace(/(^|\/)src\/main\/java\//, '$1src/test/java/');
  // repos that keep sources elsewhere still get their generated tests in the standard
  // place — never written next to production code
  const pkgPath = srcRel.replace(/^.*?((?:[a-z][\w]*\/)*[A-Z][\w$]*\.java)$/, '$1');
  return `src/test/java/${pkgPath === srcRel ? srcRel.split('/').pop() : pkgPath}`;
}

const baseName = (srcRel) => srcRel.split('/').pop().replace(/\.java$/, '');

/**
 * The two classes this round may write. Each round needs its own names: Java forbids two
 * public classes with the same name, and earlier rounds are already committed.
 */
function generatedTestPaths(srcRel, round = 1) {
  const testRoot = toTestRoot(srcRel);
  const dir = testRoot.includes('/') ? testRoot.slice(0, testRoot.lastIndexOf('/')) : '.';
  const cls = baseName(srcRel);
  const suffix = round > 1 ? `R${round}Test.java` : 'Test.java';
  return {
    covPath: `${dir}/${cls}MacCov${suffix}`,
    mutPath: `${dir}/${cls}MacMut${suffix}`,
  };
}

/** Where the project's OWN test for this class would live, most likely first. */
function existingTestCandidates(srcRel) {
  const testRoot = toTestRoot(srcRel);
  const dir = testRoot.includes('/') ? testRoot.slice(0, testRoot.lastIndexOf('/')) : '.';
  const cls = baseName(srcRel);
  return [`${dir}/${cls}Test.java`, `${dir}/${cls}Tests.java`, `${dir}/Test${cls}.java`, `${dir}/${cls}TestCase.java`];
}

module.exports = { generatedTestPaths, existingTestCandidates, toTestRoot, SUREFIRE_INCLUDED };
