package tech.mikhailov.ijt;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// PIT mutation testing, scoped to ONE class per run (whole-repo mutation is far too slow).
///
/// Wiring is the hard part and it depends on the test framework (RESEARCH.md §1):
///
///     JUnit 4  — works with a bare `pitest-maven` invocation.
///     JUnit 5+ — needs `pitest-junit5-plugin` as a plugin DEPENDENCY, which a `-D` cannot add,
///                so the plugin block is injected into the module's pom (main &lt;build&gt;, never a
///                &lt;profile&gt;: a plugin inside an inactive profile is silently ignored).
///     JUnit 6  — additionally needs junit-platform-launcher pinned to the project's platform
///                version, or PIT's bundled launcher mismatches the engine.
///     TestNG   — needs `pitest-testng-plugin`.
///
/// Injected build config is never committed (Pr commits test sources only) and is re-applied
/// before every run, since round bookkeeping discards uncommitted changes.
///
/// The IO half of this class talks to three collaborators and nothing else: {@link Exec} to
/// shell out, {@link Repo} for the checkout, and {@link State} for the run state and the event
/// log. Every one of those calls sits in {@link #runPit}, {@link #ensureMavenWiring(String)} or
/// one of the private accessors at the bottom — the decisions above them are pure and are
/// tested without a run, which is how the JS was arranged too.
public final class Pit {

    private Pit() {}

    // PIT must be CURRENT: its JUnit bridge has to understand the project's junit-platform,
    // and an old PIT against a modern JUnit fails with the unhelpful "Please check you have
    // correctly installed the pitest plugin for your project's test library".
    public static final String PIT_VERSION = envOr("PIT_VERSION", "1.25.8");
    private static final String PIT_JUNIT5_VERSION = envOr("PIT_JUNIT5_VERSION", "1.2.3");
    private static final String PIT_TESTNG_VERSION = envOr("PIT_TESTNG_VERSION", "1.0.0");
    public static final String MUTATORS = envOr("PIT_MUTATORS", "DEFAULTS");

    /// gradle-pitest-plugin must match the project's Gradle: 1.15.0 reads `reporting.baseDir`,
    /// which Gradle 9 removed, so applying it there fails outright with "Could not get unknown
    /// property 'baseDir'" — that is why every Gradle repo produced nothing. 1.19.0 works on
    /// 8/9; older projects keep the older plugin.
    ///
    /// @param gradleVersion null when the project's Gradle is unknown — see below, an
    ///                      unrecognised Gradle gets the modern plugin
    public static String gradlePitestPluginVersion(String gradleVersion) {
        String pinned = System.getenv("GRADLE_PITEST_PLUGIN_VERSION");
        if (pinned != null && !pinned.isEmpty()) return pinned;
        String head = gradleVersion == null ? "" : gradleVersion.split("\\.", -1)[0];
        Integer major = State.jsParseInt(head);
        // an unrecognised Gradle gets the modern plugin: the old one cannot even load on 9
        return major != null && major < 8 ? "1.15.0" : "1.19.0";
    }

    /// The version for the Gradle this run detected.
    public static String gradlePitestPluginVersion() {
        return gradlePitestPluginVersion(runnerText("gradleVersion"));
    }

    static final String INIT_SCRIPT = ".ijt-pitest.init.gradle";

    // ── Maven wiring ───────────────────────────────────────────────────────────

    private static final Pattern JUPITER_VERSION = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    /// junit-platform version matching a jupiter version: 5.11.3 → 1.11.3, 6.1.0 → 6.1.0.
    ///
    /// @return null when the version says nothing usable — a launcher pinned to a version we
    ///         guessed is worse than none, and {@link #pitPluginXml} omits it entirely
    public static String platformVersionFor(String jupiterVersion) {
        Matcher m = JUPITER_VERSION.matcher(jupiterVersion == null ? "" : jupiterVersion);
        if (!m.find()) return null;
        int major = State.jsParseInt(m.group(1));
        if (major >= 6) return jupiterVersion;
        if (major == 5) return "1." + m.group(2) + (m.group(3) != null ? "." + m.group(3) : "");
        return null;
    }

    public static String pitPluginXml(String framework, String frameworkVersion) {
        List<String> deps = new ArrayList<>();
        if ("junit5".equals(framework)) {
            deps.add("      <dependency><groupId>org.pitest</groupId><artifactId>pitest-junit5-plugin</artifactId>"
                    + "<version>" + PIT_JUNIT5_VERSION + "</version></dependency>");
            // Pin junit-platform-launcher to the platform the project's engine belongs to, or
            // PIT's bundled launcher mismatches it and finds no tests at all. Jupiter 5.x rides
            // platform 1.x; JUnit 6 unified the two onto one version.
            String platform = platformVersionFor(frameworkVersion);
            if (platform != null && !platform.isEmpty()) {
                deps.add("      <dependency><groupId>org.junit.platform</groupId><artifactId>junit-platform-launcher</artifactId>"
                        + "<version>" + platform + "</version></dependency>");
            }
        } else if ("testng".equals(framework)) {
            deps.add("      <dependency><groupId>org.pitest</groupId><artifactId>pitest-testng-plugin</artifactId>"
                    + "<version>" + PIT_TESTNG_VERSION + "</version></dependency>");
        }
        String dependencies = deps.isEmpty() ? ""
                : "\n      <dependencies>\n" + String.join("\n", deps) + "\n      </dependencies>";
        return "    <plugin>\n"
                + "      <groupId>org.pitest</groupId>\n"
                + "      <artifactId>pitest-maven</artifactId>\n"
                + "      <version>" + PIT_VERSION + "</version>" + dependencies + "\n"
                + "    </plugin>";
    }

    private static final Pattern PITEST_MAVEN =
            Pattern.compile("<artifactId>\\s*pitest-maven\\s*</artifactId>");
    /// The plugin's identity, and its version when it declares one — what the bridge
    /// dependency is inserted after.
    private static final Pattern PITEST_MAVEN_ANCHOR = Pattern.compile(
            "(<artifactId>\\s*pitest-maven\\s*</artifactId>\\s*(?:<version>[^<]*</version>\\s*)?)");
    // `\z`, not `$`: Java's `$` also matches before a final line terminator, so `</project>$`
    // would accept a pom with content after it. JS `$` without /m is end-of-input only.
    private static final Pattern PROJECT_END = Pattern.compile("</project>\\s*\\z");

