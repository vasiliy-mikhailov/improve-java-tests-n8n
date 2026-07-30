package tech.mikhailov.ijt;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Line coverage via JaCoCo.
///
/// Maven: JaCoCo's goals are invoked directly (prepare-agent sets the `argLine` property
/// Surefire picks up), so a project without JaCoCo configured needs no pom edit.
/// Gradle: an INIT SCRIPT applies the jacoco plugin and forces an XML report, so the
/// repo's own build files stay untouched.
///
/// Coverage is keyed by fully-qualified class name — that is what JaCoCo reports and
/// what maps cleanly onto a Java "file" (one public class per file).
///
/// The parsing and the decisions are static and pure, exactly as the JavaScript kept them:
/// {@link #mergeReport}, {@link #lineCounter}, {@link #staleCoverageArtifacts},
/// {@link #unitCoverage} and the argv builders answer questions without touching a process.
/// Only {@link #runCoverage} and {@link #uncoveredLines} do IO, and they reach the rest of
/// the backend through {@link Io} — one narrow seam instead of static coupling to the
/// state store, the repo module and the exec wrapper.
public final class Coverage {

    private Coverage() {}

    /// The agent and report plugin we supply when the project has none. Read once at load,
    /// as the JS `process.env.JACOCO_VERSION || '0.8.12'` does.
    public static final String JACOCO_VERSION = envOr("JACOCO_VERSION", "0.8.12");

    public static final String INIT_SCRIPT = ".ijt-jacoco.init.gradle";

