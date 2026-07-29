package tech.mikhailov.ijt;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class CleanupTest {

    private static final String ORIGINAL = """
            package org.json;

            import static org.junit.Assert.assertEquals;
            import org.junit.Test;

            public class JSONArrayMacMutTest {

                // Wait, let me think about this. The mutant replaces + with -, so I need
                // inputs where those differ. Let's try 2 and 3... actually any pair works.
                // Hmm, but does getNumber() unwrap? Let me reason: yes, it calls optNumber.
                @Test
                public void testGetNumberReturnsValue() {
                    JSONArray a = new JSONArray("[5]");
                    assertEquals(5, a.getNumber(0).intValue());
                }

                @Test
                public void testDoesNotThrow() {
                    JSONArray a = new JSONArray("[1]");
                    a.getNumber(0);
                }
            }
            """;

    private static final String CLEANED = """
            package org.json;

            import static org.junit.Assert.assertEquals;
            import org.junit.Test;

            public class JSONArrayMacMutTest {

                // kills NullReturnVals on getNumber
                @Test
                public void testGetNumberReturnsValue() {
                    JSONArray a = new JSONArray("[5]");
                    assertEquals(5, a.getNumber(0).intValue());
                }
            }
            """;

    /// The JS asserted with `assert.match(r.reason, /…/i)`; these two keep the case-insensitive
    /// substring search rather than demanding an exact sentence.
    private static void assertMatches(String regex, String text, String message) {
        assertNotNull(text, message);
        assertTrue(Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text).find(),
                message + ": " + text + " does not match /" + regex + "/i");
    }

    private static void assertDoesNotMatch(String regex, String text, String message) {
        assertFalse(Pattern.compile(regex).matcher(text).find(), message);
    }

    @Test
    void aCleanedJUnitFileIsRecognisedAsJavaNotJudgedByVitestShapes() {
        // The gate was /\b(it|test|describe)\s*\(/ — inherited from the JS pipeline. A Java
        // test file matches none of that, so every cleanup was rejected as "implausible
        // output" and D12 could never be demonstrated on a Java repo.
        assertDoesNotMatch("\\b(it|describe)\\s*\\(", CLEANED, "the fixture is genuinely JS-shape-free");
        assertTrue(Cleanup.plausibleCleanup(ORIGINAL, CLEANED).ok());
    }

    @Test
    void scratchReasoningRemovedAndOneIntentCommentKeptIsThePointOfThePass() {
        Cleanup.Verdict r = Cleanup.plausibleCleanup(ORIGINAL, CLEANED);
        assertTrue(r.ok());
        assertDoesNotMatch("Wait, let me think|Hmm, but", CLEANED, "the scratch reasoning is gone");
    }

    @Test
    void anAnswerThatDroppedEveryTestIsADeletionNotACleanup() {
        String empty = Pattern.compile("@Test[\\s\\S]*?\\n    \\}\\n").matcher(CLEANED).replaceFirst("");
        Cleanup.Verdict r = Cleanup.plausibleCleanup(ORIGINAL, empty);
        assertFalse(r.ok());
        assertMatches("no @Test|no tests", r.reason(), "reason");
    }

    @Test
    void aTruncatedAnswerIsRejectedRatherThanCommitted() {
        Cleanup.Verdict r = Cleanup.plausibleCleanup(ORIGINAL, CLEANED.substring(0, 120));
        assertFalse(r.ok());
    }

    @Test
    void cleanupMayNotRenameTheClassTheFileWouldStopCompiling() {
        String renamed = CLEANED.replace("JSONArrayMacMutTest", "CleanedTest");
        Cleanup.Verdict r = Cleanup.plausibleCleanup(ORIGINAL, renamed);
        assertFalse(r.ok());
        assertMatches("class", r.reason(), "reason");
    }

    @Test
    void cleanupMayNotAddTestsItInvented() {
        // the JS wrote `CLEANED.replace('}\n$', '')` — a literal string, not a regex, and one
        // that never occurs, so the fixture is simply CLEANED with an extra test appended.
        // Kept verbatim: the point of the case is the appended test, not the no-op strip.
        String grown = CLEANED.replace("}\n$", "") + """

                    @Test
                    public void testSomethingNew() {
                        assertEquals(1, 1);
                    }
                }
                """;
        Cleanup.Verdict r = Cleanup.plausibleCleanup(ORIGINAL, grown);
        assertFalse(r.ok());
        assertMatches("more tests|added", r.reason(), "reason");
    }

    @Test
    void cleanupMayNotSmuggleInReflection() {
        String sneaky = CLEANED.replace("JSONArray a = new JSONArray(\"[5]\");",
                "java.lang.reflect.Method m = JSONArray.class.getDeclaredMethod(\"getNumber\"); m.setAccessible(true);");
        Cleanup.Verdict r = Cleanup.plausibleCleanup(ORIGINAL, sneaky);
        assertFalse(r.ok());
        assertMatches("reflection", r.reason(), "reason");
    }

    @Test
    void anUnchangedAnswerIsNotAChange() {
        assertFalse(Cleanup.plausibleCleanup(ORIGINAL, ORIGINAL).ok());
    }

    @Test
    void thinkingBlocksAndMarkdownFencesNeverReachTheFile() {
        assertEquals(CLEANED.trim(),
                Cleanup.extractCleanedFile("<think>I should strip the noise</think>\n" + CLEANED).trim());
        assertEquals(CLEANED.trim(), Cleanup.extractCleanedFile("```java\n" + CLEANED + "```").trim());
        assertEquals(CLEANED.trim(), Cleanup.extractCleanedFile(CLEANED).trim());
    }

    @Test
    void theCleanedFileAlwaysEndsWithExactlyOneNewline() {
        String out = Cleanup.extractCleanedFile("```java\n" + CLEANED.stripTrailing() + "\n```");
        assertTrue(out.endsWith("}\n"), out);
    }

    @Test
    void anEmptyOrTestLessFileIsNeverWorthCommitting() {
        // A zero-byte XMLMacMutR2Test.java reached a prepared PR: a new file, empty blob, no
        // hunks. Whatever produced it, dead weight in a deliverable is exactly what D12 forbids.
        assertFalse(Cleanup.worthCommitting(""));
        assertFalse(Cleanup.worthCommitting("   \n\n  "));
        assertFalse(Cleanup.worthCommitting("package org.json;\n\npublic class T { }\n"),
                "no @Test = nothing tested");
    }

    @Test
    void aRealGeneratedTestIsWorthCommitting() {
        assertTrue(Cleanup.worthCommitting(CLEANED));
    }

    @Test
    void aParameterisedOrRepeatedTestCountsAsATest() {
        String p = """
                package a;
                import org.junit.jupiter.params.ParameterizedTest;
                public class T {
                  @ParameterizedTest
                  void t(int x) { assertEquals(1, x); }
                }
                """;
        assertTrue(Cleanup.worthCommitting(p));
    }

    // ── which files a cleanup may touch ───────────────────────────────────

    @Test
    void aCleanupOnlyTouchesTheFilesOfTheUnitItCanReMeasure() {
        // The pass cleaned every changed test file on the branch but re-measured only the
        // CURRENT unit. JSONObjectGetElementTypeMacMutTest.java was stripped again while
        // isNumberSimilar was current, and "kept" on isNumberSimilar's score — the very file
        // whose stripping had already been reverted once for dropping getElementType from
        // 80 to 60. A verification that cannot see the thing it is verifying is not one.
        List<String> changed = List.of(
                "src/test/java/org/json/JSONObjectIsNumberSimilarMacMutTest.java",
                "src/test/java/org/json/JSONObjectIsNumberSimilarMacMutR2Test.java",
                "src/test/java/org/json/JSONObjectGetElementTypeMacMutTest.java");
        List<String> mine = Cleanup.ownedByUnit(changed, new TestPaths.Generated(
                "src/test/java/org/json/JSONObjectIsNumberSimilarMacCovTest.java",
                "src/test/java/org/json/JSONObjectIsNumberSimilarMacMutTest.java"));
        assertEquals(List.of(
                "src/test/java/org/json/JSONObjectIsNumberSimilarMacMutTest.java",
                "src/test/java/org/json/JSONObjectIsNumberSimilarMacMutR2Test.java"), mine);
    }

    @Test
    void everyRoundOfTheCurrentUnitIsIncludedNotJustThisOne() {
        List<String> changed = List.of(
                "src/test/java/a/BRunMacCovTest.java",
                "src/test/java/a/BRunMacMutR4Test.java");
        List<String> mine = Cleanup.ownedByUnit(changed, new TestPaths.Generated(
                "src/test/java/a/BRunMacCovR2Test.java", "src/test/java/a/BRunMacMutR2Test.java"));
        assertEquals(changed.stream().sorted().toList(), mine.stream().sorted().toList());
    }

    @Test
    void aSiblingMethodOfTheSameClassIsNotOursToClean() {
        List<String> changed = List.of("src/test/java/a/BOtherMacMutTest.java");
        assertEquals(List.of(), Cleanup.ownedByUnit(changed, new TestPaths.Generated(
                "src/test/java/a/BRunMacCovTest.java", "src/test/java/a/BRunMacMutTest.java")));
    }

    @Test
    void theProjectsOwnTestFileIsNeverACleanupTarget() {
        List<String> changed = List.of("src/test/java/a/BTest.java");
        assertEquals(List.of(), Cleanup.ownedByUnit(changed, new TestPaths.Generated(
                "src/test/java/a/BRunMacCovTest.java", "src/test/java/a/BRunMacMutTest.java")));
    }
}
