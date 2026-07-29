package tech.mikhailov.ijt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/// Getting PIT into somebody else's build, finding the report it leaves behind, and reading it.
///
/// This half of pit.js was the least-tested and the most superstitious: every rule in it exists
/// because a real repo failed in a way that looked like something else. A wrong
/// junit-platform-launcher makes PIT report "no tests" rather than a version conflict. A plugin
/// injected into a &lt;profile&gt; is silently ignored, so the run looks like PIT simply found
/// nothing to mutate. Reading another module's mutations.xml yields numbers that are entirely
/// plausible and describe a different class.
///
/// Nothing here is mocked. ensureMavenWiring edits a real pom on disk and the assertions read
/// that file back, because the defect being pinned is what ends up in the file.
///
/// Ported from sidecar/test/unit/pitwiring.test.js, pitscope.test.js, pitbudget.test.js,
/// pitgreensuite.test.js, pitindex.test.js, gradlepit.test.js and gradle.test.js, plus the three
/// killDifficulty cases that rounds.test.js kept next to the module that consumes them.
class PitTest {

    private static void assertMatches(String regex, String actual, String message) {
        assertTrue(Pattern.compile(regex, Pattern.DOTALL).matcher(actual).find(), message + "\n" + actual);
    }

    private static void assertNoMatch(String regex, String actual, String message) {
        assertFalse(Pattern.compile(regex, Pattern.DOTALL).matcher(actual).find(), message + "\n" + actual);
    }

    // ── junit-platform version matching ───────────────────────────────────────
    // Jupiter 5.x rides platform 1.x; JUnit 6 unified them. Get this wrong and PIT's bundled
    // launcher mismatches the project's engine and finds no tests at all — which reads as
    // "your class has no coverage", not as a dependency problem.

    @Test
    void jupiter5xMapsOntoTheMatchingPlatform1x() {
        assertEquals("1.11.3", Pit.platformVersionFor("5.11.3"));
        assertEquals("1.10", Pit.platformVersionFor("5.10"));
    }

    @Test
    void junit6UnifiedTheVersionsSoItMapsToItself() {
        assertEquals("6.1.0", Pit.platformVersionFor("6.1.0"));
        assertEquals("7.0.1", Pit.platformVersionFor("7.0.1"));
    }

    @Test
    void junit4AndUnparseableVersionsHaveNoPlatformAtAll() {
        // Returning a wrong-but-plausible version here would be worse than returning nothing:
        // pitPluginXml omits the launcher dependency entirely when this is null.
        assertNull(Pit.platformVersionFor("4.13.2"));
        assertNull(Pit.platformVersionFor("not-a-version"));
        assertNull(Pit.platformVersionFor(""));
        assertNull(Pit.platformVersionFor(null));
    }

    // ── the plugin block ──────────────────────────────────────────────────────

    @Test
    void junit5GetsBothTheEngineBridgeAndAPinnedPlatformLauncher() {
        String xml = Pit.pitPluginXml("junit5", "5.11.3");
        assertTrue(xml.contains("pitest-junit5-plugin"), xml);
        assertTrue(xml.contains("junit-platform-launcher"), xml);
        assertTrue(xml.contains("<version>1.11.3</version>"), xml);
    }

    @Test
    void junit5WithAnUnreadableVersionStillGetsTheBridgeButNoLauncher() {
        // A launcher pinned to a version we guessed is worse than none: PIT's own default at
        // least matches something.
        String xml = Pit.pitPluginXml("junit5", "wat");
        assertTrue(xml.contains("pitest-junit5-plugin"), xml);
        assertFalse(xml.contains("junit-platform-launcher"), xml);
    }

    @Test
    void testngGetsItsOwnBridgeAndNothingFromTheJunitFamily() {
        String xml = Pit.pitPluginXml("testng", null);
        assertTrue(xml.contains("pitest-testng-plugin"), xml);
        assertFalse(xml.contains("junit"), xml);
    }

    @Test
    void junit4NeedsNoBridgeSoNoDependenciesBlockIsEmittedAtAll() {
        // An empty <dependencies/> is not equivalent — it is a valid pom that says something
        // different, and a mutant that turns the guard into "always emit" must not survive.
        String xml = Pit.pitPluginXml("junit4", "4.13.2");
        assertFalse(xml.contains("<dependencies>"), xml);
        assertTrue(xml.contains("pitest-maven"), xml);
    }

    // ── injecting into a real pom ─────────────────────────────────────────────

    private static final String PLAIN = """
            <project>
              <artifactId>a</artifactId>
              <build>
                <plugins>
                  <plugin><artifactId>maven-surefire-plugin</artifactId></plugin>
                </plugins>
              </build>
            </project>
            """;

    private static Path fixture(Path dir, String pomXml) throws IOException {
        if (pomXml != null) Files.writeString(dir.resolve("pom.xml"), pomXml);
        return dir;
    }

    private static String pomOf(Path dir) throws IOException {
        return Files.readString(dir.resolve("pom.xml"));
    }

    /// The JS fixture's default runner: `{ testFramework: 'junit5', testFrameworkVersion: '5.11.3' }`.
    private static String wire(Path dir, String moduleRel) {
        return Pit.ensureMavenWiring(dir, moduleRel, "junit5", "5.11.3");
    }

    @Test
    void aModuleWithNoPomIsReportedNotGuessedAt(@TempDir Path dir) {
        assertEquals("no-pom", wire(dir, "."));
        assertFalse(Files.exists(dir.resolve("pom.xml")), "must not create one");
    }

    @Test
    void pitIsInjectedIntoTheExistingTopLevelPlugins(@TempDir Path dir) throws IOException {
        fixture(dir, PLAIN);
        assertEquals("injected", wire(dir, "."));
        String pom = pomOf(dir);
        assertTrue(pom.contains("pitest-maven"), pom);
        // and it landed inside build/plugins, not appended after </project>
        assertTrue(pom.indexOf("pitest-maven") < pom.indexOf("</plugins>"), pom);
    }

    @Test
    void aBuildWithoutPluginsGetsOneCreated(@TempDir Path dir) throws IOException {
        fixture(dir, "<project><build></build></project>\n");
        assertEquals("injected", wire(dir, "."));
        assertMatches("<plugins>.*pitest-maven.*</plugins>", pomOf(dir), "no plugins block");
    }

