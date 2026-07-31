package tech.mikhailov.ijt.orchestrator.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The guard has to be CALLED, not merely to exist.
///
/// `OverrideKeysTest` covers `State.unknownOverrideKeys` and passes whatever the route does with
/// it. If the two lines that call it were deleted from `RunController`, every test in this repo
/// would still be green — and `deploy.sh`, whose last smoke check now posts a deliberately
/// invalid body and treats **400 as the pass**, would go back to starting a real run against the
/// real repository on every deploy. That is exactly how the original defect worked.
///
/// This project's signature failure is a component that is correct, tested, and connected to
/// nothing; it has been found six times. Writing a pure function, testing the pure function, and
/// leaving the call site uncovered is how the seventh gets made. So this test asserts on the
/// ROUTE.
///
/// The launcher is deliberately **null**. If the guard runs, it throws before anything touches
/// the launcher. If the guard is ever removed, the route reaches `launcher.launch(body)` and this
/// fails with a NullPointerException instead of the RunFailure it asks for — which is the point:
/// the test cannot pass without the wiring.
class RunControllerGuardTest {

    private static final RunParameters PARAMETERS = new RunParameters(new ObjectMapper());

    private static RunController routeWithNoLauncher() {
        return new RunController(null, PARAMETERS);
    }

    @Test
    void anUnknownKeyIsRefusedBeforeTheLauncherIsEverReached() {
        RunFailure failure = assertThrows(RunFailure.class,
                () -> routeWithNoLauncher().improveRun("{\"dryProbe\":true}"));

        assertEquals(HttpStatus.BAD_REQUEST, failure.status());
        assertTrue(failure.getMessage().contains("dryProbe"),
                "the caller has to be told WHICH key: " + failure.getMessage());
    }

    @Test
    void theDeployProbeIsRefused() {
        // deploy.sh posts exactly this and treats 400 as the passing answer. If this ever starts
        // returning 202, every deploy launches a production run again.
        RunFailure failure = assertThrows(RunFailure.class,
                () -> routeWithNoLauncher().improveRun("{\"deployProbeNotAConfigKey\":true}"));
        assertEquals(HttpStatus.BAD_REQUEST, failure.status());
    }

    @Test
    void everyUnknownKeyIsNamedAtOnce() {
        // one round trip per mistake, not one per deploy
        RunFailure failure = assertThrows(RunFailure.class,
                () -> routeWithNoLauncher().improveRun("{\"dryProbe\":true,\"maxRounds\":3}"));
        assertTrue(failure.getMessage().contains("dryProbe"), failure.getMessage());
        assertTrue(failure.getMessage().contains("maxRounds"), failure.getMessage());
    }

    @Test
    void aVALIDBodyGetsPastTheGuard() {
        // THE negative half, and the one that stops the guard being "reject everything". A body of
        // real config keys must reach the launcher — which is null here, so reaching it is a
        // NullPointerException. That exception IS the assertion: anything else means the guard
        // rejected a legitimate run.
        assertThrows(NullPointerException.class,
                () -> routeWithNoLauncher().improveRun("{\"repoUrl\":\"https://example.invalid/r\",\"force\":true}"));
    }

    @Test
    void anEmptyBodyGetsPastTheGuard() {
        // the manual start posts nothing at all, and must keep working
        assertThrows(NullPointerException.class, () -> routeWithNoLauncher().improveRun(null));
    }
}
