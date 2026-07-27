'use strict';
// Stop the build publishing a scan of the user's repo to gradle.com on every invocation.
//
// java-dataloader applies `com.gradle.develocity` in settings.gradle with
// `publishing.onlyIf { true }` — a build scan on every build. Two costs, both real:
//
//   the pipeline runs the suite, the coverage build and PIT once per round, so it was
//   sending build data off the machine dozens of times per unit, unasked; and
//
//   in the container the upload times out. One `./gradlew test` sat for 780 seconds with
//   "Publishing Build Scan failed due to network error ... (1 retry remaining)" as its
//   last output, and the first batch spent 94 minutes improving a three-mutant method.
//
// `--no-scan` fixes both — but Gradle rejects it as an unknown command-line option on a
// build that applies no scan plugin, which would fail every invocation on every other
// Gradle repo. So it is passed only where a scan plugin is actually applied.

// The plugin ids, and the extension blocks that configure them. Matched against build
// scripts with comments stripped: a line saying "we do not use buildScan" must not make
// us pass a flag Gradle would then reject.
const SCAN_MARKERS = [
  /\bcom\.gradle\.develocity\b/,
  /\bcom\.gradle\.enterprise\b/,
  /\bcom\.gradle\.build-scan\b/,
  /\bdevelocity\s*\{/,
  /\bgradleEnterprise\s*\{/,
  /\bbuildScan\s*\{/,
];

/** Comments are not configuration. */
const stripComments = (s) => String(s || '')
  .replace(/\/\*[\s\S]*?\*\//g, ' ')
  .replace(/(^|[^:])\/\/[^\n]*/g, '$1');

/**
 * Does any of these build scripts apply a build-scan plugin?
 * @param {Array<string|null|undefined>} scripts  contents of settings.gradle, build.gradle, ...
 */
function appliesBuildScan(scripts) {
  return (scripts || []).some((s) => {
    const code = stripComments(s);
    return SCAN_MARKERS.some((re) => re.test(code));
  });
}

/** The flag, where Gradle will accept it, and nothing where it would not. */
function gradleScanArgs(scripts) {
  return appliesBuildScan(Array.isArray(scripts) ? scripts : [scripts]) ? ['--no-scan'] : [];
}

module.exports = { appliesBuildScan, gradleScanArgs, SCAN_MARKERS };
