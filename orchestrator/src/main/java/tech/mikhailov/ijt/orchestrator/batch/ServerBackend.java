package tech.mikhailov.ijt.orchestrator.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import org.springframework.stereotype.Component;

import tech.mikhailov.ijt.Server;
import tech.mikhailov.ijt.State;
import tech.mikhailov.ijt.Util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// {@link Backend}, in process.
///
/// Every method here is one entry of {@link Server#routes()} invoked directly. No socket, no
/// serialisation of the request, no client timeout — which is the whole point of the move: the
/// timeout contract between an HTTP client and the subprocess it waited on cost a live run 6h47m,
/// and there is no longer a client to lose that race. (What replaces it is a Batch step's
/// transaction timeout — see {@link StepTimeouts}.)
///
/// **Why the route table and not the domain classes.** The route bodies are where the per-call
/// rules live, they are package-private to `tech.mikhailov.ijt`, and each of them records a
/// production defect: the PIT route refunds the iteration a no-mutants class charged, writes
/// `unmeasured` without inventing a zero, and appends to the improvement ledger; `/api/verify` is
/// a hundred lines of round adjudication. Calling `Pit.runPit` from here would reimplement all of
/// it, badly and separately. `Server.routes()` is public precisely so an embedding process can do
/// this.
///
/// **The one thing this reimplements on purpose** is the error handling of `Server#handle`. In
/// n8n a failing node aborted the execution, and the backend — in its HTTP handler — marked the
/// run failed so the dashboard did not go on showing it as in progress. That handler is not on
/// this path any more, so the same two lines happen here, and then the exception is rethrown:
/// Batch fails the step, the job goes to FAILED, and the job instance stays restartable. The 409
/// concurrency guard `handle` distinguishes cannot arise here — it belongs to `POST
/// /api/run/start`, which this job never calls.
@Component
public final class ServerBackend implements Backend {

    private final Map<String, Server.Route> routes;
    private final ObjectMapper mapper;

    public ServerBackend() {
        this(Server.routes(), new ObjectMapper());
    }

    ServerBackend(Map<String, Server.Route> routes, ObjectMapper mapper) {
        this.routes = routes;
        this.mapper = mapper;
    }

    @Override
    public Map<String, Object> cloneRepo() {
        return post("POST /api/repo/clone", body());
    }

    @Override
    public Map<String, Object> applyRules(String stage, Map<String, Object> context) {
        return post("POST /api/rules/apply", body("stage", stage, "context", context));
    }

    @Override
    public Map<String, Object> prepare() {
        return post("POST /api/repo/prepare", body());
    }

    @Override
    public Map<String, Object> baselineCoverage() {
        return post("POST /api/coverage/run", body("phase", "baseline", "stage", "measuring_baseline"));
    }

    @Override
    public Map<String, Object> candidates() {
        return get("GET /api/files/candidates", Server.Query.EMPTY);
    }

    @Override
    public Map<String, Object> startIteration(String unit) {
        return post("POST /api/iteration/start", body("file", unit));
    }

    @Override
    public Map<String, Object> baselineMutation(String unit) {
        return post("POST /api/pit/run", body("file", unit, "phase", "baseline", "stage", "improving_mutation"));
    }

    @Override
    public Map<String, Object> coverageGaps(String unit) {
        // The node builds `?path={{ encodeURIComponent(...) }}`; the query object is what that URL
        // parses back into, so it is built directly and no unit key is ever percent-encoded and
        // decoded to reach a method call in the same process.
        return get("GET /api/files/gaps", new Server.Query(Map.of("path", List.of(unit))));
    }

    @Override
    public Map<String, Object> buildPrompt(Phase phase, String unit) {
        return post(phase.promptRoute(), body("path", unit));
    }

    @Override
    public Map<String, Object> chat(Map<String, Object> ask) {
        // `$json` — the whole prompt object, `system`/`prompt`/`maxTokens`/`json`/`stage` and all.
        return post("POST /api/llm/chat", ask == null ? body() : new LinkedHashMap<>(ask));
    }

    @Override
    public Map<String, Object> parseTests(Map<String, Object> answer, Map<String, Object> plan) {
        return post("POST /api/tests/parse", body("resp", answer, "plan", plan));
    }

    @Override
    public Map<String, Object> writeTests(Object tests, String stage, String note, Object targetMutant) {
        return post("POST /api/test/write-many",
                body("tests", tests, "stage", stage, "note", note, "targetMutant", targetMutant));
    }

