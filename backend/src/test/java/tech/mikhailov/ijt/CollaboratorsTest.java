package tech.mikhailov.ijt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The test cannot construct a type it has never seen.
///
/// Eight of eleven recorded v3 failures were `cannot find symbol`, and the diagnosis is
/// unusually clean because the run supplied its own control: SimpleStatisticsCollector went
/// MAC 0 → 100 in one pass, while DelegatingStatisticsCollector — same package, same method
/// names, same 3-target batch — lost four consecutive units that v1 had improved. The only
/// difference is that the Delegating one's test must build a StatisticsCollector to pass to
/// the constructor, and the prompt never shows what StatisticsCollector looks like.
///
/// DataLoaderHelper#load then lost SIXTEEN tests in one file to the same missing symbol.
///
/// The prompt carries the class under test and its sibling signatures. It has to also carry
/// the CONSTRUCTOR's parameter types, and — where the project defines them — a concrete
/// implementation the test can instantiate instead of hand-rolling one.
class CollaboratorsTest {

    private static final String DELEGATING = """
            package org.dataloader.stats;

            public class DelegatingStatisticsCollector implements StatisticsCollector {
                private final StatisticsCollector delegateCollector;
                private final StatisticsCollector collector = new SimpleStatisticsCollector();

                public DelegatingStatisticsCollector(StatisticsCollector delegateCollector) {
                    this.delegateCollector = nonNull(delegateCollector);
                }

                @Override
                public long incrementLoadCount(IncrementLoadCountStatisticsContext context) {
                    delegateCollector.incrementLoadCount(context);
                    return collector.incrementLoadCount(context);
                }
            }
            """;

    private static final String COLLECTOR = """
            package org.dataloader.stats;

            public interface StatisticsCollector {
                long incrementLoadCount(IncrementLoadCountStatisticsContext context);

                @Deprecated
                long incrementLoadCount();

                long incrementBatchLoadCountBy(long delta);

                Statistics getStatistics();
            }
            """;

    /// `assert.ok(api.some((s) => /re/.test(s)))` — does any signature match?
    private static boolean some(List<String> api, String regex) {
        Pattern p = Pattern.compile(regex);
        return api.stream().anyMatch(s -> p.matcher(s).find());
    }

    private static String joined(List<String> api) {
        return String.join(" | ", api);
    }

    // ── which types does a test have to build? ────────────────────────────────
    @Test
    void constructorParameterTypesAreWhatTheTestMustSupply() {
        List<String> t = Collaborators.constructorTypes(DELEGATING, "DelegatingStatisticsCollector");
        assertTrue(t.contains("StatisticsCollector"), "got " + t);
    }

    @Test
    void primitivesAndJavaLangTypesAreNotCollaborators() {
        String src = "public class A { public A(int n, String s, long ms, boolean b) {} }";
        assertEquals(List.of(), Collaborators.constructorTypes(src, "A"), "nothing here needs constructing");
    }

    @Test
    void genericsAreReducedToTheTypeATestWouldName() {
        String src = "public class A { public A(CacheMap<Object, String> m) {} }";
        assertEquals(List.of("CacheMap"), Collaborators.constructorTypes(src, "A"));
    }

    @Test
    void aClassWithNoDeclaredConstructorAsksForNothing() {
        assertEquals(List.of(), Collaborators.constructorTypes("public class A { public void go() {} }", "A"));
    }

    // ── what does that type look like? ────────────────────────────────────────
    @Test
    void thePublicApiOfACollaboratorIsExtractedSoTheTestCanImplementIt() {
        List<String> api = Collaborators.publicApi(COLLECTOR);
        assertTrue(some(api, "incrementBatchLoadCountBy"));
        assertTrue(some(api, "getStatistics"));
        assertTrue(api.size() >= 4, "expected every abstract method, got " + api.size());
    }

    @Test
    void theExtractedApiKeepsParameterTypesWhichIsTheWholePoint() {
        // "call incrementBatchLoadCountBy()" when it takes a long is how six rounds were lost on
        // XMLTokener#isValidDecimal
        List<String> api = Collaborators.publicApi(COLLECTOR);
        assertTrue(some(api, "incrementBatchLoadCountBy\\(\\s*long"), joined(api));
    }

