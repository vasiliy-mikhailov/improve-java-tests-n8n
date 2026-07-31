package tech.mikhailov.ijt;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/// Repo lifecycle for JAVA projects: clone, detect build tool / JDK / test framework,
/// compile, branches, source+test file access.
///
/// Java-specific realities this module encodes (see RESEARCH.md §1):
///   - Maven or Gradle, wrapper preferred (./mvnw, ./gradlew) — it pins the build version.
///   - The JDK a project needs to BUILD can exceed its declared bytecode target, and PIT's
///     forked minion dies under the wrong JDK, so we detect a JDK and use it everywhere.
///   - Multi-module repos: every command must run in the module that owns the class.
///   - Tests live in src/test/java and must be named so Surefire/Gradle pick them up.
///
/// Every child process goes through {@link Exec}; every read and write of the run's state goes
/// through {@link State}, exactly as the JS module imported `exec.run` and `state`. The
/// decisions each of those wraps — which JDK to try, which files are in scope, what a method's
/// context is — are separated out as pure functions below, and those are what the tests pin.
public final class Repo {

    private Repo() {}

    /// Declared before every constant that builds a path out of it: static fields initialise in
    /// source order and `File.separator` is not a compile-time constant, so a constant that read
    /// it from further up the file would splice in a null.
    private static final String FS = java.io.File.separator;

    // ── the repository ─────────────────────────────────────────────────────────

    public static Path repoDir() {
        State.Config cfg = config();
        // the JS guard is falsy: no run, no config, or an empty REPO_URL all land here
        if (cfg == null || cfg.repoUrl() == null || cfg.repoUrl().isEmpty()) {
            throw new IllegalStateException("no run started / REPO_URL missing");
        }
        return State.DATA_DIR.resolve("repos").resolve(Util.slugify(cfg.repoUrl()));
    }

    /// @param dir  where the working copy lives
    /// @param head the full commit sha it is sitting on
    public record Cloned(Path dir, String head) {}

    /// `//user:password@` in a git remote — the shape a token takes when someone inlines it.
    private static final Pattern INLINE_CREDS = Pattern.compile("//[^/@\\s]+:[^/@\\s]+@");

    /// Credentials are deliberately NOT inlined into the remote URL — git echoes the full
    /// URL in failure messages, which would put the token in the event log and dashboard.
    /// Auth comes from the gh credential helper entrypoint.sh installs.
    ///
    /// (Named `cloneRepo` rather than `clone`: a static method may not hide `Object#clone`,
    /// and javac rejects the class outright for trying.)
    public static Cloned cloneRepo() {
        State.Config cfg = config();
        Path dir = repoDir();
        mkdirs(dir.getParent());
        // Ask the remote which branches it actually has before naming one. A configured branch
        // the repo does not have used to end the run here; now it falls back to the repo's own
        // default and says so.
        Exec.Result ls = run(List.of("git", "ls-remote", "--symref", cfg.repoUrl()), 120_000, "git ls-remote");
        if (ls.code() != 0) {
            throw new IllegalStateException("cannot reach " + cfg.repoUrl() + ": " + tail(ls.stderr(), 300));
        }
        Branch.Resolved resolved = Branch.resolveBranch(cfg.repoBranch(), ls.stdout());
        if (resolved.fellBack()) State.event("cloning", resolved.reason());
        // the PR base has to follow: it was frozen at the UNRESOLVED branch name, so on a repo
        // whose default is not `main` every `gh pr create --base main` failed after the work
        // had already been measured and committed
        Map<String, Object> rewrite = new LinkedHashMap<>();
        if (cfg.prBase() == null || cfg.prBase().isEmpty() || cfg.prBase().equals(cfg.repoBranch())) {
            rewrite.put("prBase", resolved.branch());
        }
        rewrite.put("repoBranch", resolved.branch());
        // The JS assigns the two fields in place. State.Config is a record, so the run gets a
        // new one built by the same function that built it: applyOverrides only ever touches
        // the keys it is handed, and re-running its coercions over unchanged values is a no-op.
        synchronized (State.STATE) {
            State.STATE.run.config = State.applyOverrides(cfg, rewrite);
        }
        State.save();
        cfg = config();

        if (Files.exists(dir.resolve(".git"))) {
            State.event("cloning", "repo exists, fetching latest");
            Exec.Result remote = run(List.of("git", "remote", "get-url", "origin"), dir, 10_000, null);
            if (INLINE_CREDS.matcher(remote.stdout()).find()) {
                // replaceFirst, not replaceAll: the JS is `.replace(re, '//')` with no /g flag
                String clean = INLINE_CREDS.matcher(remote.stdout().trim()).replaceFirst("//");
                run(List.of("git", "remote", "set-url", "origin", clean), dir, 10_000, null);
                State.event("cloning", "removed inlined credentials from the git remote");
            }
            Exec.Result r = run(List.of("git", "fetch", "origin", cfg.repoBranch()), dir, 300_000, "git fetch");
            if (r.code() != 0) throw new IllegalStateException("git fetch failed: " + tail(r.stderr(), 400));
            run(List.of("git", "checkout", "-f", cfg.repoBranch()), dir, 60_000, null);
            run(List.of("git", "reset", "--hard", "origin/" + cfg.repoBranch()), dir, 60_000, null);
            run(List.of("git", "clean", "-fd"), dir, 60_000, null);
        } else {
            State.event("cloning", "cloning " + cfg.repoUrl() + " (branch " + cfg.repoBranch() + ")");
            Exec.Result r = run(List.of("git", "clone", "--branch", cfg.repoBranch(), "--single-branch",
                    cfg.repoUrl(), dir.toString()), 900_000, "git clone");
            if (r.code() != 0) throw new IllegalStateException("git clone failed: " + tail(r.stderr(), 400));
        }
        Exec.Result head = run(List.of("git", "rev-parse", "HEAD"), dir, 10_000, null);
        String sha = head.stdout().trim();
        State.event("cloning", "repo ready at " + sha.substring(0, Math.min(12, sha.length())));
        return new Cloned(dir, sha);
    }

    // ── build tool ─────────────────────────────────────────────────────────────

    static String readTextSafe(Path abs) { return readTextSafe(abs, 400_000); }

    static String readTextSafe(Path abs, int max) {
        try {
            // Decoded leniently, the way node's 'utf8' is: `Files.readString` THROWS on a
            // malformed byte, and one non-UTF-8 source file must not take a whole detection
            // with it.
            String s = new String(Files.readAllBytes(abs), StandardCharsets.UTF_8);
            return s.length() > max ? s.substring(0, max) : s;
        } catch (Exception e) {
            return "";
        }
    }

    private static final List<String> BUILD_FILES = List.of("pom.xml", "build.gradle", "build.gradle.kts");

