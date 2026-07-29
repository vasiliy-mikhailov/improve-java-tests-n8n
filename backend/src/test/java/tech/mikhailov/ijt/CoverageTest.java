package tech.mikhailov.ijt;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/// Reading a JaCoCo report, and deciding what it says about the units we track.
class CoverageTest {

    // ── report parsing (ported from coverage.test.js) ─────────────────────────────────

    /// Shape of a real JaCoCo report: `<class>` holds a `<method>` per method, each with its OWN
    /// counters, and the class totals come LAST. Here the first method is uncovered (0/3) and
    /// the class as a whole is 12/15 — reading the first counter reports 0 %.
    private static final String XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <report name="demo">
            <package name="org/apache/commons/cli">
              <class name="org/apache/commons/cli/MissingOptionException" sourcefilename="MissingOptionException.java">
                <method name="&lt;init&gt;" desc="(Ljava/util/List;)V" line="41">
                  <counter type="INSTRUCTION" missed="7" covered="0"/>
                  <counter type="LINE" missed="3" covered="0"/>
                </method>
                <method name="createMessage" desc="(Ljava/util/List;)Ljava/lang/String;" line="55">
                  <counter type="INSTRUCTION" missed="0" covered="30"/>
                  <counter type="LINE" missed="0" covered="12"/>
                </method>
                <counter type="INSTRUCTION" missed="7" covered="30"/>
                <counter type="LINE" missed="3" covered="12"/>
              </class>
              <sourcefile name="MissingOptionException.java">
                <line nr="41" mi="2" ci="0" mb="0" cb="0"/>
                <line nr="42" mi="1" ci="0" mb="0" cb="0"/>
                <line nr="55" mi="0" ci="4" mb="0" cb="0"/>
                <counter type="LINE" missed="3" covered="12"/>
              </sourcefile>
            </package>
            <counter type="LINE" missed="3" covered="12"/>
            </report>""";

    private static Coverage.Report merged(String xml) {
        Coverage.Report acc = new Coverage.Report();
        Coverage.mergeReport(acc, xml);
        return acc;
    }

    @Test
    void classCoverageComesFromTheClassTotalNotItsFirstMethod() {
        Coverage.ClassCov c = merged(XML).classes.get("org.apache.commons.cli.MissingOptionException");
        assertNotNull(c, "class must be keyed by fully-qualified name");
        assertEquals(12, c.covered);
        assertEquals(3, c.missed);
        double pct = (c.covered * 100.0) / (c.covered + c.missed);
        assertEquals(80, Math.round(pct), "not 0 % — that was the first method");
    }

    @Test
    void lineCounterTakesTheLastLineCounterInTheElement() {
        String frag = "<counter type=\"LINE\" missed=\"3\" covered=\"0\"/>"
                + "<counter type=\"LINE\" missed=\"1\" covered=\"9\"/>";
        assertEquals(new Coverage.LineCount(1, 9), Coverage.lineCounter(frag));
    }

    @Test
    void lineCounterReturnsNullWhenTheElementHasNoLineCounter() {
        // null and not a zeroed counter: "no counter" and "nothing covered" are different
        // answers, and only one of them may be divided by
        assertNull(Coverage.lineCounter("<counter type=\"BRANCH\" missed=\"1\" covered=\"2\"/>"));
    }

    @Test
    void perLineHitsAreCollectedForTheUncoveredLinesPrompt() {
        Map<Integer, Coverage.LineHit> lines =
                merged(XML).classes.get("org.apache.commons.cli.MissingOptionException").lines;
        assertEquals(new Coverage.LineHit(2, 0), lines.get(41));
        assertEquals(new Coverage.LineHit(0, 4), lines.get(55));
    }

    @Test
    void aSelfClosingClassDoesNotSwallowTheClassesAfterIt() {
        // JaCoCo emits self-closing tags for synthetic classes with no methods. A paired
        // <class>…</class> regex reads such a tag as an opening tag and consumes the NEXT
        // class's body, so everything after it reported 0 % coverage.
        String xml = """
                <report name="d"><package name="a/b">
                  <class name="a/b/Outer$1" sourcefilename="Outer.java"/>
                  <class name="a/b/Victim" sourcefilename="Victim.java">
                    <method name="m" desc="()V" line="9"><counter type="LINE" missed="0" covered="7"/></method>
                    <counter type="LINE" missed="0" covered="7"/>
                  </class>
                </package></report>""";
        Coverage.Report acc = merged(xml);
        assertNotNull(acc.classes.get("a.b.Victim"), "the class after a self-closing sibling must still be seen");
        assertEquals(7, acc.classes.get("a.b.Victim").covered);
        Coverage.ClassCov outer = acc.classes.get("a.b.Outer");
        assertEquals(0, outer == null ? 0 : outer.covered, "the synthetic class contributes nothing");
    }

    @Test
    void innerClassesFoldIntoTheirOuterClass() {
        String xml = """
                <report name="d"><package name="a/b">
                  <class name="a/b/Outer" sourcefilename="Outer.java"><counter type="LINE" missed="1" covered="4"/></class>
                  <class name="a/b/Outer$Inner" sourcefilename="Outer.java"><counter type="LINE" missed="0" covered="6"/></class>
                </package></report>""";
        Coverage.Report acc = merged(xml);
        assertEquals(10, acc.classes.get("a.b.Outer").covered);
        assertEquals(1, acc.classes.get("a.b.Outer").missed);
    }

    @Test
    void methodCountersCarryTheDeclaredLineWithTheEntitiesDecoded() {
        // `&lt;init&gt;` read raw produced the literal string "&lt;init&gt;" as a method name,
        // which then flowed into unit keys, PIT's excludedMethods (where it can never match)
        // and the dashboard.
        Map<String, Coverage.MethodCov> methods =
                merged(XML).classes.get("org.apache.commons.cli.MissingOptionException").methods;
        assertTrue(methods.containsKey("<init>"), "the constructor is keyed as <init>, not as &lt;init&gt;");
        assertEquals(41, methods.get("<init>").line);
        assertEquals(0, methods.get("<init>").covered);
        assertEquals(3, methods.get("<init>").missed);
        assertEquals(12, methods.get("createMessage").covered);
        assertEquals(55, methods.get("createMessage").line);
    }

    @Test
    void reportTotalsComeFromTheLastLineCounterInTheDocument() {
        Coverage.Report acc = merged(XML);
        assertEquals(3, acc.missed);
        assertEquals(12, acc.covered);
        assertEquals(80.0, Coverage.totalPct(acc));
    }

    @Test
    void nothingExecutableIsZeroPercentAndNotADivisionByZero() {
        assertEquals(0.0, Coverage.totalPct(new Coverage.Report()));
    }

    @Test
    void unescDecodesOnceSoAnEscapedEntityStaysEscaped() {
        assertEquals("<init>", Coverage.unesc("&lt;init&gt;"));
        assertEquals("a\"b'c&d", Coverage.unesc("a&quot;b&apos;c&amp;d"));
        // `&amp;` is decoded LAST, so this yields `&lt;` rather than `<`
        assertEquals("&lt;", Coverage.unesc("&amp;lt;"));
    }

    // ── artefacts that must not survive a run (ported from coverage-artifacts.test.js) ─

    @Test
    void theExecutionDataFileIsClearedBeforeARunOrCoverageAccumulates() {
        // JaCoCo's Maven plugin APPENDS to jacoco.exec, and target/ is gitignored so
        // `git clean -fd` leaves it. On JSON-java the file reached 10 MB, and JSONArray#getNumber
        // read 0% covered in one run and 100% in the next — the 100% came from a generated test
        // that had already been deleted. Every number derived from it was wrong.
        List<String> paths = Coverage.staleCoverageArtifacts(List.of("."));
        assertTrue(paths.contains("target/jacoco.exec"), "Maven exec data");
        assertTrue(paths.contains("build/jacoco/test.exec"), "Gradle exec data");
    }

    @Test
    void theXmlReportIsClearedTooAStaleOneReadsExactlyLikeAFreshOne() {
        List<String> paths = Coverage.staleCoverageArtifacts(List.of("."));
        assertTrue(paths.contains("target/site/jacoco/jacoco.xml"));
        assertTrue(paths.stream().anyMatch(p -> p.matches("build/reports/jacoco/.*\\.xml")));
    }

    @Test
    void everyModuleIsClearedNotJustTheRoot() {
        List<String> paths = Coverage.staleCoverageArtifacts(List.of(".", "core", "json-path"));
        assertTrue(paths.contains("core/target/jacoco.exec"));
        assertTrue(paths.contains("json-path/target/jacoco.exec"));
        assertTrue(paths.contains("target/jacoco.exec"), "the root module keeps unprefixed paths");
    }

    @Test
    void nothingOutsideTheRepoIsEverNamedForDeletion() {
        Pattern escapes = Pattern.compile("(^|/)\\.\\.(/|$)");
        for (String p : Coverage.staleCoverageArtifacts(List.of(".", "../evil", "/etc"))) {
            assertFalse(escapes.matcher(p).find(), p + " escapes the repo");
            assertFalse(p.startsWith("/"), p + " is absolute");
        }
    }

    @Test
    void aModuleListWithDuplicatesYieldsEachPathOnce() {
        List<String> paths = Coverage.staleCoverageArtifacts(List.of(".", ".", "core", "core"));
        assertEquals(Set.copyOf(paths).size(), paths.size());
    }

    @Test
    void noModulesMeansTheRootModule() {
        assertTrue(Coverage.staleCoverageArtifacts(List.of()).contains("target/jacoco.exec"));
        assertTrue(Coverage.staleCoverageArtifacts().contains("target/jacoco.exec"));
        assertTrue(Coverage.staleCoverageArtifacts(null).contains("target/jacoco.exec"));
    }

    @Test
    void aLeadingDotSlashIsStrippedSoOneModuleIsNotClearedTwice() {
        assertTrue(Coverage.staleCoverageArtifacts(List.of("./core")).contains("core/target/jacoco.exec"));
    }

    @Test
    void aPathThatSomehowArrivesAbsoluteStillLandsInsideTheRepo() {
        // Node's path.join drops a leading separator on its second argument; Path.resolve
        // replaces the base with it instead. This call site DELETES files, so the difference
        // is the whole repo directory.
        Path repo = Path.of("/data/repos/x");
        assertEquals(Path.of("/data/repos/x/target/jacoco.exec"),
                Coverage.joinUnder(repo, "/target/jacoco.exec"));
        assertEquals(Path.of("/data/repos/x/target/jacoco.exec"),
                Coverage.joinUnder(repo, "target/jacoco.exec"));
    }

    // ── finding and clearing reports on disk ──────────────────────────────────────────

    @Test
    void everyJacocoXmlUnderTheRepoIsFoundOnePerModule(@TempDir Path dir) throws IOException {
        write(dir.resolve("target/site/jacoco/jacoco.xml"), "<report/>");
        write(dir.resolve("core/build/reports/jacoco/test/jacocoTestReport.xml"), "<report/>");
        write(dir.resolve("target/site/jacoco/index.html"), "<html/>");
        Set<String> found = Coverage.findReports(dir).stream()
                .map(p -> dir.relativize(p).toString()).collect(Collectors.toSet());
        assertEquals(Set.of("target/site/jacoco/jacoco.xml",
                "core/build/reports/jacoco/test/jacocoTestReport.xml"), found);
    }

    @Test
    void theWalkNeverDescendsIntoGitOrNodeModules(@TempDir Path dir) throws IOException {
        write(dir.resolve(".git/jacoco.xml"), "<report/>");
        write(dir.resolve("node_modules/jacoco.xml"), "<report/>");
        assertEquals(List.of(), Coverage.findReports(dir));
    }

    @Test
    void aRunClearsBothTheExecDataAndAnyReportLeftFromLastTime(@TempDir Path dir) throws IOException {
        write(dir.resolve("target/jacoco.exec"), "binary");
        write(dir.resolve("core/target/jacoco.exec"), "binary");
        // not on the enumerated list, and exactly what findReports would read as this run's result
        write(dir.resolve("odd/place/jacoco.xml"), "<report/>");
        List<String> events = new ArrayList<>();
        int removed = Coverage.clearStaleCoverage(dir, List.of(".", "core"), (stage, msg) -> events.add(stage + ": " + msg));
        assertEquals(3, removed);
        assertFalse(Files.exists(dir.resolve("target/jacoco.exec")));
        assertFalse(Files.exists(dir.resolve("core/target/jacoco.exec")));
        assertFalse(Files.exists(dir.resolve("odd/place/jacoco.xml")));
        assertEquals(1, events.size(), "one event, and only because something was actually removed");
        assertTrue(events.get(0).startsWith("coverage: cleared 3 stale JaCoCo artefact(s)"));
    }

    @Test
    void aCleanCheckoutSaysNothing(@TempDir Path dir) {
        List<String> events = new ArrayList<>();
        assertEquals(0, Coverage.clearStaleCoverage(dir, List.of("."), (stage, msg) -> events.add(msg)));
        assertEquals(List.of(), events, "no artefacts, no event");
    }

    @Test
    void theModulesToClearAreTheDistinctOnesTheUnitsLiveIn() {
        Map<String, Coverage.UnitRef> files = new LinkedHashMap<>();
        files.put("core/src/main/java/a/B.java", new Coverage.UnitRef(null, null, null, "core", null));
        files.put("core/src/main/java/a/C.java", new Coverage.UnitRef(null, null, null, "core", null));
        files.put("json/src/main/java/a/D.java", new Coverage.UnitRef(null, null, null, "json", null));
        files.put("src/main/java/a/E.java", new Coverage.UnitRef(null, null, null, null, null));
        assertEquals(List.of("core", "json"), Coverage.modulesOf(files));
        assertEquals(List.of("."), Coverage.modulesOf(Map.of()), "no module says means the root");
        assertEquals(List.of("."), Coverage.modulesOf(null));
    }

    // ── the command lines ─────────────────────────────────────────────────────────────

    @Test
    void aProjectWithItsOwnJacocoIsNeverGivenASecondAgent() {
        // Two -javaagent entries kill the test JVM outright (`java.lang.instrument ASSERTION
        // FAILED`), which is how every Apache Commons repo failed in the first sweep.
        List<String> argv = Coverage.mavenArgv("./mvnw", true);
        assertFalse(argv.stream().anyMatch(a -> a.contains("prepare-agent")), "no agent of ours");
        // the bare prefix uses the PROJECT's plugin version, so the report tool matches the
        // agent that wrote the exec data
        assertTrue(argv.contains("jacoco:report"));
        assertFalse(argv.stream().anyMatch(a -> a.startsWith("org.jacoco:jacoco-maven-plugin:")));
    }

    @Test
    void aProjectWithoutJacocoGetsOursPinnedToOneVersion() {
        List<String> argv = Coverage.mavenArgv("./mvnw", false);
        String g = "org.jacoco:jacoco-maven-plugin:" + Coverage.JACOCO_VERSION;
        assertEquals(List.of("./mvnw", "-B", "-ntp", g + ":prepare-agent", "test", g + ":report",
                "-Dmaven.test.failure.ignore=true", "-DfailIfNoTests=false"), argv);
        assertTrue(argv.indexOf(g + ":prepare-agent") < argv.indexOf("test"),
                "the agent has to be set up before the suite runs");
    }

    @Test
    void aFailingSuiteMustNotAbortTheMeasurement() {
        // -Dmaven.test.failure.ignore=true is why this build's exit code is not the suite's
        // verdict, and why the reported counts are the only honest signal in the log
        for (boolean hasJacoco : new boolean[]{true, false}) {
            assertTrue(Coverage.mavenArgv("mvn", hasJacoco).contains("-Dmaven.test.failure.ignore=true"));
            assertTrue(Coverage.mavenArgv("mvn", hasJacoco).contains("-DfailIfNoTests=false"));
        }
    }

    @Test
    void gradleGetsTheInitScriptAndKeepsGoingPastAFailedModule() {
        List<String> argv = Coverage.gradleArgv("./gradlew", List.of("--no-scan"));
        assertEquals(List.of("./gradlew", "--no-daemon", "--no-scan", "--continue",
                "-I", Coverage.INIT_SCRIPT, "test", "jacocoTestReport"), argv);
    }

    @Test
    void gradleWithoutAScanPluginIsPassedNoScanFlagAtAll() {
        // Gradle rejects --no-scan as an unknown option on a build that applies no scan
        // plugin, which would fail every invocation on every other Gradle repo
        assertEquals(List.of("./gradlew", "--no-daemon", "--continue",
                "-I", Coverage.INIT_SCRIPT, "test", "jacocoTestReport"),
                Coverage.gradleArgv("./gradlew", List.of()));
        assertEquals(Coverage.gradleArgv("./gradlew", List.of()), Coverage.gradleArgv("./gradlew", null));
    }

    @Test
    void theInitScriptForcesAnXmlReportAndLeavesTheReposBuildFilesAlone() {
        String s = Coverage.gradleInitScript();
        assertTrue(s.contains("apply plugin: 'jacoco'"));
        assertTrue(s.contains("xml.required = true"), "the XML report is the one we parse");
        assertTrue(s.contains("html.required = false"), "and the HTML one is pure cost");
        assertTrue(s.contains("ignoreFailures = true"), "a red suite must still produce a measurement");
        assertTrue(s.contains("finalizedBy"), "the report has to run after the tests do");
    }

    // ── mapping the report onto the units we track ────────────────────────────────────

    private static Coverage.Report reportWith(String fqcn, int missed, int covered,
                                              String method, int mMissed, int mCovered, int mLine) {
        Coverage.Report r = new Coverage.Report();
        Coverage.ClassCov c = new Coverage.ClassCov();
        c.missed = missed;
        c.covered = covered;
        if (method != null) {
            Coverage.MethodCov m = new Coverage.MethodCov(mLine);
            m.missed = mMissed;
            m.covered = mCovered;
            c.methods.put(method, m);
        }
        r.classes.put(fqcn, c);
        return r;
    }

    @Test
    void aMethodUnitTakesItsOwnCountersAndNotTheClassTotal() {
        Coverage.Report r = reportWith("a.B", 40, 60, "small", 1, 3, 12);
        Map<String, Coverage.UnitRef> files = Map.of(
                "src/main/java/a/B.java::small", new Coverage.UnitRef("a.B", "src/main/java/a/B.java", "small", null, null));
        Coverage.UnitUpdates u = Coverage.unitCoverage(r, files, p -> "unused");
        Map<String, Object> patch = u.patches().get("src/main/java/a/B.java::small");
        assertEquals(75.0, patch.get("coverage"), "3 of 4, not the class's 60 of 100");
        assertEquals(3, patch.get("coveredLines"));
        assertEquals(1, patch.get("missedLines"));
        assertEquals(4, patch.get("executableLines"));
        assertEquals(12, patch.get("methodLine"));
        assertEquals(75.0, u.byPath().get("src/main/java/a/B.java::small"));
    }

    @Test
    void aMethodTheReportNeverDescribedIsZeroWithNoLineRatherThanLineZero() {
        Coverage.Report r = reportWith("a.B", 40, 60, "other", 1, 3, 12);
        Map<String, Coverage.UnitRef> files = Map.of(
                "a/B.java::missing", new Coverage.UnitRef("a.B", "a/B.java", "missing", null, null));
        Map<String, Object> patch = Coverage.unitCoverage(r, files, p -> "unused").patches().get("a/B.java::missing");
        assertEquals(0.0, patch.get("coverage"));
        assertEquals(0, patch.get("executableLines"));
        assertTrue(patch.containsKey("methodLine"));
        assertNull(patch.get("methodLine"), "no known line — 0 is a line number the prompt would quote");
    }

    @Test
    void aFileUnitTakesTheClassCountersAndLeavesMethodLineAlone() {
        Coverage.Report r = reportWith("a.B", 40, 60, null, 0, 0, 0);
        Map<String, Coverage.UnitRef> files = Map.of(
                "a/B.java", new Coverage.UnitRef("a.B", "a/B.java", null, null, null));
        Map<String, Object> patch = Coverage.unitCoverage(r, files, p -> "unused").patches().get("a/B.java");
        assertEquals(60.0, patch.get("coverage"));
        assertEquals(60, patch.get("coveredLines"));
        assertEquals(40, patch.get("missedLines"));
        assertEquals(100, patch.get("executableLines"));
        assertFalse(patch.containsKey("methodLine"),
                "a key absent from the patch keeps whatever the record already held");
    }

    @Test
    void aClassAbsentFromTheReportEntirelyHasNoExecutableCodeAndIsNotZeroPercentCovered() {
        // JaCoCo lists every class it saw on the classpath, so absence means there is no
        // executable code at all — an annotation (@interface), a marker interface, a constants
        // holder. Those read as "0 % covered" and used to sort straight to the top of the pick
        // list, where they consumed a whole run each and could never improve.
        Map<String, Coverage.UnitRef> files = Map.of(
                "a/Marker.java", new Coverage.UnitRef("a.Marker", "a/Marker.java", null, null, null));
        Map<String, Object> patch = Coverage.unitCoverage(new Coverage.Report(), files, p -> "a.Marker")
                .patches().get("a/Marker.java");
        assertEquals(0.0, patch.get("coverage"));
        assertEquals(0, patch.get("executableLines"));
        assertFalse(patch.containsKey("coveredLines"), "nothing was counted, so nothing is claimed");
    }

    @Test
    void aUnitAlreadyCarryingANumberKeepsItWhenThisReportDoesNotMentionIt() {
        Map<String, Coverage.UnitRef> files = Map.of(
                "a/B.java", new Coverage.UnitRef("a.B", "a/B.java", null, null, 42.0));
        Coverage.UnitUpdates u = Coverage.unitCoverage(new Coverage.Report(), files, p -> "a.B");
        assertFalse(u.patches().containsKey("a/B.java"), "an unmentioned unit is not overwritten with 0");
        assertFalse(u.byPath().containsKey("a/B.java"), "and the route does not report a number it did not measure");
    }

    @Test
    void aUnitWithNoStoredFqcnIsResolvedFromItsSourcePath() {
        Coverage.Report r = reportWith("a.B", 0, 10, null, 0, 0, 0);
        Map<String, Coverage.UnitRef> files = Map.of(
                "a/B.java::m", new Coverage.UnitRef(null, "a/B.java", null, null, null));
        List<String> asked = new ArrayList<>();
        Coverage.UnitUpdates u = Coverage.unitCoverage(r, files, p -> { asked.add(p); return "a.B"; });
        assertEquals(List.of("a/B.java"), asked, "resolved from the unit's path, not from its unit key");
        assertEquals(100.0, u.byPath().get("a/B.java::m"));
    }

    @Test
    void aUnitWithNeitherFqcnNorPathFallsBackToItsKey() {
        Coverage.Report r = reportWith("a.B", 0, 10, null, 0, 0, 0);
        Map<String, Coverage.UnitRef> files = Map.of(
                "a/B.java", new Coverage.UnitRef(null, null, null, null, null));
        List<String> asked = new ArrayList<>();
        Coverage.unitCoverage(r, files, p -> { asked.add(p); return "a.B"; });
        assertEquals(List.of("a/B.java"), asked);
    }

    @Test
    void aClassWithNoExecutableLinesIsZeroAndNotADivisionByZero() {
        Coverage.Report r = reportWith("a.B", 0, 0, null, 0, 0, 0);
        Map<String, Coverage.UnitRef> files = Map.of(
                "a/B.java", new Coverage.UnitRef("a.B", "a/B.java", null, null, null));
        assertEquals(0.0, Coverage.unitCoverage(r, files, p -> "a.B").byPath().get("a/B.java"));
    }

    // ── the uncovered-lines prompt ────────────────────────────────────────────────────

    @Test
    void aClassNoTestEverExecutedReportsAllLinesRatherThanAnEmptyList() {
        ObjectNode out = Coverage.uncoveredLinesOf(new Coverage.Report(), "a.B");
        assertEquals("all", out.get("lines").asText());
        assertEquals("class never executed by any test — 0% coverage", out.get("note").asText());
        assertFalse(out.has("coveredLines"), "there is nothing to count");
    }

    @Test
    void onlyLinesWithMissedInstructionsAndNoCoveredOnesAreUncovered() {
        Coverage.Report r = merged(XML);
        ObjectNode out = Coverage.uncoveredLinesOf(r, "org.apache.commons.cli.MissingOptionException");
        List<Integer> lines = new ArrayList<>();
        out.get("lines").forEach(n -> lines.add(n.asInt()));
        assertEquals(List.of(41, 42), lines, "55 is covered, so it is not reported as missing");
        assertEquals(12, out.get("coveredLines").asInt());
        assertEquals(3, out.get("missedLines").asInt());
    }

    @Test
    void theLineListIsSortedBeforeItIsCappedSoTheCapKeepsTheFirstTwoHundred() {
        Coverage.Report r = new Coverage.Report();
        Coverage.ClassCov c = new Coverage.ClassCov();
        // inserted back to front, exactly as a report merged out of order could arrive
        for (int nr = 300; nr >= 1; nr--) c.lines.put(nr, new Coverage.LineHit(1, 0));
        r.classes.put("a.B", c);
        ObjectNode out = Coverage.uncoveredLinesOf(r, "a.B");
        assertEquals(200, out.get("lines").size());
        assertEquals(1, out.get("lines").get(0).asInt());
        assertEquals(200, out.get("lines").get(199).asInt());
    }

    @Test
    void aUnitKeyIsTheSourcePathWithTheMethodAfterADoubleColon() {
        assertEquals("src/main/java/a/B.java", Coverage.beforeUnitSeparator("src/main/java/a/B.java::toString"));
        assertEquals("src/main/java/a/B.java", Coverage.beforeUnitSeparator("src/main/java/a/B.java"));
        assertEquals("", Coverage.beforeUnitSeparator(null));
    }

    @Test
    void theErrorTailIsTheLastCharactersNotTheFirst() {
        assertEquals("cde", Coverage.tail("abcde", 3));
        assertEquals("ab", Coverage.tail("ab", 700));
        assertEquals("", Coverage.tail(null, 700));
    }

    // ── the run, over a faked-out world ───────────────────────────────────────────────

    /// A recording {@link Coverage.Io}: no processes, no state store, and a repo directory
    /// the test owns. The build "writes" its report by copying whatever the test staged.
    private static final class FakeIo implements Coverage.Io {
        final Path dir;
        Coverage.Runner runner;
        final Map<String, Coverage.UnitRef> files = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> upserts = new LinkedHashMap<>();
        final List<String> events = new ArrayList<>();
        final List<List<String>> commands = new ArrayList<>();
        /// report content each successive build should leave behind; null leaves none
        final List<String> reportsPerBuild = new ArrayList<>();
        Coverage.Proc next = new Coverage.Proc(0, "Tests run: 3, Failures: 0, Errors: 0", "", false);

        FakeIo(Path dir) { this.dir = dir; }

        @Override public Path repoDir() { return dir; }
        @Override public Coverage.Runner runner() { return runner; }
        @Override public String fqcnOf(String p) { return "a.B"; }
        @Override public Map<String, Coverage.UnitRef> files() { return files; }
        @Override public void upsertFile(String unit, Map<String, Object> patch) { upserts.put(unit, patch); }
        @Override public void event(String stage, String message) { events.add(stage + ": " + message); }

        @Override public Coverage.Proc run(List<String> argv, long timeoutMs, String label) {
            commands.add(argv);
            int nth = commands.size() - 1;
            String xml = nth < reportsPerBuild.size() ? reportsPerBuild.get(nth) : null;
            if (xml != null) {
                try { write(dir.resolve("target/site/jacoco/jacoco.xml"), xml); }
                catch (IOException e) { throw new RuntimeException(e); }
            }
            return next;
        }

        @Override public Tests.Suite suiteOutcome(String log) {
            return new Tests.Suite(true, 3, 0, 0, log);
        }
    }

    @Test
    void aRunWithNoBuildDetectedSaysWhichRouteToCallFirst(@TempDir Path dir) {
        FakeIo io = new FakeIo(dir);
        io.runner = null;
        assertTrue(assertThrows(IllegalStateException.class, () -> Coverage.runCoverage(io))
                .getMessage().contains("/api/repo/prepare"));
        io.runner = new Coverage.Runner(null, "./mvnw", false, List.of());
        assertThrows(IllegalStateException.class, () -> Coverage.runCoverage(io));
    }

    @Test
    void aMavenRunMeasuresEveryTrackedUnitAndReportsTheRepoTotal(@TempDir Path dir) throws IOException {
        FakeIo io = new FakeIo(dir);
        io.runner = new Coverage.Runner("maven", "./mvnw", false, List.of());
        io.reportsPerBuild.add(XML);
        io.files.put("a/B.java::createMessage", new Coverage.UnitRef(
                "org.apache.commons.cli.MissingOptionException", "a/B.java", "createMessage", null, null));
        Coverage.Result r = Coverage.runCoverage(io);

        assertEquals(80.0, r.totalPct());
        assertEquals(1, r.reports());
        assertEquals(0, r.exitCode());
        assertEquals(100.0, r.files().get("a/B.java::createMessage"), "12 of 12 lines in that method");
        assertEquals(100.0, io.upserts.get("a/B.java::createMessage").get("coverage"));
        assertTrue(r.classes().containsKey("org.apache.commons.cli.MissingOptionException"));
        assertEquals(Boolean.TRUE, r.suite().passed(),
                "the run executed the suite, so the caller needs no second execution to learn that");
        // "80%", never "80.0%", in front of a person reading the log
        assertTrue(io.events.stream().anyMatch(e -> e.contains("total line coverage 80% over 1 JaCoCo report(s)")),
                io.events.toString());
    }

    @Test
    void aProjectsOwnJacocoProducingNothingIsRetriedWithOurAgent(@TempDir Path dir) throws IOException {
        // its agent can sit in an inactive profile, in which case deferring measures nothing
        FakeIo io = new FakeIo(dir);
        io.runner = new Coverage.Runner("maven", "./mvnw", true, List.of());
        io.reportsPerBuild.add(null);   // first build: the project's own JaCoCo, no report
        io.reportsPerBuild.add(XML);    // second: ours
        Coverage.Result r = Coverage.runCoverage(io);

        assertEquals(2, io.commands.size(), "two sequential builds — which is why the HTTP ceiling is doubled");
        assertFalse(io.commands.get(0).stream().anyMatch(a -> a.contains("prepare-agent")));
        assertTrue(io.commands.get(1).stream().anyMatch(a -> a.contains("prepare-agent")));
        assertTrue(io.events.stream().anyMatch(e -> e.contains("retrying with our agent")));
        assertEquals(80.0, r.totalPct());
    }

    @Test
    void aProjectsOwnJacocoThatDidProduceAReportIsNotRerun(@TempDir Path dir) throws IOException {
        FakeIo io = new FakeIo(dir);
        io.runner = new Coverage.Runner("maven", "./mvnw", true, List.of());
        io.reportsPerBuild.add(XML);
        Coverage.runCoverage(io);
        assertEquals(1, io.commands.size(), "one build, and no second agent anywhere near it");
    }

    @Test
    void aBuildThatProducedNoReportAtAllFailsWithTheTailOfItsLog(@TempDir Path dir) {
        FakeIo io = new FakeIo(dir);
        io.runner = new Coverage.Runner("maven", "./mvnw", false, List.of());
        io.next = new Coverage.Proc(1, "stdout noise", "the real reason", false);
        String msg = assertThrows(IllegalStateException.class, () -> Coverage.runCoverage(io)).getMessage();
        assertTrue(msg.contains("JaCoCo produced no XML report (exit 1)"), msg);
        assertTrue(msg.contains("the real reason"), "stderr is preferred over stdout when it has anything to say");
    }

    @Test
    void aGradleRunWritesTheInitScriptIntoTheRepoRatherThanEditingItsBuildFiles(@TempDir Path dir)
            throws IOException {
        FakeIo io = new FakeIo(dir);
        io.runner = new Coverage.Runner("gradle", "./gradlew", false, List.of("--no-scan"));
        io.reportsPerBuild.add(XML);
        Coverage.runCoverage(io);
        Path script = dir.resolve(Coverage.INIT_SCRIPT);
        assertTrue(Files.exists(script));
        assertTrue(Files.readString(script).contains("apply plugin: 'jacoco'"));
        assertTrue(io.commands.get(0).contains("--no-scan"));
        assertTrue(io.commands.get(0).contains("jacocoTestReport"));
    }

    @Test
    void staleArtefactsAreClearedBeforeTheBuildRunsAndNotAfter(@TempDir Path dir) throws IOException {
        // A leftover report parses exactly like a fresh one, so a build that produced nothing
        // would otherwise be measured against the previous run's numbers.
        FakeIo io = new FakeIo(dir);
        io.runner = new Coverage.Runner("maven", "./mvnw", false, List.of());
        write(dir.resolve("target/site/jacoco/jacoco.xml"), "<report name=\"last time\"/>");
        write(dir.resolve("target/jacoco.exec"), "old");
        io.next = new Coverage.Proc(1, "", "boom", false);
        assertThrows(IllegalStateException.class, () -> Coverage.runCoverage(io),
                "the stale report must not be mistaken for this run's result");
        assertFalse(Files.exists(dir.resolve("target/jacoco.exec")));
    }

    private static void write(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
    }
}