    @Override
    public Map<String, Object> runTests(String stage) {
        return post("POST /api/test/run", body("stage", stage));
    }

    @Override
    public Map<String, Object> buildRepair(String unit, String stage, Object summary, Object tests) {
        return post("POST /api/prompt/repair",
                body("path", unit, "stage", stage, "summary", summary, "tests", tests));
    }

    @Override
    public Map<String, Object> parseRepair(Map<String, Object> answer, Map<String, Object> previous) {
        return post("POST /api/tests/parse-repair", body("resp", answer, "prev", previous));
    }

    @Override
    public Map<String, Object> deleteTests(List<String> paths) {
        return post("POST /api/test/delete-many", body("paths", paths));
    }

    @Override
    public Map<String, Object> verify(String unit) {
        return post("POST /api/verify", body("file", unit));
    }

    @Override
    public Map<String, Object> acceptRound(String unit) {
        return post("POST /api/round/accept", body("file", unit));
    }

    @Override
    public Map<String, Object> missRound(String unit) {
        return post("POST /api/round/miss", body("file", unit));
    }

    @Override
    public Map<String, Object> dropLastRound(String unit) {
        return post("POST /api/round/drop", body("file", unit));
    }

    @Override
    public Map<String, Object> cleanupTests(String unit) {
        return post("POST /api/test/cleanup", body("file", unit));
    }

    @Override
    public Map<String, Object> createPr(String unit, Object title, Object prBody, Object labels) {
        return post("POST /api/pr/create", body("file", unit, "title", title, "body", prBody, "labels", labels));
    }

    @Override
    public Map<String, Object> discardChanges(String unit, String reason) {
        return post("POST /api/iteration/discard", body("file", unit, "reason", reason));
    }

    @Override
    public Map<String, Object> finishRun() {
        return post("POST /api/run/finish", body());
    }

    // ── dispatch ──────────────────────────────────────────────────────────────

    private Map<String, Object> get(String key, Server.Query query) {
        return invoke(key, query, new LinkedHashMap<>());
    }

    private Map<String, Object> post(String key, Map<String, Object> body) {
        return invoke(key, Server.Query.EMPTY, body);
    }

    private Map<String, Object> invoke(String key, Server.Query query, Map<String, Object> body) {
        Server.Route route = routes.get(key);
        if (route == null) {
            // The generator had a build-time check that every node pointed at a real route, after
            // a path typo surfaced as a 404 an hour into a run. This is that check, moved to the
            // first call rather than the build — a compiled method name cannot be a typo, but a
            // route can still be renamed out from under it.
            throw new IllegalStateException("no such backend route: " + key);
        }
        try {
            return asMap(route.handle(query, body));
        } catch (Exception e) {
            failRun(key, e);
            throw e instanceof RuntimeException runtime ? runtime : new IllegalStateException(message(e), e);
        }
    }

    /// What `Server#handle` did when a route threw during a POST: say so in the event log, and
    /// mark the run failed rather than leaving the dashboard showing a run that is no longer
    /// happening. A GET does not — reading state cannot have broken it.
    private void failRun(String key, Exception e) {
        State.event("error", key + ": " + message(e));
        if (!key.startsWith("POST ")) {
            return;
        }
        State.Run run = State.STATE.run;
        if (run == null || !"running".equals(run.status)) {
            return;
        }
        run.status = "failed";
        run.finishedAt = Util.nowSec();
        String detail = Util.redact(message(e));
        State.setStage("failed", detail.length() <= 200 ? detail : detail.substring(0, 200));
    }

    /// A route answers with a record, a map, or a map of records. The n8n node read whatever
    /// Jackson made of it, and so does the caller here — one conversion, so a field name that
    /// reaches a branch is the field name the workflow tested.
    private Map<String, Object> asMap(Object answer) {
        if (answer == null) {
            return new LinkedHashMap<>();
        }
        return mapper.convertValue(answer, TypeFactory.defaultInstance()
                .constructMapType(LinkedHashMap.class, String.class, Object.class));
    }

    /// A request body. `LinkedHashMap` and not `Map.of`, which rejects nulls — and half of these
    /// values are deliberately null: a repair write sends no stage, a re-run sends no body at all,
    /// and a rules stage with no context sends `{}`.
    private static Map<String, Object> body(Object... keysAndValues) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            out.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return out;
    }

    private static String message(Throwable e) {
        String m = e.getMessage();
        return (m == null || m.isEmpty()) ? e.toString() : m;
    }
}
