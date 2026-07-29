package tech.mikhailov.ijt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static tech.mikhailov.ijt.Targets.Mutant;
import static tech.mikhailov.ijt.Targets.Target;

/// A different shape for a round: ask once per surviving LINE, measure once per class.
///
/// The one-at-a-time loop pays two PIT runs per round per method-unit — 609 runs across 22
/// classes on JSON-java, 43% of the run's wall-clock — and asks the model for one mutant at a
/// time. Most of that is re-measurement of things that did not change.
class TargetsTest {

    private static Mutant m(String mutator, int line, String method) {
        return m(mutator, line, method, "SURVIVED");
    }

    private static Mutant m(String mutator, int line, String method, String status) {
        return new Mutant(method, line, mutator, status);
    }

    // ── the marker, not the name ──────────────────────────────────────────
    // The first version made the model name every test kill_<method>_line<N>. That is not how
    // these repos name tests — java-dataloader has clearCacheOnError and disableCache,
    // JSON-java has emptyStringCookieList — and a reviewer reading the PR sees the generated
    // ones as machine output. It is also a lie the moment the source shifts a line.
    @Test
    void aMutantMapsToAStableMarkerNamingItsMethodAndLine() {
        String k = Targets.targetMarker(m("ConditionalsBoundaryMutator", 170, "mustEscape"));
        assertTrue(k.contains("mustEscape"));
        assertTrue(k.contains("170"));
        assertEquals(k, Targets.targetMarker(m("RemoveConditionalMutator_EQUAL_ELSE", 170, "mustEscape")),
                "stable across mutators");
    }

    @Test
    void everyMutationOfTheSameLineSharesOneMarker() {
        // > became >=, > became <, the branch was removed — one test that exercises the
        // boundary distinguishes all three, so asking three times is three wasted calls
        assertEquals(Targets.targetMarker(m("ConditionalsBoundaryMutator", 170, "mustEscape")),
                     Targets.targetMarker(m("RemoveConditionalMutator_EQUAL_ELSE", 170, "mustEscape")));
    }

    @Test
    void differentLinesAndMethodsStayDistinct() {
        assertNotEquals(Targets.targetMarker(m("MathMutator", 170, "a")), Targets.targetMarker(m("MathMutator", 171, "a")));
        assertNotEquals(Targets.targetMarker(m("MathMutator", 170, "a")), Targets.targetMarker(m("MathMutator", 170, "b")));
    }

    @Test
    void constructorsAndLambdasProduceAMarkerACommentCanCarry() {
        for (String meth : new String[]{"<init>", "lambda$dispatchQueueBatch$2"}) {
            String k = Targets.targetMarker(m("NullReturnValsMutator", 42, meth));
            assertFalse(k.matches("(?s).*[\r\n].*"), "a marker lives on one comment line");
            assertFalse(k.isEmpty());
        }
    }

    @Test
    void javaSafeMethodKeepsLambdasReadableRatherThanStrippingThemToNothing() {
        assertEquals("Ctor", Targets.javaSafeMethod("<init>"));
        assertEquals("StaticInit", Targets.javaSafeMethod("<clinit>"));
        assertEquals("lambdaDispatchQueueBatch2", Targets.javaSafeMethod("lambda$dispatchQueueBatch$2"));
        assertEquals("Method", Targets.javaSafeMethod("$$$"), "never reduce to an empty name");
    }

    // ── grouping ──────────────────────────────────────────────────────────
    @Test
    void survivorsCollapseToOneTargetPerLineCarryingEveryMutationOnIt() {
        List<Target> g = Targets.groupTargets(List.of(
                m("ConditionalsBoundaryMutator", 170, "mustEscape"),
                m("RemoveConditionalMutator_EQUAL_ELSE", 170, "mustEscape"),
                m("MathMutator", 205, "mustEscape")));
        assertEquals(2, g.size(), "170 collapses, 205 is its own");
        Target first = g.stream().filter(t -> t.line() == 170).findFirst().orElseThrow();
        assertEquals(2, first.mutants().size(), "the prompt still names both mutations");
        assertEquals("mustEscape", first.method());
    }

