package tech.mikhailov.ijt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// A unit that cannot yield a kill must never reach the candidate list.
///
/// SelectEligibilityTest already covered `Select.eligibleUnit` and passed. The predicate was
/// still called by nothing, so on the next from-scratch run Cookie#&lt;init&gt;, CookieList#&lt;init&gt;,
/// Property#&lt;init&gt;, StringBuilderWriter#close and #flush were each picked, given a baseline
/// coverage run and a PIT run, and written off as UNMEASURED in turn — exactly the waste the
/// predicate was written to stop.
///
/// A filter nothing calls filters nothing, and a unit test of that filter cannot tell. So these
/// assert on what `candidates()` RETURNS. That is the second time in this project a component
/// was built, tested in isolation, reported as done, and connected to nothing — the H2 store
/// was the first.
class CandidateEligibilityTest {

    @BeforeEach
    void freshRun() {
        State.STATE.run = State.freshRun(Map.of("repoUrl", "https://example.invalid/r", "force", true));
        State.STATE.files = new LinkedHashMap<>();
    }

    /// One unit as the store holds it. `totalMutants` present means PIT has measured it.
    private static void unit(String key, String method, Integer totalMutants, Double mac) {
        Map<String, Object> f = new LinkedHashMap<>();
        // `candidates()` skips anything not in this state — a unit already improved, failed or
        // settled is not up for selection.
        f.put("status", "candidate");
        f.put("key", key);
        f.put("path", key.split("::")[0]);
        f.put("method", method);
        f.put("coverage", 100.0);
        f.put("mac", mac);
        if (totalMutants != null) f.put("totalMutants", totalMutants);
        f.put("executableLines", 3);
        State.STATE.files.put(key, f);
    }

    @SuppressWarnings("unchecked")
    private static List<String> candidatePaths() {
        Object c = Server.candidates().get("candidates");
        return ((List<Map<String, Object>>) c).stream().map(r -> String.valueOf(r.get("path"))).toList();
    }

    @Test
    void anEmptyBodyNeverBecomesACandidate() {
        // `public Cookie() { }` — PIT emits nothing, so a pick costs a coverage run, a PIT run,
        // a model call and a verify to learn there was never anything to kill.
        unit("src/main/java/org/json/Cookie.java::<init>", "<init>", 0, null);
        unit("src/main/java/org/json/XML.java::parse", "parse", 12, 40.0);
        assertEquals(List.of("src/main/java/org/json/XML.java::parse"), candidatePaths());
    }

    @Test
    void anAlreadyPerfectUnitNeverBecomesACandidate() {
        unit("src/main/java/org/json/S.java::combine", "combine", 0, 100.0);
        unit("src/main/java/org/json/XML.java::parse", "parse", 12, 40.0);
        assertEquals(List.of("src/main/java/org/json/XML.java::parse"), candidatePaths());
    }

    @Test
    void aSyntheticMethodNeverBecomesACandidate() {
        unit("src/main/java/org/json/H.java::lambda$dispatchQueueBatch$3", "lambda$dispatchQueueBatch$3", 5, 0.0);
        unit("src/main/java/org/json/XML.java::parse", "parse", 12, 40.0);
        assertEquals(List.of("src/main/java/org/json/XML.java::parse"), candidatePaths());
    }

    @Test
    void aUnitPitHasNotMEASUREDYETIsStillACandidate() {
        // The dangerous inverse. A unit with no totalMutants has not been measured — reading
        // that absence as "zero mutants" would drop EVERY candidate before the first baseline
        // ran, and the run would finish instantly having done nothing.
        unit("src/main/java/org/json/Fresh.java::m", "m", null, null);
        assertEquals(List.of("src/main/java/org/json/Fresh.java::m"), candidatePaths());
    }

    @Test
    void aRealUnitWithSurvivorsIsStillACandidate() {
        // the negative half: "filter everything" would pass every test above
        unit("src/main/java/org/json/XML.java::parse", "parse", 12, 40.0);
        assertEquals(List.of("src/main/java/org/json/XML.java::parse"), candidatePaths());
    }
}