    @Test
    void aPomWithNoBuildAtAllGetsOneBeforeProjectEnd(@TempDir Path dir) throws IOException {
        fixture(dir, "<project>\n  <artifactId>a</artifactId>\n</project>\n");
        assertEquals("injected", wire(dir, "."));
        String pom = pomOf(dir);
        assertMatches("<build>.*pitest-maven.*</build>", pom, "no build block");
        assertMatches("</project>\\s*\\z", pom, "the document must still end with </project>");
    }

    @Test
    void aBuildThatLivesInsideProfilesIsNotWherePitGoes(@TempDir Path dir) throws IOException {
        // This is the whole reason buildIsTopLevel exists. A plugin declared inside a profile
        // that is not activated is silently ignored: the run completes, PIT never executes, and
        // the failure surfaces as "no mutations found" — which reads like a property of the
        // class under test rather than a wiring mistake.
        fixture(dir, """
                <project>
                  <profiles>
                    <profile>
                      <id>release</id>
                      <build><plugins><plugin><artifactId>other</artifactId></plugin></plugins></build>
                    </profile>
                  </profiles>
                </project>
                """);
        assertEquals("injected", wire(dir, "."));
        String pom = pomOf(dir);
        boolean inProfile = pom.indexOf("pitest-maven") > pom.indexOf("<profiles>")
                && pom.indexOf("pitest-maven") < pom.indexOf("</profiles>");
        assertFalse(inProfile, "PIT was injected into the profile:\n" + pom);
    }

    @Test
    void aProjectThatAlreadyWiresPitProperlyIsLeftAlone(@TempDir Path dir) throws IOException {
        fixture(dir, """
                <project><build><plugins>
                  <plugin><artifactId>pitest-maven</artifactId>
                    <dependencies><dependency><artifactId>pitest-junit5-plugin</artifactId></dependency></dependencies>
                  </plugin>
                </plugins></build></project>
                """);
        String before = pomOf(dir);
        assertEquals("present", wire(dir, "."));
        assertEquals(before, pomOf(dir), "the pom must be byte-identical — we defer to the project");
    }

    @Test
    void aProjectDeclaringPitWithoutTheEngineBridgeGetsJustTheBridge(@TempDir Path dir) throws IOException {
        fixture(dir, """
                <project><build><plugins>
                  <plugin><artifactId>pitest-maven</artifactId><version>1.20.0</version></plugin>
                </plugins></build></project>
                """);
        assertEquals("patched", wire(dir, "."));
        String pom = pomOf(dir);
        assertTrue(pom.contains("pitest-junit5-plugin"), pom);
        // their version is theirs; we add a dependency, we do not take the plugin over
        assertTrue(pom.contains("<version>1.20.0</version>"), pom);
    }

    @Test
    void aTestngProjectDeclaringPitGetsTheTestngBridgeNotTheJunit5One(@TempDir Path dir) throws IOException {
        fixture(dir, """
                <project><build><plugins>
                  <plugin><artifactId>pitest-maven</artifactId></plugin>
                </plugins></build></project>
                """);
        assertEquals("patched", Pit.ensureMavenWiring(dir, ".", "testng", null));
        assertTrue(pomOf(dir).contains("pitest-testng-plugin"), pomOf(dir));
    }

    @Test
    void wiringTargetsTheModulePomNotTheAggregatorAtTheRoot(@TempDir Path dir) throws IOException {
        fixture(dir, "<project><artifactId>root</artifactId></project>\n");
        Files.createDirectories(dir.resolve("mod"));
        Files.writeString(dir.resolve("mod/pom.xml"), PLAIN);
        assertEquals("injected", wire(dir, "mod"));
        assertFalse(pomOf(dir).contains("pitest-maven"), "the root pom must be untouched");
        assertTrue(Files.readString(dir.resolve("mod/pom.xml")).contains("pitest-maven"));
    }

    // ── where PIT is pointed ──────────────────────────────────────────────────
    // Every case here failed silently in production: not as an error, but as "this method has
    // no mutation surface", which the pipeline recorded as a finished unit.

    @Test
    void targetTestsCarriesALeadingWildcardBecauseTestsLiveInSiblingPackages() {
        // org.json.junit.XMLTest tests org.json.XML. A package-anchored glob matched none of
        // them and PIT reported the class as having no coverage.
        Pit.Scope s = Pit.pitScope("org.json.XML", null);
        assertEquals("*XML*Test*", s.targetTests());
        assertTrue(s.targetTests().startsWith("*"), "a leading wildcard is the entire point");
    }

    @Test
    void targetClassesCarriesATrailingWildcardSoInnerClassesComeAlong() {
        assertEquals("org.json.XML*", Pit.pitScope("org.json.XML", null).targetClasses());
    }

    @Test
    void aClassInTheDefaultPackageStillYieldsAUsableGlob() {
        Pit.Scope s = Pit.pitScope("Widget", null);
        assertEquals("*Widget*Test*", s.targetTests());
        assertEquals("Widget*", s.targetClasses());
    }

    @Test
    void aClassWideRunExcludesNothing() {
        Pit.Scope s = Pit.pitScope("a.B", null, List.of("x", "y"), List.of());
        assertEquals(List.of(), s.excluded(), "no target method means mutate the whole class");
    }

    @Test
    void targetingOneMethodExcludesItsSiblingsButNeverItself() {
        Pit.Scope s = Pit.pitScope("a.B", "load", List.of("load", "prime", "clear"), List.of());
        assertEquals(List.of("clear", "prime"), s.excluded().stream().sorted().toList());
    }

    @Test
    void aConstructorRecognisesItselfDespiteArrivingXmlEscaped() {
        // THE bug this function was extracted for. The sibling list spells constructors
        // `&lt;init&gt;`; onlyMethod is `<init>`. Without normalising both sides the constructor
        // failed to remove itself from its own exclusion list, so PIT was told to mutate <init>
        // AND to exclude <init> — leaving nothing to mutate, reported as zero mutants.
        Pit.Scope s = Pit.pitScope("a.B", "<init>", List.of("&lt;init&gt;", "load"), List.of());
        assertFalse(s.excluded().contains("<init>"), "the constructor excluded itself: " + s.excluded());
        assertEquals(List.of("load"), s.excluded());
    }

    @Test
    void siblingUnitsArePreferredOverAPreviousReport() {
        Pit.Scope s = Pit.pitScope("a.B", "load", List.of("load", "prime"), List.of("stale", "gone"));
        assertEquals(List.of("prime"), s.excluded(), "the live unit list wins");
    }

