package tech.mikhailov.ijt.orchestrator.batch;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The four decision points inside a phase: Has Work?, Green?, Wrote Any?, Green After Repair?
///
/// The workflow builds this subgraph twice — thirteen nodes each — from one function, so both
/// instances are checked here for the two things that differ: the prompt route and the stage.
class PhaseTest {

    private static final String UNIT = "src/main/java/A.java::one";

    private final FakeBackend backend = new FakeBackend();

    @Test
    void nothing_to_ask_for_costs_one_prompt_build_and_no_model_call() {
        backend.phaseSkip = true;

        Phase.COVERAGE.run(backend, UNIT);

        assertEquals(List.of("buildPrompt(COVERAGE)"), backend.calls);
    }

    @Test
    void a_green_suite_ends_the_phase_without_a_repair() {
        backend.phaseSkip = false;
        backend.green = true;

        Phase.MUTATION.run(backend, UNIT);

        assertEquals(List.of("buildPrompt(MUTATION)", "chat", "parseTests",
                "writeTests(improving_mutation)", "runTests(improving_mutation)"), backend.calls);
    }

    @Test
    void a_red_suite_this_phase_did_not_write_is_not_repaired() {
        backend.phaseSkip = false;
        backend.green = false;
        // Wrote Any? -- no. Repairing here would hand the model somebody else's failure.
        backend.parsedCount = 0;

        Phase.COVERAGE.run(backend, UNIT);

        assertEquals(List.of("buildPrompt(COVERAGE)", "chat", "parseTests",
                "writeTests(improving_coverage)", "runTests(improving_coverage)"), backend.calls);
        assertTrue(backend.deleted.isEmpty());
    }

    @Test
    void a_repair_that_goes_green_keeps_the_tests() {
        backend.phaseSkip = false;
        backend.green = false;
        backend.greenAfterRepair = true;

        Phase.MUTATION.run(backend, UNIT);

        assertEquals(List.of("buildPrompt(MUTATION)", "chat", "parseTests",
                        "writeTests(improving_mutation)", "runTests(improving_mutation)",
                        "buildRepair(improving_mutation)", "chat", "parseRepair",
                        // the repair write carries no stage, no note and no target: it rewrites
                        // files it was handed, and re-stamping the round's target would let a
                        // later round inherit one it never aimed at
                        "writeTests(null)", "runTests(null)"),
                backend.calls);
        assertTrue(backend.deleted.isEmpty());
    }

    @Test
    void a_repair_that_stays_red_deletes_both_the_generated_and_the_repaired_paths() {
        backend.phaseSkip = false;
        backend.green = false;
        backend.greenAfterRepair = false;

        Phase.COVERAGE.run(backend, UNIT);

        assertEquals("deleteTests", backend.calls.get(backend.calls.size() - 1));
        // concatenated, not replaced. The repair may write to paths generation did not, and
        // leaving either half behind leaves a red suite for the next unit to inherit.
        assertEquals(List.of(List.of("src/test/java/T1.java", "src/test/java/T2.java")), backend.deleted);
    }

    @Test
    void the_chosen_mutant_becomes_the_note_and_the_round_target() {
        backend.phaseSkip = false;
        Map<String, Object> chosen = new LinkedHashMap<>();
        chosen.put("mutator", "NEGATE_CONDITIONALS");
        chosen.put("line", 118);
        backend.chosen = chosen;

        Phase.MUTATION.run(backend, UNIT);

        assertEquals("targeting NEGATE_CONDITIONALS at line 118", backend.notes.get(0));
        assertEquals(chosen, backend.targets.get(0));
    }

    @Test
    void a_phase_that_targeted_nothing_writes_no_note_and_no_target() {
        backend.phaseSkip = false;
        backend.chosen = null;

        Phase.COVERAGE.run(backend, UNIT);

        assertNull(backend.notes.get(0));
        assertNull(backend.targets.get(0));
    }

    @Test
    void the_two_phases_differ_only_in_their_route_and_their_stage() {
        // `/api/prompt/batch`, not `/api/prompt/mutation`: one call for every surviving line and
        // then ONE measurement. The per-mutant loop it replaced spent 609 PIT runs across 22
        // classes on a single run, 43% of its wall clock.
        assertEquals("POST /api/prompt/coverage", Phase.COVERAGE.promptRoute());
        assertEquals("POST /api/prompt/batch", Phase.MUTATION.promptRoute());
        assertEquals("improving_coverage", Phase.COVERAGE.stage());
        assertEquals("improving_mutation", Phase.MUTATION.stage());
    }
}
