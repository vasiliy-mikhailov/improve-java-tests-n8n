package tech.mikhailov.ijt;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Deciding whether a model's "cleaned" test file may replace the one on disk.
///
/// The cleanup pass strips chain-of-thought scratch comments and vacuous tests (D11/D12).
/// Its gate was inherited from the JavaScript pipeline and asked whether the result
/// contained `it(`, `test(` or `describe(` — shapes no Java test file has. Every cleanup
/// on a Java repo was therefore rejected as "implausible cleanup output", silently, so the
/// pass has never actually run on the repos this pipeline targets.
///
/// The gate below asks Java questions, and each one is a way the pass could damage a PR:
/// a file that no longer compiles, tests deleted wholesale, invented tests, or reflection
/// smuggled in past the write_test rules.
public final class Cleanup {

    private Cleanup() {}

    private static final Pattern TEST_ANNOTATION =
            Pattern.compile("@(?:Test|ParameterizedTest|RepeatedTest|TestFactory)\\b");
    private static final Pattern REFLECTION = Pattern.compile(
            "\\b(?:setAccessible|getDeclaredMethod|getDeclaredField|Class\\.forName|MethodHandles)\\b");

    /// A thinking block, if the completion opened with one.
    private static final Pattern THINK_BLOCK = Pattern.compile("^[\\s\\S]*?</think>");
    private static final Pattern FENCE_OPEN = Pattern.compile("^\\s*```[a-z]*\\s*\\n?", Pattern.MULTILINE);
    private static final Pattern FENCE_CLOSE = Pattern.compile("```\\s*$", Pattern.MULTILINE);

    /// The file content out of a raw completion: no thinking block, no markdown fence.
    public static String extractCleanedFile(String text) {
        String body = text == null ? "" : text;
        // each of these strips at most ONE occurrence, exactly as the JS `.replace(re, ...)`
        // without the /g flag does — a fence inside the file itself is left alone
        body = THINK_BLOCK.matcher(body).replaceFirst("");
        body = FENCE_OPEN.matcher(body).replaceFirst("");
        body = FENCE_CLOSE.matcher(body).replaceFirst("");
        body = body.trim();
        return body.isEmpty() ? "" : body + "\n";
    }

    private static final Pattern PUBLIC_CLASS =
            Pattern.compile("public\\s+(?:final\\s+|abstract\\s+)?class\\s+(\\w+)");

    /// The declared public class, or null when the text has none.
    static String publicClassOf(String src) {
        Matcher m = PUBLIC_CLASS.matcher(src == null ? "" : src);
        return m.find() ? m.group(1) : null;
    }

    static int countTests(String src) {
        Matcher m = TEST_ANNOTATION.matcher(src == null ? "" : src);
        int n = 0;
        while (m.find()) n += 1;
        return n;
    }

    private static final Pattern TEST_NAME = Pattern.compile(
            "@(?:Test|ParameterizedTest|RepeatedTest|TestFactory)\\b[\\s\\S]{0,400}?\\b(\\w+)\\s*\\(");

    /// Names of the annotated test methods — cleanup may drop these, never invent one.
    static Set<String> testNames(String src) {
        // insertion-ordered, because the rejection message lists the invented names
        Set<String> names = new LinkedHashSet<>();
        Matcher m = TEST_NAME.matcher(src == null ? "" : src);
        while (m.find()) names.add(m.group(1));
        return names;
    }

    private static final Pattern PACKAGE_DECL = Pattern.compile("\\bpackage\\s+[\\w.]+\\s*;");

    /// The verdict on a cleanup.
    ///
    /// @param reason       why it was refused — null when it was accepted
    /// @param testsBefore  null on refusal: a rejected cleanup was never counted, and a
    ///                     primitive 0 here would read as "the original had no tests"
    /// @param testsAfter   likewise
    public record Verdict(boolean ok, String reason, Integer testsBefore, Integer testsAfter) {
        static Verdict no(String reason) { return new Verdict(false, reason, null, null); }
        static Verdict yes(int testsBefore, int testsAfter) {
            return new Verdict(true, null, testsBefore, testsAfter);
        }
    }

