package tech.mikhailov.ijt.orchestrator.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tech.mikhailov.ijt.Server;
import tech.mikhailov.ijt.State;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The adapter, checked against the real route table without invoking a single route.
///
/// The generator had a build-time check that every HTTP node pointed at a route the sidecar
/// actually serves, added after a path typo surfaced as a 404 an hour into a run. Method names
/// cannot be typos, but the route keys inside them still can be — and a route can be renamed out
/// from under this file. This is that check, and it is the reason the keys are asserted rather
/// than the behaviour: the behaviour belongs to `backend`, where 902 tests already cover it.
class ServerBackendTest {

    private final List<String> asked = new ArrayList<>();
    private final List<Map<String, Object>> bodies = new ArrayList<>();

    /// Replaced by the tests that want a failure.
    private Server.Route stub = (query, body) -> {
        bodies.add(body);
        return Map.of("ok", true);
    };

    /// A route table that records what was looked up and serves nothing real.
    private Map<String, Server.Route> recordingTable() {
        return new HashMap<String, Server.Route>() {
            @Override
            public Server.Route get(Object key) {
                asked.add(String.valueOf(key));
                return stub;
            }
        };
    }

    private ServerBackend backend() {
        return new ServerBackend(recordingTable(), new ObjectMapper());
    }

    private void callEverything(ServerBackend backend) {
        backend.cloneRepo();
        backend.applyRules("post_clone", null);
        backend.prepare();
        backend.baselineCoverage();
        backend.candidates();
        backend.startIteration("A.java::one");
        backend.baselineMutation("A.java::one");
        backend.coverageGaps("A.java::one");
        for (Phase phase : Phase.values()) {
            backend.buildPrompt(phase, "A.java::one");
        }
        backend.chat(Map.of("prompt", "hello"));
        backend.parseTests(Map.of(), Map.of());
        backend.writeTests(List.of(), "improving_mutation", "targeting X at line 1", Map.of());
        backend.runTests("improving_mutation");
        backend.buildRepair("A.java::one", "improving_mutation", "boom", List.of());
        backend.parseRepair(Map.of(), Map.of());
        backend.deleteTests(List.of("src/test/java/T1.java"));
        backend.verify("A.java::one");
        backend.acceptRound("A.java::one");
        backend.missRound("A.java::one");
        backend.dropLastRound("A.java::one");
        backend.cleanupTests("A.java::one");
        backend.createPr("A.java::one", "title", "body", List.of("tests"));
        backend.discardChanges("A.java::one", "not approved");
        backend.finishRun();
    }

    @Test
    void every_route_the_job_calls_is_one_the_backend_serves() {
        callEverything(backend());

        Set<String> served = Server.routes().keySet();
        assertEquals(List.of(), asked.stream().filter(key -> !served.contains(key)).toList(),
                "the job calls a route the backend does not serve");
        // 25 calls, 25 distinct routes: both prompt routes, and nothing called twice by accident.
        assertEquals(25, asked.size());
        assertEquals(25, Set.copyOf(asked).size());
    }

    @Test
    void a_missing_route_fails_loudly_instead_of_being_skipped() {
        ServerBackend backend = new ServerBackend(new LinkedHashMap<>(), new ObjectMapper());

        IllegalStateException e = assertThrows(IllegalStateException.class, backend::finishRun);

        assertTrue(e.getMessage().contains("/api/run/finish"), e.getMessage());
    }

    @Test
    void the_repair_write_and_the_re_run_send_no_stage() {
        ServerBackend backend = backend();

        backend.writeTests(List.of(), null, null, null);
        backend.runTests(null);

        // The workflow sends `{tests}` and `{}` here. An explicit null reads identically to an
        // absent key on the other side — both routes test their `stage` for truthiness — and it
        // keeps the request shape uniform. What matters is that the round's target stamp is NOT
        // re-applied by a repair.
        assertNull(bodies.get(0).get("stage"));
        assertNull(bodies.get(0).get("note"));
        assertNull(bodies.get(0).get("targetMutant"));
        assertNull(bodies.get(1).get("stage"));
    }

    @Test
    void the_pr_carries_what_the_make_pr_rule_composed() {
        backend().createPr("A.java::one", "test: improve MAC", "body text", List.of("tests"));

        Map<String, Object> body = bodies.get(0);
        assertEquals("A.java::one", body.get("file"));
        assertEquals("test: improve MAC", body.get("title"));
        assertEquals("body text", body.get("body"));
        assertEquals(List.of("tests"), body.get("labels"));
    }

    @Test
    void the_baseline_runs_are_asked_for_by_phase_and_stage() {
        ServerBackend backend = backend();

        backend.baselineCoverage();
        backend.baselineMutation("A.java::one");

        assertEquals("baseline", bodies.get(0).get("phase"));
        assertEquals("measuring_baseline", bodies.get(0).get("stage"));
        assertEquals("baseline", bodies.get(1).get("phase"));
        assertEquals("improving_mutation", bodies.get(1).get("stage"));
    }

    @Test
    void a_failing_step_marks_the_run_failed_rather_than_leaving_it_in_progress(@TempDir Path dataDir) {
        // The store is a static singleton and writes where DATA_DIR points, so this test does what
        // the backend's own do: its own directory, restored afterwards. These tests must not run
        // in parallel with anything that touches the store.
        Path previousDataDir = State.DATA_DIR;
        State.Run previousRun = State.STATE.run;
        try {
            State.DATA_DIR = dataDir;
            State.Run run = new State.Run();
            run.status = "running";
            State.STATE.run = run;
            stub = (query, body) -> {
                throw new IllegalStateException("mvn died");
            };

            // rethrown, so Batch fails the step and the job instance stays restartable
            assertThrows(IllegalStateException.class, () -> backend().verify("A.java::one"));

            // and the dashboard does not go on showing a run that is no longer happening — the
            // HTTP handler used to do this, and it is not on the path any more
            assertEquals("failed", run.status);
            assertNotNull(run.finishedAt);
            assertEquals("failed", State.STATE.stage.name());
        } finally {
            State.flushNow();   // no pending write can land after DATA_DIR moves back
            State.STATE.run = previousRun;
            State.DATA_DIR = previousDataDir;
        }
    }

    @Test
    void a_failing_read_does_not_kill_the_run(@TempDir Path dataDir) {
        Path previousDataDir = State.DATA_DIR;
        State.Run previousRun = State.STATE.run;
        try {
            State.DATA_DIR = dataDir;
            State.Run run = new State.Run();
            run.status = "running";
            State.STATE.run = run;
            stub = (query, body) -> {
                throw new IllegalStateException("could not read the gaps");
            };

            assertThrows(IllegalStateException.class, () -> backend().candidates());

            // A GET cannot have broken the run by reading it, and the workflow's own handler drew
            // the same line.
            assertEquals("running", run.status);
        } finally {
            State.flushNow();
            State.STATE.run = previousRun;
            State.DATA_DIR = previousDataDir;
        }
    }
}
