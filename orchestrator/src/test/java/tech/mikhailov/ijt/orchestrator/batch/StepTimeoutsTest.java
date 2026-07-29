package tech.mikhailov.ijt.orchestrator.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import tech.mikhailov.ijt.Timeouts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The timeout ordering, which is the one thing this port could lose silently.
///
/// A live run once sat in `improving_mutation / running tests (full suite)` for 6h47m with the
/// dashboard showing it in progress, and 312 of 457 units were never attempted. The cause was two
/// halves of one contract written independently and given the same number: the caller's clock
/// started first, so the caller always lost. The HTTP client is gone; a Batch step's transaction
/// timeout is now the outer clock, and it can lose the same race.
class StepTimeoutsTest {

    /// Where the shared contract lives relative to a module's build directory — the same path
    /// `TimeoutsContractTest` uses in `backend`.
    private static final Path CONFIG = Path.of("..", "config", "timeouts.json");

    private static StepTimeouts loaded() {
        return StepTimeouts.load(null, StepTimeouts.NO_TIMEOUT);
    }

    @Test
    void the_ceilings_come_from_the_shared_config() throws Exception {
        assertTrue(Files.exists(CONFIG), "config/timeouts.json is the shared contract and must exist");
        JsonNode routes = new ObjectMapper().readTree(Files.readString(CONFIG)).get("routes");

        StepTimeouts timeouts = loaded();

        routes.fieldNames().forEachRemaining(route ->
                assertEquals(routes.get(route).get("ceilingMs").asLong(), timeouts.ceilingMs(route), route));
    }

    @Test
    void an_unknown_route_is_refused_rather_than_given_a_default() {
        // An unknown route silently given the generic timeout is how this class of bug returns.
        assertThrows(IllegalArgumentException.class, () -> loaded().ceilingMs("/api/nope"));
    }

    @Test
    void every_step_timeout_exceeds_every_ceiling_it_can_incur() {
        StepTimeouts timeouts = loaded();

        long setup = timeouts.setupStepSeconds() * 1000L;
        assertTrue(setup > timeouts.setupBudgetMs(),
                "setupStep must outlive a clone, a build and two sequential coverage runs");
        for (String route : List.of("/api/test/run", "/api/coverage/run", "/api/pit/run")) {
            assertTrue(setup > timeouts.ceilingMs(route), "setupStep vs " + route);
        }
        assertTrue(timeouts.finishStepSeconds() * 1000L > StepTimeouts.PLAIN_MS);
    }

    @Test
    void a_step_budget_is_a_sum_because_a_step_is_a_subgraph() {
        StepTimeouts timeouts = loaded();

        // n8n gave each NODE its own timeout; a step covers a whole subgraph, so its budget is
        // the sum of the ceilings it can spend — not the largest of them.
        assertEquals(StepTimeouts.CLONE_MS + StepTimeouts.PLAIN_MS + StepTimeouts.PREPARE_MS
                        + timeouts.ceilingMs("/api/coverage/run") + StepTimeouts.PLAIN_MS + StepTimeouts.PLAIN_MS,
                timeouts.setupBudgetMs());
        assertTrue(timeouts.roundBudgetMs() > 2 * timeouts.phaseBudgetMs());
        assertTrue(timeouts.minimumUnitBudgetMs() > timeouts.roundBudgetMs());
    }

    @Test
    void verify_and_cleanup_are_composed_from_the_parts_and_not_copied_from_the_workflow() {
        StepTimeouts timeouts = loaded();

        // Both routes run more than one child process and neither is in config/timeouts.json, so
        // the workflow's hand-written 5 400 000 ms sits BELOW what they may legitimately take:
        // /api/verify runs a coverage build and then PIT, /api/test/cleanup a full suite and then
        // PIT. Inheriting those numbers would carry the original defect into the port.
        assertEquals(timeouts.ceilingMs("/api/coverage/run") + timeouts.ceilingMs("/api/pit/run"),
                timeouts.verifyBudgetMs());
        assertEquals(timeouts.ceilingMs("/api/test/run") + timeouts.ceilingMs("/api/pit/run"),
                timeouts.cleanupBudgetMs());
        assertTrue(timeouts.verifyBudgetMs() > 5_400_000L, "the n8n Verify node was given 5 400 000 ms");
        assertTrue(timeouts.cleanupBudgetMs() > 5_400_000L, "the n8n Cleanup node was given 5 400 000 ms");
    }

    @Test
    void a_configured_improve_step_timeout_below_one_unit_is_refused_at_startup() {
        StepTimeouts reference = loaded();
        int tooTight = (int) (reference.minimumUnitBudgetMs() / 1000);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> StepTimeouts.load(null, tooTight));

        // Failing at startup, not at hour six.
        assertTrue(e.getMessage().contains("improveStep"), e.getMessage());
        assertTrue(e.getMessage().contains("6h47m"), e.getMessage());
    }

    @Test
    void a_configured_improve_step_timeout_above_one_unit_is_accepted() {
        StepTimeouts reference = loaded();
        int roomy = (int) (reference.minimumUnitBudgetMs() / 1000) + 1;

        assertEquals(roomy, StepTimeouts.load(null, roomy).improveStepSeconds());
    }

    @Test
    void no_outer_clock_is_the_default_because_a_unit_is_bounded_by_the_run_not_by_this_file() {
        // MAX_ROUNDS_PER_FILE=0 (uncapped) is what the deployment ships, and UNIT_BUDGET_SEC is
        // per-run and overridable per request. A step clock derived here from either would be a
        // second copy of a contract whose other half moves — which is the original defect exactly.
        assertEquals(StepTimeouts.NO_TIMEOUT, loaded().improveStepSeconds());
        assertEquals(-1, StepTimeouts.NO_TIMEOUT);
    }

    @Test
    void a_missing_config_falls_back_to_the_backend_constants_rather_than_failing_to_start() {
        StepTimeouts fallback = StepTimeouts.load("/nowhere/timeouts.json", StepTimeouts.NO_TIMEOUT);

        assertEquals("tech.mikhailov.ijt.Timeouts", fallback.source());
        assertEquals(Timeouts.HTTP_MARGIN_MS, fallback.marginMs());
        // The same numbers, not a second opinion: TimeoutsContractTest fails the build if the
        // constants and the JSON ever disagree.
        StepTimeouts fromFile = loaded();
        for (String route : Timeouts.SUBPROCESS_CEILING_MS.keySet()) {
            assertEquals(fromFile.ceilingMs(route), fallback.ceilingMs(route), route);
        }
        assertEquals(fromFile.setupStepSeconds(), fallback.setupStepSeconds());
    }

    @Test
    void the_source_is_stated_so_a_run_can_be_asked_which_numbers_it_used() {
        assertNotNull(loaded().source());
        assertTrue(loaded().source().endsWith("timeouts.json"), loaded().source());
    }

    @Test
    void the_transaction_attribute_carries_the_step_clock() {
        StepTimeouts timeouts = loaded();

        assertEquals(timeouts.setupStepSeconds(), timeouts.attribute(timeouts.setupStepSeconds()).getTimeout());
        assertEquals(StepTimeouts.NO_TIMEOUT, timeouts.attribute(StepTimeouts.NO_TIMEOUT).getTimeout());
    }
}
