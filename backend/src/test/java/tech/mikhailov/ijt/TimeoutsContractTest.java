package tech.mikhailov.ijt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// The timeout contract spans two languages, so something has to check it.
///
/// The backend is Java; n8n/generate-workflows.mjs, which sets each HTTP node's timeout, is
/// JavaScript and stays that way. Neither can import the other, and re-typing the numbers in
/// both is precisely the defect that cost a live run 6h47m — two halves of one contract,
/// written independently, given the same value, and the caller always loses that race.
///
/// So config/timeouts.json is the shared source and the generator reads it directly. Java
/// keeps compile-time constants rather than parsing JSON at boot — a backend that fails to
/// start because a config file moved is a worse failure than a drifted number — and this test
/// is what makes that safe. If the two ever disagree, this fails.
class TimeoutsContractTest {

    private static final Path CONFIG = Path.of("..", "config", "timeouts.json");

    private static JsonNode config() throws Exception {
        assertTrue(Files.exists(CONFIG), "config/timeouts.json is the shared contract and must exist");
        return new ObjectMapper().readTree(Files.readString(CONFIG));
    }

    @Test
    void everyPerCallLimitMatchesTheSharedConfig() throws Exception {
        JsonNode perCall = config().get("perCallMs");
        assertEquals(Timeouts.SUITE_RUN_MS, perCall.get("suiteRun").asLong());
        assertEquals(Timeouts.COVERAGE_RUN_MS, perCall.get("coverageRun").asLong());
        assertEquals(Timeouts.PIT_COMPILE_MS, perCall.get("pitCompile").asLong());
        assertEquals(Timeouts.PIT_RUN_MAX_MS, perCall.get("pitRunMax").asLong());
    }

    @Test
    void everyRouteCeilingMatchesTheSharedConfig() throws Exception {
        JsonNode routes = config().get("routes");
        routes.fieldNames().forEachRemaining(route -> {
            Long java = Timeouts.SUBPROCESS_CEILING_MS.get(route);
            assertNotNull(java, route + " is in config/timeouts.json but not in Timeouts.java");
            assertEquals(routes.get(route).get("ceilingMs").asLong(), java.longValue(), route);
        });
        assertEquals(Timeouts.SUBPROCESS_CEILING_MS.size(), routes.size(),
                "Timeouts.java knows a route the shared config does not, or vice versa");
    }

    @Test
    void theHttpMarginMatchesTheSharedConfig() throws Exception {
        assertEquals(Timeouts.HTTP_MARGIN_MS, config().get("httpMarginMs").asLong());
    }

    @Test
    void eachCeilingIsActuallyTheSumOfThePartsItClaims() throws Exception {
        // A ceiling is a SUM over a route's child processes. Reading it as a per-call limit is
        // how /api/coverage/run came to allow half of what it needs — it runs the project's own
        // JaCoCo and then, when that yields no report, a full retry with our agent.
        JsonNode cfg = config();
        JsonNode perCall = cfg.get("perCallMs");
        JsonNode routes = cfg.get("routes");
        routes.fieldNames().forEachRemaining(route -> {
            long sum = 0;
            for (JsonNode part : routes.get(route).get("composedOf")) {
                JsonNode v = perCall.get(part.asText());
                assertNotNull(v, route + " is composed of an unknown part: " + part.asText());
                sum += v.asLong();
            }
            assertEquals(routes.get(route).get("ceilingMs").asLong(), sum,
                    route + " claims a ceiling that is not the sum of " + routes.get(route).get("composedOf"));
        });
    }

    @Test
    void theMarginIsBigEnoughToBeWorthHaving() throws Exception {
        // A one-second margin would satisfy every check above and fix nothing: killing a Gradle
        // process group, draining its output and serialising the reply is not instantaneous.
        assertTrue(config().get("httpMarginMs").asLong() >= 60_000,
                "a margin under a minute is too tight to matter");
    }
}
