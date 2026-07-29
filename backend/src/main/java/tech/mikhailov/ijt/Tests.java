package tech.mikhailov.ijt;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Run the project's test suite (optionally scoped to one test class). No coverage — this
/// is the fast green/red gate that guards every round, and PIT refuses to run on red tests.
///
/// Everything that decides something — which command to run, whether a log says green, which
/// forty lines of a build log an LLM can act on — is a static function over its arguments and
/// is tested as one. The only IO is the child process and the event line that records it, and
/// both go through {@link Io}, which the server wires to the real exec/state/repo modules.
public final class Tests {

    private Tests() {}

    /// The part of `state.runner` this module reads.
    ///
    /// Every field is nullable: build detection runs after the clone, and until it has, the
    /// runner is null — which is precisely the case {@link #argv} refuses to guess at.
    /// `scanArgs` is Gradle-only and decided once per repo by {@link GradleScan} — `--no-scan`
    /// where a build-scan plugin is applied, and nothing at all where none is, because Gradle
    /// rejects the flag as an unknown option on a build that has no scan plugin. It is
    /// absent (null) for Maven.
    public record Build(String tool, String wrapper, List<String> scanArgs) {}

    /// One finished child process, as the exec wrapper reports it.
    ///
    /// `exitCode` is boxed on purpose. A process killed at the timeout dies by signal and has
    /// no exit code at all; JS normalised that to 1 inside exec.run before this module ever
    /// saw it, and a Java primitive defaulting to 0 would report a suite that never finished
    /// as green. The normalisation is therefore repeated here, visibly — see {@link #report}.
    public record Ran(Integer exitCode, String stdout, String stderr, boolean timedOut) {}

    /// What `/api/test/run` answers with. These field names are a wire contract: the n8n
    /// workflow branches on `passed` and feeds `summary` into the repair prompt.
    ///
    /// `exitCode` is a primitive because by this point it cannot be unknown — {@link #report}
    /// has already turned "killed, no code" into a failing 1.
    public record Result(boolean passed, int exitCode, String summary) {}

    /// The outside world, as this module needs it. One interface, five calls, so the wiring is
    /// mechanical and the decisions above stay testable without a repo on disk.
    ///
    /// Maps onto the Node sidecar one-for-one:
    /// `repoDir()` → repo.repoDir(), `runner()` → state.runner, `buildEnv()` → repo.buildEnv(),
    /// `run(...)` → exec.run(argv, {cwd, timeoutMs, label, env}), `event(...)` → state.event.
    public interface Io {
        /// Clone directory of the repo under improvement; the child's working directory.
        String repoDir();

        /// The detected build, or null when detection has not run.
        Build runner();

        /// JAVA_HOME/PATH for the JDK this project must build under — PIT's forked minion
        /// dies under the wrong one, so every build command carries it.
        Map<String, String> buildEnv();

        /// Spawn a child and wait for it.
        ///
        /// @param argv      command and arguments, no shell
        /// @param cwd       working directory
        /// @param timeoutMs after which the whole process GROUP is killed — Maven and Gradle
        ///                  fork Surefire JVMs that would otherwise be orphaned and keep
        ///                  burning CPU
        /// @param label     what the dashboard's progress line calls this
        /// @param env       extra environment on top of the parent's
        Ran run(List<String> argv, String cwd, long timeoutMs, String label, Map<String, String> env);

        /// Append one line to the run's event log.
        void event(String stage, String msg);
    }

    /// {@link Exec}, adapted to {@link Io#run} — the one line a server-side `Io` needs, so
    /// that wiring this module is four lookups (`repo.repoDir()`, `state.runner`,
    /// `repo.buildEnv()`, `state.event`) and a method reference to this.
    ///
    /// The child process stays behind the interface even so. The whole point of the seam is
    /// that the decisions above — which command, green or red, which lines of the log — can
    /// be tested without spawning Maven, and a module that reached for Exec directly could
    /// not be asked what it does with an exit code that never arrived.
    public static Ran exec(List<String> argv, String cwd, long timeoutMs, String label,
                           Map<String, String> env) {
        Exec.Result r = Exec.run(argv, Exec.Options.of()
                .withCwd(cwd == null ? null : Path.of(cwd))
                .withEnv(env)
                .withTimeoutMs(timeoutMs)
                .withLabel(label));
        return new Ran(r.code(), r.stdout(), r.stderr(), r.timedOut());
    }

    /// @param io    the world: the repo directory, the detected build, the child process
    /// @param scope repo-relative test file path, a test class name, or null for everything
    public static Result runTests(Io io, String scope) {
        String dir = io.repoDir();
        Build build = io.runner();
        List<String> argv = argv(build, scope);
        // Shared with the workflow generator: the n8n node calling this route derives its own
        // timeout from this number, and must outlive it. See Timeouts.
        Ran r = io.run(argv, dir, Timeouts.SUITE_RUN_MS, "tests", io.buildEnv());
        Result result = report(r);
        io.event("tests", eventLine(scope, result));
        return result;
    }

