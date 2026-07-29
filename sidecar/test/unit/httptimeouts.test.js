'use strict';
// The caller must outlive the work it asked for.
//
// A live run hung for seven hours and the dashboard went on showing a stage in progress,
// because the two halves of one contract were written independently and given the SAME
// number:
//
//   tests.js:24            subprocess killed after 3_600_000 ms
//   n8n node 'Run Tests'   HTTP request abandoned after 3_600_000 ms
//
// n8n starts its clock first — the request arrives, the route sets the stage, and only
// then is the child spawned — so on any run that reaches the ceiling the client gives up
// while the sidecar is still killing its child and composing a reply. The workflow
// execution ends; the sidecar writes its response to a socket nobody is reading; and the
// state left behind still names the stage it was in, forever. Nothing reports an error,
// which is why it looked like a hang rather than a failure.
//
// `improving_mutation / running tests (full suite)` sat untouched for 6h47m. 312 of 457
// units were never attempted.
//
// The invariant: every HTTP node's timeout must exceed the sidecar's own ceiling for that
// route, by enough margin to send the answer. Equal is not "just barely safe" — equal is
// guaranteed to lose, because the client's clock starts earlier.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { SUBPROCESS_CEILING_MS, HTTP_MARGIN_MS, httpTimeoutFor } = require('../../timeouts');

const WF = path.join(__dirname, '../../../n8n/workflows/Improve-Java-Tests.json');
const nodes = JSON.parse(fs.readFileSync(WF, 'utf8')).nodes
  .filter((n) => n.type && n.type.includes('httpRequest'))
  .map((n) => ({
    name: n.name,
    // Not `new URL()`: an n8n url may be an expression rather than a literal, e.g.
    // `=http://127.0.0.1:3000/api/files/gaps?path={{ ... }}`, which URL rejects outright.
    route: (String(n.parameters.url).match(/https?:\/\/[^/]+(\/[^?{\s]*)/) || [])[1] || null,
    timeout: n.parameters.options && n.parameters.options.timeout,
  }));

test('the generated workflow actually contains HTTP nodes to check', () => {
  // a guard against the assertions below passing vacuously if the shape ever changes
  assert.ok(nodes.length > 20, `only found ${nodes.length} HTTP nodes`);
  assert.ok(nodes.every((n) => typeof n.timeout === 'number'), 'every node must declare a timeout');
});

test('no HTTP node gives up before the subprocess it starts', () => {
  const bad = nodes
    .filter((n) => SUBPROCESS_CEILING_MS[n.route] !== undefined)
    .filter((n) => n.timeout < SUBPROCESS_CEILING_MS[n.route] + HTTP_MARGIN_MS)
    .map((n) => `${n.name} (${n.route}): http ${n.timeout} vs subprocess ${SUBPROCESS_CEILING_MS[n.route]}`);
  assert.deepEqual(bad, [], 'these nodes abandon work that is still running:\n  ' + bad.join('\n  '));
});

test('every long-running route is actually covered by the ceiling table', () => {
  // The table is the contract. A route that runs a subprocess but is missing from it would
  // silently opt out of the check above — which is exactly how this defect survived.
  for (const route of Object.keys(SUBPROCESS_CEILING_MS)) {
    assert.ok(nodes.some((n) => n.route === route), `${route} is in the table but no node calls it`);
  }
});

test('the margin is big enough to be worth having', () => {
  // A 1-second margin would satisfy the check and fix nothing: killing a Gradle process
  // group, draining its output and serialising the reply is not instantaneous.
  assert.ok(HTTP_MARGIN_MS >= 60000, `margin of ${HTTP_MARGIN_MS}ms is too tight to matter`);
});

test('httpTimeoutFor refuses a route it does not know', () => {
  // Better to fail at generation than to emit a node with `undefined` as its timeout,
  // which n8n treats as "wait forever" — the same hang wearing a different hat.
  assert.throws(() => httpTimeoutFor('/api/not/a/route'), /unknown route/i);
});

test('httpTimeoutFor is the margin applied to the ceiling', () => {
  for (const [route, ceiling] of Object.entries(SUBPROCESS_CEILING_MS)) {
    assert.equal(httpTimeoutFor(route), ceiling + HTTP_MARGIN_MS, route);
  }
});
