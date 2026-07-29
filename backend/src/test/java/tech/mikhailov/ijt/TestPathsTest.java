package tech.mikhailov.ijt;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class TestPathsTest {

    private static final String SRC = "src/main/java/org/json/XML.java";
    private static final String MODULE = "core/src/main/java/com/acme/deep/pkg/Thing.java";

    @Test
    void generatedTestsLandInSrcTestJavaBesideTheClassPackageIntact() {
        TestPaths.Generated g = TestPaths.generatedTestPaths(SRC, 1, "parse");
        assertEquals("src/test/java/org/json/XMLParseMacCovTest.java", g.covPath());
        assertEquals("src/test/java/org/json/XMLParseMacMutTest.java", g.mutPath());
    }

    @Test
    void twoMethodsOfOneClassNeverShareAGeneratedFile() {
        // JSONArray#optEnum wrote rounds 1-4, then JSONArray#putAll started at round 1 and
        // overwrote optEnum's round-1 file. Its test — a verified kill — vanished from the
        // prepared PR, which still claimed the MAC that test had been measured with.
        var a = TestPaths.generatedTestPaths("src/main/java/org/json/JSONArray.java", 1, "optEnum");
        var b = TestPaths.generatedTestPaths("src/main/java/org/json/JSONArray.java", 1, "putAll");
        assertNotEquals(a.mutPath(), b.mutPath());
        assertNotEquals(a.covPath(), b.covPath());
    }

    @Test
    void aConstructorYieldsALegalJavaClassName() {
        var g = TestPaths.generatedTestPaths("src/main/java/org/json/Cookie.java", 1, "<init>");
        assertTrue(g.mutPath().matches("^[\\w/]+/Cookie\\w*MacMutTest\\.java$"), g.mutPath());
        assertFalse(g.mutPath().matches(".*[<>].*"));
    }

    @Test
    void aModulesPathPrefixSurvivesTheMapping() {
        var g = TestPaths.generatedTestPaths(MODULE, 1, "run");
        assertEquals("core/src/test/java/com/acme/deep/pkg/ThingRunMacMutTest.java", g.mutPath());
    }

    @Test
    void everyGeneratedNameIsOneSurefireActuallyRuns() {
        // A class named PricingMacTestCov compiled, was silently skipped by Surefire, left
        // JaCoCo blind, and PIT — which uses its own glob — then reported 100%. The suite was
        // green and every number was a lie.
        for (int round : new int[]{1, 2, 3, 11}) {
            var g = TestPaths.generatedTestPaths(SRC, round, "parse");
            for (String p : List.of(g.covPath(), g.mutPath())) {
                String cls = p.substring(p.lastIndexOf('/') + 1).replaceAll("\\.java$", "");
                assertTrue(TestPaths.SUREFIRE_INCLUDED.stream().anyMatch(re -> re.matcher(cls).find()),
                        cls + " is not picked up by Surefire");
            }
        }
    }

    @Test
    void eachRoundGetsItsOwnClassJavaForbidsTwoPublicClassesOfOneName() {
        Set<String> names = new LinkedHashSet<>();
        for (int round : new int[]{1, 2, 3, 4, 5}) {
            var g = TestPaths.generatedTestPaths(SRC, round, "parse");
            assertNotEquals(g.covPath(), g.mutPath(), "coverage and mutation classes must differ");
            for (String p : List.of(g.covPath(), g.mutPath())) {
                assertFalse(names.contains(p), p + " collides with an earlier round");
                names.add(p);
            }
        }
        assertEquals(10, names.size());
    }

    @Test
    void theProjectsOwnTestIsLookedForUnderEveryConventionItMightUse() {
        assertEquals(List.of(
                "src/test/java/org/json/XMLTest.java",
                "src/test/java/org/json/XMLTests.java",
                "src/test/java/org/json/TestXML.java",
                "src/test/java/org/json/XMLTestCase.java"), TestPaths.existingTestCandidates(SRC));
    }

    @Test
    void aSourcePathOutsideSrcMainJavaStillYieldsATestPathUnderSrcTestJava() {
        // some repos keep sources in java/ or src/ — a generated path must never be written
        // next to production code
        for (String src : new String[]{"src/java/org/json/XML.java", "java/org/json/XML.java", "XML.java"}) {
            var g = TestPaths.generatedTestPaths(src, 1, "parse");
            assertTrue(Pattern.compile("(^|/)src/test/java/").matcher(g.mutPath()).find(),
                    src + " → " + g.mutPath());
            assertTrue(g.mutPath().endsWith("XMLParseMacMutTest.java"), src + " → " + g.mutPath());
        }
    }
}
