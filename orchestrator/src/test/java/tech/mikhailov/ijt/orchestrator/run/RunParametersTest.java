package tech.mikhailov.ijt.orchestrator.run;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobParameters;
import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The webhook body is the identity of a run, so the way it is written down decides whether a
/// crashed run can ever be recognised again.
class RunParametersTest {

    private final RunParameters parameters = new RunParameters(new ObjectMapper());

    @Test
    void anAbsentBodyIsAnEmptyOverrideSet() {
        // `$json.body || {}`: the manual trigger posted nothing at all and meant "run with the
        // environment's configuration". A 415 or a null map here would break the simplest start
        // there is.
        assertEquals(Map.of(), parameters.body(null));
        assertEquals(Map.of(), parameters.body(""));
        assertEquals(Map.of(), parameters.body("   "));
        assertEquals(Map.of(), parameters.body("null"));
    }

    @Test
    void theBodyIsKeptAsSentAndNotInterpreted() {
        Map<String, Object> body = parameters.body("{\"scopeLimit\":3,\"force\":true,\"rules\":{\"pick_file\":\"x\"}}");
        assertEquals(3, body.get("scopeLimit"));
        assertEquals(Boolean.TRUE, body.get("force"));
        assertEquals(Map.of("pick_file", "x"), body.get("rules"));
    }

    @Test
    void somethingThatIsNotAJsonObjectIsRefusedRatherThanIgnored() {
        // A JSON array would otherwise parse to nothing and start a run nobody configured — with
        // the caller's settings silently dropped.
        RunFailure array = assertThrows(RunFailure.class, () -> parameters.body("[1,2]"));
        assertEquals(HttpStatus.BAD_REQUEST, array.status());

        RunFailure broken = assertThrows(RunFailure.class, () -> parameters.body("{oops"));
        assertEquals(HttpStatus.BAD_REQUEST, broken.status());
    }

    @Test
    void theSameSettingsInADifferentOrderAreTheSameRun() {
        // The identifying parameters ARE the job instance. If key order changed the identity, a
        // crashed run could never be matched to the trigger that would resume it — and resuming
        // at the next unit instead of re-cloning and re-measuring is the reason this is a Batch
        // job at all. Nesting is sorted too: `rules` is an object.
        Map<String, Object> one = parameters.body("{\"b\":1,\"a\":{\"z\":1,\"y\":2}}");
        Map<String, Object> other = parameters.body("{\"a\":{\"y\":2,\"z\":1},\"b\":1}");
        assertEquals(parameters.canonicalJson(one), parameters.canonicalJson(other));
        assertEquals(parameters.forNewRun(one, 7L), parameters.forNewRun(other, 7L));
    }

    @Test
    void aSecondRunWithTheSameSettingsIsANewInstance() {
        // Without `startedAt` the second batch of a full-repo run would ask Batch to restart a
        // COMPLETED instance, which it refuses outright — the driver posts an identical body
        // every batch.
        Map<String, Object> body = parameters.body("{\"scopeLimit\":25}");
        assertNotEquals(parameters.forNewRun(body, 1L), parameters.forNewRun(body, 2L));
    }

    @Test
    void bothParametersIdentifyTheRun() {
        JobParameters params = parameters.forNewRun(parameters.body("{\"scopeLimit\":1}"), 5L);
        assertTrue(params.getParameters().get(RunParameters.OVERRIDES).isIdentifying());
        assertTrue(params.getParameters().get(RunParameters.STARTED_AT).isIdentifying());
        assertEquals(5L, params.getLong(RunParameters.STARTED_AT).longValue());
    }

    @Test
    void theBodyReadsBackOutOfTheParameters() {
        // What a restart resumes with: the settings the run STARTED with, not today's request
        // and not today's environment.
        Map<String, Object> body = parameters.body("{\"scopeLimit\":25,\"rules\":{\"pick_file\":\"weakest\"}}");
        assertEquals(body, parameters.overridesOf(parameters.forNewRun(body, 1L)));
        assertEquals(Map.of(), parameters.overridesOf(new JobParameters()));
        assertEquals(Map.of(), parameters.overridesOf(null));
    }

    @Test
    void aBodyTooLargeForAJobParameterIsRefusedWithTheLimitNamed() {
        // PARAMETER_VALUE is VARCHAR(2500) in Batch's own schema. Left to reach the database this
        // is either an exception from inside the launcher — after the run has been created — or,
        // on a database that truncates quietly, a run configured with half a rule.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rules", Map.of("write_test", "x".repeat(RunParameters.MAX_OVERRIDES_CHARS)));
        RunFailure failure = assertThrows(RunFailure.class, () -> parameters.canonicalJson(body));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, failure.status());
        assertTrue(failure.getMessage().contains(String.valueOf(RunParameters.MAX_OVERRIDES_CHARS)),
                failure.getMessage());
        assertTrue(failure.getMessage().contains("RULES_"), failure.getMessage());
    }
}