    /// The command that runs the suite. A decision, not IO — which is why it is tested.
    ///
    /// @param scope repo-relative test file path, a test class name, or null for everything
    static List<String> argv(Build build, String scope) {
        // No detected build means no command to guess at. The route turns this into
        // `{ok:false, error}` rather than a run that measures nothing.
        if (build == null || build.tool() == null || build.tool().isEmpty()) {
            throw new IllegalStateException("build not detected");
        }
        // An empty scope is "everything", exactly as the falsy check in JS read it — an empty
        // string must not become a filter matching no test at all.
        String cls = (scope == null || scope.isEmpty()) ? null : classNameOf(scope);
        List<String> argv = new ArrayList<>();
        argv.add(build.wrapper());
        if ("maven".equals(build.tool())) {
            argv.add("-B");
            argv.add("-ntp");
            argv.add("test");
            argv.add("-DfailIfNoTests=false");
            if (cls != null) {
                // A scoped run matches nothing in most modules of a multi-module repo, and
                // Surefire fails the build for that unless BOTH of these say otherwise —
                // `-DfailIfNoTests` for the plugin's own check and
                // `-Dsurefire.failIfNoSpecifiedTests` for the `-Dtest=` filter specifically.
                // The first is repeated verbatim from the base argv above: Maven does not
                // mind, and the command line stays the one production has been running.
                argv.add("-Dtest=" + cls);
                argv.add("-DfailIfNoTests=false");
                argv.add("-Dsurefire.failIfNoSpecifiedTests=false");
            }
        } else {
            argv.addAll(GradleScan.argv(List.of("--no-daemon"), build.scanArgs()));
            argv.add("test");
            if (cls != null) {
                argv.add("--tests");
                argv.add(cls);
            }
        }
        return argv;
    }

    private static final Pattern TEST_SOURCE_ROOT = Pattern.compile("src/test/java/(.+)\\.java$");
    private static final Pattern DOT_JAVA = Pattern.compile("\\.java$");

    /// Simple class name (or FQCN) from a test file path — what Maven/Gradle filters take.
    public static String classNameOf(String scope) {
        // The caller filters null before asking (see argv); answering null rather than
        // throwing keeps that the caller's decision and not a crash inside a filter argument.
        if (scope == null) return null;
        if (!scope.endsWith(".java")) return scope;
        Matcher m = TEST_SOURCE_ROOT.matcher(scope);
        if (m.find()) return m.group(1).replace('/', '.');
        return DOT_JAVA.matcher(scope.substring(scope.lastIndexOf('/') + 1)).replaceFirst("");
    }

    /// A green run, a red run, and which of the two the child actually reported.
    ///
    /// A killed or crashed child has no exit code, and that must resolve to a failure and
    /// never to success: reporting an unfinished suite as green lets a broken test through
    /// the one gate the whole round depends on. Both stdout and stderr are searched, because
    /// Maven writes its errors to one and the JVM writes stack traces to the other.
    static Result report(Ran r) {
        String out = (r.stdout() == null ? "" : r.stdout()) + "\n" + (r.stderr() == null ? "" : r.stderr());
        // exactly what exec.run did in JS (`code == null ? 1 : code`), repeated here so this
        // module is right regardless of which wrapper hands it the result
        int code = r.exitCode() == null ? 1 : r.exitCode();
        // `timedOut` is not in the JS condition because it could not be: a killed child there
        // always arrived with a null code. Under ProcessBuilder a process that finishes in the
        // same instant it is destroyed can still report 0, and a suite abandoned at the
        // one-hour ceiling has not passed.
        boolean passed = code == 0 && !r.timedOut();
        return new Result(passed, code, failureSummary(out, passed));
    }

    /// The event-log line for a finished run. `scope` absent — or empty — is the full suite.
    static String eventLine(String scope, Result result) {
        String what = (scope == null || scope.isEmpty()) ? "full suite" : scope;
        return what + ": " + (result.passed() ? "green" : "RED (exit " + result.exitCode() + ")");
    }

    private static final Pattern TESTS_RUN = Pattern.compile("Tests run:");

    /// Lines worth keeping out of a failing build: compiler errors, failure counts, the
    /// assertion's own words, the frames of the stack trace that name a .java file.
    private static final Pattern INTERESTING = Pattern.compile(
            "^\\[ERROR\\]|COMPILATION ERROR|error: |Tests run:.*(Failures|Errors): [1-9]|expected:"
                    + "|but was:|^\\s+at [\\w.$]+\\(.*\\.java:\\d+\\)|FAILED|Caused by:");

    private static final Pattern ERROR_PREFIX = Pattern.compile("^\\[ERROR\\]\\s*");

    private static final int MAX_KEPT_LINES = 60;
    private static final int FALLBACK_TAIL_LINES = 40;
    private static final int MAX_SUMMARY_CHARS = 4000;
    private static final int MAX_GREEN_SUMMARY_CHARS = 500;

