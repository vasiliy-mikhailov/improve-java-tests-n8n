package tech.mikhailov.ijt;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/// A batch round writes N tests into one file. One bad assertion used to cost all N.
///
/// The very first unit of the v2 run proved it: three targets, two of them asserting wrongly,
/// the whole file deleted for breaking the suite, and the round recorded as cov 0→0 mut 0→0.
/// Under the old one-mutant-per-round loop a bad assertion cost one test; batching made it
/// cost the batch.
class SalvageTest {

    /// Braces inside string literals are not structure — the fixture below deliberately
    /// asserts on "}" — so balance is checked over CODE only. Counting raw characters made the
    /// UNMODIFIED fixture look unbalanced (4 vs 6) and failed a correct implementation. That
    /// was a bug in the test, not the code, and it is worth not repeating in the port.
    private static int[] braces(String s) {
        String c = JavaSrc.stripNonCode(s);
        return new int[]{
                (int) c.chars().filter(ch -> ch == '{').count(),
                (int) c.chars().filter(ch -> ch == '}').count()};
    }

    // captured verbatim from the live v2 run
    private static final String GRADLE = """
            ThreadLocalStatisticsCollectorIncrementBatchLoadExceptionCountMacMutTest > kill_incrementBatchLoadExceptionCount_line76() FAILED
                at org.hamcrest.MatcherAssert.assertThat(MatcherAssert.java:20)
                at org.dataloader.stats.ThreadLocalStatisticsCollectorIncrementBatchLoadExceptionCountMacMutTest.kill_incrementBatchLoadExceptionCount_line76(ThreadLocalStatisticsCollectorIncrementBatchLoadExceptionCountMacMutTest.java:27)
            ThreadLocalStatisticsCollectorIncrementBatchLoadExceptionCountMacMutTest > kill_incrementBatchLoadExceptionCount_line82() FAILED
                at org.hamcrest.MatcherAssert.assertThat(MatcherAssert.java:20)""";

    private static final String SUREFIRE = """
            [ERROR] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0
            [ERROR] kill_mustEscape_line170(org.json.XMLMacMutTest)  Time elapsed: 0.01 s  <<< FAILURE!
            java.lang.AssertionError: expected:<true> but was:<false>""";

    @Test
    void theFailingMethodNamesAreReadOutOfAGradleRun() {
        assertEquals(
                List.of("kill_incrementBatchLoadExceptionCount_line76", "kill_incrementBatchLoadExceptionCount_line82"),
                Salvage.failedTestNames(GRADLE).stream().sorted().toList());
    }

    @Test
    void andOutOfASurefireRun() {
        assertEquals(List.of("kill_mustEscape_line170"), List.copyOf(Salvage.failedTestNames(SUREFIRE)));
    }

    @Test
    void aGreenLogNamesNothing() {
        assertEquals(0, Salvage.failedTestNames("BUILD SUCCESSFUL in 12s").size());
        assertEquals(0, Salvage.failedTestNames("").size());
        assertEquals(0, Salvage.failedTestNames(null).size());
    }

    // ── cutting the bad methods out ───────────────────────────────────────
    private static final String FILE = """
            package a;

            import org.junit.jupiter.api.Test;
            import static org.hamcrest.MatcherAssert.assertThat;
            import static org.hamcrest.CoreMatchers.equalTo;

            class BMacMutTest {

                @Test
                void kill_m_line10() {
                    assertThat(new B().m(), equalTo(1));
                }

                @Test
                void kill_m_line20() {
                    // this one asserts the wrong thing
                    assertThat(new B().m(), equalTo(99));
                }

                @Test
                void kill_m_line30() {
                    assertThat(new B().n("}"), equalTo("}"));
                }
            }
            """;

    @Test
    void onlyTheNamedMethodIsRemoved() {
        String out = Salvage.dropTestMethods(FILE, Set.of("kill_m_line20"));
        assertFalse(out.contains("kill_m_line20"));
        assertTrue(out.contains("kill_m_line10"));
        assertTrue(out.contains("kill_m_line30"));
    }

    @Test
    void theFileStillHasItsPackageImportsAndClass() {
        String out = Salvage.dropTestMethods(FILE, Set.of("kill_m_line20"));
        assertTrue(out.startsWith("package a;"));
        assertTrue(out.contains("import org.junit.jupiter.api.Test;"));
        assertTrue(out.contains("class BMacMutTest {"));
        int[] b = braces(out);
        assertEquals(b[0], b[1], "braces balance");
    }

