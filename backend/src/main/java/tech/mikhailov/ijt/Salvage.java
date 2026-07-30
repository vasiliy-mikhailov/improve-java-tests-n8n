package tech.mikhailov.ijt;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Keeping the good tests when one of a batch goes bad.
///
/// A batch round writes N tests into one file, and one bad assertion used to cost all N: the
/// suite went red, the file was deleted, and the round recorded cov 0→0 mut 0→0. That happened
/// on the very first unit of the v2 run — three targets, two asserting wrongly, three tests
/// lost.
///
/// It does not have to. The file COMPILED; the failures were assertion failures; the runner
/// names the methods that failed; and those names are the deterministic target names this
/// pipeline chose (see {@link Targets}). So the failing methods can be cut out and the rest
/// kept — which is only possible because the names were decided up front rather than left to
/// the model.
public final class Salvage {

    private Salvage() {}

    //   Gradle:   `SomeTest > methodName() FAILED`
    private static final Pattern GRADLE_FAILED =
            Pattern.compile(">\\s*([A-Za-z_$][\\w$]*)\\s*\\([^)]*\\)\\s*FAILED");
    //   Surefire: `methodName(pkg.SomeTest)  Time elapsed: 0.01 s  <<< FAILURE!`
    private static final Pattern SUREFIRE_FAILED = Pattern.compile(
            "^\\s*(?:\\[ERROR\\]\\s*)?([A-Za-z_$][\\w$]*)\\([\\w.$]+\\).*<<<\\s*(?:FAILURE|ERROR)!",
            Pattern.MULTILINE);

    /// Test method names the runner reported as failing.
    public static Set<String> failedTestNames(String log) {
        Set<String> out = new LinkedHashSet<>();
        String s = log == null ? "" : log;
        for (Pattern p : List.of(GRADLE_FAILED, SUREFIRE_FAILED)) {
            Matcher m = p.matcher(s);
            while (m.find()) out.add(m.group(1));
        }
        return out;
    }

    /// Boilerplate that says nothing a reader of this event does not already know.
    ///
    /// "BUILD FAILURE" is why we are here; "-> [Help 1]" is Maven advertising. Neither
    /// distinguishes a test that would not compile from one that asserted the wrong thing, which
    /// is the only distinction this line exists to make.
    /// `Tests run: 3, Failures: 1` is a COUNT, not a cause. It is also the line Surefire prints
    /// first, so leaving it in would hide the very next line — the one naming the test that
    /// failed, which is the whole point.
    private static final List<String> NOISE = List.of(
            "BUILD FAILURE", "-> [Help", "----", "To see the full stack trace",
            "Re-run Maven using", "For more information about the errors", "Tests run:");

    /// The single most informative line of a suite failure, or "" when there is none.
    ///
    /// 814 rounds were deleted for breaking the suite without one word about the cause, which is
    /// 58% of every miss this pipeline has recorded. The remedy for a test that will not compile
    /// (more type context in the prompt) and the remedy for a test that compiles and asserts
    /// wrongly (a better description of the mutant) are opposites, and nothing in the log said
    /// which was happening.
    ///
    /// An empty answer means NOT CAPTURED and must read that way. Guessing a cause here would put
    /// a fabricated explanation into the record people use to judge the run — this project has
    /// invented two verdicts before and paid for both.
    public static String whyItBroke(String log) {
        if (log == null || log.isBlank()) return "";
        String best = null;
        for (String raw : log.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            if (NOISE.stream().anyMatch(line::contains)) continue;
            // strip the level marker; it is the same on every line and costs width
            String bare = line.replaceFirst("^\\[(?:ERROR|WARNING|INFO)\\]\\s*", "").strip();
            if (bare.isEmpty() || bare.equals("COMPILATION ERROR :")) continue;
            // The FIRST real error explains the build; Maven repeats and then trails off into
            // help text, so later lines are progressively less informative.
            best = bare;
            break;
        }
        if (best == null) return "";
        return best.length() <= 200 ? best : best.substring(0, 197) + "...";
    }

    /// Where the method's body ends, counting braces over code only.
    ///
    /// Braces inside string literals and comments are not structure: a test asserting on "}"
    /// would otherwise appear to end halfway through, and the cut would take the rest of the
    /// class with it. stripNonCode blanks those out while preserving offsets.
    public static int bodyEnd(String src, int openIdx) {
        String code = JavaSrc.stripNonCode(src);
        int depth = 0;
        for (int i = openIdx; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') depth += 1;
            else if (c == '}') {
                depth -= 1;
                if (depth == 0) return i + 1;
            }
        }
        return -1;
    }

    /// Remove the named test methods, with their annotations, leaving everything else intact.
    /// Unknown names are ignored — the caller passes whatever the runner said, and the runner
    /// also names tests from other classes.
    public static String dropTestMethods(String source, Set<String> names) {
        String src = source == null ? "" : source;
        if (names == null || names.isEmpty()) return src;
        for (String name : names) {
            Pattern decl = Pattern.compile(
                    "(^[ \\t]*(?:@[\\w.]+(?:\\([^)]*\\))?[ \\t]*\\r?\\n[ \\t]*)*)"
                            + "[\\w<>\\[\\], ]*?\\b" + Pattern.quote(name) + "\\s*\\([^)]*\\)[^{;]*\\{",
                    Pattern.MULTILINE);
            Matcher m = decl.matcher(src);
            if (!m.find()) continue;
            int open = src.indexOf('{', m.end() - 1);
            int end = bodyEnd(src, open);
            if (end < 0) continue;
            // take the trailing newline too, so removals do not leave a widening gap
            int stop = end;
            while (stop < src.length() && (src.charAt(stop) == '\n' || src.charAt(stop) == '\r')) stop += 1;
            src = src.substring(0, m.start()) + src.substring(stop);
        }
        return src;
    }

    /// The single decision both droppers make: cut the named methods, and say whether anything
    /// worth keeping is left.
    ///
    /// Two callers reach for this — the red-suite path and the PIT green-suite recovery — and
    /// when only the first knew how to salvage, a batch rescued from a red suite was wiped
    /// whole moments later by the second.
    ///
    /// @return null when there is nothing to keep: no source, no named method found in it, or
    ///         every test cut. A file whose only test failed is one to delete, not to keep empty.
    public static String salvageSource(String src, Set<String> names) {
        String s = src == null ? "" : src;
        if (s.isEmpty() || names == null || names.isEmpty()) return null;
        Set<String> mine = new LinkedHashSet<>();
        for (String n : names) {
            if (Pattern.compile("\\b" + Pattern.quote(n) + "\\s*\\(").matcher(s).find()) mine.add(n);
        }
        if (mine.isEmpty()) return null;
        String kept = dropTestMethods(s, mine);
        return kept.contains("@Test") ? kept : null;
    }
}
