package tech.mikhailov.ijt.orchestrator.run;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;

/// The webhook body, as job parameters — and back again.
///
/// The n8n trigger passed `$json.body || {}` straight to `POST /api/run/start`, and the body is
/// free-form: `{scopeLimit, maxIterations, force, clearLedger, repoUrl, rules: {...}, ...}`.
/// `State.applyOverrides` decides which keys mean anything, so nothing here interprets them.
///
/// **Why the whole body is ONE parameter and not one parameter per key.** Job parameters are
/// scalars, and the body carries a nested `rules` object; flattening it would need a naming
/// convention, and every value would then exist twice — once flattened for Batch and once in
/// the map the domain layer actually reads. One JSON string has one meaning.
///
/// **Why the JSON is canonical (keys sorted, recursively).** The identifying parameters ARE the
/// job instance. Two triggers carrying the same settings in a different key order would
/// otherwise be two instances, and a crashed run would never be recognised as the same intent —
/// which is the one thing Spring Batch is here for. Sorting makes `{"a":1,"b":2}` and
/// `{"b":2,"a":1}` the same run.
@Component
public class RunParameters {

    /// The request body, canonicalised. Identifying: it is what makes two triggers the same run.
    public static final String OVERRIDES = "overrides";

    /// Wall-clock millis, added ONLY when a new job instance is wanted. Identifying, and the
    /// reason a second run with identical settings is a new instance instead of
    /// `JobInstanceAlreadyCompleteException`.
    public static final String STARTED_AT = "startedAt";

    /// `BATCH_JOB_EXECUTION_PARAMS.PARAMETER_VALUE` is `VARCHAR(2500)` in Spring Batch's own
    /// schema. A body past that is refused here, with the limit named, instead of reaching the
    /// database and failing as a truncation error from inside the launcher — or, on a database
    /// that truncates silently, becoming a run configured with half a rule.
    static final int MAX_OVERRIDES_CHARS = 2500;

    private final ObjectMapper mapper;
    private final ObjectWriter canonical;

    public RunParameters(ObjectMapper mapper) {
        this.mapper = mapper;
        // A writer, never `mapper.configure(...)`: the mapper is the application's shared bean
        // and ordering every map it ever serialises — including the dashboard's payloads, whose
        // key order is the Node sidecar's — is not this class's decision to make.
        this.canonical = mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    /// Parse a request body the way the n8n webhook did: absent, empty and `null` all mean `{}`.
    ///
    /// A POST with no body at all was a legitimate trigger — the manual start sent one — and it
    /// means "run with the environment's configuration". Anything that is not a JSON OBJECT is a
    /// mistake worth naming: `[1,2]` would otherwise become an empty override set and start a
    /// run nobody configured.
    public Map<String, Object> body(String raw) {
        if (raw == null || raw.isBlank()) return new LinkedHashMap<>();
        try {
            Map<String, Object> parsed = mapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (JsonProcessingException e) {
            throw new RunFailure(HttpStatus.BAD_REQUEST,
                    "the run body must be a JSON object: " + e.getOriginalMessage(), e);
        }
    }

    /// The parameters for a NEW run: this body, and the moment it was asked for.
    public JobParameters forNewRun(Map<String, Object> body, long startedAt) {
        return new JobParametersBuilder()
                .addString(OVERRIDES, canonicalJson(body), true)
                .addLong(STARTED_AT, startedAt, true)
                .toJobParameters();
    }

    /// The body a job execution was launched with — what `State.applyOverrides` needs and what a
    /// restart must reuse rather than re-read from a new request.
    ///
    /// Reading it back through the same mapper is what keeps the round trip honest: a restart
    /// resumes with exactly the settings the run started with, not with today's environment.
    public Map<String, Object> overridesOf(JobParameters parameters) {
        String json = parameters == null ? null : parameters.getString(OVERRIDES);
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            Map<String, Object> parsed = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (JsonProcessingException e) {
            // Written by this class, so this cannot happen without the row having been edited by
            // hand — and a run resumed on a body nobody can read is worse than one that stops.
            throw new RunFailure(HttpStatus.INTERNAL_SERVER_ERROR,
                    "job parameter '" + OVERRIDES + "' is not readable JSON: " + e.getOriginalMessage(), e);
        }
    }

    /// The body as one deterministic string. Package-private: it is the identity of a job
    /// instance, and comparing two of them is {@link RunLauncher}'s business, not a caller's.
    String canonicalJson(Map<String, Object> body) {
        String json;
        try {
            json = canonical.writeValueAsString(body == null ? Map.of() : body);
        } catch (JsonProcessingException e) {
            throw new RunFailure(HttpStatus.BAD_REQUEST,
                    "the run body cannot be serialised: " + e.getOriginalMessage(), e);
        }
        if (json.length() > MAX_OVERRIDES_CHARS) {
            throw new RunFailure(HttpStatus.PAYLOAD_TOO_LARGE,
                    "the run body is " + json.length() + " characters and a job parameter holds "
                            + MAX_OVERRIDES_CHARS + " (BATCH_JOB_EXECUTION_PARAMS.PARAMETER_VALUE) — "
                            + "put long team rules in RULES_* environment variables rather than in the trigger");
        }
        return json;
    }
}