    /// Make sure the module's pom carries a usable PIT plugin. Returns 'present' when the
    /// project already declares one (we defer to it), 'injected' when we added ours.
    ///
    /// Takes the checkout and the framework explicitly rather than reading them from the run
    /// state: what this does is edit a pom on disk, and that is assertable — and worth
    /// asserting — without a run. {@link #ensureMavenWiring(String)} is the wiring.
    ///
    /// @return `no-pom` | `present` | `patched` | `injected`
    public static String ensureMavenWiring(Path dir, String moduleRel, String framework, String frameworkVersion) {
        String pomRel = pomRelFor(moduleRel);
        Path pomAbs = dir.resolve(pomRel);
        if (!Files.exists(pomAbs)) return "no-pom";
        String pom = readUtf8(pomAbs);
        if (PITEST_MAVEN.matcher(pom).find()) {
            boolean needsJunit5 = "junit5".equals(framework) && !pom.contains("pitest-junit5-plugin");
            boolean needsTestng = "testng".equals(framework) && !pom.contains("pitest-testng-plugin");
            if (!needsJunit5 && !needsTestng) return "present";
            // project declares PIT but not the engine bridge it needs — add just the dependency
            String dep = needsJunit5
                    ? "<dependencies><dependency><groupId>org.pitest</groupId><artifactId>pitest-junit5-plugin</artifactId>"
                    + "<version>" + PIT_JUNIT5_VERSION + "</version></dependency></dependencies>"
                    : "<dependencies><dependency><groupId>org.pitest</groupId><artifactId>pitest-testng-plugin</artifactId>"
                    + "<version>" + PIT_TESTNG_VERSION + "</version></dependency></dependencies>";
            // replaceFirst, not replaceAll: the JS regex carries no /g. `$1` is the anchor put
            // back verbatim, so only the inserted text is quoted.
            pom = PITEST_MAVEN_ANCHOR.matcher(pom)
                    .replaceFirst("$1" + Matcher.quoteReplacement(dep + "\n      "));
            writeUtf8(pomAbs, pom);
            return "patched";
        }
        String block = pitPluginXml(framework, frameworkVersion);
        int profilesAt = pom.indexOf("<profiles>");
        int buildAt = pom.indexOf("<build>");
        boolean buildIsTopLevel = buildAt != -1 && (profilesAt == -1 || buildAt < profilesAt);
        if (buildIsTopLevel) {
            int pluginsAt = pom.indexOf("<plugins>", buildAt);
            int buildEnd = pom.indexOf("</build>", buildAt);
            if (pluginsAt != -1 && pluginsAt < buildEnd) {
                pom = pom.substring(0, pluginsAt + "<plugins>".length()) + "\n" + block
                        + pom.substring(pluginsAt + "<plugins>".length());
            } else {
                pom = pom.substring(0, buildAt + "<build>".length())
                        + "\n  <plugins>\n" + block + "\n  </plugins>"
                        + pom.substring(buildAt + "<build>".length());
            }
        } else {
            pom = PROJECT_END.matcher(pom).replaceFirst(Matcher.quoteReplacement(
                    "  <build>\n    <plugins>\n" + block + "\n    </plugins>\n  </build>\n</project>\n"));
        }
        writeUtf8(pomAbs, pom);
        return "injected";
    }

    /// The same wiring for the module this run is working in, with the outcome announced on the
    /// event log the way the JS did from inside.
    public static String ensureMavenWiring(String moduleRel) {
        String framework = runnerText("testFramework");
        String pomRel = pomRelFor(moduleRel);
        String outcome = ensureMavenWiring(Repo.repoDir(), moduleRel, framework, runnerText("testFrameworkVersion"));
        // needsJunit5 is only ever true for a junit5 project, so the branch is the framework
        if (outcome.equals("patched")) {
            State.event("pit", "added the missing PIT " + ("junit5".equals(framework) ? "JUnit 5" : "TestNG")
                    + " bridge to " + pomRel);
        } else if (outcome.equals("injected")) {
            State.event("pit", "injected PIT " + PIT_VERSION + " into " + pomRel + " (" + framework + ")");
        }
        return outcome;
    }

    private static String pomRelFor(String moduleRel) {
        return moduleRel == null || moduleRel.isEmpty() || moduleRel.equals(".")
                ? "pom.xml" : moduleRel + "/pom.xml";
    }

    // ── Gradle wiring ──────────────────────────────────────────────────────────

    /// What the init script needs that the caller may know better than the defaults.
    ///
    /// A null field means "not stated" and takes the module default — `??` in the JS, which is
    /// null-coalescing and NOT falsy-coalescing: an explicit empty string is a stated value and
    /// stays one.
    public record InitOpts(String gradleVersion, String testFramework, String pitVersion,
                           String junit5PluginVersion, String mutators, String mirrorUrl) {

        public static InitOpts of() { return new InitOpts(null, null, null, null, null, null); }

        public InitOpts withGradleVersion(String v) {
            return new InitOpts(v, testFramework, pitVersion, junit5PluginVersion, mutators, mirrorUrl);
        }

        public InitOpts withTestFramework(String v) {
            return new InitOpts(gradleVersion, v, pitVersion, junit5PluginVersion, mutators, mirrorUrl);
        }

        public InitOpts withPitVersion(String v) {
            return new InitOpts(gradleVersion, testFramework, v, junit5PluginVersion, mutators, mirrorUrl);
        }

        public InitOpts withJunit5PluginVersion(String v) {
            return new InitOpts(gradleVersion, testFramework, pitVersion, v, mutators, mirrorUrl);
        }

        public InitOpts withMutators(String v) {
            return new InitOpts(gradleVersion, testFramework, pitVersion, junit5PluginVersion, v, mirrorUrl);
        }

        public InitOpts withMirrorUrl(String v) {
            return new InitOpts(gradleVersion, testFramework, pitVersion, junit5PluginVersion, mutators, v);
        }
    }

    /// The Gradle init script that applies gradle-pitest-plugin without touching the repo.
    ///
    /// The JS read its unstated options out of the run state from in here; here the one
    /// production caller ({@link #runPit}) passes them in, so the script — every line of which
    /// is a repo that produced nothing until it was written that way — can be asserted without
    /// a run.
    public static String gradleInitScript(String targetClasses, String targetTests,
                                          List<String> excluded, InitOpts opts) {
        InitOpts o = opts == null ? InitOpts.of() : opts;
        String pitVersion = o.pitVersion() != null ? o.pitVersion() : PIT_VERSION;
        String junit5Plugin = o.junit5PluginVersion() != null ? o.junit5PluginVersion() : PIT_JUNIT5_VERSION;
        String mutators = o.mutators() != null ? o.mutators() : MUTATORS;
        String mirrorUrl = o.mirrorUrl() != null ? o.mirrorUrl()
                : envOr("MAVEN_MIRROR_URL", "https://plugins.gradle.org/m2");
        List<String> ex = excluded == null ? List.of() : excluded;
        String junit5Line = "junit5".equals(o.testFramework()) ? "junit5PluginVersion = '" + junit5Plugin + "'" : "";
        String targetTestsLine = truthy(targetTests) ? "targetTests = ['" + targetTests + "']" : "";
        String excludedLine = ex.isEmpty() ? ""
                : "excludedMethods = [" + String.join(", ", ex.stream().map(m -> "'" + m + "'").toList()) + "]";
        return """
                // injected by improve-java-tests-n8n — applies gradle-pitest-plugin without touching the repo
                initscript {
                  // allowInsecureProtocol: the on-host Nexus is plain http, and Gradle 7+ refuses
                  // http repositories without it — that rejection killed the Gradle PIT path.
                  repositories {
                    maven { url = uri('%s'); allowInsecureProtocol = true }
                    gradlePluginPortal()
                    mavenCentral()
                  }
                  dependencies { classpath 'info.solidsoft.gradle.pitest:gradle-pitest-plugin:%s' }
                }
                allprojects { p ->
                  p.plugins.withId('java') {
                    p.apply plugin: info.solidsoft.gradle.pitest.PitestPlugin
                    p.pitest {
                      pitestVersion = '%s'
                      %s
                      targetClasses = ['%s']
                      %s
                      mutators = ['%s']
                      %s
                      outputFormats = ['XML']
                      timestampedReports = false
                      failWhenNoMutations = false
                      // parity with the Maven path. Without it Gradle used PIT's default, and on an
                      // async class every hung mutant is paid for at whatever that default happens to be
                      timeoutConstInMillis = 8000
                    }
                  }
                }
                """.formatted(mirrorUrl, gradlePitestPluginVersion(o.gradleVersion()), pitVersion,
                junit5Line, targetClasses, targetTestsLine, mutators, excludedLine);
    }