    @Test
    void aBraceInsideAStringLiteralDoesNotEndTheMethodEarly() {
        // kill_m_line30 asserts on "}" — counting braces naively would cut the file in half
        String out = Salvage.dropTestMethods(FILE, Set.of("kill_m_line30"));
        assertFalse(out.contains("kill_m_line30"));
        assertTrue(out.contains("kill_m_line10"));
        assertTrue(out.contains("kill_m_line20"));
        int[] b = braces(out);
        assertEquals(b[0], b[1], "braces balance");
    }

    @Test
    void removingEveryMethodLeavesACompilableEmptyClassNotABrokenFile() {
        String out = Salvage.dropTestMethods(FILE, Set.of("kill_m_line10", "kill_m_line20", "kill_m_line30"));
        assertTrue(out.contains("class BMacMutTest {"));
        assertFalse(out.contains("@Test"));
        int[] b = braces(out);
        assertEquals(b[0], b[1]);
    }

    @Test
    void namingAMethodThatIsNotThereChangesNothing() {
        assertEquals(FILE, Salvage.dropTestMethods(FILE, Set.of("kill_m_line999")));
        assertEquals(FILE, Salvage.dropTestMethods(FILE, Set.of()));
    }

    // ── one decision, used by both places that drop tests ─────────────────
    // The red-suite path learned to cut only the failing methods. The PIT green-suite
    // recovery did not, and deleted the whole file — so a batch that survived a red suite by
    // salvage was then wiped whole when PIT refused to run against one of its members. Two
    // callers, one decision, or they drift.
    @Test
    void cuttingTheNamedMethodsReturnsTheFileWhenSomethingStillRuns() {
        String out = Salvage.salvageSource(FILE, Set.of("kill_m_line20"));
        assertNotNull(out);
        assertFalse(out.contains("kill_m_line20"));
        assertTrue(out.contains("kill_m_line10"));
    }

    @Test
    void cuttingEverythingReturnsNullThereIsNothingWorthKeeping() {
        // a file whose only tests all failed is a file to delete, not to keep empty
        assertNull(Salvage.salvageSource(FILE, Set.of("kill_m_line10", "kill_m_line20", "kill_m_line30")));
    }

    @Test
    void namingNothingThatIsInTheFileReturnsNullNotANoOpKeep() {
        // the caller asked us to drop something we cannot find — deleting is the safe answer,
        // since the file demonstrably broke the build and we cannot say which part
        assertNull(Salvage.salvageSource(FILE, Set.of("kill_other_line1")));
        assertNull(Salvage.salvageSource(FILE, Set.of()));
    }

    @Test
    void aFileWeCannotReadIsNotSalvageable() {
        assertNull(Salvage.salvageSource(null, Set.of("kill_m_line10")));
        assertNull(Salvage.salvageSource("", Set.of("kill_m_line10")));
    }

    @Test
    void aMethodNameCarryingADollarIsMatchedLiterallyAndItsSiblingsSurvive() {
        // A DELIBERATE divergence from salvage.js, recorded here rather than reproduced.
        //
        // The JS interpolates the name straight into a regex — `new RegExp(`\\b${n}\\s*\\(`)` —
        // and `failedTestNames` deliberately admits `$` (its own pattern is `[A-Za-z_$][\w$]*`,
        // which is what a Java identifier allows). For a method called `a$b` the pattern becomes
        // `\ba$b\s*\(`, where `$` is an end-of-input anchor, so it can never match: `mine` comes
        // out empty, salvageSource returns null, and BOTH callers read that as "nothing worth
        // keeping" and delete the whole generated file — losing the sibling tests that passed.
        //
        // Pattern.quote here matches the name literally, so only the named method is cut. This
        // is not the JS behaviour, and it is not being made to be: the JS reading destroys work
        // the run has already paid for, and no caller wants it.
        String src = """
                class T {

                    @Test
                    void a$b() {
                        assertTrue(true);
                    }

                    @Test
                    void keep() {
                        assertTrue(true);
                    }
                }
                """;
        String out = Salvage.salvageSource(src, Set.of("a$b"));
        assertNotNull(out, "the JS answers null here and the whole file is deleted with it");
        assertFalse(out.contains("a$b"));
        assertTrue(out.contains("keep()"), "the sibling that passed is kept");
    }
}
