'use strict';
// One contract, one place.
//
// The sidecar decides how long a subprocess may run; the n8n node decides how long to wait
// for the answer. Those were written independently and given the same number, which reads
// as agreement and is in fact a guaranteed loss for the caller: n8n starts its clock when
// the request arrives, the route sets the stage and only then spawns the child, so the
// client always reaches its deadline first. It abandons the request while the sidecar is
// still killing the process group and composing a reply.
//
// Nothing errors. The workflow execution ends, the response goes to a socket nobody reads,
// and the state left behind still names the stage it was in. A live run sat in
// `improving_mutation / running tests (full suite)` for 6h47m and the dashboard showed it
// as in progress the whole time; 312 of 457 units were never attempted.
//
// So the ceilings live here and the workflow generator derives its timeouts from them.
// Adding a subprocess to one of these routes means raising its ceiling HERE, and the node
// follows automatically.

// Per-call limits. These are the numbers the modules themselves pass to run(); importing
// them from here is what keeps the table below honest when one of them changes.
const SUITE_RUN_MS = 3600000;      // tests.js — one full suite
const COVERAGE_RUN_MS = 3600000;   // coverage.js — one JaCoCo build
const PIT_COMPILE_MS = 1800000;    // pit.js — test-compile before PIT
const PIT_RUN_MAX_MS = 3600000;    // pit.js — PIT itself, the upper bound of pitTimeoutMs

/**
 * The longest a route can legitimately spend in child processes, end to end.
 *
 * These are SUMS, not per-call limits — a route that shells out twice in sequence can take
 * both. Getting that wrong is how /api/coverage/run came to allow half of what it needs.
 */
const SUBPROCESS_CEILING_MS = {
  '/api/test/run': SUITE_RUN_MS,
  // The project's own JaCoCo, then — when that produces no report because its agent sits in
  // an inactive profile — a full retry with our agent. Two sequential builds, not one.
  '/api/coverage/run': COVERAGE_RUN_MS * 2,
  // test-compile and then PIT. The compile is not optional: `mvn <plugin>:<goal>` runs no
  // lifecycle phase, so without it PIT finds no classes and reports a score of zero.
  '/api/pit/run': PIT_COMPILE_MS + PIT_RUN_MAX_MS,
};

/**
 * How much longer the caller waits than the work can take.
 *
 * Not a rounding allowance. When a run does hit its ceiling the sidecar still has to kill a
 * process group — Maven and Gradle fork Surefire and PIT minion JVMs — drain what they
 * wrote, parse a report and serialise a reply. Five minutes is generous on purpose: the
 * cost of being too generous is noticing a dead run late, and the cost of being too tight
 * is this defect.
 */
const HTTP_MARGIN_MS = 300000;

/** The timeout an n8n HTTP node must carry to outlive the work it starts. */
function httpTimeoutFor(route) {
  const ceiling = SUBPROCESS_CEILING_MS[route];
  if (ceiling === undefined) {
    // Never fall back to a default. An unknown route silently given the generic timeout is
    // how this class of bug returns; n8n treats a missing timeout as "wait forever", which
    // is the same hang wearing a different hat.
    throw new Error(`unknown route for HTTP timeout: ${route} — add it to SUBPROCESS_CEILING_MS`);
  }
  return ceiling + HTTP_MARGIN_MS;
}

module.exports = {
  SUBPROCESS_CEILING_MS, HTTP_MARGIN_MS, httpTimeoutFor,
  SUITE_RUN_MS, COVERAGE_RUN_MS, PIT_COMPILE_MS, PIT_RUN_MAX_MS,
};
