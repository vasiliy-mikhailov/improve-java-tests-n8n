package tech.mikhailov.ijt;

import java.util.List;
import java.util.regex.Pattern;

/// Where a generated test file goes, and what its class is called.
///
/// This is not cosmetic. Surefire only runs classes matching its default includes, and a
/// generated class named `PricingMacTestCov` compiled fine, was silently skipped by the suite,
/// left JaCoCo with nothing to record — while PIT, which selects tests by its own glob, ran it
/// anyway and reported 100% mutation on a class with 0% coverage. Green suite, confident
/// numbers, all of them false. Hence SUREFIRE_INCLUDED below, asserted against every name this
/// class can produce.
public final class TestPaths {

    private TestPaths() {}

    /// Surefire/Gradle default test-class includes.
    public static final List<Pattern> SUREFIRE_INCLUDED = List.of(
            Pattern.compile("^Test[A-Z0-9_].*$"),
            Pattern.compile("Test$"),
            Pattern.compile("Tests$"),
            Pattern.compile("TestCase$"));

    /// The two files a round may write.
    public record Generated(String covPath, String mutPath) {}

    private static final Pattern MAIN_JAVA = Pattern.compile("(^|/)src/main/java/");

    /// src/main/java/... → src/test/java/..., preserving any module prefix and the package.
    public static String toTestRoot(String srcRel) {
        String s = srcRel == null ? "" : srcRel;
        // replaceFirst, not replaceAll: the JS is `.replace(re, …)` with no /g, so only the
        // FIRST occurrence moves. A repo that vendors sources under `a/src/main/java/x/src/
        // main/java/C.java` has two, and rewriting the inner one invents a path nothing wrote.
        if (MAIN_JAVA.matcher(s).find()) return MAIN_JAVA.matcher(s).replaceFirst("$1src/test/java/");
        // repos that keep sources elsewhere still get their generated tests in the standard
        // place — never written next to production code
        String pkgPath = s.replaceAll("^.*?((?:[a-z][\\w]*/)*[A-Z][\\w$]*\\.java)$", "$1");
        return "src/test/java/" + (pkgPath.equals(s) ? s.substring(s.lastIndexOf('/') + 1) : pkgPath);
    }

    static String baseName(String srcRel) {
        String s = srcRel == null ? "" : srcRel;
        return s.substring(s.lastIndexOf('/') + 1).replaceAll("\\.java$", "");
    }

    /// A Java-identifier fragment for a method name: `optEnum` → `OptEnum`, `<init>` → `Ctor`.
    public static String methodTag(String method) {
        String m = method == null ? "" : method;
        if (m.isEmpty()) return "";
        if (m.equals("<init>")) return "Ctor";
        if (m.equals("<clinit>")) return "StaticInit";
        String clean = m.replaceAll("[^A-Za-z0-9]", "");
        return clean.isEmpty() ? "" : Character.toUpperCase(clean.charAt(0)) + clean.substring(1);
    }

    /// The unit is a METHOD, so the file name has to name the method.
    ///
    /// Round alone was not enough: JSONArray#optEnum wrote rounds 1-4, then JSONArray#putAll
    /// began at round 1 and overwrote optEnum's round-1 file — its test, a verified kill,
    /// vanished from the prepared PR while the PR still reported the MAC that test had been
    /// measured with.
    ///
    /// Each ROUND still needs its own name too: Java forbids two public classes of one name,
    /// and earlier rounds are already committed.
    public static Generated generatedTestPaths(String srcRel, int round, String method) {
        String testRoot = toTestRoot(srcRel);
        String dir = testRoot.contains("/") ? testRoot.substring(0, testRoot.lastIndexOf('/')) : ".";
        String cls = baseName(srcRel) + methodTag(method);
        String suffix = round > 1 ? "R" + round + "Test.java" : "Test.java";
        return new Generated(dir + "/" + cls + "MacCov" + suffix, dir + "/" + cls + "MacMut" + suffix);
    }

    public static Generated generatedTestPaths(String srcRel) {
        return generatedTestPaths(srcRel, 1, "");
    }

    /// Where the project's OWN test for this class would live, most likely first.
    public static List<String> existingTestCandidates(String srcRel) {
        String testRoot = toTestRoot(srcRel);
        String dir = testRoot.contains("/") ? testRoot.substring(0, testRoot.lastIndexOf('/')) : ".";
        String cls = baseName(srcRel);
        return List.of(
                dir + "/" + cls + "Test.java",
                dir + "/" + cls + "Tests.java",
                dir + "/Test" + cls + ".java",
                dir + "/" + cls + "TestCase.java");
    }
}