    private static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        // JS `||` treats "" as absent, and an empty JACOCO_VERSION would build the goal
        // `org.jacoco:jacoco-maven-plugin::prepare-agent`, which Maven rejects.
        return (v == null || v.isEmpty()) ? fallback : v;
    }

    public static String gradleInitScript() {
        return """
                // injected by improve-java-tests-spring — applies JaCoCo and forces an XML report
                allprojects { p ->
                  p.plugins.withId('java') {
                    p.apply plugin: 'jacoco'
                    p.tasks.withType(Test).configureEach { t -> t.ignoreFailures = true; t.finalizedBy(p.tasks.named('jacocoTestReport')) }
                    p.tasks.named('jacocoTestReport').configure { r ->
                      r.dependsOn p.tasks.withType(Test)
                      r.reports { xml.required = true; html.required = false }
                    }
                  }
                }
                """;
    }

    // ── the data JaCoCo's XML carries ─────────────────────────────────────────────────

    /// One source line's hit counters: `mi` missed instructions, `ci` covered instructions.
    public record LineHit(int mi, int ci) {}

    /// A LINE counter's two numbers. Null — never a zeroed instance — when an element
    /// carries no LINE counter at all, because "no counter" and "nothing covered" are
    /// different answers and only one of them may be divided by.
    public record LineCount(int missed, int covered) {}

    /// Per-method LINE counters, which JaCoCo already reports.
    ///
    /// `line` is the method's declared line and is 0 — not null — when the report gave none,
    /// matching the JS `parseInt(... || '0', 10)`. The distinction is load-bearing: 0 is the
    /// value the "take the lowest declared line" rule below tests for.
    public static final class MethodCov {
        public int missed;
        public int covered;
        public int line;

        MethodCov(int line) { this.line = line; }
    }

    /// Everything known about one class, folded across inner classes and merged across the
    /// module reports that mention it. Mutable because merging is accumulation; the JS
    /// accumulator object is mutated in exactly the same way.
    public static final class ClassCov {
        public int missed;
        public int covered;
        /// source line number → its hit counters, for the uncovered-lines prompt
        public final Map<Integer, LineHit> lines = new LinkedHashMap<>();
        /// method name (XML entities already decoded) → its own counters
        public final Map<String, MethodCov> methods = new LinkedHashMap<>();
    }

    /// The accumulator {@link #mergeReport} fills: one entry per fully-qualified class plus
    /// the report-level totals.
    public static final class Report {
        public final Map<String, ClassCov> classes = new LinkedHashMap<>();
        public int missed;
        public int covered;
    }

    // ── parsing ───────────────────────────────────────────────────────────────────────

    /// XML attribute values are escaped, and JaCoCo writes constructors as `&lt;init&gt;`.
    /// Reading the raw attribute produced the literal string "&lt;init&gt;" as a method name,
    /// which then flowed into unit keys, PIT's excludedMethods (where it can never match) and
    /// the dashboard.
    ///
    /// `&amp;` is decoded LAST, so `&amp;lt;` decodes to `&lt;` and stops there rather than
    /// being decoded twice into `<`.
    public static String unesc(String v) {
        String s = v == null ? "" : v;
        return s.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    private static final Pattern LINE_COUNTER = Pattern.compile(
            "<counter\\s+type=\"LINE\"\\s+missed=\"(\\d+)\"\\s+covered=\"(\\d+)\"\\s*/>");

    /// The class-level LINE counter — which is the LAST one in the element, not the first.
    /// A `<class>` contains a `<method>` per method, each with its own counters, and only then
    /// its own totals. Reading the first match returned whichever method happened to come
    /// first in the file: a class whose first method was uncovered reported 0 % coverage
    /// while the repo total said 98 %.
    ///
    /// @return null when the element has no LINE counter at all
    public static LineCount lineCounter(String fragment) {
        Matcher m = LINE_COUNTER.matcher(fragment == null ? "" : fragment);
        LineCount last = null;
        while (m.find()) last = new LineCount(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        return last;
    }

    // <package name="com/x"> … </package>
    private static final Pattern PACKAGE_EL = Pattern.compile(
            "<package\\s+name=\"([^\"]*)\"\\s*>(.*?)</package>", Pattern.DOTALL);
    // <sourcefile name="Foo.java"> <line nr="7" mi="0" ci="2" .../> …
    private static final Pattern SOURCEFILE_EL = Pattern.compile(
            "<sourcefile\\s+name=\"([^\"]+)\"\\s*>(.*?)</sourcefile>", Pattern.DOTALL);
    private static final Pattern LINE_EL = Pattern.compile(
            "<line\\s+nr=\"(\\d+)\"\\s+mi=\"(\\d+)\"\\s+ci=\"(\\d+)\"");
    private static final Pattern NAME_ATTR = Pattern.compile("^name=\"([^\"]+)\"");
    private static final Pattern LINE_ATTR = Pattern.compile("\\sline=\"(\\d+)\"");
    private static final Pattern DOT_JAVA = Pattern.compile("\\.java$");

    /// Pull per-class LINE counters and per-sourcefile line hits out of a JaCoCo XML report.
    /// Regex-scanned rather than DOM-parsed: the backend is dependency-free by design apart
    /// from JSON, and the report's shape is fixed and flat.
    public static void mergeReport(Report acc, String xml) {
        String doc = xml == null ? "" : xml;
        Matcher pm = PACKAGE_EL.matcher(doc);
        while (pm.find()) {
            String pkg = pm.group(1).replace('/', '.');
            String body = pm.group(2);

            // <class name="com/x/Foo" sourcefilename="Foo.java"> … <counter type="LINE" …/></class>
            //
            // Split on class BOUNDARIES instead of matching <class>…</class> pairs. JaCoCo emits
            // SELF-CLOSING <class …/> for synthetic classes with no methods (`Foo$1`), and a
            // paired regex treats such a tag as an opening tag: `[\s\S]*?</class>` then runs on
            // and swallows the following classes, stealing their counters. Every class after a
            // self-closing sibling silently reported 0 % coverage. Splitting bounds each
            // fragment at the next class, so a self-closing tag simply carries no counter.
            //
            // The -1 limit keeps trailing empty fragments, matching JS String.split, which has
            // no such truncation. Java's default limit would drop them and shift nothing here,
            // but the habit is what stops the next split from silently losing an element.
            String[] classParts = body.split("<class\\s+", -1);
            for (int ci = 1; ci < classParts.length; ci++) {
                String part = classParts[ci];
                Matcher nm = NAME_ATTR.matcher(part);
                if (!nm.find()) continue;
                String name = nm.group(1);
                int end = part.indexOf("</class>");
                if (end == -1) continue;                   // self-closing → no counters to read
                String frag = part.substring(0, end);
                LineCount line = lineCounter(frag);
                if (line == null) continue;
                String fq = foldInnerClass(name);          // fold inner classes into the outer one
                ClassCov e = acc.classes.computeIfAbsent(fq, k -> new ClassCov());
                e.missed += line.missed();
                e.covered += line.covered();

                // Per-METHOD counters, which JaCoCo already reports. They cost nothing extra and
                // are what makes the method the pipeline's unit of work: every method's line
                // coverage is known before a single mutation run.
                String[] methodParts = frag.split("<method\\s+", -1);
                for (int mi = 1; mi < methodParts.length; mi++) {
                    String mpart = methodParts[mi];
                    Matcher mnm = NAME_ATTR.matcher(mpart);
                    String mname = unesc(mnm.find() ? mnm.group(1) : "");
                    if (mname.isEmpty()) continue;
                    Matcher lm = LINE_ATTR.matcher(mpart);
                    int mline = lm.find() ? Integer.parseInt(lm.group(1)) : 0;
                    int mend = mpart.indexOf("</method>");
                    LineCount mc = lineCounter(mend == -1 ? mpart : mpart.substring(0, mend));
                    if (mc == null) continue;
                    MethodCov me = e.methods.computeIfAbsent(mname, k -> new MethodCov(mline));
                    me.missed += mc.missed();
                    me.covered += mc.covered();
                    // the lowest declared line wins; 0 means the report never said
                    if (mline != 0 && (me.line == 0 || mline < me.line)) me.line = mline;
                }
            }

            Matcher sm = SOURCEFILE_EL.matcher(body);
            while (sm.find()) {
                String base = DOT_JAVA.matcher(sm.group(1)).replaceFirst("");
                String fq = pkg.isEmpty() ? base : pkg + "." + base;
                ClassCov e = acc.classes.computeIfAbsent(fq, k -> new ClassCov());
                Matcher lm = LINE_EL.matcher(sm.group(2));
                while (lm.find()) {
                    e.lines.put(Integer.parseInt(lm.group(1)),
                            new LineHit(Integer.parseInt(lm.group(2)), Integer.parseInt(lm.group(3))));
                }
            }
        }
        // report-level totals: the LAST top-level LINE counter belongs to <report>
        LineCount totals = lineCounter(doc);
        if (totals != null) {
            acc.missed += totals.missed();
            acc.covered += totals.covered();
        }
    }

    /// `com/x/Outer$Inner` → `com.x.Outer`. Everything before the FIRST `$`, so
    /// `Outer$1$2` folds all the way back to `Outer`.
    private static String foldInnerClass(String slashName) {
        String dotted = slashName.replace('/', '.');
        int dollar = dotted.indexOf('$');
        return dollar == -1 ? dotted : dotted.substring(0, dollar);
    }

    /// Repo-wide line coverage, or 0 when nothing executable was reported. Not null: this is
    /// a measurement over an empty set, and the route publishes it as the run's baseline.
    public static double totalPct(Report merged) {
        int total = merged.missed + merged.covered;
        return total > 0 ? Util.round2((merged.covered * 100.0) / total) : 0;
    }

    // ── mapping class coverage onto the units we track ────────────────────────────────

    /// What the mapping needs to know about one tracked unit. Every field is boxed and may
    /// be null, because the state record genuinely holds null for each of them: a unit
    /// discovered but never measured has no `fqcn`, a FILE unit has no `method`, and
    /// `coverage` is null until something measures it — which is the whole point of the
    /// third branch below.
    ///
    /// @param coverage the LAST measured percentage, null when never measured
    public record UnitRef(String fqcn, String path, String method, String module, Double coverage) {}

    /// The mapping's answer: the per-unit percentages the route returns, and the patch to
    /// apply to each unit's state record.
    ///
    /// The patches are maps rather than a record on purpose. `upsertFile` merges keys, so a
    /// key that is ABSENT keeps whatever the record already holds while a key present with a
    /// null value overwrites it. A record cannot express that difference, and it matters:
    /// the class branch deliberately does not touch `methodLine`.
    public record UnitUpdates(Map<String, Double> byPath, Map<String, Map<String, Object>> patches) {}

    /// Map class coverage onto the units we track (a unit is one METHOD).
    ///
    /// @param fqcnOf resolves a repo-relative source path to a fully-qualified class name —
    ///               injected because the fallback reads the file's package declaration
    public static UnitUpdates unitCoverage(Report merged, Map<String, UnitRef> files,
                                           UnaryOperator<String> fqcnOf) {
        Map<String, Double> byPath = new LinkedHashMap<>();
        Map<String, Map<String, Object>> patches = new LinkedHashMap<>();
        if (files == null) return new UnitUpdates(byPath, patches);

        for (Map.Entry<String, UnitRef> entry : files.entrySet()) {
            String rel = entry.getKey();
            UnitRef f = entry.getValue();
            if (f == null) continue;
            String fq = isBlank(f.fqcn()) ? fqcnOf.apply(isBlank(f.path()) ? rel : f.path()) : f.fqcn();
            ClassCov c = merged.classes.get(fq);
            Map<String, Object> patch = new LinkedHashMap<>();
            if (c != null && !isBlank(f.method())) {
                // a method unit takes its own counters
                MethodCov m = c.methods.get(f.method());
                int executable = m != null ? m.missed + m.covered : 0;
                double pct = executable > 0 ? Util.round2((m.covered * 100.0) / executable) : 0;
                byPath.put(rel, pct);
                patch.put("coverage", pct);
                patch.put("coveredLines", m != null ? m.covered : 0);
                patch.put("missedLines", m != null ? m.missed : 0);
                patch.put("executableLines", executable);
                // null, not 0 — a method the report never described has no known line, and 0
                // is a line number the prompt would then try to quote. Boxed explicitly:
                // `m != null ? m.line : null` is legal but reads as a place an unboxing NPE
                // could hide, and this value is genuinely nullable.
                patch.put("methodLine", m != null ? Integer.valueOf(m.line) : null);
                patches.put(rel, patch);
            } else if (c != null) {
                int executable = c.missed + c.covered;
                double pct = executable > 0 ? Util.round2((c.covered * 100.0) / executable) : 0;
                byPath.put(rel, pct);
                patch.put("coverage", pct);
                patch.put("coveredLines", c.covered);
                patch.put("missedLines", c.missed);
                patch.put("executableLines", executable);
                patches.put(rel, patch);
            } else if (f.coverage() == null) {
                // Absent from the report entirely. JaCoCo lists every class it saw on the
                // classpath, so absence means there is no executable code at all — an
                // annotation (@interface), a marker interface, a constants holder. Those read
                // as "0 % covered" and used to sort straight to the top of the pick list, where
                // they consumed a whole run each and could never improve.
                patch.put("coverage", 0.0);
                patch.put("executableLines", 0);
                patches.put(rel, patch);
                byPath.put(rel, 0.0);
            }
            // a unit already carrying a number, absent from this report, keeps that number
        }
        return new UnitUpdates(byPath, patches);
    }

    private static boolean isBlank(String s) { return s == null || s.isEmpty(); }

    // ── the artefacts that must not survive a run ─────────────────────────────────────

    private static final List<String> PER_MODULE_ARTIFACTS = List.of(
            "target/jacoco.exec",
            "target/site/jacoco/jacoco.xml",
            "build/jacoco/test.exec",
            "build/reports/jacoco/test/jacocoTestReport.xml");

    private static final Pattern LEADING_DOT_SLASH = Pattern.compile("^\\./");

    /// Coverage artefacts that MUST be deleted before a run.
    ///
    /// JaCoCo's Maven plugin appends to jacoco.exec, and `target/` is gitignored, so
    /// `git clean -fd` (no -x) leaves it in place. On JSON-java the file reached 10 MB, and
    /// JSONArray#getNumber measured 0% covered in one run and 100% in the next — the 100% was
    /// produced by a generated test that had already been deleted. The XML report matters just
    /// as much: a stale one parses exactly like a fresh one, which is how a stale mutations.xml
    /// once reported "0 mutants" for a 25-mutant method.
    public static List<String> staleCoverageArtifacts(List<String> modules) {
        List<String> source = (modules == null || modules.isEmpty()) ? List.of(".") : modules;
        List<String> mods = new ArrayList<>();
        for (String raw : source) {
            // JS `String(m || '.')`: null, undefined and "" all read as the root module
            String m = isBlank(raw) ? "." : raw;
            m = LEADING_DOT_SLASH.matcher(m).replaceFirst("");
            // never name anything outside the repo for deletion
            if (m.equals(".") || (!m.startsWith("/") && !List.of(m.split("/", -1)).contains(".."))) {
                mods.add(m);
            }
        }
        Set<String> out = new LinkedHashSet<>();
        for (String m : mods) {
            for (String rel : PER_MODULE_ARTIFACTS) out.add(m.equals(".") ? rel : m + "/" + rel);
        }
        return new ArrayList<>(out);
    }

    /// No module list at all still means the root module.
    public static List<String> staleCoverageArtifacts() { return staleCoverageArtifacts(null); }

    private static final Pattern REPORT_FILE = Pattern.compile("^(jacoco|jacocoTestReport)\\.xml$");

    /// Every jacoco XML report under the repo (one per module).
    public static List<Path> findReports(Path dir) {
        List<Path> out = new ArrayList<>();
        walkReports(dir, 0, out);
        return out;
    }

    private static void walkReports(Path d, int depth, List<Path> out) {
        if (depth > 7) return;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(d)) {
            for (Path p : entries) {
                String name = p.getFileName().toString();
                // NOFOLLOW_LINKS on purpose: Node's Dirent reports a symlink as a link and not
                // as a directory, so the JS walk never descends through one. Files.isDirectory
                // follows links by default and would, which on a repo with a self-referential
                // link is an unbounded walk that the depth cap only eventually stops.
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    if (name.equals(".git") || name.equals("node_modules")) continue;
                    walkReports(p, depth + 1, out);
                } else if (REPORT_FILE.matcher(name).matches()) {
                    out.add(p);
                }
            }
        } catch (IOException | RuntimeException e) {
            // unreadable directory — the JS `catch { return; }` around readdirSync
        }
    }

    /// Node's `path.join(dir, rel)` DROPS a leading separator on the second argument, so a
    /// relative name that somehow arrives as "/target/jacoco.exec" still lands inside the
    /// repo. `Path.resolve` does the opposite — an absolute argument replaces the base
    /// entirely — and this call site deletes files. Stripping the separator keeps the Node
    /// behaviour rather than reaching out of the repo.
    static Path joinUnder(Path dir, String rel) {
        String s = rel == null ? "" : rel;
        while (s.startsWith("/")) s = s.substring(1);
        return dir.resolve(s).normalize();
    }

    /// Delete last run's exec data and reports. Belt and braces: the enumerated paths cover
    /// Maven and Gradle in every module we know about, and any jacoco XML still on disk
    /// afterwards is removed too — findReports() would otherwise read it as this run's result.
    ///
    /// @param event the event sink, called only when something was actually removed
    /// @return how many artefacts were deleted
    public static int clearStaleCoverage(Path dir, List<String> modules, BiConsumer<String, String> event) {
        int removed = 0;
        for (String rel : staleCoverageArtifacts(modules)) {
            try { if (Files.deleteIfExists(joinUnder(dir, rel))) removed += 1; }
            catch (IOException | RuntimeException e) { /* not there */ }
        }
        for (Path abs : findReports(dir)) {
            try { if (Files.deleteIfExists(abs)) removed += 1; }
            catch (IOException | RuntimeException e) { /* ignore */ }
        }
        if (removed > 0 && event != null) {
            event.accept("coverage", "cleared " + removed + " stale JaCoCo artefact(s) — exec data"
                    + " accumulates across runs, and a leftover report reads exactly like a fresh one");
        }
        return removed;
    }

    /// The distinct modules the tracked units live in, or the root module when none says.
    static List<String> modulesOf(Map<String, UnitRef> files) {
        Set<String> mods = new LinkedHashSet<>();
        if (files != null) {
            for (UnitRef f : files.values()) {
                if (f != null && !isBlank(f.module())) mods.add(f.module());
            }
        }
        return mods.isEmpty() ? List.of(".") : new ArrayList<>(mods);
    }

    // ── the command lines ─────────────────────────────────────────────────────────────

    /// The Maven invocation for one JaCoCo build.
    ///
    /// Only supply an agent when the project has none: two -javaagent entries kill the
    /// test JVM outright (`java.lang.instrument ASSERTION FAILED`), which is how every
    /// Apache Commons repo failed in the first sweep.
    /// When deferring, use the PROJECT's plugin version (bare `jacoco:report` prefix)
    /// so the report tool matches the agent that wrote the exec data.
    public static List<String> mavenArgv(String wrapper, boolean hasJacoco) {
        String g = "org.jacoco:jacoco-maven-plugin:" + JACOCO_VERSION;
        List<String> argv = new ArrayList<>(List.of(wrapper, "-B", "-ntp"));
        if (hasJacoco) argv.addAll(List.of("test", "jacoco:report"));
        else argv.addAll(List.of(g + ":prepare-agent", "test", g + ":report"));
        argv.add("-Dmaven.test.failure.ignore=true");
        argv.add("-DfailIfNoTests=false");
        return argv;
    }

    /// The Gradle invocation. `scanArgs` carries `--no-scan` where the repo applies a
    /// build-scan plugin and nothing where Gradle would reject the flag — see {@link GradleScan}.
    public static List<String> gradleArgv(String wrapper, List<String> scanArgs) {
        return GradleScan.argv(List.of(wrapper, "--no-daemon"), scanArgs,
                List.of("--continue", "-I", INIT_SCRIPT, "test", "jacocoTestReport"));
    }

    // ── the IO seam ───────────────────────────────────────────────────────────────────

    /// The build the repo module detected. `hasJacoco` is boxed because detection can decline
    /// to answer, and a primitive false would silently mean "the project has none" — which
    /// makes us inject a second agent and kill the test JVM.
    public record Runner(String tool, String wrapper, Boolean hasJacoco, List<String> scanArgs) {}

    /// One finished subprocess. `code` is already normalised by the exec wrapper: a killed or
    /// signalled child reports a failure, never 0.
    public record Proc(int code, String stdout, String stderr, boolean timedOut) {}

    /// Everything a coverage run produced.
    ///
    /// @param suite the suite outcome read off THIS build's log — {@link Tests.Suite}, not a
    ///              second copy of that shape, because the JS returned exactly what
    ///              `tests.suiteOutcome` gave it and two records of the same five fields would
    ///              only drift apart. The run executed the suite, so it already knows whether
    ///              it passed and the caller needs no second full execution to find out.
    ///              `suite.passed() == null` means the log did not say.
    public record Result(double totalPct, Map<String, Double> files, int exitCode, int reports,
                         Map<String, ClassCov> classes, Tests.Suite suite) {}

    /// What this module needs from the rest of the backend, in one place.
    ///
    /// The adapter supplies `cwd` (the repo directory) and `env` (the detected JDK's
    /// JAVA_HOME and PATH) to every {@link #run} call — every subprocess this module starts
    /// wants both, so passing them at each call site could only ever be a way to forget one.
    public interface Io {
        /// The checkout the run is improving.
        Path repoDir();

        /// The detected build, or null when nothing was detected yet.
        Runner runner();

        /// Fully-qualified class name for a repo-relative source path.
        String fqcnOf(String repoRelativePath);

        /// The tracked units, keyed by unit key (`path` or `path::method`), in insertion order.
        Map<String, UnitRef> files();

        /// Merge a patch into one unit's state record. Keys absent from the patch keep the
        /// value they already had.
        void upsertFile(String unit, Map<String, Object> patch);

        /// Append to the event log the dashboard polls.
        void event(String stage, String message);

        /// Shell out through the exec wrapper, in the repo directory, under the build env.
        Proc run(List<String> argv, long timeoutMs, String label);

        /// Read a suite outcome out of a build log — wire it to `Tests::suiteOutcome`.
        Tests.Suite suiteOutcome(String log);
    }

    // ── the run ───────────────────────────────────────────────────────────────────────

    /// Run the project's suite under JaCoCo and map the result onto every tracked unit.
    ///
    /// This route may run TWO of these builds in sequence (see the hasJacoco fallback below),
    /// which is why its HTTP ceiling is double {@link Timeouts#COVERAGE_RUN_MS}.
    public static Result runCoverage(Io io) throws IOException {
        Path dir = io.repoDir();
        Runner build = io.runner();
        if (build == null || isBlank(build.tool())) {
            throw new IllegalStateException("build not detected — call /api/repo/prepare first");
        }
        clearStaleCoverage(dir, modulesOf(io.files()), io::event);

        Proc r;
        boolean hasJacoco = Boolean.TRUE.equals(build.hasJacoco());
        if ("maven".equals(build.tool())) {
            r = io.run(mavenArgv(build.wrapper(), hasJacoco), Timeouts.COVERAGE_RUN_MS, "jacoco");
            // If deferring to the project produced nothing (its agent sits in an inactive
            // profile, say), fall back to supplying ours.
            if (hasJacoco && findReports(dir).isEmpty()) {
                io.event("coverage", "the project’s own JaCoCo produced no report — retrying with our agent");
                r = io.run(mavenArgv(build.wrapper(), false), Timeouts.COVERAGE_RUN_MS, "jacoco");
            }
        } else {
            Files.writeString(joinUnder(dir, INIT_SCRIPT), gradleInitScript(), StandardCharsets.UTF_8);
            r = io.run(gradleArgv(build.wrapper(), build.scanArgs()), Timeouts.COVERAGE_RUN_MS, "jacoco");
        }

        List<Path> reports = findReports(dir);
        if (reports.isEmpty()) {
            String log = isBlank(r.stderr()) ? r.stdout() : r.stderr();
            throw new IllegalStateException(
                    "JaCoCo produced no XML report (exit " + r.code() + "): " + tail(log, 700));
        }
        Report merged = new Report();
        for (Path rep : reports) mergeReport(merged, readUtf8(rep));
        double totalPct = totalPct(merged);

        UnitUpdates updates = unitCoverage(merged, io.files(), io::fqcnOf);
        for (Map.Entry<String, Map<String, Object>> e : updates.patches().entrySet()) {
            io.upsertFile(e.getKey(), e.getValue());
        }
        // showMetric, not String.valueOf: a whole percentage must print as "80", the way the
        // JS template literal did, and not as "80.0" in front of a person reading the log
        io.event("coverage", "total line coverage " + Util.showMetric(totalPct) + "% over "
                + reports.size() + " JaCoCo report(s) (build exit " + r.code() + ")");

        // This run EXECUTED the suite, so it already knows whether it passed — the caller does
        // not need a second full execution to find out. `passed == null` means the log did not
        // say, and the caller must run the tests properly rather than assume.
        Tests.Suite suite = io.suiteOutcome(r.stdout() + "\n" + r.stderr());
        return new Result(totalPct, updates.byPath(), r.code(), reports.size(), merged.classes, suite);
    }

    // ── the uncovered-lines prompt ────────────────────────────────────────────────────

    /// Uncovered lines of one source file, for the coverage-improvement prompt.
    ///
    /// The JSON shape is read by name in the prompt builder, so both branches are kept
    /// exactly as the Node sidecar wrote them: `lines` is either the STRING "all" or an
    /// array of line numbers, and the two branches carry different companion fields.
    public static ObjectNode uncoveredLinesOf(Report merged, String fqcn) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        ClassCov entry = merged == null ? null : merged.classes.get(fqcn);
        if (entry == null || entry.lines.isEmpty()) {
            out.put("lines", "all");
            out.put("note", "class never executed by any test — 0% coverage");
            return out;
        }
        List<Integer> uncovered = new ArrayList<>();
        for (Map.Entry<Integer, LineHit> e : entry.lines.entrySet()) {
            if (e.getValue().ci() == 0 && e.getValue().mi() > 0) uncovered.add(e.getKey());
        }
        Collections.sort(uncovered);
        ArrayNode arr = out.putArray("lines");
        // sorted BEFORE the cap, so the 200 reported are the first 200 lines of the file and
        // not 200 arbitrary ones
        for (int i = 0; i < Math.min(200, uncovered.size()); i++) arr.add(uncovered.get(i));
        out.put("coveredLines", entry.covered);
        out.put("missedLines", entry.missed);
        return out;
    }

    /// Uncovered lines of the source behind one unit key, read from whatever reports are on
    /// disk right now.
    public static ObjectNode uncoveredLines(Io io, String unitKey) {
        Path dir = io.repoDir();
        UnitRef unit = io.files().get(unitKey);
        String srcPath = (unit != null && !isBlank(unit.path())) ? unit.path() : beforeUnitSeparator(unitKey);
        String fq = (unit != null && !isBlank(unit.fqcn())) ? unit.fqcn() : io.fqcnOf(srcPath);
        Report acc = new Report();
        for (Path rep : findReports(dir)) {
            try { mergeReport(acc, readUtf8(rep)); }
            catch (IOException | RuntimeException e) { /* a report we cannot read says nothing */ }
        }
        return uncoveredLinesOf(acc, fq);
    }

    /// `Foo.java::bar` → `Foo.java`. A unit key is the source path, optionally with the
    /// method after a `::`.
    static String beforeUnitSeparator(String unitKey) {
        String k = unitKey == null ? "" : unitKey;
        int at = k.indexOf("::");
        return at == -1 ? k : k.substring(0, at);
    }

    /// Decode leniently, the way Node's `readFileSync(p, 'utf8')` does. `Files.readString`
    /// throws on a malformed byte, and a build log or report with one stray byte in it must
    /// not take down a measurement that is otherwise perfectly readable.
    private static String readUtf8(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    /// The last `n` characters — JS `.slice(-n)`.
    static String tail(String s, int n) {
        String v = s == null ? "" : s;
        return v.length() <= n ? v : v.substring(v.length() - n);
    }
}