    /// Module directory that owns a repo-relative path: nearest ancestor with a build file.
    public static String moduleOf(String rel) {
        Path dir = repoDir();
        // node's path.dirname over the '/'-separated repo-relative paths the ledger holds —
        // already ported and tested next door; see the note on Purge.dirname for why it is not
        // Path#getParent
        String d = Purge.dirname(rel == null ? "" : rel);
        while (!d.isEmpty() && !d.equals(".") && !d.equals("/")) {
            for (String f : BUILD_FILES) {
                if (Files.exists(dir.resolve(d).resolve(f))) return d;
            }
            d = Purge.dirname(d);
        }
        return ".";
    }

    /// Maven artifactId of a module dir — needed for `-pl` in multi-module builds.
    public static String moduleSelector(String moduleRel) {
        return moduleRel != null && !moduleRel.isEmpty() && !moduleRel.equals(".") ? moduleRel : null;
    }

    /// What {@link #detectBuild()} finds.
    ///
    /// @param tool          "maven", "gradle", or null when the repo is neither — the one case
    ///                      {@link #install()} refuses to continue from
    /// @param gradleVersion the version the wrapper pins, or null. The PIT plugin has to match
    ///                      its API, so a guess here is worse than an absence.
    /// @param scanArgs      `--no-scan` where the build publishes a scan, and nothing where it
    ///                      does not — Gradle rejects the flag on a build with no scan plugin
    public record Build(String tool, String wrapper, boolean hasPom, boolean hasGradle,
                        String gradleVersion, List<String> scanArgs) {}

    private static final Pattern GRADLE_DIST = Pattern.compile("gradle-([\\d.]+)-(?:bin|all)");

    public static Build detectBuild() {
        Path dir = repoDir();
        boolean hasPom = Files.exists(dir.resolve("pom.xml"));
        boolean hasGradle = Stream.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
                .anyMatch(f -> Files.exists(dir.resolve(f)));
        String tool = hasPom ? "maven" : hasGradle ? "gradle" : null;
        // the wrapper pins the Gradle version, and the PIT plugin has to match its API
        String gradleVersion = null;
        if ("gradle".equals(tool)) {
            Matcher m = GRADLE_DIST.matcher(readTextSafe(
                    dir.resolve("gradle").resolve("wrapper").resolve("gradle-wrapper.properties"), 20_000));
            gradleVersion = m.find() ? m.group(1) : null;
        }
        String wrapper = "maven".equals(tool)
                ? (Files.exists(dir.resolve("mvnw")) ? "./mvnw" : "mvn")
                : (Files.exists(dir.resolve("gradlew")) ? "./gradlew" : "gradle");
        // Decided once, here, because it is a property of the repo and it is needed by every
        // Gradle invocation: the suite, the coverage build and PIT, once per round each.
        List<String> scanArgs = "gradle".equals(tool)
                ? GradleScan.gradleScanArgs(Stream.of("settings.gradle", "settings.gradle.kts",
                        "build.gradle", "build.gradle.kts")
                        .map(f -> readTextSafe(dir.resolve(f), 200_000)).toList())
                : List.of();
        return new Build(tool, wrapper, hasPom, hasGradle, gradleVersion, scanArgs);
    }

    /// The JDK decision — see {@link #detectJdk()}.
    ///
    /// Every number is BOXED and can be null. A repo that declares no bytecode target has no
    /// `declared`, and an image carrying no JDK at all has no `chosen`; a primitive would have
    /// to call both of those "Java 0", and the fallback below reads `declared` as a floor.
    ///
    /// @param declared  the highest bytecode target the build files declare, or null
    /// @param chosen    the JDK to use first — `order[0]`, until install() proves otherwise
    /// @param order     JDKs to try, best first
    /// @param javaHome  JAVA_HOME for `chosen`; "" when none could be found at all
    /// @param installed majors this image actually carries, ascending
    public record Jdk(Integer declared, Integer chosen, List<Integer> order, String javaHome,
                      List<Integer> installed) {}

    /// Every place a build file states the Java version it wants.
    private static final List<Pattern> JDK_DECLARATIONS = List.of(
            Pattern.compile("<maven\\.compiler\\.(?:release|source|target)>\\s*([\\d.]+)\\s*<"),
            Pattern.compile("<(?:release|source|target)>\\s*([\\d.]+)\\s*<"),
            Pattern.compile("<java\\.version>\\s*([\\d.]+)\\s*<"),
            Pattern.compile("sourceCompatibility\\s*=?\\s*['\"]?(?:JavaVersion\\.VERSION_)?([\\d._]+)"),
            Pattern.compile("targetCompatibility\\s*=?\\s*['\"]?(?:JavaVersion\\.VERSION_)?([\\d._]+)"),
            Pattern.compile("languageVersion\\s*=\\s*JavaLanguageVersion\\.of\\((\\d+)\\)"));

    /// JDK the project needs to BUILD. Declared bytecode target is a floor, not the answer:
    /// plugins/annotation processors often need newer. We take the max of what is declared
    /// and clamp to a JDK that is actually installed (JAVA_HOME_<major> env, set by the image).
    public static Jdk detectJdk() {
        Path dir = repoDir();
        List<String> texts = new ArrayList<>();
        for (String f : List.of("pom.xml", "build.gradle", "build.gradle.kts", "gradle.properties")) {
            String t = readTextSafe(dir.resolve(f));
            if (!t.isEmpty()) texts.add(t);
        }
        Integer declared = declaredJdk(String.join("\n", texts));
        List<Integer> installed = availableJdks();
        List<Integer> order = jdkPreference(declared, installed);
        Integer chosen = order.isEmpty() ? null : order.get(0);
        return new Jdk(declared, chosen, order, javaHomeFor(chosen), installed);
    }

    /// The highest Java version the build files declare, or null when they declare none.
    /// The pure half of {@link #detectJdk()}, and the half worth pinning.
    public static Integer declaredJdk(String blob) {
        List<Integer> versions = new ArrayList<>();
        for (Pattern re : JDK_DECLARATIONS) {
            Matcher m = re.matcher(blob == null ? "" : blob);
            while (m.find()) {
                // `1.8` → 8, `17.0.1` → 17. The underscores are Gradle's `VERSION_1_8` form.
                String v = m.group(1).replace("_", ".").replaceFirst("^1\\.", "");
                Integer n = jsParseInt(v);
                // 6..25: below that is not a JDK anyone builds on, above it is a version string
                // that means something else (a plugin's, a library's)
                if (n != null && n >= 6 && n <= 25) versions.add(n);
            }
        }
        return versions.isEmpty() ? null : versions.stream().max(Comparator.naturalOrder()).orElseThrow();
    }