    @Test
    void aNoCoverageLineIsGroupedTooItStillNeedsATest() {
        // PIT never reached this line, so nothing killed the mutant and nothing could have. It
        // is still work: grouping must produce a target for it, and must carry the mutant
        // through verbatim so the status reaches the prompt, which says something different
        // about an unreached line than about one whose tests simply did not notice.
        List<Target> g = Targets.groupTargets(List.of(m("NullReturnValsMutator", 12, "x", "NO_COVERAGE")));
        assertEquals(1, g.size());
        assertEquals("NO_COVERAGE", g.get(0).mutants().get(0).status());
    }

    @Test
    void noSurvivorsNoTargets() {
        assertEquals(List.of(), Targets.groupTargets(List.of()));
        assertEquals(List.of(), Targets.groupTargets(null));
    }

    // ── the fast filter: never pay the model for a test that exists ───────
    @Test
    void aTargetWhoseTestIsAlreadyWrittenIsNotAskedForAgain() {
        // 1000 mutants at ~100 tokens and 30 tokens/sec is hours of generation. A string check
        // over the test sources costs nothing. The test is found by its MARKER, so it can be
        // called whatever the repo's convention calls for.
        List<Target> targets = Targets.groupTargets(List.of(
                m("MathMutator", 170, "mustEscape"), m("MathMutator", 205, "mustEscape")));
        String existing = """
                class XMustEscapeMacMutTest {
                  @Test void escapesAmpersandAtBoundary() {
                    // %s
                  }
                }""".formatted(Targets.targetMarker(m("MathMutator", 170, "mustEscape")));
        List<Target> pending = Targets.pendingTargets(targets, List.of(existing));
        assertEquals(1, pending.size());
        assertEquals(205, pending.get(0).line());
    }

    @Test
    void theFilterReadsEveryTestSourceItIsGiven() {
        List<Target> targets = Targets.groupTargets(List.of(m("MathMutator", 170, "a"), m("MathMutator", 205, "a")));
        String s1 = "// " + Targets.targetMarker(m("MathMutator", 170, "a"));
        String s2 = "// " + Targets.targetMarker(m("MathMutator", 205, "a"));
        assertEquals(0, Targets.pendingTargets(targets, List.of(s1, s2)).size());
    }

    @Test
    void aMarkerForANearbyLineDoesNotCountAsCovered() {
        // the marker for line 17 must not satisfy line 170
        List<Target> targets = Targets.groupTargets(List.of(m("MathMutator", 170, "mustEscape")));
        String near = "// " + Targets.targetMarker(m("MathMutator", 17, "mustEscape"));
        assertEquals(1, Targets.pendingTargets(targets, List.of(near)).size(), "line 17 is not line 170");
    }

    @Test
    void noSourcesMeansEverythingIsPending() {
        List<Target> targets = Targets.groupTargets(List.of(m("MathMutator", 170, "a")));
        assertEquals(1, Targets.pendingTargets(targets, List.of()).size());
        assertEquals(1, Targets.pendingTargets(targets, null).size());
    }

    // ── what the route has to assemble ────────────────────────────────────
    @Test
    void targetsComeFromTheSurvivorsMinusAnythingAlreadyNamedInTheSources() {
        List<Mutant> survivors = List.of(
                m("MathMutator", 170, "mustEscape"),
                m("MathMutator", 205, "mustEscape"),
                m("NullReturnValsMutator", 205, "mustEscape"));
        String written = "// " + Targets.targetMarker(m("MathMutator", 170, "mustEscape"));
        Targets.Plan r = Targets.targetsFor(survivors, List.of(written));
        assertEquals(2, r.all().size(), "170 and 205");
        assertEquals(1, r.pending().size());
        assertEquals(205, r.pending().get(0).line());
        assertEquals(1, r.covered(), "and it reports what the filter saved");
    }

    @Test
    void withNothingWrittenYetEveryTargetIsPending() {
        Targets.Plan r = Targets.targetsFor(List.of(m("MathMutator", 170, "a")), List.of());
        assertEquals(1, r.pending().size());
        assertEquals(0, r.covered());
    }

    @Test
    void noSurvivorsIsNotAnErrorItIsAFinishedClass() {
        Targets.Plan r = Targets.targetsFor(List.of(), List.of("whatever"));
        assertEquals(List.of(), r.all());
        assertEquals(List.of(), r.pending());
        assertEquals(0, r.covered());
    }
}