    /// The Gradle command that runs PIT.
    ///
    /// `--rerun` is not optional. Two verify runs of the same unit present identical `pitest`
    /// inputs to Gradle — same class, same target method, same excludedMethods — so with a
    /// build cache enabled (java-dataloader sets org.gradle.caching=true) the second is skipped
    /// as up-to-date and writes no report. Since a missing report must never read as zero, the
    /// skip surfaced as UNMEASURED and cost round 2 of every multi-round unit on that repo.
    ///
    /// `--rerun` forces only the named task; `--rerun-tasks` would recompile the world.
    public static List<String> gradlePitArgv(String wrapper, List<String> scanArgs, String initScript, String task) {
        return GradleScan.argv(List.of(wrapper, "--no-daemon"), scanArgs, List.of("-I", initScript, task, "--rerun"));
    }

    // ── decisions ──────────────────────────────────────────────────────────────

    /// Names this pipeline gives its own generated tests.
    private static final Pattern OURS_RE = Pattern.compile("(MacMut|MacCov)(R\\d+)?Test$");

    private static final Pattern GREEN_SUITE = Pattern.compile("did not pass without mutation");
    private static final Pattern GREEN_SUITE_WHERE =
            Pattern.compile("testClass=([\\w.$]+)[^\\n]*?\\[method:([\\w$]+)\\(");
    private static final Pattern GREEN_SUITE_CLASS = Pattern.compile("testClass=([\\w.$]+)");

    /// The test PIT refused to run for, and whether it is one of ours.
    ///
    /// @param method null when the engine printed no `[method:…]` part — say so rather than
    ///               invent one
    public record GreenSuiteFailure(String testClass, String method, boolean ours) {}

    /// Did PIT refuse to run because a test fails on UNMUTATED code?
    ///
    /// PIT computes line coverage first by running every targetTests class against the original
    /// bytecode, and aborts if any fails: "Mutation testing requires a green suite." When the
    /// offender is one of OUR generated tests — as it was on
    /// ThreadLocalStatisticsCollector#incrementLoadErrorCount, where round 1's test and round
    /// 2's collided over thread-local state inside PIT's single minion JVM — the run is not
    /// unmeasurable in any deep sense. We wrote the broken test and we can take it back.
    ///
    /// The message names the class and the method; the code used to discard both and report
    /// "targetTests glob or compiled classes are wrong", which was a guess, and wrong, and cost
    /// three rounds of diagnosis.
    public static GreenSuiteFailure pitGreenSuiteFailure(String out) {
        String s = out == null ? "" : out;
        if (!GREEN_SUITE.matcher(s).find()) return null;
        Matcher m = GREEN_SUITE_WHERE.matcher(s);
        if (!m.find()) {
            Matcher only = GREEN_SUITE_CLASS.matcher(s);
            return only.find() ? new GreenSuiteFailure(only.group(1), null, ours(only.group(1))) : null;
        }
        return new GreenSuiteFailure(m.group(1), m.group(2), ours(m.group(1)));
    }

    private static boolean ours(String testClass) {
        return OURS_RE.matcher(testClass).find();
    }

    /// Never below this: a doomed run teaches nothing.
    static final long PIT_TIMEOUT_MIN_MS = 120_000L;
    /// Never above it: the old hard ceiling. Shared with the workflow generator — /api/pit/run's
    /// HTTP node must outlive the compile AND the PIT run that follow it. See {@link Timeouts}.
    static final long PIT_TIMEOUT_MAX_MS = Timeouts.PIT_RUN_MAX_MS;

    /// How long this unit's PIT run may take.
    ///
    /// The unit budget was only consulted by decide(), AFTER a round returned, while the
    /// subprocess itself got a flat hour. DataLoader#getValueCache() then spent 984 seconds
    /// inside one `pitest` task — timing out mutant after mutant on a CompletableFuture-heavy
    /// class — against a configured budget of 900. A budget the subprocess never sees cannot
    /// stop anything.
    ///
    /// @param unitBudgetSec    null or 0 documents "uncapped"; boxed so that the two stay
    ///                         distinguishable from a stated small budget
    /// @param spentSec         seconds this unit has already burned, null for none
    /// @param attemptStartedAt epoch seconds of the live attempt, null/0 when not running
    /// @param now              epoch seconds, null for the clock
    public static long pitTimeoutMs(Integer unitBudgetSec, Long spentSec, Long attemptStartedAt, Long now) {
        // 0 documents "uncapped" and must not read as a 0ms timeout that fails every run
        if (unitBudgetSec == null || unitBudgetSec == 0) return PIT_TIMEOUT_MAX_MS;
        long clock = now != null ? now : Util.nowSec();
        long started = attemptStartedAt == null ? 0L : attemptStartedAt;
        long onThisAttempt = started != 0L ? Math.max(0L, clock - started) : 0L;
        long leftSec = unitBudgetSec - (spentSec == null ? 0L : spentSec) - onThisAttempt;
        return Math.min(PIT_TIMEOUT_MAX_MS, Math.max(PIT_TIMEOUT_MIN_MS, leftSec * 1000L));
    }

    /// A method name as PIT expects it in `excludedMethods`.
    ///
    /// JaCoCo and PIT disagree about constructors: one writes `<init>`, the other XML-escapes
    /// it. The live excluded list read `[&lt;init&gt;, resetThread, ...]`, and since PIT matches
    /// on the literal name, no method matched — the constructor was never excluded and its
    /// mutants came back as strays on every scoped run.
    public static String escapeMethodForPit(String m) {
        return unescapeXml(m == null ? "" : m);
    }

    /// May PIT be asked again after a green-suite refusal?
    ///
    /// Cutting the offending test removes the thing that blocked PIT, so the measurement is now
    /// possible — but the clean v2 run cut it and then reported UNMEASURED anyway, throwing the
    /// salvage away. Only a cut earns a retry: if the failing test belongs to the project, or
    /// nothing was salvageable, re-running fails identically.
    ///
    /// Bounded at two, because each retry can uncover another offender and an unbounded loop
    /// would spend the unit's whole budget finding them one at a time.
    public static int greenSuiteRetries(int spent, boolean cut) {
        return cut && spent < 2 ? 1 : 0;
    }

    /// Where PIT is pointed for one unit.
    ///
    /// @param target   `onlyMethod` in the spelling PIT uses, and the one name NOT excluded
    /// @param excluded every other method we know of; empty for a class-wide run
    public record Scope(String targetTests, String targetClasses, String target, List<String> excluded) {}

