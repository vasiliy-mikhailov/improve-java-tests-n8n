package tech.mikhailov.ijt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// 814 rounds died without saying why.
///
/// Counted over /data/events.jsonl at the end of run-1785444794893: "deleted generated tests that
/// broke the suite" appears 814 times against ~1395 misses — 58% of every miss this pipeline has
/// ever recorded is a test that never ran. Grepping the same log for `cannot find symbol` returns
/// 0, and for `COMPILATION ERROR` returns 0. The pipeline knows the suite broke, deletes the test,
/// and records nothing about the cause.
///
/// That is the difference between two completely different remedies. A test that does not compile
/// needs more type context in the prompt; a test that compiles and asserts the wrong thing needs a
/// better description of the mutant. With no captured reason there is no way to tell which, and
/// the whole question of what to fix next rests on guesswork.
///
/// The text is already in hand — `lastSuiteFailure` holds the runner's summary, clipped to 600
/// characters, and `deleteMany` reads it to decide what to salvage. It just never reached the
/// event stream. This is the line that puts it there.
class SuiteFailureReasonTest {

    @Test
    void aCompileErrorIsNamed() {
        String log = """
                [INFO] BUILD FAILURE
                [ERROR] COMPILATION ERROR :
                [ERROR] /w/src/test/java/org/json/XMLMacMutTest.java:[42,17] cannot find symbol
                  symbol:   method setKeepStrings(boolean)
                """;
        assertEquals("/w/src/test/java/org/json/XMLMacMutTest.java:[42,17] cannot find symbol",
                Salvage.whyItBroke(log));
    }

    @Test
    void anAssertionFailureIsNamed() {
        // the other half of the fork, and the one that needs a DIFFERENT remedy
        String log = """
                [ERROR] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0
                [ERROR] testBoundary(org.json.XMLMacMutTest)  Time elapsed: 0.01 s  <<< FAILURE!
                org.opentest4j.AssertionFailedError: expected: <2> but was: <3>
                """;
        assertTrue(Salvage.whyItBroke(log).contains("testBoundary"),
                "the failing test's own name is the most useful thing here");
    }

    @Test
    void theFirstErrorWinsOverLaterNoise() {
        // Maven repeats itself at length and the tail is usually "-> [Help 1]". The first real
        // error is the one that explains the build.
        String log = """
                [ERROR] /w/A.java:[1,1] cannot find symbol
                [ERROR] /w/A.java:[9,9] cannot find symbol
                [ERROR] -> [Help 1]
                """;
        assertEquals("/w/A.java:[1,1] cannot find symbol", Salvage.whyItBroke(log));
    }

    @Test
    void mavenBoilerplateIsNotAReason() {
        // "BUILD FAILURE" says nothing a caller did not already know from being here
        String log = """
                [INFO] ------------------------------------------------------------------------
                [INFO] BUILD FAILURE
                [INFO] ------------------------------------------------------------------------
                [ERROR] Failed to execute goal ... : There are test failures.
                """;
        String why = Salvage.whyItBroke(log);
        assertTrue(why.contains("Failed to execute goal"), "got: " + why);
    }

    @Test
    void nothingUsableIsAnEmptyStringNotAGuess() {
        // A blank reason must read as "not captured", never as a fabricated cause — this project
        // has invented two verdicts before and both cost a round of diagnosis.
        assertEquals("", Salvage.whyItBroke(""));
        assertEquals("", Salvage.whyItBroke(null));
        assertEquals("", Salvage.whyItBroke("   \n  \n"));
    }

    @Test
    void theReasonIsClippedSoOneLineStaysOneLine() {
        // it goes into the activity stream, which people read
        String log = "[ERROR] " + "x".repeat(900);
        assertTrue(Salvage.whyItBroke(log).length() <= 200);
    }
}
