package tech.mikhailov.ijt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tech.mikhailov.ijt.Timeouts.COVERAGE_RUN_MS;
import static tech.mikhailov.ijt.Timeouts.HTTP_MARGIN_MS;
import static tech.mikhailov.ijt.Timeouts.PIT_COMPILE_MS;
import static tech.mikhailov.ijt.Timeouts.PIT_RUN_MAX_MS;
import static tech.mikhailov.ijt.Timeouts.SUBPROCESS_CEILING_MS;
import static tech.mikhailov.ijt.Timeouts.SUITE_RUN_MS;

/// The caller must outlive the work it asked for.
///
/// A live run hung for seven hours and the dashboard went on showing a stage in progress,
/// because the two halves of one contract were written independently and given the SAME
/// number:
///
///   Tests                  subprocess killed after 3_600_000 ms
///   n8n node 'Run Tests'   HTTP request abandoned after 3_600_000 ms
///
/// n8n starts its clock first — the request arrives, the route sets the stage, and only
/// then is the child spawned — so on any run that reaches the ceiling the client gives up
/// while the backend is still killing its child and composing a reply. The workflow
/// execution ends; the backend writes its response to a socket nobody is reading; and the
/// state left behind still names the stage it was in, forever. Nothing reports an error,
/// which is why it looked like a hang rather than a failure.
///
/// `improving_mutation / running tests (full suite)` sat untouched for 6h47m. 312 of 457
/// units were never attempted.
///
/// The invariant: every HTTP node's timeout must exceed the backend's own ceiling for that
/// route, by enough margin to send the answer. Equal is not "just barely safe" — equal is
/// guaranteed to lose, because the client's clock starts earlier.
class TimeoutsTest {

    /// One HTTP node of the generated workflow. `route` and `timeout` are both boxed because
    /// the JSON can leave either out, and a 0 in place of a missing timeout would read as a
    /// declared timeout of zero rather than as "this node declares none".
    private record HttpNode(String name, String route, Long timeout) {}

    // Not `new URI()`: an n8n url may be an expression rather than a literal, e.g.
    // `=http://127.0.0.1:3000/api/files/gaps?path={{ ... }}`, which URI rejects outright.
    private static final Pattern ROUTE = Pattern.compile("https?://[^/]+(/[^?{\\s]*)");

    // Declared AFTER the pattern it uses: static initialisers run in source order, and loading
    // the nodes above ROUTE leaves the pattern null at the moment routeOf() needs it.
    private static final List<HttpNode> NODES = loadNodes();

    private static String routeOf(String url) {
        Matcher m = ROUTE.matcher(url == null ? "" : url);
        if (!m.find()) return null;
        String route = m.group(1);
        return route.isEmpty() ? null : route;
    }

