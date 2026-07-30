package tech.mikhailov.ijt.orchestrator.ws;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tech.mikhailov.ijt.Server;
import tech.mikhailov.ijt.State;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// The read-only routes that outlive n8n.
///
/// The 44 orchestration routes the workflow used to call are gone on purpose — they are
/// in-process method calls now, which is most of the point of this migration. These four are
/// different: they are consumed from OUTSIDE the process, and dropping them turns a working
/// deployment into a broken one in ways that do not look like a missing route.
///
/// Found by probing a running orchestrator rather than by reading the code, after the audit
/// agents that should have caught them died mid-run:
///
///   GET /api/health   — deploy.sh gates on it (`docker exec ijtspring curl -sf .../api/health`).
///                       A 404 makes the deploy loop spin for two minutes and then fail with
///                       no explanation of what it was waiting for.
///   GET /dashboard/   — Caddy reverse-proxies /dashboard to this port, and deploy.sh smoke
///                       checks it. Served by DashboardConfig, not here.
///   GET /api/events   — how a reconnecting client catches up. WebSocket delivers what happens
///                       WHILE you are attached; this is what fills the gap either side of a
///                       drop, and without it a reconnect silently loses everything in between.
///   GET /api/metrics  — the dashboard's summary poll, and every diagnostic in this repo.
@RestController
public class ApiController {

    /// Liveness. Deliberately cheap and deliberately not `/actuator/health`: the existing
    /// deploy script, the compose healthcheck and the Node sidecar before it all agree on this
    /// path and this body, and a migration is not the moment to renegotiate that.
    @GetMapping(path = "/api/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        // Renamed from "ijt-sidecar" along with everything else. Safe only because every
        // consumer is in this repo — deploy.sh gates on the HTTP status, not this field, and
        // the dashboard is versioned with the backend. An externally-matched string would not
        // have been worth renaming.
        out.put("service", "improve-java-tests");
        out.put("stage", State.STATE.stage.name());
        out.put("ts", System.currentTimeMillis());
        return out;
    }

    /// Everything after `seq`.
    ///
    /// The parameter is the client's high-water mark, not a page number. A reconnecting
    /// dashboard sends the last seq it actually received, which is why seq must keep rising
    /// across runs — reset it and this returns events the client already has while silently
    /// skipping the ones it does not.
    @GetMapping(path = "/api/events", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> events(@RequestParam(name = "after", defaultValue = "0") String after) {
        Integer parsed = State.jsParseInt(after);
        long from = parsed == null ? 0 : parsed;
        List<State.Event> out;
        // under the store's monitor: the batch job writes events from its own thread, and an
        // unsynchronised stream over a growing ArrayList can throw or skip
        synchronized (State.STATE) {
            out = State.STATE.events.stream().filter(e -> e.seq() > from).toList();
        }
        return Map.of("events", out);
    }

    /// The dashboard's summary numbers, computed by the backend that owns them.
    @GetMapping(path = "/api/metrics", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> metrics() {
        // no-store for the same reason /api/state uses it: a cached copy is a stale seq, and a
        // client that trusts it subscribes from a position it never reached
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Server.metricsPayload());
    }
}
