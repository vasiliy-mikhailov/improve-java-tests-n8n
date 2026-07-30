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

}