    /// JDKs to TRY, best first. The declared bytecode target is a floor, not an answer:
    /// Apache Commons and friends declare `maven.compiler.source=1.8` while their plugin
    /// stack (japicmp, animal-sniffer, modern Surefire) refuses to run on JDK 8. Building
    /// those under the declared 8 is what killed half the first eval sweep. So prefer a
    /// modern JDK that can still emit the declared target, and fall back downwards only if
    /// the build actually fails ({@link #install()} walks this list).
    public static List<Integer> jdkPreference(Integer declared, List<Integer> installed) {
        // the JS `declared || 8`: no declaration reads as the oldest target worth assuming,
        // not as "anything will do"
        int d = declared == null || declared == 0 ? 8 : declared;
        List<Integer> inst = installed == null ? List.of() : installed;
        List<Integer> wish;
        if (d <= 8) wish = List.of(17, 11, 21, 8);
        else if (d <= 11) wish = List.of(17, 11, 21);
        else if (d <= 17) wish = List.of(17, 21);
        // A target past 17 asks for itself and then for the newest LTS. At d == 21 that names
        // the same JDK twice, as it does in the JS: harmless, because the loop stops at the
        // first success and a failure merely retries the same JDK once.
        else wish = List.of(d, 21);
        List<Integer> order = wish.stream().filter(v -> inst.contains(v) && v >= d).toList();
        if (!order.isEmpty()) return order;
        // never return empty: fall back to anything installed that can build the target
        List<Integer> fallback = new ArrayList<>(inst.stream().filter(v -> v >= d).toList());
        fallback.addAll(inst);
        return List.copyOf(fallback.subList(0, Math.min(2, fallback.size())));
    }

    private static final Pattern JAVA_HOME_VAR = Pattern.compile("^JAVA_HOME_(\\d+)$");

