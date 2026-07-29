package tech.mikhailov.ijt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/// Stop the build publishing a scan of the user's repo to gradle.com on every invocation.
///
/// java-dataloader applies `com.gradle.develocity` in settings.gradle with
/// `publishing.onlyIf { true }` — a build scan on every build. Two costs, both real:
///
///   the pipeline runs the suite, the coverage build and PIT once per round, so it was
///   sending build data off the machine dozens of times per unit, unasked; and
///
///   in the container the upload times out. One `./gradlew test` sat for 780 seconds with
///   "Publishing Build Scan failed due to network error ... (1 retry remaining)" as its
///   last output, and the first batch spent 94 minutes improving a three-mutant method.
///
/// `--no-scan` fixes both — but Gradle rejects it as an unknown command-line option on a
/// build that applies no scan plugin, which would fail every invocation on every other
/// Gradle repo. So it is passed only where a scan plugin is actually applied.
public final class GradleScan {

    private GradleScan() {}

    /// The plugin ids, and the extension blocks that configure them. Matched against build
    /// scripts with comments stripped: a line saying "we do not use buildScan" must not make
    /// us pass a flag Gradle would then reject.
    public static final List<Pattern> SCAN_MARKERS = List.of(
            Pattern.compile("\\bcom\\.gradle\\.develocity\\b"),
            Pattern.compile("\\bcom\\.gradle\\.enterprise\\b"),
            Pattern.compile("\\bcom\\.gradle\\.build-scan\\b"),
            Pattern.compile("\\bdevelocity\\s*\\{"),
            Pattern.compile("\\bgradleEnterprise\\s*\\{"),
            Pattern.compile("\\bbuildScan\\s*\\{"));

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    // `[^:]` keeps a URL's "https://" from reading as a comment — the develocity block this
    // has to recognise configures termsOfUseUrl, and stripping from there would blank the
    // rest of the line. `^` is deliberately NOT multi-line, matching the JS: a comment at the
    // start of any later line is preceded by the newline, which `[^:]` matches and restores.
    private static final Pattern LINE_COMMENT = Pattern.compile("(^|[^:])//[^\n]*");

    /// Comments are not configuration.
    static String stripComments(String s) {
        String src = s == null ? "" : s;
        return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(src).replaceAll(" ")).replaceAll("$1");
    }

    /// Does any of these build scripts apply a build-scan plugin?
    ///
    /// @param scripts contents of settings.gradle, build.gradle, ... — the list itself and any
    ///                entry may be null, since a script that is not there reads as nothing
    public static boolean appliesBuildScan(List<String> scripts) {
        if (scripts == null) return false;
        for (String s : scripts) {
            String code = stripComments(s);
            for (Pattern re : SCAN_MARKERS) {
                if (re.matcher(code).find()) return true;
            }
        }
        return false;
    }

    /// The flag, where Gradle will accept it, and nothing where it would not.
    public static List<String> gradleScanArgs(List<String> scripts) {
        return appliesBuildScan(scripts) ? List.of("--no-scan") : List.of();
    }

    /// One script rather than a list — the JS took either, and callers that read a single
    /// settings.gradle should not have to wrap it.
    public static List<String> gradleScanArgs(String script) {
        return gradleScanArgs(Collections.singletonList(script));
    }

    /// The argv of one Gradle invocation, assembled from parts, skipping any part that is null
    /// — `scanArgs` is absent, not empty, on a runner detected before the scripts were read.
    ///
    /// It exists so that a call site can name `--no-daemon` and its `scanArgs` in the SAME
    /// source line, which is not cosmetic. The guard that keeps every Gradle invocation
    /// passing the flag scans the backend sources line by line (see GradleScanTest), because
    /// enumerating the call sites by hand is exactly how install() shipped without it. An argv
    /// built one `add` per token hides the scan args from that guard, so a call site could
    /// drop them and still look right.
    @SafeVarargs
    public static List<String> argv(List<String>... parts) {
        List<String> out = new ArrayList<>();
        for (List<String> p : parts) if (p != null) out.addAll(p);
        return out;
    }
}
