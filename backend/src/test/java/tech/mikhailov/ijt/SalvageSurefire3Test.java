package tech.mikhailov.ijt;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Partial salvage has never once run on this repo.
///
/// Salvage exists so that one bad assertion in a batch of N tests costs one test, not the file.
/// It reads the runner's summary, takes the names of the methods that failed, cuts those out and
/// keeps the rest. It shipped with two patterns: Gradle, and Surefire **2.x**:
///
///     methodName(pkg.SomeTest)  Time elapsed: 0.01 s  <<< FAILURE!
///
/// JSON-java builds with maven-surefire-plugin:3.6.0-M1, which prints the fully-qualified name
/// first and no parentheses at all:
///
///     org.json.CDLAppendRowValueMacMutTest.appendRowValueNullObject -- Time elapsed: 0.003 s <<< FAILURE!
///
/// The 2.x pattern cannot match that. Run over the 64 suite-failure texts this pipeline actually
/// stored, the shipped patterns name a failing test in **0** of them; the pattern added here
/// names one in 45 — precisely the assertion-failure subset. In the whole 57k-line event log
/// "kept the passing tests and cut only the failing ones" appears 11 times, every one of them on
/// java-dataloader, which builds with Gradle. On JSON-java: zero.
///
/// So on 492 rounds the entire generated file was deleted — 382 of them batch files, median
/// 4,954 bytes of tests thrown away, typically for a single wrong assertion. Whatever the other
/// tests in that file had covered went with it.
///
/// It also corrects the assumption I was about to spend on. Of those 64 texts, 45 (70%) are
/// assertion or runtime failures and only 18 (28%) are compile errors — so "give the model more
/// type context" addresses the minority, and salvage addresses the majority.
class SalvageSurefire3Test {

    /// Verbatim from /data/state.json.pre-restart-1785442531, CDL.java::appendRowValue.
    private static final String SUREFIRE_3 = """
            [ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.005 s <<< FAILURE! -- in org.json.CDLAppendRowValueMacMutTest
            [ERROR] org.json.CDLAppendRowValueMacMutTest.appendRowValueNullObject -- Time elapsed: 0.003 s <<< FAILURE!
            org.opentest4j.AssertionFailedError: expected: <null> but was: <>
            """;

    @Test
    void aSurefire3FailureNamesItsTest() {
        assertEquals(Set.of("appendRowValueNullObject"), Salvage.failedTestNames(SUREFIRE_3));
    }

    @Test
    void theClassSummaryLineIsNotATestName() {
        // THE TRAP. `Tests run: 1, ... <<< FAILURE! -- in org.json.CDLAppendRowValueMacMutTest`
        // carries the same marker as the line below it. Reading it as a failing method would cut
        // whatever it happened to name — the pattern anchors on `-- Time elapsed` BEFORE the
        // marker, which only the per-method line has.
        String summaryOnly = "[ERROR] Tests run: 3, Failures: 0, Errors: 1, Skipped: 0, "
                + "Time elapsed: 0.005 s <<< FAILURE! -- in org.json.CDLRowToJSONArrayMacCovTest";
        assertTrue(Salvage.failedTestNames(summaryOnly).isEmpty(),
                "a count is not a method name");
    }

    @Test
    void anErrorIsNamedAsWellAsAFailure() {
        // Surefire says ERROR! for a thrown exception and FAILURE! for a failed assertion. Both
        // make the suite red and both must be cut.
        String errored = "[ERROR] org.json.CDLRowToJSONArrayMacCovTest.testRowToJSONArrayWithEmptyInput "
                + "-- Time elapsed: 0.003 s <<< ERROR!";
        assertEquals(Set.of("testRowToJSONArrayWithEmptyInput"), Salvage.failedTestNames(errored));
    }

    @Test
    void severalFailuresInOneFileAreAllNamed() {
        // the batch case, which is the one that matters: N tests in a file, some of them wrong
        String two = """
                [ERROR] org.json.CDLShouldQuoteValueMacMutTest.shouldQuoteValueBooleanTrueReturn -- Time elapsed: 0.001 s <<< FAILURE!
                [ERROR] org.json.CDLShouldQuoteValueMacMutTest.shouldQuoteValueContainsNullCharacter -- Time elapsed: 0 s <<< FAILURE!
                """;
        assertEquals(Set.of("shouldQuoteValueBooleanTrueReturn", "shouldQuoteValueContainsNullCharacter"),
                Salvage.failedTestNames(two));
    }

    @Test
    void theOlderFormatsStillWork() {
        // the negative half. java-dataloader builds with Gradle and is the only repo salvage has
        // ever fired on — breaking it to fix JSON-java would trade one for the other.
        assertEquals(Set.of("boundary"),
                Salvage.failedTestNames("SomeTest > boundary() FAILED"));
        assertEquals(Set.of("legacyStyle"),
                Salvage.failedTestNames("[ERROR] legacyStyle(org.json.XTest)  Time elapsed: 0.01 s  <<< FAILURE!"));
    }

    @Test
    void aCompileFailureNamesNothing() {
        // 28% of the sample, and the case salvage CANNOT help with: there are no test results
        // because nothing ran. An empty set means "delete the file", which is right here.
        String compileError = """
                [ERROR] COMPILATION ERROR :
                [ERROR] /w/src/test/java/org/json/XMLMacMutTest.java:[42,17] cannot find symbol
                """;
        assertTrue(Salvage.failedTestNames(compileError).isEmpty());
    }

    /// The end-to-end shape: a batch file that loses one test and keeps the others.
    @Test
    void aBatchFileLosesOnlyTheFailingTest() {
        // Laid out as generated code actually is. Written on one line each, the cut misses them
        // — Salvage anchors a method at the start of a line, the same limitation publicApi has.
        String src = """
                package org.json;
                import org.junit.Test;
                public class CDLAppendRowValueMacMutTest {
                    @Test
                    public void appendRowValueNullObject() {
                        fail();
                    }

                    @Test
                    public void appendRowValueKeepsQuotes() {
                        assertTrue(true);
                    }
                }
                """;
        String kept = Salvage.salvageSource(src, Salvage.failedTestNames(SUREFIRE_3));
        assertTrue(kept != null && !kept.isEmpty(), "the file survives");
        assertFalse(kept.contains("appendRowValueNullObject"), "the failing test is cut");
        assertTrue(kept.contains("appendRowValueKeepsQuotes"),
                "and the one that passed is what makes this worth doing");
    }
}