    @Test
    void anEmptyOrUnreadableSourceYieldsNothingNotABrokenSignature() {
        assertEquals(List.of(), Collaborators.publicApi(""));
        assertEquals(List.of(), Collaborators.publicApi(null));
    }

    // ── is there something the project already ships? ─────────────────────────
    @Test
    void aConcreteImplementationTheProjectProvidesIsPreferredToHandRollingOne() {
        // java-dataloader ships SimpleStatisticsCollector and NoOpStatisticsCollector; naming
        // them is cheaper and safer than asking for an anonymous class with every method
        List<String> found = Collaborators.concreteImplementors("StatisticsCollector", List.of(
                new Collaborators.SourceFile("src/main/java/org/dataloader/stats/SimpleStatisticsCollector.java",
                        "public class SimpleStatisticsCollector implements StatisticsCollector {}"),
                new Collaborators.SourceFile("src/main/java/org/dataloader/stats/NoOpStatisticsCollector.java",
                        "public class NoOpStatisticsCollector implements StatisticsCollector {}"),
                new Collaborators.SourceFile("src/main/java/org/dataloader/stats/Statistics.java",
                        "public class Statistics {}")));
        assertEquals(List.of("NoOpStatisticsCollector", "SimpleStatisticsCollector"), found.stream().sorted().toList());
    }

    @Test
    void anAbstractImplementationIsNotSomethingATestCanInstantiate() {
        List<String> found = Collaborators.concreteImplementors("StatisticsCollector", List.of(
                new Collaborators.SourceFile("a/AbstractCollector.java",
                        "public abstract class AbstractCollector implements StatisticsCollector {}")));
        assertEquals(List.of(), found);
    }

    @Test
    void aSubclassCountsTooNotOnlyADirectImplementor() {
        List<String> found = Collaborators.concreteImplementors("StatisticsCollector", List.of(
                new Collaborators.SourceFile("a/Delegating.java",
                        "public class DelegatingStatisticsCollector extends SimpleStatisticsCollector"
                                + " implements StatisticsCollector {}")));
        assertEquals(List.of("DelegatingStatisticsCollector"), found);
    }

    @Test
    void nothingImplementsItSaySoRatherThanInventAName() {
        assertEquals(List.of(), Collaborators.concreteImplementors("Nothing",
                List.of(new Collaborators.SourceFile("a/A.java", "public class A {}"))));
        assertEquals(List.of(), Collaborators.concreteImplementors("X", List.of()));
    }

    // ── every built-in, named one at a time ───────────────────────────────────
    // The BUILT_IN table is data, and data rots silently: drop an entry and the only symptom
    // is a prompt that asks the model to construct a String. Mutation testing showed ~30
    // living mutants across these four lines, one per name, because the tests happened to
    // exercise only four of them.
    //
    // The expected names are written out HERE rather than read from BUILT_IN. Iterating the
    // set under test would be a tautology — a mutant that blanks 'Integer' would make the
    // loop assert about '' and pass. This list has to be maintained by hand; that is the
    // point of it.
    @ParameterizedTest(name = "{0} is something a test can produce without help")
    @ValueSource(strings = {
            "String", "Integer", "Long", "Short", "Byte", "Character", "Boolean", "Float", "Double",
            "Object", "Number", "CharSequence", "Class", "Throwable", "Exception", "RuntimeException",
            "List", "Map", "Set", "Collection", "Optional", "Iterable"})
    void aBuiltInIsSomethingATestCanProduceWithoutHelp(String t) {
        assertEquals(List.of(),
                Collaborators.constructorTypes("public class A { public A(" + t + " v) {} }", "A"),
                t + " was reported as a collaborator the test must construct");
    }

    // Primitives take the other route out — capitalisation, not the table. Listed separately
    // so that the two mechanisms stay visibly distinct: if someone ever drops the `^[A-Z]`
    // guard, these fail and the table above does not.
    @ParameterizedTest(name = "{0} is excluded by being lower-case, not by the table")
    @ValueSource(strings = {"int", "long", "short", "byte", "char", "boolean", "float", "double", "void"})
    void aPrimitiveIsExcludedByBeingLowerCaseNotByTheTable(String t) {
        assertEquals(List.of(), Collaborators.constructorTypes("public class A { public A(" + t + " v) {} }", "A"));
    }

