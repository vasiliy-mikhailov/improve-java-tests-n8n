package tech.mikhailov.ijt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The round-boundary reset, pinned at the ROUTE.
///
/// An adversarial review of this session deleted both `roundTestPaths` resets and the
/// `lastRoundBroken` write, and ran the full suite: **1 133 tests, 0 failures.** Nothing drove
/// `/api/round/miss` or `/api/round/accept` at all — `ServerTest` asserts only that the route
/// KEYS exist. So both regressions would ship green, and the prompt would quietly go back to
/// advising the model about an assertion in a file that never compiled.
///
/// Those two resets are named in `LastRoundFeedbackTest`'s own header as the preconditions that
/// had to be true before rendering the feedback block was safe. Documenting a precondition and
/// leaving it untested is how it gets deleted by someone tidying up.
///
/// Why the boundary and not the write: generation and repair are two writes inside one round, and
/// a repair that writes nothing must not erase what generation left. The list has to survive the
/// second write and die at the end of the round.
class RoundBoundaryTest {

    private static final String UNIT = "src/main/java/org/json/XML.java::parse";

    @BeforeEach
    void freshRun() {
        State.STATE.run = State.freshRun(Map.of("repoUrl", "https://example.invalid/r", "force", true));
        State.STATE.files = new LinkedHashMap<>();
        State.STATE.currentUnit = UNIT;

        Map<String, Object> f = new LinkedHashMap<>();
        f.put("status", "candidate");
        f.put("key", UNIT);
        f.put("path", "src/main/java/org/json/XML.java");
        f.put("method", "parse");
        // what the round left on disk, which is what testsPresent is computed from
        f.put("roundTestPaths", List.of("src/test/java/org/json/XMLParseMacMutTest.java"));
        f.put("consecutiveMisses", 0);
        f.put("lastSurvived", List.of());
        f.put("attemptedMutants", List.of());
        State.STATE.files.put(UNIT, f);
    }

    @SuppressWarnings("unchecked")
    private static List<String> roundTestPaths() {
        Object v = State.STATE.files.get(UNIT).get("roundTestPaths");
        return v instanceof List ? (List<String>) v : List.of();
    }

    @Test
    void aMissedRoundEndsWithItsFileListCleared() throws Exception {
        assertEquals(1, roundTestPaths().size(), "precondition: the round wrote a file");

        Server.routes().get("POST /api/round/miss")
                .handle(Server.Query.EMPTY, Map.of("file", UNIT));

        assertTrue(roundTestPaths().isEmpty(),
                "the next round's testsPresent would answer about THIS round's file: " + roundTestPaths());
    }

    @Test
    void anAcceptedRoundEndsWithItsFileListClearedToo() throws Exception {
        // A kept round commits its tests, so they stay on the branch — but they are no longer
        // THIS round's, and the next round must not read them as its own.
        Server.routes().get("POST /api/round/accept")
                .handle(Server.Query.EMPTY, Map.of("file", UNIT));

        assertTrue(roundTestPaths().isEmpty(), "got: " + roundTestPaths());
    }

    @Test
    void aRoundThatWroteNothingIsStillCleared() throws Exception {
        // idempotent: the reset must not depend on there having been a file
        State.upsertFile(UNIT, mapOf("roundTestPaths", List.of()));
        Server.routes().get("POST /api/round/miss")
                .handle(Server.Query.EMPTY, Map.of("file", UNIT));
        assertTrue(roundTestPaths().isEmpty());
    }

    private static Map<String, Object> mapOf(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }
}