    /// Which classes to mutate, which tests to run it against, and which methods to exclude so
    /// that only the target is mutated.
    ///
    /// Pure, and separated from runPit deliberately — every rule below is a scar, and each one
    /// failed *silently*, as "this method has no mutation surface" rather than as an error:
    ///
    ///  - targetTests carries a LEADING wildcard because a project's tests often sit in a
    ///    sibling package (org.json.junit.XMLTest for org.json.XML); a package-anchored glob
    ///    matched none of them.
    ///  - targetClasses carries a TRAILING one so inner classes come along.
    ///  - PIT has no "mutate this method" option, only excludedMethods — so targeting one
    ///    method means naming every other one.
    ///  - Both sides are escaped before comparison. The known list arrives with constructors
    ///    spelled `&lt;init&gt;` while onlyMethod is `<init>`, so a constructor unit did not
    ///    recognise itself in its own sibling list, failed to remove itself, and shipped a
    ///    target that was ALSO excluded — leaving PIT nothing to mutate at all.
    ///
    /// @param siblingMethods the class's other units, from the baseline enumeration
    /// @param statMethods    failing that, whatever a previous PIT report knew about
    public static Scope pitScope(String fqcn, String onlyMethod,
                                 List<String> siblingMethods, List<String> statMethods) {
        String name = fqcn == null ? "" : fqcn;
        String cls = name.substring(name.lastIndexOf('.') + 1);
        List<String> siblings = siblingMethods == null ? List.of() : siblingMethods;
        List<String> stats = statMethods == null ? List.of() : statMethods;
        List<String> known = !siblings.isEmpty() ? siblings : stats;
        String target = escapeMethodForPit(onlyMethod);
        List<String> excluded = new ArrayList<>();
        if (truthy(onlyMethod)) {
            // a name repeated across sources is excluded once: PIT takes names, not signatures
            for (String m : new LinkedHashSet<>(known.stream().map(Pit::escapeMethodForPit).toList())) {
                if (!m.equals(target)) excluded.add(m);
            }
        }
        return new Scope("*" + cls + "*Test*", name + "*", target, List.copyOf(excluded));
    }

    public static Scope pitScope(String fqcn, String onlyMethod) {
        return pitScope(fqcn, onlyMethod, List.of(), List.of());
    }

    // ── the report ─────────────────────────────────────────────────────────────

    /// One mutant as PIT reported it.
    ///
    /// @param line       boxed: a mutant whose `<lineNumber>` is absent or 0 has no line, and
    ///                   the JS `parseInt(...) || null` says so rather than claiming line 0
    /// @param index      PIT's per-mutant identifier, from inside `<indexes>`. Several mutations
    ///                   of one kind can share a line, and without this they share an attempt
    ///                   key too — so killing one writes off the rest.
    /// @param difficulty filled in for survivors only, by {@link #killDifficulty}
    public record Mutant(String status, boolean detected, Integer line, String mutator, String method,
                         String description, String block, Integer index, Integer difficulty) {

        /// A mutant known only by kind and status — all {@link #killDifficulty} reads.
        public static Mutant kind(String mutator, String status) {
            return new Mutant(status, false, null, mutator, null, null, null, null, null);
        }

        public Mutant withDifficulty(Integer d) {
            return new Mutant(status, detected, line, mutator, method, description, block, index, d);
        }
    }

    /// The per-method breakdown: the unit of work for rounds, and the list of method names
    /// (bytecode truth) that method scoping excludes against.
    public record MethodStat(int total, int killed, int survived) {}

    /// What one PIT run says about one unit.
    ///
    /// Every field the JS put on the returned object, and boxed wherever the JS could leave one
    /// out: `totalMutants` null means the run produced no usable result, which is not zero
    /// mutants, and `score` null means UNMEASURED, which is not a score of 0.
    ///
    /// @param empty      set only by {@link #scopeToMethod}: null for a class-wide result
    /// @param noTests    PIT ran nothing — either it refused, or the glob matched nothing
    /// @param unmeasured the number is unknown. Never store it as a measurement.
    /// @param testsRun   how many tests PIT reported running, null when it did not say
    public record PitResult(String file, String fqcn, Integer totalMutants, int killed, Double score,
                            List<Mutant> survived, Map<String, MethodStat> byMethod, List<Mutant> all,
                            String report, Boolean noTests, Boolean unmeasured, Boolean empty,
                            String scope, Integer testsRun, GreenSuiteFailure greenSuiteFailure) {

        /// The unit could not be measured. Deliberately score null and totalMutants null: a
        /// missing report must read as a failure, never as zero.
        public static PitResult unmeasured(String file, String fqcn, GreenSuiteFailure green) {
            return new PitResult(file, fqcn, null, 0, null, List.of(), null, null, null,
                    true, true, null, null, null, green);
        }

        /// PIT ran and nothing it mutated was killed, because nothing exercises the class.
        /// A real 0, unlike {@link #unmeasured}.
        public static PitResult noMutations(String file, String fqcn) {
            return new PitResult(file, fqcn, null, 0, 0.0, List.of(), null, null, null,
                    true, null, null, null, null, null);
        }

        public PitResult withScope(String s) {
            return new PitResult(file, fqcn, totalMutants, killed, score, survived, byMethod, all,
                    report, noTests, unmeasured, empty, s, testsRun, greenSuiteFailure);
        }

        public PitResult withTestsRun(Integer t) {
            return new PitResult(file, fqcn, totalMutants, killed, score, survived, byMethod, all,
                    report, noTests, unmeasured, empty, scope, t, greenSuiteFailure);
        }
    }

    private static final Pattern EASY_MUTATOR = Pattern.compile(
            "(ReturnVals|PrimitiveReturns|BooleanTrueReturn|BooleanFalseReturn|EmptyObjectReturn|NullReturn"
                    + "|Math|Increments|InlineConstant|ConditionalsBoundary|NegateConditionals|Switch)");
    private static final Pattern HARD_MUTATOR = Pattern.compile(
            "(RemoveConditional|VoidMethodCall|ConstructorCall|NonVoidMethodCall|MemberVariable|RemoveIncrements)");

    /// How hard a mutant is to kill, from ITS OWN PROPERTIES — not from anyone's opinion.
    /// Lower is easier, and the order is what the pipeline attacks first.
    ///
    /// The ranking is empirical. Watching JSON-java, four consecutive rounds picked
    /// RemoveConditionalMutator_EQUAL_ELSE on the model's confident reasoning and every one
    /// survived: removing an equality guard usually leaves behaviour that a short test cannot
    /// distinguish, and often nothing can (an equivalent mutant). Value-returning and
    /// arithmetic mutants are the opposite — the method returns a different value, so one
    /// assertion on the return value settles it.
    ///
    /// SURVIVED beats NO_COVERAGE at every difficulty: the line already executes, so only an
    /// assertion is missing rather than a whole path to reach.
    public static int killDifficulty(Mutant m) {
        String k = m.mutator() == null ? "" : m.mutator();
        int d = EASY_MUTATOR.matcher(k).find() ? 0 : HARD_MUTATOR.matcher(k).find() ? 2 : 1;
        if (!"SURVIVED".equals(m.status())) d += 3;      // NO_COVERAGE needs the path reached first
        return d;
    }

