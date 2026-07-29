package tech.mikhailov.ijt.orchestrator.run;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/// The two routes, at the paths their callers already use.
///
/// A standalone setup rather than `@WebMvcTest`: the point of these assertions is the HTTP
/// contract — what a caller may post and what it gets back — and loading the application's
/// context to check it would make them fail for reasons that have nothing to do with HTTP.
class RunControllerTest {

    private final RunLauncher launcher = mock(RunLauncher.class);
    private final RunParameters parameters = new RunParameters(new ObjectMapper());
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new RunController(launcher, parameters)).build();

    private static final RunLauncher.Launched STARTED =
            new RunLauncher.Launched(11L, 7L, "run-1730000000000", false, false, List.of());

    @Test
    void aTriggerIsAcceptedImmediatelyAndNamesWhatToWatch() throws Exception {
        // n8n answered `onReceived` — a status code in milliseconds and hours of work
        // afterwards. Everything that triggers a run in this repo posts and then polls.
        when(launcher.launch(any())).thenReturn(STARTED);

        mvc.perform(post("/webhook/improve-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopeLimit\":1}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.runId").value("run-1730000000000"))
                .andExpect(jsonPath("$.jobExecutionId").value(11))
                .andExpect(jsonPath("$.jobInstanceId").value(7))
                .andExpect(jsonPath("$.resumed").value(false))
                .andExpect(jsonPath("$.queued").value(false));

        verify(launcher).launch(Map.of("scopeLimit", 1));
    }

    @Test
    void aPostWithNoBodyAtAllIsAValidTrigger() throws Exception {
        // The manual trigger sent one, and it means "run with the environment's configuration".
        // A bound @RequestBody Map would answer 415 to a request with no Content-Type.
        when(launcher.launch(any())).thenReturn(STARTED);

        mvc.perform(post("/webhook/improve-run"))
                .andExpect(status().isAccepted());

        verify(launcher).launch(Map.of());
    }

    @Test
    void aQueuedTakeoverSaysWhatItIsWaitingFor() throws Exception {
        when(launcher.launch(any()))
                .thenReturn(new RunLauncher.Launched(null, null, null, false, true, List.of(5L)));

        mvc.perform(post("/webhook/improve-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"force\":true}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.queued").value(true))
                .andExpect(jsonPath("$.stopping[0]").value(5))
                // there is no execution to watch yet: it starts when the stopped one ends. The
                // key is written as an explicit null rather than dropped — absent and "not yet"
                // are different answers, and the dashboard shows one of them.
                .andExpect(jsonPath("$.jobExecutionId").isEmpty());
    }

    @Test
    void aRefusalArrivesAsTheStatusItCarriesAndASentence() throws Exception {
        // `{ok:false, error}` is the shape the sidecar's routes answer with, and what the
        // dashboard and the diagnostics in this repo already read.
        when(launcher.launch(any()))
                .thenThrow(new RunFailure(HttpStatus.CONFLICT, "a run is already executing"));

        mvc.perform(post("/webhook/improve-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("a run is already executing"));
    }

    @Test
    void aBodyThatIsNotAJsonObjectIsA400() throws Exception {
        mvc.perform(post("/webhook/improve-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    void stopAnswersWithWhatItAskedToStop() throws Exception {
        // What the batch driver used to do with an n8n session cookie and
        // POST /rest/executions/{id}/stop.
        when(launcher.stop(isNull())).thenReturn(List.of(5L));

        mvc.perform(post("/api/run/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.stopped[0]").value(5));
    }

    @Test
    void stopCanNameOneExecution() throws Exception {
        when(launcher.stop(7L)).thenReturn(List.of(7L));

        mvc.perform(post("/api/run/stop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"executionId\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stopped[0]").value(7));

        verify(launcher).stop(7L);
    }
}