    /// JDK majors this image actually carries, ascending.
    public static List<Integer> availableJdks() {
        List<Integer> out = new ArrayList<>();
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            Matcher m = JAVA_HOME_VAR.matcher(e.getKey());
            if (!m.matches()) continue;
            Integer v = jsParseInt(m.group(1));
            String home = e.getValue();
            // `!home.isEmpty()` is load-bearing: `Files.exists(Path.of(""))` is TRUE — the empty
            // path names the working directory — where node's `existsSync('')` is false. Without
            // it a JAVA_HOME_21 set to nothing would read as an installed JDK 21.
            if (v != null && home != null && !home.isEmpty() && Files.exists(Path.of(home))) out.add(v);
        }
        // Integer.compare, never `a - b`: a subtracting comparator overflows, and this order is
        // load-bearing — jdkPreference's fallback takes the first two.
        out.sort(Comparator.naturalOrder());
        return List.copyOf(out);
    }

    public static String javaHomeFor(Integer major) {
        String specific = System.getenv("JAVA_HOME_" + major);
        if (specific != null && !specific.isEmpty()) return specific;
        return envOr("JAVA_HOME", "");
    }

    /// Env every build/test/PIT command runs under.
    public static Map<String, String> buildEnv() {
        // read from the STORE, not from a field of this class: after a restart mid-run the
        // detected JDK comes back from state.json and nothing re-detects it
        String home = runnerJavaHome();
        if (home == null || home.isEmpty()) home = envOr("JAVA_HOME", "");
        if (home.isEmpty()) return Map.of();
        return Map.of("JAVA_HOME", home,
                "PATH", Path.of(home).resolve("bin") + ":" + envOr("PATH", ""));
    }

    // ── test framework ─────────────────────────────────────────────────────────

    /// What {@link #detectTestFramework()} decides. `version` is null whenever it could not be
    /// read — PIT's launcher is only pinned when the version is known, because a wrong pin
    /// reports "no tests" rather than a version conflict.
    public record TestFramework(String framework, String version) {}

    private static final Pattern JUPITER_DEP = Pattern.compile("junit-jupiter|org\\.junit\\.jupiter");
    private static final Pattern JUNIT4_DEP = Pattern.compile("<artifactId>junit</artifactId>|junit:junit|junit:junit:4|org\\.junit\\.Test");
    private static final Pattern TESTNG_DEP = Pattern.compile("testng", Pattern.CASE_INSENSITIVE);
    private static final Pattern SAMPLE_JUPITER = Pattern.compile("org\\.junit\\.jupiter\\.api");
    private static final Pattern SAMPLE_JUNIT4 = Pattern.compile("^import\\s+org\\.junit\\.Test;", Pattern.MULTILINE);
    private static final Pattern SAMPLE_TESTNG = Pattern.compile("org\\.testng");

    /// Unit-test framework — decides how PIT must be wired (junit5 needs pitest-junit5-plugin,
    /// testng needs pitest-testng-plugin) and how generated tests are written.
    public static TestFramework detectTestFramework() {
        Path dir = repoDir();
        StringBuilder blob = new StringBuilder();
        for (String f : List.of("pom.xml", "build.gradle", "build.gradle.kts", "gradle/libs.versions.toml")) {
            blob.append(readTextSafe(dir.resolve(f))).append('\n');
        }
        // module build files matter in multi-module repos
        for (String m : listModules().stream().limit(30).toList()) {
            for (String f : BUILD_FILES) {
                blob.append(readTextSafe(dir.resolve(m).resolve(f), 120_000)).append('\n');
            }
        }
        // an existing test file is the most reliable signal
        TestSource sample = findAnyTestSource();
        return classifyTestFramework(blob.toString(), sample == null ? "" : readTextSafe(sample.abs(), 40_000));
    }

    /// Which framework the evidence names. Pure, and separated from the reading because the
    /// ORDER of these signals is the whole decision: a project whose build file still declares
    /// junit4 beside a jupiter engine writes jupiter tests, and only a real test file says so.
    public static TestFramework classifyTestFramework(String blob, String sampleText) {
        String b = blob == null ? "" : blob;
        String s = sampleText == null ? "" : sampleText;
        if (SAMPLE_JUPITER.matcher(s).find()) return new TestFramework("junit5", jupiterVersion(b));
        if (SAMPLE_JUNIT4.matcher(s).find()) return new TestFramework("junit4", null);
        if (SAMPLE_TESTNG.matcher(s).find()) return new TestFramework("testng", null);
        if (JUPITER_DEP.matcher(b).find()) return new TestFramework("junit5", jupiterVersion(b));
        if (JUNIT4_DEP.matcher(b).find()) return new TestFramework("junit4", null);
        if (TESTNG_DEP.matcher(b).find()) return new TestFramework("testng", null);
        return new TestFramework("junit5", null); // sane default for new tests
    }

    private static final List<Pattern> JUPITER_VERSION = List.of(
            Pattern.compile("junit-jupiter(?:-api|-engine)?[^<\\n]*?[:>]\\s*(\\d+\\.\\d+\\.\\d+)"),
            Pattern.compile("<junit[.-]?jupiter[.-]?version>\\s*(\\d+\\.\\d+\\.\\d+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("junit-bom[^\\n]*?(\\d+\\.\\d+\\.\\d+)"));

    public static String jupiterVersion(String blob) {
        for (Pattern re : JUPITER_VERSION) {
            Matcher m = re.matcher(blob == null ? "" : blob);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    private static final List<String> SKIP_DIRS = List.of("target", "build", "node_modules");

    /// All module directories (dirs holding a build file), repo-relative, '.' first.
    public static List<String> listModules() {
        Path dir = repoDir();
        List<String> out = new ArrayList<>();
        out.add(".");
        walkModules(dir, dir, 0, out);
        return out;
    }

    private static void walkModules(Path root, Path d, int depth, List<String> out) {
        if (depth > 4) return;
        for (Path p : listDir(d)) {
            String name = p.getFileName().toString();
            // NOFOLLOW_LINKS: node's dirent reports a symlink as a symlink and never as a
            // directory, so a link pointing back up the tree is skipped rather than walked
            if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) || name.startsWith(".") || SKIP_DIRS.contains(name)) continue;
            for (String f : BUILD_FILES) {
                if (Files.exists(p.resolve(f))) { out.add(relative(root, p)); break; }
            }
            walkModules(root, p, depth + 1, out);
        }
    }

    /// @param abs the file on disk
    /// @param rel the same file, repo-relative and '/'-separated
    public record TestSource(Path abs, String rel) {}

    private static final Pattern TEST_CLASS_FILE = Pattern.compile("Tests?\\.java$");
    private static final String SRC_TEST = FS + "src" + FS + "test" + FS;
    private static final String SRC_TEST_JAVA = FS + "src" + FS + "test" + FS + "java" + FS;

    public static TestSource findAnyTestSource() {
        Path dir = repoDir();
        return findAnyTestSource(dir, dir, 0);
    }

    private static TestSource findAnyTestSource(Path root, Path d, int depth) {
        if (depth > 8) return null;
        for (Path p : listDir(d)) {
            String name = p.getFileName().toString();
            if (name.startsWith(".") || SKIP_DIRS.contains(name)) continue;
            if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                TestSource found = findAnyTestSource(root, p, depth + 1);
                if (found != null) return found;
            } else if (TEST_CLASS_FILE.matcher(name).find() && p.toString().contains(SRC_TEST)) {
                return new TestSource(p, relative(root, p));
            }
        }
        return null;
    }

    // ── compile / prepare ──────────────────────────────────────────────────────

    /// Detect the toolchain, then prove it by compiling.
    ///
    /// @return `state.runner` — the live map every later stage reads. See {@link #runnerMap}
    ///         for why it is a map and not a record.
    public static Map<String, Object> install() {
        Path dir = repoDir();
        Build build = detectBuild();
        if (build.tool() == null) {
            throw new IllegalStateException("no Maven pom.xml or Gradle build file found — not a supported Java repo");
        }
        Jdk jdk = detectJdk();
        if (jdk.javaHome() == null || jdk.javaHome().isEmpty()) {
            throw new IllegalStateException("no JDK available in the image");
        }
        Map<String, Object> rn = runnerMap(build, jdk, new TestFramework(null, null), null);
        setRunner(rn);
        State.event("installing", "build=" + build.tool() + " (" + build.wrapper() + "), declared JDK "
                + (jdk.declared() == null ? "?" : String.valueOf(jdk.declared())) + ", "
                + "trying " + join(jdk.order(), " → ") + " (available " + join(jdk.installed(), "/") + ")");

        // Compile main + test sources under each candidate JDK until one works: this both
        // warms the Nexus-mirrored dependency cache and DISCOVERS the JDK the project really
        // needs, which is the only reliable way to know it.
        List<String> argv = compileArgv(build);
        Exec.Result r = null;
        String lastErr = "";
        List<Integer> order = jdk.order();
        for (int i = 0; i < order.size(); i++) {
            Integer major = order.get(i);
            // the env of every command below is derived from this, so it is written to the store
            // BEFORE the command that depends on it
            rn.put("jdk", jdkMap(new Jdk(jdk.declared(), major, order, javaHomeFor(major), jdk.installed())));
            r = run(argv, dir, 2_400_000, "compile (JDK " + major + ")", buildEnv());
            if (r.code() == 0) {
                if (!major.equals(order.get(0))) {
                    State.event("installing",
                            "JDK " + order.get(0) + " could not build this project — JDK " + major + " can");
                }
                break;
            }
            lastErr = tail(r.stderr() == null || r.stderr().isEmpty() ? r.stdout() : r.stderr(), 700);
            State.event("installing", "build failed under JDK " + major
                    + (i == order.size() - 1 ? "" : " — trying the next JDK"));
        }
        if (r == null || r.code() != 0) {
            throw new IllegalStateException("project build failed under every candidate JDK ("
                    + join(order, "/") + "): " + lastErr);
        }

        TestFramework tf = detectTestFramework();
        rn.put("testFramework", tf.framework());
        rn.put("testFrameworkVersion", tf.version());
        rn.put("hasJacoco", detectJacoco());
        State.save();
        // `jdk.chosen()` — the FIRST candidate — and not the one that actually compiled, which
        // is what the JS reports here too. The preceding event names the JDK that worked and
        // `state.runner.jdk` carries it; only this sentence differs, and a port is not the
        // place to change what an event says.
        State.event("installing", "ready (build=" + build.tool() + ", JDK " + jdk.chosen() + ", tests="
                + tf.framework() + (tf.version() != null ? " " + tf.version() : "") + ")");
        return rn;
    }

    /// The command that compiles main + test sources. A decision, not IO, which is why it is
    /// tested: it is also the FIRST Gradle invocation of every run, and the one that shipped
    /// without `scanArgs` while the suite, coverage and PIT calls all had it — so every run
    /// still began by uploading a build scan and blocking on it.
    static List<String> compileArgv(Build build) {
        if ("maven".equals(build.tool())) {
            return List.of(build.wrapper(), "-B", "-ntp", "-DskipTests", "test-compile");
        }
        return concat(List.of(build.wrapper(), "--no-daemon"), build.scanArgs(), List.of("-q", "testClasses"));
    }

    /// `state.runner`, as the store holds it.
    ///
    /// A map rather than a record, because this object IS the wire format: it round-trips
    /// through state.json, it is the body of `/api/repo/prepare`, and Pit, Coverage and Tests
    /// all read it by field name. The names below are therefore a contract, which is why a
    /// test asserts them.
    ///
    /// @param framework may carry nulls — install() publishes the build before it knows the
    ///                  framework, exactly as the JS sets `testFramework: null` first
    /// @param hasJacoco null until asked; see {@link #detectJacoco()}
    public static Map<String, Object> runnerMap(Build build, Jdk jdk, TestFramework framework, Boolean hasJacoco) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tool", build.tool());
        m.put("wrapper", build.wrapper());
        m.put("hasPom", build.hasPom());
        m.put("hasGradle", build.hasGradle());
        m.put("gradleVersion", build.gradleVersion());
        m.put("scanArgs", build.scanArgs() == null ? List.of() : build.scanArgs());
        m.put("jdk", jdkMap(jdk));
        m.put("testFramework", framework == null ? null : framework.framework());
        m.put("testFrameworkVersion", framework == null ? null : framework.version());
        m.put("hasJacoco", hasJacoco);
        return m;
    }

    static Map<String, Object> jdkMap(Jdk jdk) {
        if (jdk == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("declared", jdk.declared());
        m.put("chosen", jdk.chosen());
        m.put("order", jdk.order());
        m.put("javaHome", jdk.javaHome());
        m.put("installed", jdk.installed());
        return m;
    }

    private static final Pattern JACOCO_ANY = Pattern.compile("jacoco", Pattern.CASE_INSENSITIVE);
    private static final Pattern JACOCO_MAVEN_PLUGIN = Pattern.compile("jacoco-maven-plugin");

    /// Does the project already wire JaCoCo itself?
    ///
    /// It matters because adding our own `prepare-agent` on top of an existing one puts TWO
    /// -javaagent entries on the test JVM, and the JVM dies with
    /// `java.lang.instrument ASSERTION FAILED ... invokeJavaAgentMainMethod` before a single
    /// test runs. Apache's commons-parent is the common case, and it is invisible in the
    /// repo's own pom.xml — only the EFFECTIVE pom shows it, so that is what we ask for.
    /// (Cheap: resolves the parent chain, runs no tests.)
    public static boolean detectJacoco() {
        Path dir = repoDir();
        if (!"maven".equals(runnerText("tool"))) {
            // Gradle: our init script owns the wiring, and applying the plugin twice is a no-op
            String blob = String.join("\n", readTextSafe(dir.resolve("build.gradle")),
                    readTextSafe(dir.resolve("build.gradle.kts")));
            return JACOCO_ANY.matcher(blob).find();
        }
        // -Doutput, not stdout: help:effective-pom prints at INFO level, so `-q` silently
        // yields an empty POM — which made this return false for every Apache project and
        // put a second agent on the JVM anyway.
        Path out = dir.resolve(".ijt-effective-pom.xml");
        Exec.Result r = run(List.of(runnerText("wrapper"), "-B", "-ntp", "help:effective-pom", "-Doutput=" + out),
                dir, 600_000, "effective-pom", buildEnv());
        String text;
        try {
            text = new String(Files.readAllBytes(out), StandardCharsets.UTF_8);
        } catch (Exception e) {
            text = r.stdout();
        }
        try { Files.deleteIfExists(out); } catch (Exception ignored) { /* it may never have been written */ }
        if (r.code() != 0 && (text == null || text.isEmpty())) return false;   // unknown → assume not, and inject our own
        boolean has = JACOCO_MAVEN_PLUGIN.matcher(text == null ? "" : text).find();
        State.event("installing", has
                ? "project configures JaCoCo itself — using its agent (a second one would crash the test JVM)"
                : "project has no JaCoCo — the pipeline supplies its own agent");
        return has;
    }

    // ── source files ───────────────────────────────────────────────────────────

    private static final Pattern MAIN_SRC = Pattern.compile("(^|/)src/main/java/");
    private static final Pattern TEST_SRC = Pattern.compile("(^|/)src/test/java/");
    private static final Pattern BUILD_OUTPUT = Pattern.compile("(^|/)(target|build|generated|generated-sources)/");
    private static final Pattern NOT_A_CLASS = Pattern.compile("(package-info|module-info)\\.java$");

    public static List<String> listScopeFiles() {
        Path dir = repoDir();
        Exec.Result r = run(List.of("git", "ls-files"), dir, 30_000, null);
        if (r.code() != 0) throw new IllegalStateException("git ls-files failed");
        List<String> files = scopeFiles(r.stdout(), config().scopeGlob());
        // line count is the cheapest pre-measurement signal of mutant surface: the pick
        // stage uses it to prefer logic-dense classes over huge generated/DTO files
        for (String p : files) {
            Integer lines = null;
            try {
                // split(-1), because JS split keeps the trailing empty field: a file ending in
                // a newline counts one line more than its content has, and every line count in
                // the ledger was produced that way
                lines = new String(Files.readAllBytes(dir.resolve(p)), StandardCharsets.UTF_8).split("\n", -1).length;
            } catch (Exception ignored) {
                // unreadable: the record says "unknown", never 0
            }
            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("lines", lines);
            patch.put("fqcn", fqcnOf(p));
            patch.put("module", moduleOf(p));
            State.upsertFile(p, patch);
        }
        return files;
    }

    /// Which of the repo's tracked files are in scope. Pure — this is the decision, and it
    /// needs no repository on disk to check.
    public static List<String> scopeFiles(String lsFilesStdout, String scopeGlob) {
        Predicate<String> match = Util.globsToMatcher(scopeGlob);
        return Arrays.stream((lsFilesStdout == null ? "" : lsFilesStdout).split("\n", -1))
                .map(String::trim).filter(s -> !s.isEmpty())
                .filter(p -> p.endsWith(".java"))
                .filter(p -> MAIN_SRC.matcher(p).find() && !TEST_SRC.matcher(p).find())
                .filter(p -> !BUILD_OUTPUT.matcher(p).find())
                .filter(p -> !NOT_A_CLASS.matcher(p).find())
                .filter(match)
                .toList();
    }

    private static final Pattern MAIN_JAVA_CLASS = Pattern.compile("src/main/java/(.+)\\.java$");
    private static final Pattern PACKAGE_DECL = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    /// Fully-qualified class name for a repo-relative source path.
    public static String fqcnOf(String rel) {
        Matcher m = MAIN_JAVA_CLASS.matcher(rel == null ? "" : rel);
        if (m.find()) return m.group(1).replace('/', '.');
        // non-standard layout: fall back to the package declaration
        return fqcnFromSource(rel, readFileSafe(rel, 8000));
    }

    /// The fallback half of {@link #fqcnOf}, pure.
    static String fqcnFromSource(String rel, String src) {
        Matcher pm = PACKAGE_DECL.matcher(src == null ? "" : src);
        String pkg = pm.find() ? pm.group(1) : null;
        String base = rel == null ? "" : rel.substring(rel.lastIndexOf('/') + 1);
        // node's path.basename(rel, '.java'): the suffix comes off only when it is there
        String cls = base.endsWith(".java") ? base.substring(0, base.length() - ".java".length()) : base;
        return pkg != null ? pkg + "." + cls : cls;
    }

    // ── branches ───────────────────────────────────────────────────────────────

    public static String createBranch(String name) {
        Path dir = repoDir();
        State.Config cfg = config();
        // A branch is per FILE — that is what a PR is — while a unit is a METHOD, so the second
        // method of a file arrives at a branch already carrying the first method's committed
        // (and possibly already PR'd) rounds. Resetting it there loses that work.
        String runId;
        Branch.Decision decision;
        synchronized (State.STATE) {
            State.Run runState = State.STATE.run;
            if (runState.branchOwners == null) runState.branchOwners = new LinkedHashMap<>();
            runId = runState.id;
            Object owner = runState.branchOwners.get(name);
            decision = Branch.branchAction(name, owner == null ? null : String.valueOf(owner), runId);
        }
        run(List.of("git", "checkout", "-f", cfg.repoBranch()), dir, 60_000, null);
        run(List.of("git", "clean", "-fd"), dir, 60_000, null);
        List<String> argv = decision.action() == Branch.Action.REUSE
                ? List.of("git", "checkout", "-f", name)                      // continue on top of it
                : List.of("git", "checkout", "-B", name, cfg.repoBranch());   // start again from the base
        Exec.Result r = run(argv, dir, 60_000, null);
        if (r.code() != 0) throw new IllegalStateException("branch create failed: " + tail(r.stderr(), 300));
        synchronized (State.STATE) {
            State.STATE.run.branchOwners.put(name, runId);
        }
        State.save();
        State.event("branching", "working on branch " + name + " ("
                + (decision.action() == Branch.Action.REUSE ? "continuing this run's work on it" : decision.reason()) + ")");
        return name;
    }

    /// Discard uncommitted changes on the current branch, keeping its commits.
    public static void discardUncommitted() {
        Path dir = repoDir();
        // `git reset` FIRST. Producing a diff for the dashboard runs `git add -N` on new test
        // files so they appear in it, and an intent-to-add entry makes the file tracked as far
        // as `git clean` is concerned — so a missed round's test survived the discard as a
        // zero-byte file and rode into a prepared PR. Unstaging first costs nothing: accepted
        // rounds are already committed.
        run(List.of("git", "reset", "--quiet"), dir, 60_000, null);
        run(List.of("git", "checkout", "--", "."), dir, 60_000, null);
        run(List.of("git", "clean", "-fd"), dir, 60_000, null);
    }

    public static void resetToBase() {
        Path dir = repoDir();
        State.Config cfg = config();
        run(List.of("git", "checkout", "-f", cfg.repoBranch()), dir, 60_000, null);
        run(List.of("git", "clean", "-fd"), dir, 60_000, null);
    }

    // ── file access ────────────────────────────────────────────────────────────

    public static String readFileSafe(String rel) { return readFileSafe(rel, 200_000); }

    public static String readFileSafe(String rel, int maxLen) {
        Path dir = repoDir();
        Path abs = resolveInRepo(dir, rel);
        if (!inside(dir, abs)) throw new IllegalArgumentException("path escapes repo");
        try {
            String s = new String(Files.readAllBytes(abs), StandardCharsets.UTF_8);
            return s.length() > maxLen ? s.substring(0, maxLen) : s;
        } catch (Exception e) {
            return null;
        }
    }

    /// @param path  the file that was written, repo-relative
    /// @param bytes its size in UTF-8 bytes — the JS `Buffer.byteLength`, not the character
    ///              count, which differ the moment a test contains a non-ASCII literal
    public record Written(String path, int bytes) {}

    public static Written writeTestFile(String rel, String content) {
        if (rel == null || !TEST_SRC.matcher(rel).find()) {
            throw new IllegalArgumentException("refusing to write outside src/test/java: " + rel);
        }
        if (!rel.endsWith(".java")) throw new IllegalArgumentException("test file must be a .java file");
        Path dir = repoDir();
        Path abs = resolveInRepo(dir, rel);
        if (!inside(dir, abs)) throw new IllegalArgumentException("path escapes repo");
        mkdirs(abs.getParent());
        byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        try {
            Files.write(abs, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Written(rel, bytes.length);
    }

    public static boolean deleteTestFile(String rel) {
        if (rel == null || !TEST_SRC.matcher(rel).find()) {
            throw new IllegalArgumentException("refusing to delete outside src/test/java");
        }
        Path dir = repoDir();
        Path abs = resolveInRepo(dir, rel);
        if (!inside(dir, abs)) throw new IllegalArgumentException("path escapes repo");
        try {
            return Files.deleteIfExists(abs);
        } catch (Exception e) {
            return false;
        }
    }

    /// Is this generated test still on disk?
    ///
    /// Verification needs to know whether the round's test was there when PIT ran, because a
    /// test deleted for breaking the suite leaves a score that looks exactly like a test that
    /// ran and killed nothing. Asked of the file system rather than tracked in a flag: the flag
    /// is a claim, this is the thing itself.
    public static boolean testFileExists(String rel) {
        Path dir = repoDir();
        // An absent path resolves to the repo root, which is not INSIDE the repo by the test
        // below — so "no path" answers false rather than "yes, the directory is there".
        Path abs = resolveInRepo(dir, rel == null ? "" : rel);
        if (!inside(dir, abs)) return false;
        return Files.exists(abs);
    }

    /// Where the generated tests for a class go, and where its own test already is.
    ///
    /// @param path    the project's OWN test for the class — the style reference — or the most
    ///                likely place for it when the class has none
    /// @param exists  whether that file is actually there
    /// @param covPath this round's generated coverage-phase class
    /// @param mutPath this round's generated mutation-phase class
    public record TestPathGuess(String path, boolean exists, String covPath, String mutPath) {}

    public static TestPathGuess guessTestPath(String srcRel) { return guessTestPath(srcRel, 1, ""); }

    /// Where the generated tests for `srcRel` go: same module, same package, under
    /// src/test/java — and named so the BUILD actually runs them.
    ///
    /// That last part is not cosmetic. Surefire's default includes are `Test*.java`,
    /// `*Test.java`, `*Tests.java`, `*TestCase.java` (Gradle's are equivalent), so a class
    /// called `PricingMacTestCov` is compiled and then never executed: `mvn test` stays
    /// green and JaCoCo reports nothing, while PIT — which selects tests by its own glob —
    /// happily runs it and reports a mutation score. That mismatch produced a file measured
    /// at coverage 0 % / mutation 100 %. Every generated name therefore ENDS with `Test`.
    public static TestPathGuess guessTestPath(String srcRel, int round, String method) {
        Path dir = repoDir();
        // the naming rules (Surefire-includable, one class per round PER METHOD) live in
        // TestPaths, under test
        List<String> candidates = TestPaths.existingTestCandidates(srcRel);
        String own = candidates.stream().filter(c -> Files.exists(dir.resolve(c))).findFirst().orElse(null);
        TestPaths.Generated gen = TestPaths.generatedTestPaths(srcRel, round, method);
        return new TestPathGuess(own != null ? own : candidates.get(0), own != null, gen.covPath(), gen.mutPath());
    }

    /// @param path    the sibling test, repo-relative
    /// @param content its source, clipped to what a prompt can carry
    public record StyleReference(String path, String content) {}

    /// When the target class has no test, hand the LLM a sibling test to imitate: it shows
    /// the repo's assertion library (JUnit 5 vs 4, AssertJ, Hamcrest), test base classes and
    /// naming conventions. Prefers a test from the same module.
    public static StyleReference findStyleReference(String srcRel) {
        Path dir = repoDir();
        String wantModule = moduleOf(srcRel);
        List<Path> matches = new ArrayList<>();
        walkTests(dir, 0, matches);
        if (matches.isEmpty()) return null;
        List<String> rels = matches.stream().map(p -> relative(dir, p)).toList();
        List<String> sameModule = rels.stream().filter(p -> moduleOf(p).equals(wantModule)).toList();
        List<String> pool = sameModule.isEmpty() ? rels : sameModule;
        // smallest non-trivial test → most digestible reference
        String best = null;
        long bestSize = Long.MAX_VALUE;
        for (String p : pool) {
            try {
                long size = Files.size(dir.resolve(p));
                if (size > 400 && size < bestSize) { best = p; bestSize = size; }
            } catch (Exception ignored) {
                // it was there a moment ago; it is not a reference we need
            }
        }
        if (best == null) return null;
        return new StyleReference(best, readFileSafe(best, 8000));
    }

    private static void walkTests(Path d, int depth, List<Path> matches) {
        if (depth > 9 || matches.size() > 400) return;
        for (Path p : listDir(d)) {
            String name = p.getFileName().toString();
            if (name.startsWith(".") || SKIP_DIRS.contains(name)) continue;
            if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) walkTests(p, depth + 1, matches);
            else if (TEST_CLASS_FILE.matcher(name).find() && p.toString().contains(SRC_TEST_JAVA)) matches.add(p);
        }
    }

    // ── the method under test ──────────────────────────────────────────────────

    /// The part of a class the model needs in order to test ONE method.
    ///
    /// @param header     package + imports + the class declaration
    /// @param signatures sibling method signatures, one line each, no bodies
    /// @param body       the target method, from its declaration to the matching brace
    /// @param startLine  1-based line the body starts at
    public record MethodContext(String header, List<String> signatures, String body, int startLine) {}

    private static final Pattern HEADER_LINE = Pattern.compile("^\\s*(package|import)\\s");
    private static final Pattern TYPE_DECL = Pattern.compile("\\b(class|interface|enum|record)\\s+\\w");
    private static final Pattern SIGNATURE_LINE = Pattern.compile(
            "^\\s{2,6}(public|protected|private|static)[\\w\\s<>\\[\\],.]*\\([^)]*\\)\\s*(\\{|throws)");
    private static final Pattern TRAILING_BRACE = Pattern.compile("\\s*\\{\\s*$");

    /// The source the model actually needs to test ONE method: the file's package + imports,
    /// the class declaration, the sibling method signatures (so it knows the available API),
    /// and the target method's full body.
    ///
    /// Sending the whole class instead is what made each call slow: XML.java is ~1500 lines,
    /// 12k characters of prompt to produce a 400-byte test for one method. Prompt size drives
    /// both latency and cost, and most of that class is irrelevant to the mutant at hand.
    public static MethodContext methodContext(String rel, String methodName, Integer methodLine) {
        return methodContext(rel, methodName, methodLine, List.of());
    }

    /// @param alsoCovering surviving mutant lines that must be visible — see
    ///                     {@link #methodContextOf(String, String, Integer, List)}
    public static MethodContext methodContext(String rel, String methodName, Integer methodLine,
                                              List<Integer> alsoCovering) {
        String src = readFileSafe(rel, 500_000);
        if (src == null || src.isEmpty()) return null;
        return methodContextOf(src, methodName, methodLine, alsoCovering);
    }

    /// The pure half of {@link #methodContext}: that one needs a repository, this one needs a
    /// string.
    public static MethodContext methodContextOf(String src, String methodName, Integer methodLine) {
        return methodContextOf(src, methodName, methodLine, List.of());
    }

    /// The same, plus the overloads that own the surviving mutants.
    ///
    /// A unit is `path::name`, and both the expansion and `Pit.scopeToMethod` match on the NAME —
    /// so one unit spans every overload and PIT reports survivors across all of them. Showing the
    /// model only the block that owns `methodLine` makes the other targets unwritable by
    /// construction: it is asked to kill a mutant on code it cannot see.
    ///
    /// That is where the heaviest zero-conversion units in the log sit. StringBuilderWriter#write
    /// spent 21 rounds and killed nothing, with 14 mutants spread over four overloads while the
    /// record carried methodLine 36. JSONObject#toString 21 rounds, JSONTokener#next 23,
    /// JSONArray#addAll 29 — all zero.
    ///
    /// Bounded by the survivors, so only overloads with something live in them are added; an
    /// overload with nothing to kill is not worth the prompt budget. Splitting the unit by
    /// descriptor instead would change what the ledgers key on, and is deliberately not done.
    ///
    /// @param alsoCovering 1-based source lines that must be visible — the surviving mutants
    public static MethodContext methodContextOf(String src, String methodName, Integer methodLine,
                                                List<Integer> alsoCovering) {
        String[] lines = src.split("\n", -1);
        List<String> header = new ArrayList<>();
        for (String l : lines) {
            if (HEADER_LINE.matcher(l).find()) header.add(l);
            if (TYPE_DECL.matcher(l).find()) {
                header.add(TRAILING_BRACE.matcher(l).replaceFirst(" {"));
                break;
            }
        }
        // sibling signatures: one line each, no bodies
        List<String> signatures = Arrays.stream(lines)
                .filter(l -> SIGNATURE_LINE.matcher(l).find())
                .map(l -> TRAILING_BRACE.matcher(l).replaceFirst(";").trim())
                .limit(60).toList();
        // The method name goes into a regex, so it is quoted. The JS strips everything but word
        // characters first, which leaves nothing a regex could read as syntax — quoting keeps
        // that true even if the strip is ever loosened.
        String clean = (methodName == null ? "" : methodName).replaceAll("[^\\w]", "");
        Pattern call = Pattern.compile("\\b" + Pattern.quote(clean) + "\\s*\\(");

        int[] target = blockAround(lines, call, methodLine == null ? 1 : methodLine);
        // Nothing that contains the target line: fall back to the plain window rather than emit
        // a fragment of some other method. An honest 120 lines from here beats a confident lie.
        if (target == null) target = new int[] {Math.max(0, (methodLine == null ? 1 : methodLine) - 1),
                Math.min(lines.length, (methodLine == null ? 1 : methodLine) - 1 + 120)};
        List<int[]> blocks = new ArrayList<>();
        blocks.add(target);
        // The overloads that own the surviving mutants, in the order PIT reported them. The
        // target's own block leads: it is the one the unit is named for.
        for (Integer line : alsoCovering == null ? List.<Integer>of() : alsoCovering) {
            if (line == null || line < 1) continue;
            int at = line - 1;
            // already visible — the common case, one overload with its survivors inside it
            if (blocks.stream().anyMatch(b -> at >= b[0] && at < b[1])) continue;
            int[] block = blockAround(lines, call, line);
            // A survivor whose declaration could not be located is SKIPPED, not guessed at. It
            // costs one un-writable target; guessing costs a prompt full of dangling fragments.
            if (block != null) blocks.add(block);
        }
        StringBuilder body = new StringBuilder(joinLines(lines, target[0], target[1]));
        for (int i = 1; i < blocks.size(); i++) {
            body.append("\n\n  // … other members omitted …\n\n")
                    .append(joinLines(lines, blocks.get(i)[0], blocks.get(i)[1]));
        }
        return new MethodContext(String.join("\n", header), signatures, body.toString(), target[0] + 1);
    }

    /// The declaration block containing a 1-based source line, as `{start, endExclusive}`.
    ///
    /// Walks back from the line to its signature — JaCoCo points at the first EXECUTABLE line,
    /// not the declaration — then forward to the matching closing brace.
    ///
    /// Braces are counted in the text as it stands, strings and comments included: the same
    /// approximation the JS makes. It costs a method whose body holds an unbalanced brace inside
    /// a string literal, and buys not having a Java parser here.
    private static int[] blockAround(String[] lines, Pattern call, int oneBasedLine) {
        int startAt = Math.max(0, oneBasedLine - 1);
        // A DECLARATION, not merely a line mentioning the name. The walk-back used the same
        // pattern that DETECTS a call, and overloads delegate — `return this.put(value);` — so it
        // stopped on the survivor's own body line. With no `{` yet seen, the forward scan then
        // ran straight past the method's closing brace and ended on the NEXT overload's
        // signature. Measured on the real org/json/JSONArray.java: of 17 `put` overloads only 2
        // blocks began on a declaration; the model was shown 9 054 characters of bare `return`
        // statements followed by dangling signatures, and asked to write tests against them.
        int start = -1;
        for (int i = Math.min(startAt, lines.length - 1); i >= Math.max(0, startAt - 400); i--) {
            if (!call.matcher(lines[i]).find()) continue;
            if (SIGNATURE_LINE.matcher(lines[i]).find()) { start = i; break; }
        }
        if (start < 0) {
            // No declaration matched: the original 12-line walk, which is what `<init>` needs —
            // a constructor's declaration carries the CLASS name, so `\binit\(` never matches it.
            start = startAt;
            for (int i = Math.min(startAt, lines.length - 1); i >= Math.max(0, startAt - 12); i--) {
                if (call.matcher(lines[i]).find()) { start = i; break; }
            }
        }
        int depth = 0;
        boolean opened = false;
        for (int i = start; i < Math.min(lines.length, start + 600); i++) {
            for (char ch : lines[i].toCharArray()) {
                if (ch == '{') { depth += 1; opened = true; } else if (ch == '}') depth -= 1;
            }
            // and the block must actually CONTAIN the line it was asked about. A declaration
            // wrapped over several lines is missed by SIGNATURE_LINE, and the walk would then
            // anchor on an earlier overload and return a block the survivor is not in — a
            // plausible-looking fragment, which is worse than nothing.
            if (opened && depth <= 0) {
                return (startAt >= start && startAt <= i) ? new int[] {start, i + 1} : null;
            }
        }
        return new int[] {start, Math.min(lines.length, start + 120)};
    }

    // ── the state, and the children ────────────────────────────────────────────
    //
    // Every read of the run's configuration and every child process goes through one of these,
    // so a change to how either is held is a change here and nowhere else.

    static State.Config config() {
        synchronized (State.STATE) {
            State.Run run = State.STATE.run;
            return run == null ? null : run.config;
        }
    }

    private static void setRunner(Map<String, Object> runner) {
        synchronized (State.STATE) {
            State.STATE.runner = runner;
        }
        State.save();
    }

    /// A field of `state.runner` as text, null when the build was never detected.
    static String runnerText(String field) {
        synchronized (State.STATE) {
            Map<String, Object> rn = State.STATE.runner;
            Object v = rn == null ? null : rn.get(field);
            // never String.valueOf: it answers "null" for an absent field, and "null" is a
            // wrapper name this module would then try to execute
            return v == null ? null : String.valueOf(v);
        }
    }

    /// `state.runner?.jdk?.javaHome`, with the JS's tolerance for every step of it being absent.
    private static String runnerJavaHome() {
        synchronized (State.STATE) {
            Map<String, Object> rn = State.STATE.runner;
            Object jdk = rn == null ? null : rn.get("jdk");
            if (!(jdk instanceof Map<?, ?> m)) return null;
            Object home = m.get("javaHome");
            return home == null ? null : String.valueOf(home);
        }
    }

    private static Exec.Result run(List<String> argv, long timeoutMs, String label) {
        return run(argv, null, timeoutMs, label, null);
    }

    private static Exec.Result run(List<String> argv, Path cwd, long timeoutMs, String label) {
        return run(argv, cwd, timeoutMs, label, null);
    }

    private static Exec.Result run(List<String> argv, Path cwd, long timeoutMs, String label,
                                   Map<String, String> env) {
        return Exec.run(argv, Exec.Options.of()
                .withCwd(cwd)
                .withEnv(env)
                .withTimeoutMs(timeoutMs)
                .withLabel(label));
    }

    // ── small helpers ──────────────────────────────────────────────────────────

    private static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isEmpty() ? fallback : v;
    }

    /// JS `String.prototype.slice(-n)`: the last n characters, or all of them.
    static String tail(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(s.length() - n);
    }

    /// JS `parseInt(s, 10)`: leading digits win and anything else is NaN — returned here as
    /// null, because a primitive would have to invent a number for "not a number".
    static Integer jsParseInt(String s) {
        if (s == null) return null;
        String t = s.trim();
        int i = 0;
        if (i < t.length() && (t.charAt(i) == '+' || t.charAt(i) == '-')) i++;
        int digits = i;
        while (digits < t.length() && Character.isDigit(t.charAt(digits))) digits++;
        if (digits == i) return null;
        try {
            return Integer.valueOf(t.substring(0, digits));
        } catch (NumberFormatException e) {
            return null;   // more digits than an int holds
        }
    }

    private static String join(List<Integer> xs, String sep) {
        return xs == null ? "" : String.join(sep, xs.stream().map(String::valueOf).toList());
    }

    @SafeVarargs
    private static List<String> concat(List<String>... parts) {
        List<String> out = new ArrayList<>();
        for (List<String> p : parts) if (p != null) out.addAll(p);
        return List.copyOf(out);
    }

    /// JS `lines.slice(from, to).join('\n')`, including its tolerance: an out-of-range or
    /// inverted window is an empty result, where `List#subList` would throw.
    private static String joinLines(String[] lines, int from, int to) {
        int a = Math.max(0, Math.min(from, lines.length));
        int b = Math.max(a, Math.min(to, lines.length));
        return String.join("\n", Arrays.asList(lines).subList(a, b));
    }

    /// node's `path.resolve(dir, rel)`: an absolute `rel` wins, and `..` is resolved before the
    /// containment check below ever sees it.
    private static Path resolveInRepo(Path dir, String rel) {
        return dir.resolve(rel == null ? "" : rel).normalize();
    }

    /// The JS `abs.startsWith(dir + path.sep)`, deliberately string-wise: `Path#startsWith`
    /// answers true for the repo directory ITSELF, and the repo root is not a file anything
    /// here may read, write or delete.
    private static boolean inside(Path dir, Path abs) {
        return abs.toString().startsWith(dir.toString() + FS);
    }

    private static void mkdirs(Path dir) {
        try {
            if (dir != null) Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// One directory's entries, or none when it cannot be read — the JS `try { readdirSync }
    /// catch { return }` around every walk below.
    private static List<Path> listDir(Path d) {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(d)) {
            for (Path p : s) out.add(p);
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    private static String relative(Path root, Path p) {
        return Objects.toString(root.relativize(p)).replace(FS, "/");
    }
}
