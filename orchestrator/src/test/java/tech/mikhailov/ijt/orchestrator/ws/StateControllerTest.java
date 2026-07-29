package tech.mikhailov.ijt.orchestrator.ws;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tech.mikhailov.ijt.State;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/// `GET /api/state`: the same bytes the Node sidecar and the Java backend served.
///
/// A standalone MockMvc rather than a `@SpringBootTest`, deliberately. What this route promises
/// is that the body is `State.snapshotJson()` and nothing else has touched it — and a full
/// context would put Boot's ObjectMapper in the room, which is the one thing being ruled out.
class StateControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void fresh(@TempDir Path dir) throws IOException {
        StoreFixture.reset(dir);
        mvc = MockMvcBuilders.standaloneSetup(new StateController()).build();
    }

    @AfterAll
    static void drain() throws IOException {
        StoreFixture.drain();
    }

    /// A run, with the environment stubbed out so the defaults are the documented ones and not
    /// whoever's shell is running the build.
    private static void startRun() {
        State.STATE.run = State.freshRun(State.envConfig(name -> null),
                Map.of("repoUrl", "https://example.test/repo.git", "repoBranch", "main"));
    }

    @Test
    void the_body_is_the_store_serialised_by_the_store() throws Exception {
        startRun();
        // A message with characters outside ASCII: the redaction marker and the arrow both
        // appear in real event lines, and both are two or three bytes to one character. The
        // whole point of encoding in the controller is that they survive.
        State.event("cloning", "https://example.test/repo.git → /work/repo «redacted»");

        MockHttpServletResponse res = mvc.perform(get("/api/state"))
                .andExpect(status().isOk())
                // no charset parameter, exactly as before: the bytes are UTF-8 either way
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                // a cached snapshot is a stale `seq`, and the events between it and the
                // subscription would never be delivered to anyone
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse();

        byte[] expected = State.snapshotJson().getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(expected, res.getContentAsByteArray());
        // Content-Length is the BYTE count, never the character count — the two differ by three
        // in the line above alone, and a wrong one truncates the response mid-JSON.
        assertEquals(String.valueOf(expected.length), res.getHeader("Content-Length"));
    }

    @Test
    void the_field_names_are_the_contract() throws Exception {
        startRun();
        State.setStage("cloning", "https://example.test/repo.git");

        mvc.perform(get("/api/state"))
                .andExpect(jsonPath("$.seq").value(1))
                .andExpect(jsonPath("$.stage.name").value("cloning"))
                .andExpect(jsonPath("$.stage.detail").value("https://example.test/repo.git"))
                // one event line, spelled the way the dashboard's feed reads it
                .andExpect(jsonPath("$.events[0].seq").value(1))
                .andExpect(jsonPath("$.events[0].stage").value("cloning"))
                .andExpect(jsonPath("$.events[0].msg").value("https://example.test/repo.git"))
                .andExpect(jsonPath("$.events[0].ts").isNumber())
                .andExpect(jsonPath("$.run.status").value("running"))
                .andExpect(jsonPath("$.run.config.repoUrl").value("https://example.test/repo.git"))
                .andExpect(jsonPath("$.run.config.prMode").value("github"))
                // the six rule slots stay snake_case: it is what the dashboard form posts and
                // what state.json on disk has always held
                .andExpect(jsonPath("$.run.config.rules.post_clone").exists())
                .andExpect(jsonPath("$.run.config.rules.make_pr").exists())
                // the two ledgers are keys of the snapshot, not of the run: they survive
                // run/start, and every diagnostic in this repo reads them from here
                .andExpect(jsonPath("$.improvedLedger").exists())
                .andExpect(jsonPath("$.measureLedger").exists())
                .andExpect(jsonPath("$.overheadLedger").exists());
    }

    @Test
    void unmeasured_is_not_zero_even_on_the_wire() throws Exception {
        startRun();

        String body = mvc.perform(get("/api/state")).andReturn().getResponse().getContentAsString();

        // A fresh run has measured nothing. `0` here would be a measurement — and a dashboard
        // that prints 0% coverage in bold for a repo nobody has run JaCoCo against is reporting
        // a number that was never taken.
        assertTrue(body.contains("\"coveragePct\":null"), body);
        assertTrue(body.contains("\"mutationPct\":null"), body);
        assertTrue(body.contains("\"mac\":null"), body);
    }

    @Test
    void keys_this_build_does_not_know_survive_the_route() throws Exception {
        // What `Object.assign` gave the JS for free, and what a state.json written by another
        // version of this pipeline carries. The route must not quietly drop it.
        State.STATE.set("somethingNewerWrote", "keep me");

        mvc.perform(get("/api/state"))
                .andExpect(jsonPath("$.somethingNewerWrote").value("keep me"));
    }

    @Test
    void the_snapshot_carries_the_seq_a_subscriber_resumes_from() throws Exception {
        State.event("starting", "one");
        State.event("starting", "two");
        State.event("starting", "three");

        // The client reads `seq`, then keeps every streamed event above it and drops the rest.
        // If this were the count of events in the window rather than the counter, a client that
        // connected after a run/start (which clears the list and leaves the counter alone) would
        // resume from far behind and re-render events it already had.
        mvc.perform(get("/api/state"))
                .andExpect(jsonPath("$.seq").value(3))
                .andExpect(jsonPath("$.events.length()").value(3))
                .andExpect(jsonPath("$.events[2].seq").value(3));
    }
}
