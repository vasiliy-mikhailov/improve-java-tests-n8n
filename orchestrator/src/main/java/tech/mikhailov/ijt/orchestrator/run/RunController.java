package tech.mikhailov.ijt.orchestrator.run;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import tech.mikhailov.ijt.State;

/// The trigger, at the path n8n served it from.
///
/// `POST /webhook/improve-run` is the webhook node, spelling and all: the batch driver, the eval
/// harness and the README all post to that path, and keeping it means only the PORT changes for
/// them (5678 was n8n's, 3000 is this application's, and there is no longer a difference between
/// "the workflow" and "the sidecar").
///
/// `POST /api/run/stop` is new, and it replaces something that used to be done with an n8n
/// session cookie: `eval/full-run.mjs` reads /data/n8n-auth-token.txt, lists running executions
/// and stops each one before triggering the next batch. There is no n8n to ask any more, and a
/// run is a job execution, so stopping one is a route here.
///
/// Both answer immediately. The webhook node ran `responseMode: onReceived` and everything built
/// on it assumes a status code in milliseconds and hours of watching afterwards.
@RestController
public class RunController {

    private static final Logger log = LoggerFactory.getLogger(RunController.class);

    private final RunLauncher launcher;
    private final RunParameters parameters;

    public RunController(RunLauncher launcher, RunParameters parameters) {
        this.launcher = launcher;
        this.parameters = parameters;
    }

    /// Start a run with the body as its configuration overrides.
    ///
    /// The body is taken as TEXT and parsed here rather than bound to a `Map` by the framework,
    /// because the callers are not all polite: the webhook accepted a POST with no body and no
    /// `Content-Type` at all (that was the manual start), and a bound `@RequestBody Map` answers
    /// 415 to it. What the body means is `State.applyOverrides`'s business — see
    /// {@link RunParameters}.
    ///
    /// 202, not 200: the run has been accepted and is not finished, which is what `onReceived`
    /// always meant. Everything in this repo that triggers a run tests for `>= 400`.
    @PostMapping(path = "/webhook/improve-run", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> improveRun(@RequestBody(required = false) String raw) {
        Map<String, Object> body = parameters.body(raw);
        // A key nothing reads is a typo, and this route spends money. `{"dryProbe":true}` — a
        // deploy smoke check meaning to prove the route was reachable — was accepted as a valid
        // configuration and started real runs. See OverrideKeysTest.
        List<String> unknown = State.unknownOverrideKeys(body);
        if (!unknown.isEmpty()) {
            throw new RunFailure(HttpStatus.BAD_REQUEST,
                    "unrecognised key(s) in the run body: " + String.join(", ", unknown)
                            + " — this route starts a real run, so a key nothing reads is refused"
                            + " rather than ignored");
        }
        RunLauncher.Launched launched = launcher.launch(body);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("runId", launched.runId());
        out.put("jobExecutionId", launched.jobExecutionId());
        out.put("jobInstanceId", launched.jobInstanceId());
        // a resumed run skipped the setup: no clone, no rebuild, no re-measured baseline
        out.put("resumed", launched.resumed());
        // queued = the previous run was asked to stop and this launch happens when it ends, so
        // there is no execution id yet. The ids it is waiting for are named.
        out.put("queued", launched.queued());
        out.put("stopping", launched.stopping());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(out);
    }

    /// Ask the running run to stop. Idempotent: nothing running is an empty list, not an error.
    ///
    /// The stop lands at a UNIT boundary — see {@link RunLauncher#stop(Long)} — so a caller that
    /// wants to start something else should wait for the run to end rather than assume it has.
    @PostMapping(path = "/api/run/stop", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> stop(@RequestBody(required = false) String raw) {
        Map<String, Object> body = parameters.body(raw);
        Object id = body.get("executionId");
        Long executionId = id instanceof Number n ? n.longValue() : null;
        List<Long> stopped = launcher.stop(executionId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("stopped", stopped);
        return ResponseEntity.ok(out);
    }

    /// A refusal, as the status it carries and a sentence a person can act on.
    ///
    /// The same shape the sidecar's routes answer with (`{ok: false, error}`), because the
    /// dashboard and the diagnostics in this repo already read that shape, and because "a run is
    /// already active" has to arrive as a 409 with a reason rather than as a stack trace.
    @ExceptionHandler(RunFailure.class)
    public ResponseEntity<Map<String, Object>> refused(RunFailure failure) {
        log.warn("{} {}", failure.status().value(), failure.getMessage());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", failure.getMessage());
        return ResponseEntity.status(failure.status()).body(out);
    }
}
