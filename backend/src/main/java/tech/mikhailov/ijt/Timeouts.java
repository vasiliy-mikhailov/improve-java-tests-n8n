package tech.mikhailov.ijt;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/// One contract, one place.
///
/// The backend decides how long a subprocess may run; the n8n node decides how long to wait
/// for the answer. Those were written independently and given the same number, which reads
/// as agreement and is in fact a guaranteed loss for the caller: n8n starts its clock when
/// the request arrives, the route sets the stage and only then spawns the child, so the
/// client always reaches its deadline first. It abandons the request while the backend is
/// still killing the process group and composing a reply.
///
/// Nothing errors. The workflow execution ends, the response goes to a socket nobody reads,
/// and the state left behind still names the stage it was in. A live run sat in
/// `improving_mutation / running tests (full suite)` for 6h47m and the dashboard showed it
/// as in progress the whole time; 312 of 457 units were never attempted.
///
/// So the ceilings live here and the workflow generator derives its timeouts from them.
/// Adding a subprocess to one of these routes means raising its ceiling HERE, and the node
/// follows automatically.
public final class Timeouts {

    private Timeouts() {}

    // Per-call limits. These are the numbers the modules themselves pass to run(); importing
    // them from here is what keeps the table below honest when one of them changes.
    public static final long SUITE_RUN_MS = 3_600_000L;      // Tests — one full suite
    public static final long COVERAGE_RUN_MS = 3_600_000L;   // Coverage — one JaCoCo build
    public static final long PIT_COMPILE_MS = 1_800_000L;    // Pit — test-compile before PIT
    public static final long PIT_RUN_MAX_MS = 3_600_000L;    // Pit — PIT itself, the upper bound of pitTimeoutMs

    /// The longest a route can legitimately spend in child processes, end to end.
    ///
    /// These are SUMS, not per-call limits — a route that shells out twice in sequence can take
    /// both. Getting that wrong is how /api/coverage/run came to allow half of what it needs.
    public static final Map<String, Long> SUBPROCESS_CEILING_MS;

    static {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("/api/test/run", SUITE_RUN_MS);
        // The project's own JaCoCo, then — when that produces no report because its agent sits in
        // an inactive profile — a full retry with our agent. Two sequential builds, not one.
        m.put("/api/coverage/run", COVERAGE_RUN_MS * 2);
        // test-compile and then PIT. The compile is not optional: `mvn <plugin>:<goal>` runs no
        // lifecycle phase, so without it PIT finds no classes and reports a score of zero.
        m.put("/api/pit/run", PIT_COMPILE_MS + PIT_RUN_MAX_MS);
        // Deliberately NOT Map.copyOf/Map.of: every lookup here happens BEFORE the caller knows
        // the route is in the table, and the route parsed out of an n8n url may be null. An
        // immutable Map throws NullPointerException on get(null) where a LinkedHashMap answers
        // null — which is what the JS object lookup did, and what the checks below rely on.
        SUBPROCESS_CEILING_MS = Collections.unmodifiableMap(m);
    }

    /// How much longer the caller waits than the work can take.
    ///
    /// Not a rounding allowance. When a run does hit its ceiling the backend still has to kill a
    /// process group — Maven and Gradle fork Surefire and PIT minion JVMs — drain what they
    /// wrote, parse a report and serialise a reply. Five minutes is generous on purpose: the
    /// cost of being too generous is noticing a dead run late, and the cost of being too tight
    /// is this defect.
    public static final long HTTP_MARGIN_MS = 300_000L;

    /// The timeout an n8n HTTP node must carry to outlive the work it starts.
    public static long httpTimeoutFor(String route) {
        Long ceiling = SUBPROCESS_CEILING_MS.get(route);
        if (ceiling == null) {
            // Never fall back to a default. An unknown route silently given the generic timeout is
            // how this class of bug returns; n8n treats a missing timeout as "wait forever", which
            // is the same hang wearing a different hat.
            throw new IllegalArgumentException(
                    "unknown route for HTTP timeout: " + route + " — add it to SUBPROCESS_CEILING_MS");
        }
        return ceiling + HTTP_MARGIN_MS;
    }
}