    @Test
    void aTypeOutsideThatTableIsACollaborator() {
        // the negative half: without it, "return [] always" would pass every test above
        assertEquals(List.of("CacheMap"),
                Collaborators.constructorTypes("public class A { public A(CacheMap v) {} }", "A"));
        assertEquals(List.of("StatisticsCollector"),
                Collaborators.constructorTypes("public class A { public A(StatisticsCollector v) {} }", "A"));
    }

    // ── reducing a written type to the name a test would use ──────────────────
    @Test
    void genericsArraysAndVarargsAreStrippedToTheBareType() {
        assertEquals(List.of("CacheMap"),
                Collaborators.constructorTypes("public class A { public A(CacheMap<K, V> m) {} }", "A"));
        assertEquals(List.of("Widget"),
                Collaborators.constructorTypes("public class A { public A(Widget[] w) {} }", "A"));
        assertEquals(List.of("Widget"),
                Collaborators.constructorTypes("public class A { public A(Widget... w) {} }", "A"));
    }

    @Test
    void nestedGenericsAreUnwrappedCompletelyNotOneLayer() {
        // `Map<String, List<Widget>>` split naively leaves `List<Widget>>` sitting where the
        // type belongs, and the collaborator is lost without a trace
        assertEquals(List.of("Gauge"), Collaborators.constructorTypes(
                "public class A { public A(Map<String, List<Widget>> m, Gauge g) {} }", "A"));
    }

    @Test
    void aLowerCaseTypeIsNotTreatedAsAClassToConstruct() {
        // primitives beyond the table, and any identifier that cannot be a class name
        assertEquals(List.of(), Collaborators.constructorTypes("public class A { public A(foo bar) {} }", "A"));
    }

    @Test
    void theSameCollaboratorNamedTwiceIsReportedOnce() {
        assertEquals(List.of("Gauge"),
                Collaborators.constructorTypes("public class A { public A(Gauge a, Gauge b) {} }", "A"));
    }

    @Test
    void severalConstructorsContributeAllOfTheirCollaborators() {
        String src = """
                public class A {
                  public A(Gauge g) {}
                  public A(Clock c, Gauge g) {}
                }""";
        assertEquals(List.of("Clock", "Gauge"), Collaborators.constructorTypes(src, "A").stream().sorted().toList());
    }

    @Test
    void aParameterWithNoNameIsNotMistakenForAType() {
        assertEquals(List.of(), Collaborators.constructorTypes("public class A { public A() {} }", "A"));
    }

    // ── reading a collaborator's signatures ───────────────────────────────────
    @Test
    void everyModifierSpellingIsStillAMethodSignature() {
        // 18 living mutants sat on this one regex. Each modifier it accepts needs a case, or
        // removing that alternative from the pattern changes nothing any test can see.
        String src = """
                public interface I {
                  long plain(int a);
                  public long pub(int a);
                  protected long prot(int a);
                  abstract long abs(int a);
                  default long def(int a) { return 0; }
                  static long stat(int a) { return 0; }
                  final long fin(int a) { return 0; }
                }""";
        List<String> api = Collaborators.publicApi(src);
        for (String n : List.of("plain", "pub", "prot", "abs", "def", "stat", "fin")) {
            assertTrue(api.stream().anyMatch(s -> s.contains(" " + n + "(")),
                    n + " was not recognised: " + joined(api));
        }
    }

    @Test
    void aGenericQualifiedOrArrayReturnTypeSurvivesExtraction() {
        List<String> api = Collaborators.publicApi("""
                public interface I {
                  java.util.List<String> qualified();
                  Map<String, List<Long>> nested();
                  int[] arr();
                }""");
        assertTrue(some(api, "java\\.util\\.List<String>\\s+qualified\\(\\)"), joined(api));
        assertTrue(some(api, "nested\\(\\)"), joined(api));
        assertTrue(some(api, "arr\\(\\)"), joined(api));
    }

    @Test
    void bothAnInterfaceDeclarationAndAClassBodyAreRead() {
        // interface methods end in `;`, class methods in `{` — the pattern accepts either, and
        // a mutant that drops one alternative must fail something
        assertTrue(Collaborators.publicApi("public interface I {\n  long a(int x);\n}").stream()
                .anyMatch(s -> s.contains("a(int x)")));
        assertTrue(Collaborators.publicApi("public class C {\n  public long b(int x) { return 0; }\n}").stream()
                .anyMatch(s -> s.contains("b(int x)")));
    }