    @Test
    void aPreviousReportsMethodsAreTheFallbackNotAnError() {
        Pit.Scope s = Pit.pitScope("a.B", "load", List.of(), List.of("load", "prime"));
        assertEquals(List.of("prime"), s.excluded());
    }

    @Test
    void nothingKnownAboutTheClassExcludesNothing() {
        assertEquals(List.of(), Pit.pitScope("a.B", "load").excluded(),
                "better to mutate too much than to exclude everything");
    }

    @Test
    void aNameRepeatedAcrossSourcesIsExcludedOnce() {
        // overloads share a name; PIT takes names, not signatures, so a duplicate would be
        // passed twice in a comma-joined -DexcludedMethods
        Pit.Scope s = Pit.pitScope("a.B", "load", List.of("prime", "prime", "prime"), List.of());
        assertEquals(List.of("prime"), s.excluded());
    }

    // ── finding the report PIT left behind ────────────────────────────────────

    private static final String REPORT = "<mutations></mutations>";

    private static Path withReports(Path dir, String... rel) throws IOException {
        for (String r : rel) {
            Path p = dir.resolve(r);
            Files.createDirectories(p.getParent());
            Files.writeString(p, REPORT);
        }
        return dir;
    }

    @Test
    void theMavenLayoutIsFound(@TempDir Path dir) throws IOException {
        withReports(dir, "target/pit-reports/mutations.xml");
        assertEquals(dir.resolve("target/pit-reports/mutations.xml"), Pit.findReport(dir, "."));
    }

    @Test
    void theGradleLayoutIsFound(@TempDir Path dir) throws IOException {
        withReports(dir, "build/reports/pitest/mutations.xml");
        assertEquals(dir.resolve("build/reports/pitest/mutations.xml"), Pit.findReport(dir, "."));
    }

    @Test
    void aModulesOwnReportWinsOverOneAtTheRepoRoot(@TempDir Path dir) throws IOException {
        // Both exist after a multi-module run. Preferring the root report would score the wrong
        // class with numbers that look entirely reasonable.
        withReports(dir, "target/pit-reports/mutations.xml", "mod/target/pit-reports/mutations.xml");
        assertEquals(dir.resolve("mod/target/pit-reports/mutations.xml"), Pit.findReport(dir, "mod"));
    }

    @Test
    void anUnconventionalLocationIsFoundBySearchNewestFirst(@TempDir Path dir) throws IOException {
        withReports(dir, "deep/nested/out/mutations.xml", "other/place/mutations.xml");
        Path newer = dir.resolve("other/place/mutations.xml");
        Files.setLastModifiedTime(dir.resolve("deep/nested/out/mutations.xml"),
                FileTime.fromMillis(System.currentTimeMillis() - 60_000));
        assertEquals(newer, Pit.findReport(dir, "."));
    }

    @Test
    void noReportAnywhereIsNullNotAPathThatDoesNotExist(@TempDir Path dir) {
        assertNull(Pit.findReport(dir, "."));
    }

    @Test
    void everyReportIsCollectedForCleanupAndGitIsNeverWalked(@TempDir Path dir) throws IOException {
        // runPit deletes these before a run; a stale report left behind is read as this run's
        // result. Descending into .git would be slow and could match a blob by name.
        withReports(dir, "target/pit-reports/mutations.xml", "mod/build/reports/pitest/mutations.xml");
        Files.createDirectories(dir.resolve(".git"));
        Files.writeString(dir.resolve(".git/mutations.xml"), REPORT);
        List<Path> found = Pit.findAllReports(dir);
        assertEquals(2, found.size(), found.toString());
        assertTrue(found.stream().noneMatch(p -> p.toString().contains(".git")), found.toString());
    }