    /// The part of a Java build log an LLM can act on: compiler errors and test failures,
    /// not the thousand lines of dependency resolution around them.
    static String failureSummary(String out, boolean passed) {
        String text = out == null ? "" : out;
        // JS String.split('\n') keeps trailing empty fields; Java drops them without the -1
        // limit, which would quietly change which lines "the last 40" are.
        String[] lines = text.split("\n", -1);
        if (passed) {
            // the LAST "Tests run:" line — Maven's per-module lines come first and the
            // aggregate comes last
            String line = null;
            for (int i = lines.length - 1; i >= 0; i--) {
                if (TESTS_RUN.matcher(lines[i]).find()) { line = lines[i]; break; }
            }
            String s = (line == null ? "build succeeded" : line).trim();
            return s.length() > MAX_GREEN_SUMMARY_CHARS ? s.substring(0, MAX_GREEN_SUMMARY_CHARS) : s;
        }
        List<String> keep = new ArrayList<>();
        for (String l : lines) {
            if (INTERESTING.matcher(l).find()) keep.add(ERROR_PREFIX.matcher(l).replaceFirst("").trim());
            // checked after the line is kept, so the cut lands one past the limit exactly as
            // the JS loop did
            if (keep.size() > MAX_KEPT_LINES) break;
        }
        // Nothing matched: a build can fail without saying any of the above (a plugin that
        // dies, a wrapper that cannot download). The tail is the only account there is.
        List<String> use = keep.isEmpty() ? tail(lines, FALLBACK_TAIL_LINES) : keep;
        String joined = String.join("\n", use);
        // the TAIL, not the head: the failure is at the end of a build log, and truncating
        // from the front would keep the dependency downloads and drop the error
        return joined.length() > MAX_SUMMARY_CHARS
                ? joined.substring(joined.length() - MAX_SUMMARY_CHARS)
                : joined;
    }

    private static List<String> tail(String[] lines, int n) {
        return List.of(lines).subList(Math.max(0, lines.length - n), lines.length);
    }

    /// The verdict a build log supports on its own.
    ///
    /// @param passed   true, false, or NULL for "this log does not say"
    /// @param tests    counts as reported, or null when the log carries none. A Gradle build
    ///                 prints no counts on success, and that is not zero tests.
    public record Suite(Boolean passed, Integer tests, Integer failures, Integer errors, String summary) {}

    private static final Pattern TOTALS =
            Pattern.compile("Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+)");
    private static final Pattern GRADLE_SUCCESS = Pattern.compile("^BUILD SUCCESSFUL", Pattern.MULTILINE);
    // The `^` binds to the first alternative only — `Task :test FAILED` is matched anywhere on
    // a line, exactly as the unanchored branch of the JS literal was.
    private static final Pattern GRADLE_FAILURE =
            Pattern.compile("^FAILURE: Build failed|Task :test FAILED", Pattern.MULTILINE);

    /// Did the suite pass, judged from the build log alone?
    ///
    /// verify used to run the full suite, then run it AGAIN under the JaCoCo agent, then let
    /// PIT run it a third time — three executions of 1163 tests per round on JSON-java, two of
    /// them saying the same thing. The coverage run passes
    /// `-Dmaven.test.failure.ignore=true` so its exit code is always 0; the reported counts are
    /// the only honest signal in it.
    ///
    /// `passed` is deliberately three-valued. NULL means "this log does not say", and the
    /// caller must then run the suite properly — reading an unclear log as green is exactly the
    /// kind of unmeasured claim this project keeps paying for.
    public static Suite suiteOutcome(String log) {
        String out = log == null ? "" : log;
        if (out.isBlank()) return new Suite(null, null, null, null, "");

        // Maven: the LAST "Tests run:" line is the aggregate across modules
        Matcher m = TOTALS.matcher(out);
        int[] last = null;
        while (m.find()) {
            last = new int[]{
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3))};
        }
        if (last != null) {
            int tests = last[0], failures = last[1], errors = last[2];
            // nothing ran → nothing was proven, whatever the build says. The counts are still
            // reported: they are known to be zero, which is not the same as unknown.
            if (tests == 0) return new Suite(null, tests, failures, errors, failureSummary(out, false));
            boolean passed = failures == 0 && errors == 0 && !out.contains("COMPILATION ERROR");
            return new Suite(passed, tests, failures, errors, failureSummary(out, passed));
        }
        if (out.contains("COMPILATION ERROR") || out.contains("cannot find symbol")) {
            return new Suite(false, null, null, null, failureSummary(out, false));
        }
        // Gradle prints no counts on success
        if (GRADLE_SUCCESS.matcher(out).find()) return new Suite(true, null, null, null, "");
        if (GRADLE_FAILURE.matcher(out).find()) {
            return new Suite(false, null, null, null, failureSummary(out, false));
        }
        return new Suite(null, null, null, null, "");
    }
}