    @Test
    void aMemberHasToBeginALineToBeSeen() {
        // Not a bug worth fixing: real Java declares one member per line, and the line anchor is
        // what stops the pattern from matching every call expression inside a method body. It IS
        // a limitation though, so it is pinned here — a future reader should meet it in a test
        // rather than in a prompt that silently listed half an interface.
        assertEquals(List.of(), Collaborators.publicApi("public interface I { long a(int x); }"),
                "everything crammed onto one line yields nothing");
    }

    @Test
    void controlFlowKeywordsAreNotSignatures() {
        // `if (x) {` matches the shape of a method declaration almost exactly
        List<String> api = Collaborators.publicApi("""
                public class C {
                  public void go(int x) { if (x > 0) { return; } while (x > 0) { x--; } for (;;) { break; } }
                }""");
        Pattern control = Pattern.compile("\\b(if|while|for|switch|catch)\\s*\\(");
        assertEquals(List.of(), api.stream().filter(s -> control.matcher(s).find()).toList(), joined(api));
    }

    @Test
    void aSignatureAppearingTwiceIsListedOnce() {
        List<String> api = Collaborators.publicApi("public interface I {\n  long a(int x);\n  long a(int x);\n}");
        assertEquals(1, api.stream().filter(s -> s.contains("a(int x)")).count(), joined(api));
    }

    @Test
    void aMethodTakingNoArgumentsKeepsItsEmptyParameterList() {
        List<String> api = Collaborators.publicApi("public interface I {\n  Statistics getStatistics();\n}");
        assertTrue(api.stream().anyMatch(s -> s.endsWith("getStatistics()")), joined(api));
    }

    // ── which classes can stand in for a type ─────────────────────────────────
    @Test
    void extendsAndImplementsBothCountAsStandingInForTheType() {
        List<Collaborators.SourceFile> files = List.of(
                new Collaborators.SourceFile("a/A.java", "public class A implements Gauge {}"),
                new Collaborators.SourceFile("b/B.java", "public class B extends AbstractGauge implements Gauge {}"));
        assertEquals(List.of("A", "B"), Collaborators.concreteImplementors("Gauge", files).stream().sorted().toList());
    }

    @Test
    void aClassWithoutThePublicModifierStillCounts() {
        assertEquals(List.of("A"), Collaborators.concreteImplementors("Gauge",
                List.of(new Collaborators.SourceFile("a/A.java", "class A implements Gauge {}"))));
    }

    @Test
    void aGenericClassIsNamedWithoutItsTypeParameters() {
        List<String> found = Collaborators.concreteImplementors("Gauge",
                List.of(new Collaborators.SourceFile("a/A.java", "public class A<T> implements Gauge {}")));
        assertEquals(List.of("A"), found, "the test writes `new A<>()`, so the name is what matters");
    }

    @Test
    void aTypeMerelyMentionedInTheBodyIsNotAnImplementation() {
        // the declaration is everything before `{`; matching the whole file would make any
        // class that so much as references Gauge look like an implementation of it
        List<Collaborators.SourceFile> files =
                List.of(new Collaborators.SourceFile("a/A.java", "public class A implements Other { Gauge g; }"));
        assertEquals(List.of(), Collaborators.concreteImplementors("Gauge", files));
    }

    @Test
    void aSimilarlyNamedTypeIsNotAMatch() {
        List<Collaborators.SourceFile> files =
                List.of(new Collaborators.SourceFile("a/A.java", "public class A implements GaugeFactory {}"));
        assertEquals(List.of(), Collaborators.concreteImplementors("Gauge", files));
    }

    @Test
    void aFileWithNoClassDeclarationAtAllIsSkipped() {
        assertEquals(List.of(), Collaborators.concreteImplementors("Gauge", List.of(
                        new Collaborators.SourceFile("a/I.java", "public interface I extends Gauge {}"),
                        new Collaborators.SourceFile("a/E.java", ""),
                        new Collaborators.SourceFile("a/N.java", null))),
                "an interface cannot be instantiated either");
    }
}