    private static List<HttpNode> loadNodes() {
        Path wf = workflowFile();
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(Files.readString(wf));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + wf, e);
        }
        List<HttpNode> out = new ArrayList<>();
        for (JsonNode n : root.path("nodes")) {
            if (!n.path("type").asText("").contains("httpRequest")) continue;
            JsonNode params = n.path("parameters");
            JsonNode timeout = params.path("options").path("timeout");
            out.add(new HttpNode(
                    n.path("name").asText(""),
                    routeOf(params.path("url").asText("")),
                    timeout.isNumber() ? timeout.asLong() : null));
        }
        return List.copyOf(out);
    }

    /// The workflow is generated at the repo root, not inside the backend module, and Surefire
    /// runs with the module as its working directory. Walking up to whichever ancestor holds it
    /// beats a fixed `../`, which would break the day the module moves — and a broken path here
    /// would retire the whole guard, silently.
    private static Path workflowFile() {
        Path base = Path.of(System.getProperty("basedir", System.getProperty("user.dir"))).toAbsolutePath();
        for (Path d = base; d != null; d = d.getParent()) {
            Path p = d.resolve("n8n/workflows/Improve-Java-Tests.json");
            if (Files.isRegularFile(p)) return p;
        }
        throw new IllegalStateException("n8n/workflows/Improve-Java-Tests.json not found from " + base);
    }

    @Test
    void theGeneratedWorkflowActuallyContainsHttpNodesToCheck() {
        // a guard against the assertions below passing vacuously if the shape ever changes
        assertTrue(NODES.size() > 20, "only found " + NODES.size() + " HTTP nodes");
        assertTrue(NODES.stream().allMatch(n -> n.timeout() != null), "every node must declare a timeout");
    }

    @Test
    void noHttpNodeGivesUpBeforeTheSubprocessItStarts() {
        List<String> bad = NODES.stream()
                .filter(n -> SUBPROCESS_CEILING_MS.get(n.route()) != null)
                // A missing timeout counts as bad here, where the JS comparison against
                // `undefined` was NaN and quietly false. n8n reads no timeout as "wait
                // forever", which is the very hang this file exists to prevent.
                .filter(n -> n.timeout() == null
                        || n.timeout() < SUBPROCESS_CEILING_MS.get(n.route()) + HTTP_MARGIN_MS)
                .map(n -> n.name() + " (" + n.route() + "): http " + n.timeout()
                        + " vs subprocess " + SUBPROCESS_CEILING_MS.get(n.route()))
                .toList();
        assertEquals(List.of(), bad, "these nodes abandon work that is still running:\n  " + String.join("\n  ", bad));
    }

    @Test
    void everyLongRunningRouteIsActuallyCoveredByTheCeilingTable() {
        // The table is the contract. A route that runs a subprocess but is missing from it would
        // silently opt out of the check above — which is exactly how this defect survived.
        for (String route : SUBPROCESS_CEILING_MS.keySet()) {
            assertTrue(NODES.stream().anyMatch(n -> route.equals(n.route())),
                    route + " is in the table but no node calls it");
        }
    }

    @Test
    void theMarginIsBigEnoughToBeWorthHaving() {
        // A 1-second margin would satisfy the check and fix nothing: killing a Gradle process
        // group, draining its output and serialising the reply is not instantaneous.
        assertTrue(HTTP_MARGIN_MS >= 60_000, "margin of " + HTTP_MARGIN_MS + "ms is too tight to matter");
    }

    @Test
    void httpTimeoutForRefusesARouteItDoesNotKnow() {
        // Better to fail at generation than to emit a node with no timeout at all, which n8n
        // treats as "wait forever" — the same hang wearing a different hat.
        Exception e = assertThrows(IllegalArgumentException.class, () -> Timeouts.httpTimeoutFor("/api/not/a/route"));
        assertTrue(e.getMessage().toLowerCase().contains("unknown route"), e.getMessage());
    }

    @Test
    void httpTimeoutForIsTheMarginAppliedToTheCeiling() {
        for (Map.Entry<String, Long> e : SUBPROCESS_CEILING_MS.entrySet()) {
            assertEquals(e.getValue() + HTTP_MARGIN_MS, Timeouts.httpTimeoutFor(e.getKey()), e.getKey());
        }
    }

    // ── what the Java translation could silently change, which the JS never had to say ──

    @Test
    void aCeilingIsEverythingTheRouteSpendsInChildProcessesNotOneCall() {
        // Stated in terms of the per-call constants, so that raising one of them raises the
        // ceiling with it. Hardcoding 7_200_000 here is how /api/coverage/run came to allow half
        // of what it needs: the route runs the project's own JaCoCo and then, when that yields no
        // report, a full retry with our agent — two sequential builds, not one. /api/pit/run is
        // a test-compile and then PIT, likewise sequential.
        assertEquals(SUITE_RUN_MS, SUBPROCESS_CEILING_MS.get("/api/test/run").longValue());
        assertEquals(COVERAGE_RUN_MS * 2, SUBPROCESS_CEILING_MS.get("/api/coverage/run").longValue());
        assertEquals(PIT_COMPILE_MS + PIT_RUN_MAX_MS, SUBPROCESS_CEILING_MS.get("/api/pit/run").longValue());
    }

    @Test
    void theCeilingTableAnswersForARouteItHasNeverHeardOfRatherThanThrowing() {
        // The route is parsed out of a url and may legitimately be null, and every lookup happens
        // before the caller knows the route is in the table. Map.of/Map.copyOf throw
        // NullPointerException on get(null); this map must answer null, as the JS object did —
        // otherwise the check above dies on the first node whose url is an expression it cannot
        // parse, instead of reporting the nodes that are actually wrong.
        assertNull(SUBPROCESS_CEILING_MS.get(null));
        assertNull(SUBPROCESS_CEILING_MS.get("/api/not/a/route"));
    }

    @Test
    void theCeilingTableCannotBeEditedByACaller() {
        // A public static Map is reachable from every route handler. One stray put would move a
        // ceiling at runtime while the generated workflow still carries the number it was
        // generated from — the two halves of the contract disagreeing again, inside one process
        // and with nothing to show for it.
        assertThrows(UnsupportedOperationException.class, () -> SUBPROCESS_CEILING_MS.put("/api/test/run", 1L));
    }

    @Test
    void aRouteIsReadOutOfAnN8nExpressionUrlToo() {
        // The url is not always a literal: a node may compute it, and the leading `=` plus the
        // `{{ }}` placeholders make it something no URI parser will accept. Stopping at `?` and
        // at `{` is what keeps the query string and the expression out of the route.
        assertEquals("/api/files/gaps",
                routeOf("=http://127.0.0.1:3000/api/files/gaps?path={{ encodeURIComponent($json.file) }}"));
        assertEquals("/api/test/run", routeOf("http://127.0.0.1:3000/api/test/run"));
        assertEquals("/api/run/", routeOf("=http://127.0.0.1:3000/api/run/{{ $json.id }}"));
        // a node whose url we cannot read is not a node on some route we happen to know
        assertNull(routeOf("not a url at all"));
        assertNull(routeOf(""));
    }
}
