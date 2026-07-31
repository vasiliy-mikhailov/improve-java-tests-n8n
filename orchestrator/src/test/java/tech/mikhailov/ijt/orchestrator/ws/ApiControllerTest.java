package tech.mikhailov.ijt.orchestrator.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.mikhailov.ijt.State;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// The routes consumed from OUTSIDE the process.
///
/// Every one of these was missing after the migration and was found by probing a running
/// orchestrator, not by reading code — the build was green and all 1,025 tests passed while
/// `deploy.sh` would have hung on a 404 from /api/health and then failed. These tests exist so
/// the next person deletes them on purpose rather than by omission.
class ApiControllerTest {

    private final ApiController api = new ApiController();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void reset() {
        synchronized (State.STATE) {
            State.STATE.events.clear();
            State.STATE.seq = 0;
        }
    }

    @Test
    void healthAnswersTheShapeDeployScriptAndDashboardMatchOn() {
        // deploy.sh gates the whole deploy on `curl -sf .../api/health` — it checks the status,
        // not this body. The `service` field is read by the dashboard, which ships with the
        // backend, which is why renaming it from "ijt-sidecar" was safe.
        Map<String, Object> h = api.health();
        assertEquals(Boolean.TRUE, h.get("ok"));
        assertEquals("improve-java-tests", h.get("service"));
        assertNotNull(h.get("stage"));
        assertInstanceOf(Long.class, h.get("ts"));
    }

    @Test
    void healthReportsTheCurrentStageNotAConstant() {
        State.setStage("improving_mutation", "mutation testing Foo#bar()");
        assertEquals("improving_mutation", api.health().get("stage"));
    }

    @Test
    void eventsAfterZeroReturnsEverything() {
        State.event("a", "one");
        State.event("b", "two");
        assertEquals(2, events(api.events("0")));
    }

    @Test
    void eventsAfterASeqReturnsOnlyWhatCameLater() {
        // The parameter is a client's high-water mark, not a page offset. This is the whole
        // reconnect contract: send the last seq you actually received, get the gap back.
        State.event("a", "one");
        long mark = State.STATE.seq;
        State.event("b", "two");
        State.event("c", "three");
        assertEquals(2, events(api.events(String.valueOf(mark))));
    }

    @Test
    void aClientThatIsUpToDateGetsNothingRatherThanEverything() {
        State.event("a", "one");
        assertEquals(0, events(api.events(String.valueOf(State.STATE.seq))));
    }

    @Test
    void aMissingOrJunkAfterParameterMeansFromTheBeginning() {
        // A dashboard on a cold load sends no parameter at all; a corrupted one must not be read
        // as a huge number, which would return nothing and look like a dead feed.
        State.event("a", "one");
        assertEquals(1, events(api.events("0")));
        assertEquals(1, events(api.events("not-a-number")));
    }

    @Test
    void metricsAreServedWithoutCaching() {
        var r = api.metrics();
        assertEquals(200, r.getStatusCode().value());
        assertNotNull(r.getBody());
        // a cached metrics payload is a stale seq, and a client that trusts it subscribes from a
        // position it never reached
        assertEquals("no-store", r.getHeaders().getFirst("Cache-Control"));
    }

    @Test
    void metricsComeFromTheBackendRatherThanASecondImplementation() {
        // Server.metricsPayload() was made public rather than reimplemented here. Two payloads
        // agree only until someone edits one of them.
        assertEquals(tech.mikhailov.ijt.Server.metricsPayload().keySet(),
                api.metrics().getBody().keySet());
    }

    // ── the feedback file, served from OUTSIDE the process ────────────────
    //
    // These two were first added to `Server.routes()`, which is where every other route in this
    // pipeline lives. That table is reached in-process by Phase and nothing serves it over HTTP,
    // so the deployed container answered 404 to both while the whole suite passed — the route
    // inventory test asserts the map holds the key, not that anyone serves it. Found by curling
    // the container after deploying, which is how the four routes above were found too.
    //
    // So these assert on the CONTROLLER, which is the thing Spring maps.

    @Test
    void theFeedbackFileIsReadableOverHttp() {
        Map<String, Object> out = api.feedback();
        assertEquals(Boolean.TRUE, out.get("ok"));
        // no run configured in this test: an empty answer, not an exception. The dashboard asks
        // on load, before anyone has started anything.
        assertNotNull(out.get("guidance"));
        assertNotNull(out.get("records"));
    }

    @Test
    void feedbackWithNoTextIsRefusedAsFourHundred() {
        // a 500 here would read as "the pipeline is broken" for what is a caller's mistake
        var r = api.addFeedback(Map.of("apply", true));
        assertEquals(400, r.getStatusCode().value());
        assertEquals(false, r.getBody().get("ok"));
    }

    @Test
    void anEmptyBodyIsAlsoFourHundredRatherThanACrash() {
        assertEquals(400, api.addFeedback(null).getStatusCode().value());
    }

    private static int events(Map<String, Object> body) {
        JsonNode n = MAPPER.valueToTree(body).get("events");
        return n == null ? -1 : n.size();
    }
}