    private static final Pattern MUTATION_RE =
            Pattern.compile("<mutation\\b([^>]*)>([\\s\\S]*?)</mutation>");
    private static final Pattern STATUS_ATTR = Pattern.compile("status=['\"]([^'\"]+)['\"]");
    private static final Pattern DETECTED_ATTR = Pattern.compile("detected=['\"]true['\"]");

    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(\\d+);");
    private static final Pattern HEX_ENTITY = Pattern.compile("&#x([0-9a-f]+);", Pattern.CASE_INSENSITIVE);

    /// XML entity decoding for values read out of the report.
    ///
    /// PIT escapes method names, so a constructor arrives as `&lt;init&gt;` (or `&#60;init&#62;`
    /// from older versions). Unit method names come from JaCoCo, which Coverage already
    /// unescapes — so the two spellings never matched, and every constructor unit scoped to
    /// zero mutants and was written off as having no mutation surface.
    public static String unescapeXml(String v) {
        String s = String.valueOf(v);
        // quoteReplacement: a decoded `&#36;` is a literal `$`, which a replacement string
        // would otherwise read as a group reference
        s = NUMERIC_ENTITY.matcher(s).replaceAll(m ->
                Matcher.quoteReplacement(fromCharCode(m.group(1), 10)));
        s = HEX_ENTITY.matcher(s).replaceAll(m ->
                Matcher.quoteReplacement(fromCharCode(m.group(1), 16)));
        s = s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'");
        // last, or `&amp;lt;` would decode twice
        return s.replace("&amp;", "&");
    }

    /// The text of one child element, unescaped. Pattern.quote on the name: the JS builds this
    /// regex by interpolation, and an unquoted name would be read as a pattern.
    private static String tag(String body, String name) {
        Matcher m = Pattern.compile("<" + Pattern.quote(name) + ">([\\s\\S]*?)</" + Pattern.quote(name) + ">")
                .matcher(body);
        return unescapeXml(m.find() ? m.group(1).trim() : "");
    }

    public static PitResult parseReport(Path reportAbs, String fileRel, String fqcn) {
        String xml = readUtf8(reportAbs);
        List<Mutant> mutants = new ArrayList<>();
        Matcher m = MUTATION_RE.matcher(xml);
        while (m.find()) {
            String attrs = m.group(1), body = m.group(2);
            Matcher st = STATUS_ATTR.matcher(attrs);
            String status = st.find() ? st.group(1) : "UNKNOWN";
            boolean detected = DETECTED_ATTR.matcher(attrs).find();
            String mutatedClass = tag(body, "mutatedClass");
            // Inner and anonymous classes (a.B$Inner, a.B$1) belong to the target; a DIFFERENT
            // class whose name merely begins with it (a.BFoo) does not. `startsWith` accepted
            // both, and with the method filter matching on name alone, a.BFoo#run's kills were
            // credited to a.B#run.
            if (!mutatedClass.isEmpty() && !mutatedClass.equals(fqcn) && !mutatedClass.startsWith(fqcn + "$")) continue;
            String mutator = tag(body, "mutator");
            String index = tag(body, "index");
            mutants.add(new Mutant(
                    status,
                    detected,
                    zeroToNull(State.jsParseInt(tag(body, "lineNumber"))),
                    mutator.substring(mutator.lastIndexOf('.') + 1),
                    tag(body, "mutatedMethod"),
                    tag(body, "description"),
                    tag(body, "block"),
                    index.isEmpty() ? null : State.jsParseInt(index),
                    null));
        }
        // NON_VIABLE mutants cannot be killed by any test — excluding them keeps the score honest
        List<Mutant> scored = mutants.stream().filter(x -> !"NON_VIABLE".equals(x.status())).toList();
        int killed = (int) scored.stream().filter(Mutant::detected).count();
        int total = scored.size();
        double score = total != 0 ? Util.round2((killed * 100.0) / total) : 0;
        // per-method breakdown: the unit of work for rounds, and the list of method names
        // (bytecode truth) that method scoping excludes against
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (Mutant x : scored) {
            int[] e = counts.computeIfAbsent(truthy(x.method()) ? x.method() : "?", k -> new int[3]);
            e[0] += 1;
            if (x.detected()) e[1] += 1; else e[2] += 1;
        }
        Map<String, MethodStat> byMethod = new LinkedHashMap<>();
        counts.forEach((k, e) -> byMethod.put(k, new MethodStat(e[0], e[1], e[2])));
        List<Mutant> survived = new ArrayList<>(scored.stream()
                .filter(x -> !x.detected())
                .map(x -> x.withDifficulty(killDifficulty(x)))
                .toList());
        // Integer.compare, never a subtraction: the JS sorts numbers, and a Java comparator
        // that subtracts can overflow. Stable in both languages, so ties keep report order.
        survived.sort(Comparator.<Mutant>comparingInt(a -> a.difficulty() == null ? 0 : a.difficulty())
                .thenComparingInt(a -> a.line() == null ? 0 : a.line()));
        return new PitResult(fileRel, fqcn, total, killed, score, List.copyOf(survived), byMethod,
                scored, reportAbs.getFileName().toString(), null, null, null, null, null, null);
    }

    /// Narrow a class-wide report to ONE method — the unit of work.
    ///
    /// PIT has no "mutate only this method" switch; we approximate it with excludedMethods,
    /// built from the methods we know about. When that list is incomplete, mutants from other
    /// methods come back in the report, and the consequences were not subtle: a SURVIVED
    /// MathMutator in toString() outranked every NO_COVERAGE mutant of the actual unit, so a
    /// whole round wrote a toString test that was then scored against isRecordStyleAccessor —
    /// a guaranteed miss — while the unit's "mutation score" was computed over another
    /// method's mutants as well. Excluding is best-effort; this filter is not.
    public static PitResult scopeToMethod(PitResult parsed, String method) {
        if (!truthy(method)) return parsed;
        List<Mutant> all = parsed.all() == null ? List.of() : parsed.all();
        List<Mutant> mine = all.stream().filter(m -> method.equals(m.method())).toList();
        int killed = (int) mine.stream().filter(Mutant::detected).count();
        List<Mutant> survived = (parsed.survived() == null ? List.<Mutant>of() : parsed.survived())
                .stream().filter(m -> method.equals(m.method())).toList();
        return new PitResult(parsed.file(), parsed.fqcn(), mine.size(), killed,
                // no mutants for this method is 0, never 100: nothing was proven about it
                mine.isEmpty() ? 0.0 : Util.round2((killed * 100.0) / mine.size()),
                survived,
                // byMethod stays whole — it is bytecode truth about the class, and the exclusion
                // list for the next run is built from it
                parsed.byMethod(), mine, parsed.report(), parsed.noTests(), parsed.unmeasured(),
                // and the caller must be able to tell an empty scope from a measured zero
                mine.isEmpty(), parsed.scope(), parsed.testsRun(), parsed.greenSuiteFailure());
    }

    // ── finding the report PIT left behind ─────────────────────────────────────
    //
    // findReport/findAllReports are public for their own sake, not merely to be reachable from
    // a test: choosing WHICH mutations.xml to read is a decision with four layouts and a
    // newest-wins fallback behind it, and reading the wrong module's report is silent — the
    // numbers look plausible and describe a different class.

