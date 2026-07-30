package tech.mikhailov.ijt;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// A key nobody reads is a typo, and this one started a production run.
///
/// `deploy.sh` ended with a smoke check that POSTed `{"dryProbe":true}` to `/webhook/improve-run`
/// to prove the route was reachable. There is no such thing as `dryProbe` — nothing in the
/// pipeline has ever read that key. `applyOverrides` reads the fields it knows and silently
/// drops the rest, so the body was accepted as a valid configuration and a real run started
/// against the real repository, on every deploy, with the model and the token budget attached.
///
/// It went unnoticed because it usually 409'd: a run was already active, so the launcher refused
/// and the smoke check printed a number nobody read as a launch attempt. The one deploy where
/// the previous run had just been marked interrupted, it succeeded — and reset `state.files`
/// and `state.prs` out from under a seven-hour run.
///
/// So an unrecognised key is now a 400 rather than a silently ignored one. That is the right
/// default for a route that spends money: a caller who misspells `maxRoundsPerFile` should be
/// told, not handed a run with the default silently in place.
class OverrideKeysTest {

    @Test
    void anUnknownKeyIsRejected() {
        List<String> unknown = State.unknownOverrideKeys(Map.of("dryProbe", true));
        assertEquals(List.of("dryProbe"), unknown);
    }

    @Test
    void everyConfigFieldIsAccepted() {
        // the negative half, and the one that keeps this honest as Config grows: every component
        // of the record must be nameable, or a legitimate override becomes a 400
        for (var component : State.Config.class.getRecordComponents()) {
            assertTrue(State.unknownOverrideKeys(Map.of(component.getName(), "x")).isEmpty(),
                    component.getName() + " is a real config field and must be accepted");
        }
    }

    @Test
    void theLaunchersOwnControlKeysAreAccepted() {
        // not config fields, but the launcher reads them: `force` and `clearLedger` decide
        // whether a start resumes or begins again, and `executionId` addresses a running job
        assertTrue(State.unknownOverrideKeys(
                Map.of("force", true, "clearLedger", true, "executionId", 12)).isEmpty());
    }

    @Test
    void anEmptyBodyIsFine() {
        // the manual start posts nothing at all, and must keep working
        assertTrue(State.unknownOverrideKeys(Map.of()).isEmpty());
        assertTrue(State.unknownOverrideKeys(null).isEmpty());
    }

    @Test
    void everyUnknownKeyIsNamed() {
        // one round trip per mistake, not one per deploy
        List<String> unknown = State.unknownOverrideKeys(
                Map.of("dryProbe", true, "maxRounds", 3, "repoUrl", "https://example.invalid/r"));
        assertEquals(List.of("dryProbe", "maxRounds"), unknown.stream().sorted().toList());
    }
}