    public static Verdict plausibleCleanup(String original, String cleaned) {
        String before = original == null ? "" : original, after = cleaned == null ? "" : cleaned;
        if (after.trim().isEmpty()) return Verdict.no("empty result");
        if (after.trim().equals(before.trim())) return Verdict.no("no changes");

        // the class name is the file name; renaming it stops the file compiling
        String wantClass = publicClassOf(before);
        if (wantClass != null && !wantClass.equals(publicClassOf(after))) {
            String got = publicClassOf(after);
            return Verdict.no("public class changed (" + wantClass + " → " + (got == null ? "none" : got) + ")");
        }
        if (!PACKAGE_DECL.matcher(after).find() && PACKAGE_DECL.matcher(before).find()) {
            return Verdict.no("package declaration lost");
        }

        int testsBefore = countTests(before), testsAfter = countTests(after);
        // pruning a vacuous test is the job; pruning ALL of them is a decision to delete the
        // file, which this pass is not allowed to make silently
        if (testsBefore > 0 && testsAfter == 0) return Verdict.no("result has no @Test methods left");
        // counting is not enough: dropping one test and inventing another keeps the total.
        // An invented test has never been run against a mutant and earns nothing.
        Set<String> known = testNames(before);
        List<String> invented = testNames(after).stream().filter(n -> !known.contains(n)).toList();
        if (!invented.isEmpty()) {
            return Verdict.no("cleanup added tests it invented (" + String.join(", ", invented)
                    + "); it may only remove");
        }

        if (REFLECTION.matcher(after).find() && !REFLECTION.matcher(before).find()) {
            return Verdict.no("cleanup introduced reflection, which the write_test rules forbid");
        }
        // a truncated completion looks like a small tidy-up; require the result to still be a
        // recognisable file, and never much LONGER than what it was cleaning
        if (after.length() < before.length() * 0.2) return Verdict.no("result far too short — likely truncated");
        if (after.length() > before.length() * 1.2) return Verdict.no("result longer than the original — not a cleanup");

        return Verdict.yes(testsBefore, testsAfter);
    }

    /// Is this file worth putting in a pull request?
    ///
    /// A zero-byte XMLMacMutR2Test.java reached a prepared PR — a new file with an empty blob
    /// and no hunks. Dead weight in a deliverable is precisely what D12 forbids, and an empty
    /// or test-less class earns nothing wherever it came from.
    public static boolean worthCommitting(String content) {
        String src = content == null ? "" : content;
        if (src.trim().isEmpty()) return false;
        return countTests(src) > 0;
    }

    private static final Pattern GENERATED_TEST = Pattern.compile("Mac(Cov|Mut)(R\\d+)?Test\\.java$");

    /// The unit a generated test path belongs to: every round, cov and mut alike, share a stem.
    private static String stem(String p) {
        return GENERATED_TEST.matcher(p == null ? "" : p).replaceFirst("Mac");
    }

    /// Of the test files changed on this branch, the ones belonging to THIS unit.
    ///
    /// The pass used to clean every changed file but re-measure only the current unit — so a
    /// strip that broke an earlier method's kill passed verification on a score that could not
    /// see it. It happened: JSONObjectGetElementTypeMacMutTest was stripped and kept while
    /// isNumberSimilar was current, having already been reverted once for dropping
    /// getElementType from 80 to 60. Earlier units' files were verified when those units were
    /// current; they are not this round's business.
    public static List<String> ownedByUnit(List<String> changed, TestPaths.Generated paths) {
        Set<String> mine = new LinkedHashSet<>();
        for (String p : new String[]{
                paths == null ? null : paths.covPath(),
                paths == null ? null : paths.mutPath()}) {
            String s = stem(p);
            // an absent path stems to "" and is dropped, never matching everything
            if (!s.isEmpty()) mine.add(s);
        }
        List<String> out = new ArrayList<>();
        for (String p : changed == null ? List.<String>of() : changed) {
            if (mine.contains(stem(p)) && GENERATED_TEST.matcher(p == null ? "" : p).find()) out.add(p);
        }
        return out;
    }
}