    /// Every mutations.xml under the repo — used to clear stale reports before a run.
    public static List<Path> findAllReports(Path dir) {
        List<Path> out = new ArrayList<>();
        walk(dir, 0, out);
        return out;
    }

    private static void walk(Path d, int depth, List<Path> out) {
        if (depth > 7) return;
        List<Path> entries;
        try (var stream = Files.list(d)) {
            entries = new ArrayList<>(stream.toList());
        } catch (IOException | RuntimeException unreadable) {
            return;   // an unreadable directory is skipped, never fatal
        }
        // readdir order is the OS's; sorting makes deletion and the newest-wins tie-break
        // reproducible without changing which files are found
        entries.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (Path p : entries) {
            String name = p.getFileName().toString();
            // NOFOLLOW_LINKS, matching Node's Dirent: a symlink is not a directory, so a link
            // pointing back up the tree is never walked
            if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                if (!name.equals(".git")) walk(p, depth + 1, out);
            } else if (name.equals("mutations.xml")) {
                out.add(p);
            }
        }
    }

    public static Path findReport(Path dir, String moduleRel) {
        Path mod = moduleRel == null || moduleRel.isEmpty() || moduleRel.equals(".") ? dir : dir.resolve(moduleRel);
        List<Path> candidates = List.of(
                mod.resolve("target").resolve("pit-reports").resolve("mutations.xml"),
                mod.resolve("build").resolve("reports").resolve("pitest").resolve("mutations.xml"),
                dir.resolve("target").resolve("pit-reports").resolve("mutations.xml"),
                dir.resolve("build").resolve("reports").resolve("pitest").resolve("mutations.xml"));
        for (Path p : candidates) if (Files.exists(p)) return p;
        // multi-module fallback: newest mutations.xml anywhere. Stream.max keeps the FIRST of
        // equal mtimes, which is what the JS stable sort did.
        return findAllReports(dir).stream()
                .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                .orElse(null);
    }

    // ── running ────────────────────────────────────────────────────────────────

    /// The facts one PIT run reads out of the run state for its unit. Gathered in one place —
    /// {@link #unitOf} — so that everything below it is about PIT and not about state.
    ///
    /// @param method          the unit's method; null when the unit is a whole class
    /// @param siblingMethods  the class's other units, as the baseline enumerated them
    /// @param statMethods     what a previous PIT report knew about, the fallback
    /// @param coverage        JaCoCo line coverage, null when never measured — and null is not
    ///                        0 here: the "no mutations although covered" check turns on it
    public record Unit(String path, String fqcn, String module, String method,
                       List<String> siblingMethods, List<String> statMethods,
                       Long spentSec, Long attemptStartedAt, Double coverage) {}

    /// Run PIT against one source file (= one class) and return its mutation score.
    /// `targetTests` scopes which tests PIT runs — the class's own tests plus ours — so an
    /// unrelated broken test elsewhere in the module cannot sink the measurement.
    public static PitResult runPit(String fileRel) {
        return runPit(fileRel, null, 0);
    }

    static PitResult runPit(String fileRel, String onlyMethod, int greenRetry) {
        Path dir = Repo.repoDir();
        String tool = runnerText("tool");
        if (!truthy(tool)) throw new IllegalStateException("build not detected — call /api/repo/prepare first");
        Unit f = unitOf(fileRel);
        String srcPath = f.path();
        String fqcn = truthy(f.fqcn()) ? f.fqcn() : Repo.fqcnOf(srcPath);
        String moduleRel = truthy(f.module()) ? f.module() : Repo.moduleOf(srcPath);
        // the unit IS a method: mutate only it unless explicitly asked for the whole class
        if (onlyMethod == null) onlyMethod = f.method();
        Scope scope = pitScope(fqcn, onlyMethod, f.siblingMethods(), f.statMethods());

        // Delete any previous report FIRST. findReport() falls back to "newest mutations.xml
        // anywhere", so when a run produces nothing it used to return the PREVIOUS unit's
        // report; parseReport then filtered it by this class, found none of its mutants and
        // reported "0 mutants" — a real method with 25 mutants was written off as having no
        // mutation surface and skipped. A missing report must read as a failure, never as zero.
        for (Path stale : findAllReports(dir)) {
            try { Files.deleteIfExists(stale); } catch (IOException ignored) { }
        }

        // The unit's budget, handed to the subprocess. Without it a single invocation ran for
        // an hour against a configured budget of 900s, because decide() only sees the clock
        // after a round returns.
        State.Config cfg = config();
        long pitMs = pitTimeoutMs(cfg == null ? null : cfg.unitBudgetSec(),
                f.spentSec(), f.attemptStartedAt(), null);

        Exec.Result r;
        if (tool.equals("maven")) {
            ensureMavenWiring(moduleRel);
            // Compile FIRST. `mvn <plugin>:<goal>` runs the goal alone — no lifecycle phase, no
            // javac — and branch creation wipes target/ with `git clean -fd`, so a bare PIT
            // invocation finds no classes and reports "no mutations exercised", i.e. score 0.
            // That silently made every file's BASELINE 0 and flattered every improvement.
            // Arrays.asList, not List.of: a runner with no wrapper is a broken state, and it
            // should fail as a build error the way the JS did rather than as a NullPointerException
            // thrown while assembling the command line.
            List<String> compileArgv = new ArrayList<>(Arrays.asList(runnerText("wrapper"), "-B", "-ntp", "test-compile"));
            if (isModule(moduleRel)) { compileArgv.add("-pl"); compileArgv.add(moduleRel); compileArgv.add("-am"); }
            Exec.Result c = exec(compileArgv, dir, Timeouts.PIT_COMPILE_MS, "compile");
            if (c.code() != 0) {
                throw new IllegalStateException("test-compile before PIT failed: "
                        + tail(truthy(c.stderr()) ? c.stderr() : c.stdout(), 600));
            }
            // Incremental analysis would be ideal here — rounds re-measure the same method over
            // and over — but PIT 1.25 moved history behind the commercial arcmutate plugin:
            // passing historyInputFile/historyOutputFile without it fails the whole run with
            // "History has been enabled but no history plugin has been installed/activated".
            // Off unless a deployment actually has that plugin.
            boolean useHistory = envOr("PIT_HISTORY", "false").equals("true");
            Path histFile = null;
            if (useHistory) {
                Path histDir = dataDir().resolve("pit-history")
                        .resolve(Util.slugify(cfg == null ? "" : cfg.repoUrl()));
                try { Files.createDirectories(histDir); } catch (IOException e) { throw unchecked(e); }
                histFile = histDir.resolve(fqcn.replaceAll("[^\\w.]", "_") + ".txt");
            }
            List<String> argv = new ArrayList<>(Arrays.asList(runnerText("wrapper"), "-B", "-ntp",
                    "org.pitest:pitest-maven:" + PIT_VERSION + ":mutationCoverage",
                    "-DtargetClasses=" + scope.targetClasses(), "-DtargetTests=" + scope.targetTests(),
                    "-Dmutators=" + MUTATORS, "-DoutputFormats=XML", "-DtimestampedReports=false",
                    "-DfailWhenNoMutations=false", "-DtimeoutConstant=8000"));
            if (histFile != null) {
                argv.add("-DhistoryInputFile=" + histFile);
                argv.add("-DhistoryOutputFile=" + histFile);
            }
            if (!scope.excluded().isEmpty()) argv.add("-DexcludedMethods=" + String.join(",", scope.excluded()));
            // no -am here: dependencies are built by the compile step above, and -DskipTests
            // (which -am would need) makes PIT skip its own run
            if (isModule(moduleRel)) { argv.add("-pl"); argv.add(moduleRel); }
            r = exec(argv, dir, pitMs, "pit");
        } else {
            String script = gradleInitScript(scope.targetClasses(), scope.targetTests(), scope.excluded(),
                    InitOpts.of().withGradleVersion(runnerText("gradleVersion"))
                            .withTestFramework(runnerText("testFramework")));
            writeUtf8(dir.resolve(INIT_SCRIPT), script);
            String task = isModule(moduleRel) ? ":" + moduleRel.replace("/", ":") + ":pitest" : "pitest";
            r = exec(gradlePitArgv(runnerText("wrapper"), runnerStrings("scanArgs"), INIT_SCRIPT, task),
                    dir, pitMs, "pit");
        }

        Path reportAbs = findReport(dir, moduleRel);
        if (reportAbs == null) {
            String out = r.stderr() + r.stdout();
            // PIT runs every targetTests class against UNMUTATED bytecode first and refuses to
            // continue if one fails. When the offender is a test we generated, that is not a
            // mystery to report as unmeasurable — it is our own broken test, named, and we can
            // take it back. Round 1's test and round 2's collided over thread-local state inside
            // PIT's single minion JVM while `gradle test` stayed green.
            GreenSuiteFailure green = pitGreenSuiteFailure(out);
            if (green != null) {
                String where = green.testClass() + (truthy(green.method()) ? "#" + green.method() + "()" : "");
                if (green.ours()) {
                    String rel = "src/test/java/" + green.testClass().replace(".", "/") + ".java";
                    // Cut the offending method, not the file. A batch writes several tests into one
                    // file, and deleting it whole undoes work that is passing — including work the
                    // red-suite path had just salvaged moments earlier.
                    String kept = truthy(green.method())
                            ? Salvage.salvageSource(Repo.readFileSafe(rel, 400000), Set.of(green.method()))
                            : null;
                    String what;
                    boolean cut = false;
                    if (truthy(kept)) {
                        Repo.writeTestFile(rel, kept);
                        cut = true;
                        what = "cut, keeping " + count(kept, "@Test") + " passing test(s) in";
                    } else {
                        what = Repo.deleteTestFile(rel) ? "deleted" : "left (already gone)";
                    }
                    State.event("pit", where + " passes on its own and in the suite, but FAILS on unmutated code "
                            + "inside PIT's minion — PIT requires a green suite and refuses to run. It is a test "
                            + "this pipeline generated, so it has been " + what + ": " + rel);
                    // the blocker is gone, so the measurement is possible now — asking again is the
                    // whole point of having cut it
                    if (greenSuiteRetries(greenRetry, cut) != 0) {
                        State.event("pit", "re-measuring now that the blocking test is gone (attempt "
                                + (greenRetry + 2) + ")");
                        return runPit(fileRel, onlyMethod, greenRetry + 1);
                    }
                } else {
                    State.event("pit", where + " fails on unmutated code and PIT requires a green suite — that test "
                            + "belongs to the project, not to this pipeline, so it is left alone and the unit is UNMEASURED");
                }
                return PitResult.unmeasured(fileRel, fqcn, green);
            }
            if (NO_TESTS.matcher(out).find()) {
                // Sanity check on our own measurement: a class the suite demonstrably executes
                // cannot legitimately have "no mutations exercised". When those two disagree the
                // measurement is broken (missing classes, a test-name glob that matches nothing),
                // not the project — say so instead of quietly recording a 0 baseline.
                Double cov = f.coverage();
                if (cov != null && cov > 0) {
                    State.event("pit", fileRel + ": PIT found no tests to run although JaCoCo measured "
                            + Util.showMetric(cov) + "% line coverage — treating the mutation score as UNMEASURED, "
                            + "not 0 (targetTests glob or compiled classes are wrong)");
                    // The inputs and PIT's own words, because without them this is unactionable. It
                    // recurred on round 2 of two different java-dataloader units and three readings of
                    // this code produced three different theories — the run was throwing away the one
                    // thing that could settle it.
                    State.event("pit", "unmeasured inputs: targetClasses=" + scope.targetClasses()
                            + " targetTests=" + scope.targetTests()
                            + " onlyMethod=" + (truthy(onlyMethod) ? onlyMethod : "(class)")
                            + " excludedMethods=[" + String.join(", ", scope.excluded()) + "]");
                    // The TAIL of a Gradle run is its epilogue — deprecation notices and a task count.
                    // The reason lives at the line that matched, so log THAT with its context, and
                    // keep the whole output on disk. Two rounds of diagnosis were spent reading the
                    // epilogue and concluding things about task caching that the epilogue could not
                    // support.
                    String said = context(out).replaceAll("\\s+", " ");
                    State.event("pit", "PIT said: " + said.substring(0, Math.min(700, said.length())));
                    try {
                        Path dump = dataDir().resolve("pit-unmeasured.log");
                        writeUtf8(dump, "# " + ISO.format(Instant.now()) + " " + fileRel
                                + " onlyMethod=" + onlyMethod + "\n" + out);
                        State.event("pit", "full PIT output written to " + dump);
                    } catch (RuntimeException ignored) { /* diagnostics must never break the run */ }
                    return PitResult.unmeasured(fileRel, fqcn, null);
                }
                State.event("pit", fileRel + ": no mutations exercised — mutation score 0 (nothing kills mutants yet)");
                return PitResult.noMutations(fileRel, fqcn);
            }
            throw new IllegalStateException("PIT produced no report (exit " + r.code() + "): " + tail(out, 800));
        }
        // excludedMethods is best-effort — when it misses one, another method's mutants come
        // back in the report and would be scored and attacked as if they were this unit's
        PitResult whole = parseReport(reportAbs, fileRel, fqcn);
        PitResult parsed = scopeToMethod(whole, onlyMethod).withScope(truthy(onlyMethod) ? onlyMethod : "class");
        int strays = whole.totalMutants() - parsed.totalMutants();
        if (truthy(onlyMethod) && strays > 0) {
            // excludedMethods let another method through; say so rather than quietly dropping it
            State.event("pit", strays + " mutant(s) from other methods came back in the report and were "
                    + "excluded from " + onlyMethod + "()'s score and target list");
        }
        // PIT prints how many tests it ran; if our freshly written test is not in that number
        // the round cannot possibly kill anything, and we should say so rather than report an
        // unchanged score as if the test had simply been ineffective
        parsed = parsed.withTestsRun(testsRunIn(r.stdout() + r.stderr()));
        State.event("pit", fileRel + (truthy(onlyMethod) ? " [method " + onlyMethod + "()]" : "") + ": "
                + parsed.totalMutants() + " mutants, " + parsed.killed() + " killed, "
                + parsed.survived().size() + " survived+nocov, score " + Util.showMetric(parsed.score()) + "%"
                + (parsed.testsRun() != null ? " (" + parsed.testsRun() + " tests ran)" : ""));
        return parsed;
    }

    private static final Pattern NO_TESTS =
            Pattern.compile("No mutations found|no tests to run|0 tests", Pattern.CASE_INSENSITIVE);
    private static final Pattern RAN_TESTS = Pattern.compile("Ran (\\d+) tests?");
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /// How many tests PIT said it ran, or null when it did not say — and 0 is "did not say"
    /// here, exactly as the JS `|| null` had it.
    static Integer testsRunIn(String out) {
        Matcher m = RAN_TESTS.matcher(out == null ? "" : out);
        if (!m.find()) return null;
        return zeroToNull(State.jsParseInt(m.group(1)));
    }

    /// The line that matched, with four lines either side — not the tail of the log.
    static String context(String out) {
        // -1: Java drops trailing empty fields by default and JS split does not
        String[] lines = (out == null ? "" : out).split("\n", -1);
        int hit = -1;
        for (int i = 0; i < lines.length; i++) {
            if (NO_TESTS.matcher(lines[i]).find()) { hit = i; break; }
        }
        if (hit < 0) return "(no matching line)";
        return String.join(" | ", Arrays.asList(lines)
                .subList(Math.max(0, hit - 4), Math.min(lines.length, hit + 4)));
    }

    // ── the collaborators ──────────────────────────────────────────────────────
    //
    // Every call into Exec/State/Repo goes through one of these. The JS reached for
    // `state.runner?.x` at a dozen sites; concentrating them means a change to how the state is
    // held is a change here and nowhere else.

    private static Exec.Result exec(List<String> argv, Path cwd, long timeoutMs, String label) {
        return Exec.run(argv, Exec.Options.of()
                .withCwd(cwd).withEnv(Repo.buildEnv()).withTimeoutMs(timeoutMs).withLabel(label));
    }

    private static Path dataDir() { return State.DATA_DIR; }

    /// A field of `state.runner`, null when the build was never detected.
    private static String runnerText(String field) {
        Object v = State.STATE.runner == null ? null : State.STATE.runner.get(field);
        return v == null ? null : String.valueOf(v);
    }

    /// `state.runner.scanArgs` — Gradle's `--no-scan` where the repo applies a build-scan
    /// plugin, and nothing where it does not. Absent for Maven, and an absent list is an empty
    /// one, never a null the argv builder would trip over.
    private static List<String> runnerStrings(String field) {
        Object v = State.STATE.runner == null ? null : State.STATE.runner.get(field);
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) for (Object o : list) if (o != null) out.add(String.valueOf(o));
        return out;
    }

    private static State.Config config() {
        return State.STATE.run == null ? null : State.STATE.run.config;
    }

    /// Everything one unit's run reads out of `state.files`, resolved the way the JS did.
    ///
    /// Taken under the store's monitor: the JS ran on one thread and could iterate `state.files`
    /// while holding it, and here the routes answer on virtual threads. A concurrent
    /// `upsertFile` during the sibling scan is a ConcurrentModificationException, not a stale
    /// read, so a snapshot is not optional.
    private static Unit unitOf(String fileRel) {
        synchronized (State.STATE) {
            Map<String, Object> f = State.STATE.files.getOrDefault(fileRel, Map.of());
            String own = str(f.get("path"));
            String path = truthy(own) ? own : fileRel.split("::", 2)[0];
            // the class's sibling units — JaCoCo enumerated every method at baseline
            List<String> siblings = new ArrayList<>();
            for (Map<String, Object> u : State.STATE.files.values()) {
                String method = str(u.get("method"));
                if (path.equals(str(u.get("path"))) && truthy(method)) siblings.add(method);
            }
            // failing that, whatever a previous PIT report knew about — never parsed from source
            List<String> stats = new ArrayList<>();
            if (f.get("methodStats") instanceof Map<?, ?> ms && ms.get("byMethod") instanceof Map<?, ?> byMethod) {
                for (Object k : byMethod.keySet()) stats.add(String.valueOf(k));
            }
            return new Unit(path, str(f.get("fqcn")), str(f.get("module")), str(f.get("method")),
                    siblings, stats,
                    // absent and 0 mean the same thing to pitTimeoutMs, exactly as the JS
                    // defaults (`spentSec = 0`, `attemptStartedAt = 0`) had it
                    State.asLong(f.get("spentSec")), State.asLong(f.get("attemptStartedAt")),
                    // and here they do NOT: a unit with no coverage measurement is not a unit
                    // measured at 0%, and only the second may be called UNMEASURED
                    State.asDouble(f.get("coverage")));
        }
    }

    /// A stored string, or null. Never `String.valueOf(null)` — the state file is full of
    /// explicit nulls (`upsertFile` seeds every unknown field as one) and a unit whose fqcn is
    /// null would otherwise be measured as the class named "null".
    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    // ── small helpers ──────────────────────────────────────────────────────────

    /// JS truthiness for a string: null and "" are false, everything else is true. The JS leans
    /// on it constantly (`f.fqcn || repo.fqcnOf(...)`, `onlyMethod ? … : []`) and a plain null
    /// check would let an empty string through where the JS would not.
    private static boolean truthy(String s) { return s != null && !s.isEmpty(); }

    private static boolean isModule(String moduleRel) { return truthy(moduleRel) && !moduleRel.equals("."); }

    private static String envOr(String name, String fallback) {
        return State.envOr(System::getenv, name, fallback);
    }

    /// JS `parseInt(x) || null`: 0 is indistinguishable from "absent" there, and callers rely
    /// on that — a line number of 0 is no line number, and "Ran 0 tests" is the same statement
    /// as saying nothing at all.
    private static Integer zeroToNull(Integer v) { return v == null || v == 0 ? null : v; }

    /// `String.fromCharCode(parseInt(digits, radix))`. JS truncates the code point to 16 bits
    /// and so does a `(char)` cast; an entity too long to be a character at all decodes to
    /// nothing, because a malformed report must not take the whole parse down with it.
    private static String fromCharCode(String digits, int radix) {
        try {
            return String.valueOf((char) Long.parseLong(digits, radix));
        } catch (NumberFormatException tooBig) {
            return "";
        }
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) n++;
        return n;
    }

    /// JS `.slice(-n)`: the last n characters, or the whole string when it is shorter.
    private static String tail(String s, int n) {
        String v = s == null ? "" : s;
        return v.length() <= n ? v : v.substring(v.length() - n);
    }

    private static String readUtf8(Path p) {
        try { return Files.readString(p); }
        catch (IOException e) { throw unchecked(e); }
    }

    private static void writeUtf8(Path p, String content) {
        try { Files.writeString(p, content); }
        catch (IOException e) { throw unchecked(e); }
    }

    /// The JS threw a plain Error and the routes report `e.message`; a checked IOException here
    /// would change every signature above it for no gain. The message is the cause's own, not
    /// UncheckedIOException's default `java.io.IOException: …` prefix — these strings reach the
    /// event log and the HTTP response.
    private static UncheckedIOException unchecked(IOException e) {
        return new UncheckedIOException(e.getMessage(), e);
    }
}