    @Test
    void anUnreadableDirectoryIsSkippedRatherThanCrashingTheWalk(@TempDir Path dir) throws IOException {
        withReports(dir, "a/mutations.xml");
        Path locked = dir.resolve("locked");
        Files.createDirectories(locked);
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            assertEquals(List.of(dir.resolve("a/mutations.xml")), Pit.findAllReports(dir));
        } finally {
            // or the temp dir cannot be cleaned up
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    // ── the Gradle command ────────────────────────────────────────────────────
    // Gradle decided PIT had already run, and the round measured nothing.
    //
    // Round 1 of ThreadLocalStatisticsCollector#incrementLoadErrorCount measured 66.67%. Round 2
    // produced NO report at all, and the diagnostic showed why: "5 actionable tasks: 3 executed".
    // Two of the five were up-to-date. From Gradle's point of view the two verify runs have
    // identical `pitest` inputs — same class, same target method, same excludedMethods — and
    // java-dataloader sets org.gradle.caching=true, so the second is skipped and no mutations.xml
    // appears. The sidecar deletes stale reports before each run precisely so a missing report
    // cannot read as zero, which turned the skip into UNMEASURED: correct, and a whole round
    // wasted. It hit round 2 of every multi-round unit on this repo.

    @Test
    void thePitestTaskIsForcedToRerunOrGradleSkipsItAsUpToDate() {
        List<String> argv = Pit.gradlePitArgv("./gradlew", List.of(), "pit-init.gradle", "pitest");
        assertTrue(argv.contains("--rerun"), "no --rerun in " + String.join(" ", argv));
        assertTrue(argv.indexOf("--rerun") > argv.indexOf("pitest"),
                "--rerun applies to the task that precedes it");
    }

    @Test
    void itStillCarriesTheScanArgsAndTheInitScript() {
        assertEquals(List.of("./gradlew", "--no-daemon", "--no-scan", "-I", "pit-init.gradle", ":core:pitest", "--rerun"),
                Pit.gradlePitArgv("./gradlew", List.of("--no-scan"), "pit-init.gradle", ":core:pitest"));
    }

    @Test
    void aModuleScopedTaskKeepsItsPath() {
        assertTrue(Pit.gradlePitArgv("./gradlew", List.of(), "i.gradle", ":a:b:pitest").contains(":a:b:pitest"));
    }

    @Test
    void aConstructorReachesExcludedMethodsAsInitNotAsAnXmlEntity() {
        // the live list read: excludedMethods=[&lt;init&gt;, resetThread, incrementLoadCount, ...]
        // PIT matches method names, and no method is called "&lt;init&gt;" — so the constructor
        // was never excluded and its mutants came back as strays in every scoped run
        assertEquals("<init>", Pit.escapeMethodForPit("&lt;init&gt;"));
        assertEquals("<init>", Pit.escapeMethodForPit("&#60;init&#62;"));
        assertEquals("<init>", Pit.escapeMethodForPit("<init>"));
        assertEquals("getStatistics", Pit.escapeMethodForPit("getStatistics"));
    }

    // ── the Gradle init script ────────────────────────────────────────────────

    @Test
    void thePitestPluginVersionFollowsTheProjectGradleOrNothingRuns() {
        // 1.15.0 reads reporting.baseDir, which Gradle 9 removed: applying it there fails with
        // "Could not get unknown property 'baseDir'" — which is why every Gradle repo produced
        // nothing at all.
        assertEquals("1.15.0", Pit.gradlePitestPluginVersion("7.6.4"));
        assertEquals("1.19.0", Pit.gradlePitestPluginVersion("8.10.2"));
        assertEquals("1.19.0", Pit.gradlePitestPluginVersion("9.0.0"));
    }

    @Test
    void anUnknownGradleGetsTheModernPluginRatherThanTheOneThatCannotLoad() {
        assertEquals("1.19.0", Pit.gradlePitestPluginVersion((String) null));
        assertEquals("1.19.0", Pit.gradlePitestPluginVersion(""));
        assertEquals("1.19.0", Pit.gradlePitestPluginVersion("not-a-version"));
    }

    /// the JS `const opts = { gradleVersion: '8.10.2', testFramework: 'junit5',
    /// pitVersion: '1.25.8', junit5PluginVersion: '1.2.3' }`
    private static Pit.InitOpts opts() {
        return Pit.InitOpts.of().withGradleVersion("8.10.2").withTestFramework("junit5")
                .withPitVersion("1.25.8").withJunit5PluginVersion("1.2.3");
    }

    @Test
    void theInitScriptScopesPitToOneClassAndExcludesTheOtherMethods() {
        String s = Pit.gradleInitScript("org.json.XML", "*XML*Test*", List.of("parse", "toString"), opts());
        assertTrue(s.contains("targetClasses = ['org.json.XML']"), s);
        assertTrue(s.contains("targetTests = ['*XML*Test*']"), s);
        assertTrue(s.contains("excludedMethods = ['parse', 'toString']"), s);
        assertTrue(s.contains("pitestVersion = '1.25.8'"), s);
        assertTrue(s.contains("gradle-pitest-plugin:1.19.0"), s);
    }

    @Test
    void theJunit5PluginIsDeclaredOnlyForAJunit5Project() {
        assertTrue(Pit.gradleInitScript("a.B", null, List.of(), opts()).contains("junit5PluginVersion = '1.2.3'"));
        assertFalse(Pit.gradleInitScript("a.B", null, List.of(), opts().withTestFramework("junit4"))
                .contains("junit5PluginVersion"));
    }

    @Test
    void aClassWithNothingToExcludeEmitsNoExcludedMethodsAtAll() {
        String s = Pit.gradleInitScript("a.B", null, List.of(), opts());
        assertFalse(s.contains("excludedMethods"), s);
        assertFalse(s.contains("targetTests"), s);
    }

    @Test
    void constructorsSurviveIntoTheExclusionList() {
        assertTrue(Pit.gradleInitScript("a.B", null, List.of("<init>", "run"), opts())
                .contains("excludedMethods = ['<init>', 'run']"));
    }

    @Test
    void theHttpNexusMirrorIsAllowedExplicitlyOrGradle7PlusRefusesIt() {
        String s = Pit.gradleInitScript("a.B", null, List.of(),
                opts().withMirrorUrl("http://nexus:8081/repository/maven-public/"));
        assertTrue(s.contains("url = uri('http://nexus:8081/repository/maven-public/')"), s);
        assertTrue(s.contains("allowInsecureProtocol = true"), s);
    }

    @Test
    void reportsAreXmlUntimestampedAndAnEmptyMethodDoesNotFailTheBuild() {
        String s = Pit.gradleInitScript("a.B", null, List.of(), opts());
        assertTrue(s.contains("outputFormats = ['XML']"), s);
        assertTrue(s.contains("timestampedReports = false"), s);
        assertTrue(s.contains("failWhenNoMutations = false"), s);
    }

    /// I added `timeoutConstMillis` for "parity with the Maven path" and never checked the plugin
    /// has such a property. It does not — it is `timeoutConstInMillis` — so applying the init
    /// script failed with "Could not set unknown property 'timeoutConstMillis' for extension
    /// 'pitest'", every Gradle PIT run in that deploy produced no report, and units were marked
    /// failed. A comment asserting parity is not parity.
    @Test
    void thePitestTimeoutUsesThePropertyGradlePitestPluginDeclares() {
        String s = Pit.gradleInitScript("com.example.A*", "*ATest*", List.of(),
                Pit.InitOpts.of().withGradleVersion("9.2"));
        assertMatches("timeoutConstInMillis\\s*=\\s*\\d+", s, "gradle-pitest-plugin spells it timeoutConstInMillis");
        assertNoMatch("timeoutConstMillis\\s*=", s, "timeoutConstMillis is not a property and fails the whole build");
    }

    // ── the unit budget reaches the subprocess ────────────────────────────────
    // One PIT invocation could outlive the whole unit budget four times over.
    //
    // DataLoader#getValueCache() sat 984 seconds inside a single `pitest` task, emitting
    // "PIT >> WARNING : Minion exited abnormally due to TIMED_OUT" — the class is
    // CompletableFuture-heavy, so mutants hang and PIT waits out each one, and its test glob
    // matches a large async suite that reruns per mutant. The run was configured with
    // unitBudgetSec 900, but that is only consulted by decide() AFTER a round returns, while the
    // subprocess itself was given timeoutMs 3600000. So the budget could not stop it: a single
    // unit is free to spend an hour of a run whose remaining ETA is already 170 hours.

    private static final long MIN = 120_000L;
    private static final long MAX = 3_600_000L;

    @Test
    void aFreshUnitGetsItsWholeBudget() {
        assertEquals(900_000L, Pit.pitTimeoutMs(900, 0L, null, 1000L));
    }

    @Test
    void aUnitThatHasAlreadySpentSomeOfItGetsTheRest() {
        assertEquals(600_000L, Pit.pitTimeoutMs(900, 300L, null, 1000L));
    }

    @Test
    void timeOnTheCurrentAttemptCountsToo() {
        // attemptStartedAt is a live stopwatch; ignoring it lets a unit restart its budget
        // every round
        assertEquals(700_000L, Pit.pitTimeoutMs(900, 100L, 900L, 1000L),
                "spent 100s + 100s on this attempt = 200s of 900s gone");
    }

    @Test
    void aNearlyExhaustedBudgetStillGetsAFloorNotADoomedRun() {
        // a 3-second timeout guarantees failure and teaches nothing; the round should get a
        // real chance and then be stopped by decide()
        assertEquals(MIN, Pit.pitTimeoutMs(900, 899L, null, 1000L));
        assertEquals(MIN, Pit.pitTimeoutMs(900, 5000L, null, 1000L));
    }

    @Test
    void noBudgetConfiguredMeansTheOldCeilingNotZero() {
        // unitBudgetSec 0 documents "uncapped" — reading it as a 0ms timeout would fail every
        // PIT run instantly, which is how `|| 5` once turned uncapped rounds into a cap of five
        assertEquals(MAX, Pit.pitTimeoutMs(0, 0L, null, 1000L));
        assertEquals(MAX, Pit.pitTimeoutMs(null, 0L, null, 1000L));
    }

    @Test
    void anAbsurdBudgetIsStillCappedAtTheHardCeiling() {
        assertEquals(MAX, Pit.pitTimeoutMs(99999, 0L, null, 1000L));
    }

    // ── PIT refused: a test of ours fails on unmutated code ───────────────────
    // PIT computes line coverage first, running every targetTests class against the original
    // bytecode, and aborts if any fails: "Mutation testing requires a green suite." Round 2 of
    // ThreadLocalStatisticsCollector#incrementLoadErrorCount sent two test classes to the
    // minion — round 1's MacMutTest and round 2's MacMutR2Test — and round 1's failed.
    //
    // Each passes alone, and `gradle test` is green, so nothing upstream noticed. They collide
    // only in PIT's single minion JVM, on a class whose whole purpose is thread-local state. So
    // the pipeline is shipping order-dependent tests, in PRs, and calling the result UNMEASURED.
    //
    // The message names the class and the method. The code threw that away and reported
    // "targetTests glob or compiled classes are wrong" — a guess, and the wrong one; it cost
    // three rounds of diagnosis.

    /// captured verbatim from /data/pit-unmeasured.log on the live run
    private static final String REAL = """
            8:53:33?AM PIT >> INFO : Sending 2 test classes to minion
            8:53:33?AM PIT >> INFO : Sent tests to minion
            8:53:33?AM PIT >> SEVERE : Description [testClass=org.dataloader.stats.ThreadLocalStatisticsCollectorIncrementLoadErrorCountMacMutTest, name=[engine:junit-jupiter]/[class:org.dataloader.stats.ThreadLocalStatisticsCollectorIncrementLoadErrorCountMacMutTest]/[method:incrementLoadErrorCount_delegates_to_overall_and_thread_local_collectors()]] did not pass without mutation.
            8:53:33?AM PIT >> SEVERE : Tests failing without mutation:
            Exception in thread "main" org.pitest.help.PitHelpError: 1 tests did not pass without mutation when calculating line coverage. Mutation testing requires a green suite.
            * FAILURE - org.gradle.internal.exceptions.LocationAwareException: Execution failed for task ':pitest'.""";

    @Test
    void theFailingTestClassAndMethodAreRecoveredFromPitOutput() {
        Pit.GreenSuiteFailure f = Pit.pitGreenSuiteFailure(REAL);
        assertNotNull(f, "this output plainly says which test failed");
        assertEquals("org.dataloader.stats.ThreadLocalStatisticsCollectorIncrementLoadErrorCountMacMutTest", f.testClass());
        assertEquals("incrementLoadErrorCount_delegates_to_overall_and_thread_local_collectors", f.method());
    }

    @Test
    void aGeneratedTestIsRecognisedAsOurs() {
        assertTrue(Pit.pitGreenSuiteFailure(REAL).ours(),
                "MacMutTest is a name this pipeline produces — we can delete it");
    }

    @Test
    void someoneElsesFailingTestIsNotOursToDelete() {
        // the project's own suite failing under PIT is a different problem and not one to
        // "fix" by deleting their test
        String out = REAL.replace("ThreadLocalStatisticsCollectorIncrementLoadErrorCountMacMutTest",
                "StatisticsCollectorTest");
        Pit.GreenSuiteFailure f = Pit.pitGreenSuiteFailure(out);
        assertEquals("org.dataloader.stats.StatisticsCollectorTest", f.testClass());
        assertFalse(f.ours());
    }

    @Test
    void outputWithoutThatFailureYieldsNothing() {
        assertNull(Pit.pitGreenSuiteFailure("BUILD SUCCESSFUL in 12s"));
        assertNull(Pit.pitGreenSuiteFailure(""));
        assertNull(Pit.pitGreenSuiteFailure(null));
    }

    @Test
    void theRound2ClassIsRecognisedAsOursToo() {
        assertTrue(Pit.pitGreenSuiteFailure(REAL.replace("MacMutTest", "MacMutR2Test")).ours());
    }

    @Test
    void aFailureThatNamesTheClassButNotTheMethodStillIdentifiesTheClass() {
        // Not every PIT/engine combination prints the [method:...] part — TestNG and older JUnit
        // platforms report the description differently. Returning null there would throw away
        // the one fact we did get and send the run back to "UNMEASURED, cause unknown", which is
        // the exact dead end this module was written to end.
        String out = """
                PIT >> SEVERE : Description [testClass=org.dataloader.FooMacMutTest] did not pass without mutation.
                Exception in thread "main" org.pitest.help.PitHelpError: Mutation testing requires a green suite.""";
        Pit.GreenSuiteFailure f = Pit.pitGreenSuiteFailure(out);
        assertNotNull(f, "the class name is right there in the output");
        assertEquals("org.dataloader.FooMacMutTest", f.testClass());
        assertNull(f.method(), "no method was reported — say so rather than invent one");
        assertTrue(f.ours(), "still ours, so still safe to cut");
    }

    @Test
    void aClassOnlyFailureThatIsNotOursIsStillNotOurs() {
        Pit.GreenSuiteFailure f = Pit.pitGreenSuiteFailure(
                "PIT >> SEVERE : Description [testClass=org.dataloader.TheirOwnTest] did not pass without mutation.");
        assertEquals("org.dataloader.TheirOwnTest", f.testClass());
        assertFalse(f.ours());
    }

    @Test
    void theFailureBannerWithNoTestClassAtAllYieldsNothing() {
        // Better to report nothing than to hand the caller a record with an empty class name,
        // which the salvage path would then try to match against test sources.
        assertNull(Pit.pitGreenSuiteFailure("did not pass without mutation"));
    }

    // ── a salvage that is never re-measured is a salvage wasted ───────────────
    // The clean v2 run: PIT refused because one generated test fails on unmutated code, the
    // recovery cut that method and kept the other, and then the round reported UNMEASURED
    // anyway — the cut was applied and immediately thrown away. Having removed the thing that
    // blocked PIT, the run has to ask PIT again.

    @Test
    void aCutEarnsOneMoreMeasurement() {
        assertEquals(1, Pit.greenSuiteRetries(0, true), "first cut: try again");
    }

    @Test
    void butTheRetriesRunOut() {
        assertEquals(1, Pit.greenSuiteRetries(1, true), "second cut: still worth one more");
        assertEquals(0, Pit.greenSuiteRetries(2, true), "third: stop, this class of test is the problem");
    }

    @Test
    void aFailureWeCouldNotCutEarnsNothing() {
        // the offending test belongs to the project, or nothing was salvageable — re-running PIT
        // would fail identically
        assertEquals(0, Pit.greenSuiteRetries(0, false));
        assertEquals(0, Pit.greenSuiteRetries(1, false));
    }

    // ── narrowing a class-wide report to one method ───────────────────────────

    private static final String JSON_FILE = "src/main/java/org/json/JSONObject.java";
    private static final String JSON_FQCN = "org.json.JSONObject";

    private static Pit.Mutant alive(String mutator, int line, String method, String status, int difficulty) {
        return new Pit.Mutant(status, false, line, mutator, method, null, null, null, difficulty);
    }

    private static Pit.Mutant any(String mutator, int line, String method, boolean detected) {
        return new Pit.Mutant(detected ? "KILLED" : "SURVIVED", detected, line, mutator, method,
                null, null, null, null);
    }

    /// the shape parseReport returns. `totalMutants`/`killed`/`score` are the JS fixture's
    /// literals and stay stale when a case appends to `all` — scopeToMethod recomputes all three
    /// from `all`, which is the point.
    private static Pit.PitResult report(List<Pit.Mutant> all, List<Pit.Mutant> survived) {
        Map<String, Pit.MethodStat> byMethod = new LinkedHashMap<>();
        byMethod.put("toString", new Pit.MethodStat(1, 0, 1));
        byMethod.put("isRecordStyleAccessor", new Pit.MethodStat(3, 1, 2));
        return new Pit.PitResult(JSON_FILE, JSON_FQCN, 4, 1, 25.0, survived, byMethod, all,
                null, null, null, null, null, null, null);
    }

    private static List<Pit.Mutant> defaultSurvived() {
        return new ArrayList<>(List.of(
                alive("MathMutator", 3005, "toString", "SURVIVED", 0),
                alive("BooleanTrueReturnValsMutator", 2072, "isRecordStyleAccessor", "NO_COVERAGE", 3),
                alive("RemoveConditionalMutator", 2071, "isRecordStyleAccessor", "NO_COVERAGE", 5)));
    }

    private static List<Pit.Mutant> defaultAll() {
        return new ArrayList<>(List.of(
                any("MathMutator", 3005, "toString", false),
                any("BooleanTrueReturnValsMutator", 2072, "isRecordStyleAccessor", false),
                any("RemoveConditionalMutator", 2071, "isRecordStyleAccessor", false),
                any("NegateConditionalsMutator", 2077, "isRecordStyleAccessor", true)));
    }

    private static Pit.PitResult report() {
        return report(defaultAll(), defaultSurvived());
    }

    @Test
    void survivorsFromOtherMethodsNeverReachTheUnit() {
        // A MathMutator in toString() led this unit's queue because it was SURVIVED while the
        // unit's own mutants were NO_COVERAGE. A whole round was spent writing a toString test,
        // then scored against isRecordStyleAccessor — a guaranteed miss.
        Pit.PitResult r = Pit.scopeToMethod(report(), "isRecordStyleAccessor");
        assertEquals(List.of("isRecordStyleAccessor", "isRecordStyleAccessor"),
                r.survived().stream().map(Pit.Mutant::method).toList());
    }

    @Test
    void theScoreIsRecomputedOverTheMethodAlone() {
        Pit.PitResult r = Pit.scopeToMethod(report(), "isRecordStyleAccessor");
        assertEquals(3, r.totalMutants());
        assertEquals(1, r.killed());
        assertEquals(33.33, r.score().doubleValue(), "1 of 3, not 1 of 4 — the unit is the method");
    }

    @Test
    void theFullMethodListSurvivesScopingExclusionsAreBuiltFromIt() {
        Pit.PitResult r = Pit.scopeToMethod(report(), "isRecordStyleAccessor");
        assertEquals(List.of("isRecordStyleAccessor", "toString"), r.byMethod().keySet().stream().sorted().toList());
    }

    @Test
    void overloadsShareANameAndStayInTheSameUnit() {
        // toString() as well as toString(int): one killed, one surviving. `survived` is always a
        // subset of `all`, so a new survivor goes into both.
        List<Pit.Mutant> all = defaultAll();
        List<Pit.Mutant> survived = defaultSurvived();
        all.add(any("MathMutator", 2960, "toString", true));
        Pit.Mutant extra = alive("VoidMethodCallMutator", 2971, "toString", "NO_COVERAGE", 4);
        all.add(extra);
        survived.add(extra);
        Pit.PitResult r = Pit.scopeToMethod(report(all, survived), "toString");
        assertEquals(3, r.totalMutants(), "both toString(int) and toString() count");
        assertEquals(1, r.killed());
        assertEquals(2, r.survived().size());
    }

    @Test
    void aClassScopedRunIsLeftExactlyAsItIs() {
        Pit.PitResult rep = report();
        assertSame(rep, Pit.scopeToMethod(rep, null));
        // the JS passes `undefined` here as well; in Java the falsy spellings of "no method"
        // are null and the empty string, and both must mean the whole class
        assertSame(rep, Pit.scopeToMethod(rep, ""));
    }

    @Test
    void aMethodPitFoundNothingForScores0MutantsNot100Percent() {
        Pit.PitResult r = Pit.scopeToMethod(report(), "noSuchMethod");
        assertEquals(0, r.totalMutants());
        assertEquals(0, r.survived().size());
        assertEquals(0.0, r.score().doubleValue(), "an unmutated method is not a perfect one");
        assertEquals(Boolean.TRUE, r.empty(), "and the caller must be able to tell");
    }

    @Test
    void constructorsAreAddressableLikeAnyOtherMethod() {
        List<Pit.Mutant> all = defaultAll();
        List<Pit.Mutant> survived = defaultSurvived();
        all.add(any("MathMutator", 100, "<init>", false));
        survived.add(alive("MathMutator", 100, "<init>", "SURVIVED", 0));
        Pit.PitResult r = Pit.scopeToMethod(report(all, survived), "<init>");
        assertEquals(1, r.totalMutants());
        assertEquals(1, r.survived().size());
    }

    // ── parsing a real PIT report ─────────────────────────────────────────────

    private static Path writeReport(Path dir, String mutations) throws IOException {
        Path file = dir.resolve("mutations.xml");
        Files.writeString(file, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mutations>\n" + mutations + "\n</mutations>\n");
        return file;
    }

    private static String mutation(String cls, String method, int line, boolean detected, String status, String mutator) {
        return "<mutation detected='" + detected + "' status='" + status + "'>"
                + "<sourceFile>B.java</sourceFile><mutatedClass>" + cls + "</mutatedClass>"
                + "<mutatedMethod>" + method + "</mutatedMethod>"
                + "<lineNumber>" + line + "</lineNumber>"
                + "<mutator>org.pitest.mutationtest.engine.gregor.mutators." + mutator + "</mutator>"
                + "<description>mutated</description></mutation>";
    }

    /// the JS defaults: cls 'a.B', method 'run', detected false, status SURVIVED, MathMutator
    private static String mutation(String method, int line) {
        return mutation("a.B", method, line, false, "SURVIVED", "MathMutator");
    }

    @Test
    void aConstructorArrivesXmlEscapedFromPitAndMustStillBeItsOwnUnit(@TempDir Path dir) throws IOException {
        // JaCoCo names give us "<init>"; PIT's report writer escapes it. Comparing the two
        // matches nothing, so scopeToMethod returned 0 mutants and the unit was written off as
        // having no mutation surface — for a constructor PIT had actually mutated.
        Path file = writeReport(dir, String.join("\n",
                mutation("&lt;init&gt;", 5),
                mutation("a.B", "&lt;init&gt;", 6, true, "KILLED", "MathMutator"),
                mutation("run", 10)));
        Pit.PitResult parsed = Pit.parseReport(file, "src/main/java/a/B.java", "a.B");
        assertEquals(List.of("<init>", "run"),
                parsed.all().stream().map(Pit.Mutant::method).distinct().sorted().toList());
        Pit.PitResult scoped = Pit.scopeToMethod(parsed, "<init>");
        assertEquals(2, scoped.totalMutants());
        assertEquals(1, scoped.killed());
        assertEquals(Boolean.FALSE, scoped.empty());
    }

    @Test
    void theNumericEscapingOlderPitEmitsDecodesToo(@TempDir Path dir) throws IOException {
        Path file = writeReport(dir, mutation("&#60;init&#62;", 5));
        assertEquals("<init>", Pit.parseReport(file, "src/main/java/a/B.java", "a.B").all().get(0).method());
    }

    @Test
    void anotherClassWhoseNameMerelyStartsWithOursIsNotScoredIntoTheUnit(@TempDir Path dir) throws IOException {
        // the filter was mutatedClass.startsWith(fqcn), so a.BFoo counted as a.B — and with a
        // method filter by NAME only, a.BFoo#run was scored into a.B#run's result
        Path file = writeReport(dir, String.join("\n",
                mutation("a.B", "run", 10, false, "SURVIVED", "MathMutator"),
                mutation("a.BFoo", "run", 99, true, "KILLED", "MathMutator")));
        Pit.PitResult parsed = Pit.parseReport(file, "src/main/java/a/B.java", "a.B");
        assertEquals(List.of(10), parsed.all().stream().map(Pit.Mutant::line).toList(), "a.BFoo is a different class");
        assertEquals(0, Pit.scopeToMethod(parsed, "run").killed(), "its kill must not be credited here");
    }

    @Test
    void anInnerOrAnonymousClassOfTheTargetStillBelongsToIt(@TempDir Path dir) throws IOException {
        Path file = writeReport(dir, String.join("\n",
                mutation("a.B", "run", 10, false, "SURVIVED", "MathMutator"),
                mutation("a.B$Inner", "run", 20, false, "SURVIVED", "MathMutator"),
                mutation("a.B$1", "run", 30, false, "SURVIVED", "MathMutator")));
        assertEquals(3, Pit.parseReport(file, "src/main/java/a/B.java", "a.B").all().size());
    }

    // ── PIT's mutant index, parsed from the shape PIT actually writes ─────────
    // Captured verbatim from /data/repos/.../build/reports/pitest/mutations.xml on the box: the
    // index is NOT a bare <index> element, it is nested inside <indexes>, and <block> inside
    // <blocks>. A fixture written from memory would have used the flat spelling, passed, and left
    // the parser returning null for every mutant — the same mistake as fixtures whose braces
    // balanced when the file that broke the parser did not.

    private static String indexed(String status, int line, String mutator, int index, int block) {
        boolean detected = !status.equals("SURVIVED") && !status.equals("NO_COVERAGE");
        return "<mutation detected='" + detected + "' status='" + status + "' numberOfTestsRun='0'>"
                + "<sourceFile>JSONObject.java</sourceFile>"
                + "<mutatedClass>org.json.JSONObject</mutatedClass>"
                + "<mutatedMethod>parseJSONObject</mutatedMethod>"
                + "<methodDescription>()V</methodDescription>"
                + "<lineNumber>" + line + "</lineNumber>"
                + "<mutator>org.pitest.mutationtest.engine.gregor.mutators." + mutator + "</mutator>"
                + "<indexes><index>" + index + "</index></indexes>"
                + "<blocks><block>" + block + "</block></blocks>"
                + "<killingTest/><description>removed conditional</description></mutation>";
    }

    /// the real case: three mutants, one kind, one line
    private static Path indexReport(Path dir) throws IOException {
        Path p = dir.resolve("mutations.xml");
        Files.writeString(p, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mutations partial=\"true\">\n"
                + indexed("SURVIVED", 248, "RemoveConditionalMutator_EQUAL_ELSE", 11, 0) + "\n"
                + indexed("SURVIVED", 248, "RemoveConditionalMutator_EQUAL_ELSE", 14, 0) + "\n"
                + indexed("NO_COVERAGE", 248, "RemoveConditionalMutator_EQUAL_ELSE", 17, 1) + "\n"
                + indexed("KILLED", 250, "MathMutator", 22, 2) + "\n"
                + "</mutations>\n");
        return p;
    }

    @Test
    void theIndexIsReadFromInsideIndexesWherePitPutsIt(@TempDir Path dir) throws IOException {
        Pit.PitResult r = Pit.parseReport(indexReport(dir), JSON_FILE, JSON_FQCN);
        List<Pit.Mutant> aliveAt248 = r.survived().stream().filter(m -> m.line() == 248).toList();
        assertEquals(3, aliveAt248.size());
        assertEquals(List.of(11, 14, 17), aliveAt248.stream().map(Pit.Mutant::index).sorted().toList(),
                "a null here means the parser is looking for a flat <index> that PIT never writes");
    }

    @Test
    void soTheThreeOfThemAreThreeAttemptKeys(@TempDir Path dir) throws IOException {
        Pit.PitResult r = Pit.parseReport(indexReport(dir), JSON_FILE, JSON_FQCN);
        Set<String> keys = new LinkedHashSet<>(r.survived().stream().filter(m -> m.line() == 248)
                .map(m -> Select.attemptKey(new Select.Mutant(m.mutator(), m.line(), m.index(), m.difficulty())))
                .toList());
        assertEquals(3, keys.size(), "one key for all three is what settled parseJSONObject at 78.57%");
    }

    @Test
    void theBlockIsStillReadFromInsideBlocks(@TempDir Path dir) throws IOException {
        Pit.PitResult r = Pit.parseReport(indexReport(dir), JSON_FILE, JSON_FQCN);
        assertEquals("1", r.survived().stream().filter(m -> m.index() == 17).findFirst().orElseThrow().block());
    }

    // ── mutant selection comes from PIT's data, not from anyone's judgement ───
    // These three move here from rounds.test.js: the ranking is pit.js's to assert.

    @Test
    void valueReturningAndArithmeticMutantsRankAheadOfConditionalRemoval() {
        // Empirical, from JSON-java: four consecutive rounds targeted
        // RemoveConditionalMutator_EQUAL_ELSE on the model's confident reasoning and every one
        // survived. A changed return value is settled by one assertion; a removed equality guard
        // often leaves behaviour nothing short can distinguish.
        int easy = Pit.killDifficulty(Pit.Mutant.kind("ReturnValsMutator", "SURVIVED"));
        int math = Pit.killDifficulty(Pit.Mutant.kind("MathMutator", "SURVIVED"));
        int hard = Pit.killDifficulty(Pit.Mutant.kind("RemoveConditionalMutator_EQUAL_ELSE", "SURVIVED"));
        assertTrue(easy < hard && math < hard);
    }

    @Test
    void aCoveredMutantAlwaysRanksAheadOfAnUncoveredOneOfTheSameKind() {
        assertTrue(Pit.killDifficulty(Pit.Mutant.kind("MathMutator", "SURVIVED"))
                        < Pit.killDifficulty(Pit.Mutant.kind("MathMutator", "NO_COVERAGE")),
                "SURVIVED needs only an assertion; NO_COVERAGE needs the path reached");
    }

    @Test
    void evenAnEasyMutatorRanksBehindACoveredHardOneWhenUncovered() {
        assertTrue(Pit.killDifficulty(Pit.Mutant.kind("RemoveConditionalMutator", "SURVIVED"))
                < Pit.killDifficulty(Pit.Mutant.kind("ReturnValsMutator", "NO_COVERAGE")));
    }

    // ── the survivor queue's order, which the ranking above only half decides ──

    @Test
    void survivorsAreOrderedByDifficultyThenByLine(@TempDir Path dir) throws IOException {
        // The JS comparator is `a.difficulty - b.difficulty || (a.line||0) - (b.line||0)`. Ported
        // as a subtraction it would be a correct-looking Java comparator that overflows on
        // extreme values; ported without the line tie-break the queue's order would depend on
        // report order, which PIT does not promise.
        Path file = writeReport(dir, String.join("\n",
                mutation("a.B", "run", 90, false, "NO_COVERAGE", "MathMutator"),
                mutation("a.B", "run", 30, false, "SURVIVED", "RemoveConditionalMutator"),
                mutation("a.B", "run", 20, false, "SURVIVED", "MathMutator"),
                mutation("a.B", "run", 10, false, "SURVIVED", "VoidMethodCallMutator")));
        List<Integer> lines = Pit.parseReport(file, "src/main/java/a/B.java", "a.B")
                .survived().stream().map(Pit.Mutant::line).toList();
        assertEquals(List.of(20, 10, 30, 90), lines,
                "easy+SURVIVED first, then the two hard SURVIVED by line, then anything NO_COVERAGE");
    }

    // ── what PIT said about how much it ran ───────────────────────────────────

    @Test
    void theTestCountPitPrintsIsReadOrNullWhenItSaidNothing() {
        // an unchanged score means one thing when our new test ran and another when it did not,
        // and "0" is the same statement as silence: no test of ours was executed
        assertEquals(1163, Pit.testsRunIn("PIT >> INFO : Ran 1163 tests (2.4 tests per mutation)"));
        assertEquals(1, Pit.testsRunIn("Ran 1 test"));
        assertNull(Pit.testsRunIn("Ran 0 tests"));
        assertNull(Pit.testsRunIn("BUILD SUCCESSFUL"));
    }

    @Test
    void theDiagnosticQuotesTheLineThatMatchedNotTheTailOfTheLog() {
        // Two rounds of diagnosis were spent reading a Gradle epilogue — deprecation notices and
        // a task count — and concluding things about task caching that the epilogue could not
        // support. The reason lives at the line that matched.
        String out = String.join("\n", "a", "b", "c", "d", "e", "PIT >> No mutations found", "f", "g", "h", "i", "j");
        String around = Pit.context(out);
        assertTrue(around.contains("PIT >> No mutations found"), around);
        assertEquals("b | c | d | e | PIT >> No mutations found | f | g | h", around,
                "four lines either side, joined — not the tail");
        assertEquals("(no matching line)", Pit.context("BUILD SUCCESSFUL in 12s"));
    }
}
