package tech.mikhailov.ijt;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class PickTest {

    // already ranked weakest-first by select.rankUnits
    private static final List<Pick.Candidate> RANKED = List.of(
            new Pick.Candidate("src/main/java/a/JSONArray.java::getNumber", "src/main/java/a/JSONArray.java", "getNumber"),
            new Pick.Candidate("src/main/java/ui/Widget.java::render", "src/main/java/ui/Widget.java", "render"),
            new Pick.Candidate("src/main/java/a/JSONObject.java::hashCode", "src/main/java/a/JSONObject.java", "hashCode"));

    private static void assertMatches(String regex, String actual) {
        assertNotNull(actual);
        assertTrue(Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(actual).find(),
                actual + " does not match /" + regex + "/i");
    }

    @Test
    void withNothingExcludedThePickIsTheTopRankedUnit() {
        // the model used to CHOOSE, and chose the 5th-ranked private method over three
        // directly callable ones. Its job is the team rule; the ranking is measurement.
        var r = Pick.choosePick(RANKED, List.of());
        assertEquals("src/main/java/a/JSONArray.java::getNumber", r.file());
        assertMatches("rank|weakest", r.reason());
    }

    @Test
    void anExcludedUnitIsSkippedAndTheNextSurvivorTaken() {
        var r = Pick.choosePick(RANKED, List.of("src/main/java/a/JSONArray.java::getNumber"));
        assertEquals("src/main/java/ui/Widget.java::render", r.file());
    }

    @Test
    void excludingAFileExcludesEveryMethodInIt() {
        // "don't touch ui" is a statement about files, and the model answers in those terms
        var r = Pick.choosePick(RANKED, List.of("src/main/java/ui/Widget.java", "src/main/java/a/JSONArray.java"));
        assertEquals("src/main/java/a/JSONObject.java::hashCode", r.file());
    }

    @Test
    void aRuleThatExcludesEverythingEndsTheBatchAndIsNotRetried() {
        var r = Pick.choosePick(RANKED, RANKED.stream().map(Pick.Candidate::path).toList());
        assertNull(r.file());
        assertEquals(Boolean.FALSE, r.retry());
        assertFalse(r.shouldRetry());
        assertMatches("excluded", r.reason());
    }

    @Test
    void anExclusionNamingSomethingNotOnTheListIsIgnored() {
        var r = Pick.choosePick(RANKED, List.of("src/main/java/nowhere/Ghost.java::gone"));
        assertEquals("src/main/java/a/JSONArray.java::getNumber", r.file());
    }

    @Test
    void noCandidatesAtAllIsNotARuleDecision() {
        var r = Pick.choosePick(List.of(), List.of());
        assertNull(r.file());
        assertEquals(Boolean.FALSE, r.retry());
        assertFalse(r.shouldRetry());
        assertMatches("no candidates", r.reason());
    }

    @Test
    void theChoiceIsAFunctionOfTheRankingAloneSameInputSameUnit() {
        assertEquals(Pick.choosePick(RANKED, List.of()).file(), Pick.choosePick(RANKED, List.of()).file());
    }

    @Test
    void aSuccessfulPickStatesNoRetryAndReadsAsFalsy() {
        // Java-side only: the JS answer for a pick has no `retry` key, so the boxed field is
        // null — never true. Unboxing it would throw where the JS merely read undefined, so
        // callers go through shouldRetry().
        var r = Pick.choosePick(RANKED, List.of());
        assertNull(r.retry());
        assertFalse(r.shouldRetry());
    }
}
