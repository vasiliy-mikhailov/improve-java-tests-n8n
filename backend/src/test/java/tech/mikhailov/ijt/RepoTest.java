package tech.mikhailov.ijt;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/// The repo lifecycle: what git is actually asked to do, and the decisions taken around it.
///
/// The discard tests below exercise the REAL git commands against a throwaway repository,
/// because the defect they pin is entirely in how git behaves — mocking it would pin the
/// wrong thing. Everything else here is a pure decision (which JDK to try, which files are in
/// scope, what a method's context is) tested without a repository at all, which is the split
/// the JS module already had.
///
/// Like {@link StateTest}, these share the one process-wide store and must not run in
/// parallel; each points DATA_DIR at its own temp directory first.
class RepoTest {

    private static final String REPO_URL = "file:///tmp/fixture-repo";
    private static final String TEST_REL = "src/test/java/a/BMacMutTest.java";
    private static final String TEST_SRC = """
            package a;
            import org.junit.Test;
            public class BMacMutTest {
              @Test public void t() { assertEquals(1, 1); }
            }
            """;

    @BeforeEach
    void fresh(@TempDir Path dir) {
        State.DATA_DIR = dir;   // this test's own data directory…
        State.flushNow();       // …and drain whatever the previous test left owed, into it
        State.Store s = State.STATE;
        s.runner = null;
        s.files = new LinkedHashMap<>();
        s.events = new ArrayList<>();
        s.seq = 0;
        State.Run run = State.freshRun(State.envConfig(k -> null),
                Map.of("repoUrl", REPO_URL, "repoBranch", "master", "prBase", "master"));
        run.id = "run-test";
        s.run = run;
    }

    @AfterAll
    static void drainPendingFlushes() throws IOException {
        // A debounced flush outlives the test that asked for it, and the shutdown hook would
        // otherwise recreate a deleted temp directory just to land it.
        Path dir = Files.createTempDirectory("ijt-repo-drain-");
        State.DATA_DIR = dir;
        State.flushNow();
        Files.deleteIfExists(dir.resolve("state.json"));
        Files.deleteIfExists(dir.resolve("events.jsonl"));
        Files.deleteIfExists(dir);
    }

    // ── discarding a missed round ─────────────────────────────────────────────

    @Test
    void aMissedRoundLeavesNoTraceOfItsTestNotEvenAnEmptyFile() throws IOException {
        // verify() calls diffAgainstBase(), which runs `git add -N` on the new test so it shows
        // up in the diff. A missed round then ran `git checkout -- .`, which MATERIALISES that
        // intent-to-add entry as an empty blob, and `git clean -fd` skipped it because it was
        // by then tracked. A zero-byte JSONArrayMacMutR2Test.java reached a prepared PR.
        Path dir = fixture();
        write(dir, TEST_REL, TEST_SRC);
        stageIntentToAdd();                             // the real diffAgainstBase, as verify() calls it
        Repo.discardUncommitted();                      // the round missed
        assertFalse(Files.exists(dir.resolve(TEST_REL)), "the file must be gone, not emptied");
        assertEquals("", git(dir, "status", "--porcelain").trim(), "and the tree must be clean");
    }

    @Test
    void anAcceptedRoundAlreadyCommittedSurvivesALaterDiscard() throws IOException {
        Path dir = fixture();
        write(dir, TEST_REL, TEST_SRC);
        git(dir, "add", "--", TEST_REL);
        git(dir, "commit", "-q", "-m", "kept round");
        write(dir, "src/test/java/a/Other.java", TEST_SRC);
        stageIntentToAdd();
        Repo.discardUncommitted();
        assertEquals(TEST_SRC, Files.readString(dir.resolve(TEST_REL)), "committed work is untouched");
        assertFalse(Files.exists(dir.resolve("src/test/java/a/Other.java")));
    }

    @Test
    void aModifiedTrackedFileIsRestoredNotTruncated() throws IOException {
        Path dir = fixture();
        write(dir, TEST_REL, TEST_SRC);
        git(dir, "add", "--", TEST_REL);
        git(dir, "commit", "-q", "-m", "round 1");
        write(dir, TEST_REL, TEST_SRC + "// round 2 scribble\n");
        Repo.discardUncommitted();
        assertEquals(TEST_SRC, Files.readString(dir.resolve(TEST_REL)));
    }

    @Test
    void aBranchThisRunAlreadyWorkedOnIsContinuedNotReset() throws IOException {
        // The branch is per FILE and the unit is per METHOD, so the second method of a class
        // lands on a branch that already carries the first method's committed rounds. `git
        // checkout -B` moved it back to the base and the later force-push rewrote an open PR
        // to contain only the second method's test.
        Path dir = fixture();
        Repo.createBranch("tests/improve-a-b");
        write(dir, TEST_REL, TEST_SRC);
        git(dir, "add", "--", TEST_REL);
        git(dir, "commit", "-q", "-m", "first method's round");

        Repo.createBranch("tests/improve-a-b");         // the file's second method arrives
        assertTrue(Files.exists(dir.resolve(TEST_REL)), "the first method's committed test is still there");
        assertEquals("run-test", String.valueOf(State.STATE.run.branchOwners.get("tests/improve-a-b")));
    }

    @Test
    void aBranchLeftBehindByAnEarlierRunIsResetToTheBase() throws IOException {
        Path dir = fixture();
        Repo.createBranch("tests/improve-a-b");
        write(dir, TEST_REL, TEST_SRC);
        git(dir, "add", "--", TEST_REL);
        git(dir, "commit", "-q", "-m", "an earlier run's work");
        // a new run: its branchOwners map is empty, so the branch belongs to nobody here
        State.STATE.run.branchOwners = new LinkedHashMap<>();
        State.STATE.run.id = "run-later";

        Repo.createBranch("tests/improve-a-b");
        assertFalse(Files.exists(dir.resolve(TEST_REL)),
                "commits from a run that is over must not smuggle themselves into this run's baseline");
    }

    // ── cloning ───────────────────────────────────────────────────────────────

    @Test
    void aBranchTheRepoDoesNotHaveFallsBackToItsDefaultAndTakesThePrBaseWithIt(@TempDir Path up) throws IOException {
        // REPO_BRANCH=main against a repo whose default is master used to end the whole run at
        // the first step with "couldn't find remote ref main". And when it did not, the PR base
        // stayed frozen at the unresolved name, so every `gh pr create --base main` failed
        // AFTER the work had been measured and committed.
        String remote = upstream(up, "master");
        useRemote(remote, "main", "main");

        Repo.Cloned cloned = Repo.cloneRepo();
        assertTrue(Files.exists(cloned.dir().resolve("pom.xml")), "the working copy is there");
        assertEquals(40, cloned.head().length(), "and it reports the full sha");
        assertEquals("master", Repo.config().repoBranch());
        assertEquals("master", Repo.config().prBase(), "the PR base has to follow the branch");
    }

    @Test
    void aSecondCloneFetchesTheExistingWorkingCopyInsteadOfStartingAgain(@TempDir Path up) throws IOException {
        String remote = upstream(up, "master");
        useRemote(remote, "master", "master");
        Repo.Cloned first = Repo.cloneRepo();
        write(first.dir(), "scratch.txt", "left over from the last run");

        Repo.Cloned second = Repo.cloneRepo();
        assertEquals(first.head(), second.head());
        assertFalse(Files.exists(second.dir().resolve("scratch.txt")), "and cleans it on the way");
    }

    @Test
    void credentialsInlinedIntoTheRemoteAreStrippedBeforeGitCanEchoThem(@TempDir Path up) throws IOException {
        // git prints the FULL remote URL in its failure messages, and those messages go into
        // the event log and onto the dashboard. Auth belongs to the credential helper.
        String remote = upstream(up, "master");
        useRemote(remote, "master", "master");
        Path dir = Repo.cloneRepo().dir();
        git(dir, "remote", "set-url", "origin", remote.replaceFirst("^file://", "file://user:s3cret@"));

        Repo.cloneRepo();
        assertEquals(remote, git(dir, "remote", "get-url", "origin").trim());
    }

    // ── the JDK ───────────────────────────────────────────────────────────────

    @Test
    void aProjectDeclaringJava8IsNotBuiltOnJava8First() {
        // Apache Commons declares maven.compiler.source=1.8 while japicmp, animal-sniffer and
        // modern Surefire refuse to run on JDK 8. Building those under the declared 8 killed
        // half the first eval sweep.
        assertEquals(List.of(17, 11, 21, 8), Repo.jdkPreference(8, List.of(8, 11, 17, 21)));
        assertEquals(List.of(17, 11, 21, 8), Repo.jdkPreference(null, List.of(8, 11, 17, 21)));
    }

    @Test
    void aDeclaredTargetIsAFloorNoCandidateGoesBelowIt() {
        assertEquals(List.of(17, 11, 21), Repo.jdkPreference(11, List.of(8, 11, 17, 21)));
        assertEquals(List.of(17, 21), Repo.jdkPreference(17, List.of(8, 11, 17, 21)));
        assertTrue(Repo.jdkPreference(17, List.of(8, 11, 17, 21)).stream().allMatch(v -> v >= 17));
    }

    @Test
    void aTargetNewerThanAnythingInstalledStillGetsCandidatesToTry() {
        // never empty while a JDK exists: an empty order means install() never runs a compile
        // and reports "failed under every candidate JDK ()", naming none
        List<Integer> order = Repo.jdkPreference(25, List.of(11, 17, 21));
        assertFalse(order.isEmpty());
        assertEquals(2, order.size(), "the fallback offers two, not the whole image");
        assertEquals(List.of(11, 17), order);
    }

    @Test
    void nothingInstalledCanOnlyAnswerNothing() {
        assertEquals(List.of(), Repo.jdkPreference(11, List.of()));
        assertEquals(List.of(), Repo.jdkPreference(null, null));
    }

    @Test
    void theDeclaredVersionIsTheHighestAnyBuildFileAsksFor() {
        String pom = """
                <properties>
                  <maven.compiler.source>1.8</maven.compiler.source>
                  <maven.compiler.target>1.8</maven.compiler.target>
                  <java.version>11</java.version>
                </properties>
                """;
        assertEquals(11, Repo.declaredJdk(pom), "1.8 reads as 8, and the highest of the three wins");
    }

    @Test
    void gradleSpellsItsVersionsSeveralWays() {
        assertEquals(8, Repo.declaredJdk("sourceCompatibility = JavaVersion.VERSION_1_8"));
        assertEquals(17, Repo.declaredJdk("targetCompatibility = '17'"));
        assertEquals(21, Repo.declaredJdk("languageVersion = JavaLanguageVersion.of(21)"));
    }

    @Test
    void aVersionThatIsNotAJdkVersionIsIgnored() {
        // <version> tags are everywhere in a pom; only 6..25 can be a JDK anyone builds on
        assertNull(Repo.declaredJdk("<properties><java.version>3.2.1</java.version></properties>"));
        assertNull(Repo.declaredJdk("<source>99</source>"));
        assertNull(Repo.declaredJdk(""));
        assertNull(Repo.declaredJdk(null));
    }

    @Test
    void theBuildEnvironmentComesFromTheStoreNotFromAFieldOfThisClass() {
        // after a restart mid-run the detected JDK comes back from state.json, and nothing
        // re-detects it before the next build command
        State.STATE.runner = Repo.runnerMap(
                new Repo.Build("maven", "./mvnw", true, false, null, List.of()),
                new Repo.Jdk(8, 17, List.of(17, 11), "/opt/java/jdk-17", List.of(11, 17)),
                new Repo.TestFramework("junit5", "5.11.3"), Boolean.FALSE);
        Map<String, String> env = Repo.buildEnv();
        assertEquals("/opt/java/jdk-17", env.get("JAVA_HOME"));
        assertTrue(env.get("PATH").startsWith("/opt/java/jdk-17/bin:"), env.get("PATH"));
    }

    @Test
    void theRunnerRecordKeepsTheFieldNamesEveryOtherModuleReads() {
        // `state.runner` is a wire contract: Pit reads tool/wrapper/gradleVersion/scanArgs/
        // testFramework/testFrameworkVersion by name out of the state JSON, Coverage reads
        // hasJacoco, and the dashboard renders the lot. Renaming one here is silent.
        Map<String, Object> rn = Repo.runnerMap(
                new Repo.Build("gradle", "./gradlew", false, true, "8.10.2", List.of("--no-scan")),
                new Repo.Jdk(17, 17, List.of(17), "/opt/java/jdk-17", List.of(17)),
                new Repo.TestFramework("junit5", "5.11.3"), Boolean.TRUE);
        assertEquals(List.of("tool", "wrapper", "hasPom", "hasGradle", "gradleVersion", "scanArgs",
                "jdk", "testFramework", "testFrameworkVersion", "hasJacoco"), List.copyOf(rn.keySet()));
        assertEquals("./gradlew", rn.get("wrapper"));
        assertEquals(List.of("--no-scan"), rn.get("scanArgs"));
        assertEquals(Boolean.TRUE, rn.get("hasJacoco"));
        assertEquals("/opt/java/jdk-17", ((Map<?, ?>) rn.get("jdk")).get("javaHome"));
    }

    // ── the build tool ────────────────────────────────────────────────────────

    @Test
    void theWrapperIsPreferredBecauseItPinsTheBuildVersion() throws IOException {
        Path dir = bareRepo();
        write(dir, "pom.xml", "<project/>");
        assertEquals("mvn", Repo.detectBuild().wrapper());
        write(dir, "mvnw", "#!/bin/sh\n");
        Repo.Build build = Repo.detectBuild();
        assertEquals("maven", build.tool());
        assertEquals("./mvnw", build.wrapper());
        assertTrue(build.hasPom());
        assertEquals(List.of(), build.scanArgs(), "Maven has no --no-scan flag to reject");
    }

    @Test
    void aGradleRepoIsRecognisedByAnyOfItsBuildFiles() throws IOException {
        Path dir = bareRepo();
        write(dir, "settings.gradle.kts", "rootProject.name = \"x\"");
        write(dir, "gradlew", "#!/bin/sh\n");
        write(dir, "gradle/wrapper/gradle-wrapper.properties",
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10.2-bin.zip\n");
        Repo.Build build = Repo.detectBuild();
        assertEquals("gradle", build.tool());
        assertEquals("./gradlew", build.wrapper());
        // the wrapper pins the Gradle version, and the PIT plugin has to match its API
        assertEquals("8.10.2", build.gradleVersion());
    }

    @Test
    void aRepoThatIsNeitherHasNoTool() throws IOException {
        bareRepo();
        assertNull(Repo.detectBuild().tool(), "install() refuses to continue from this");
    }

    @Test
    void theScanFlagIsDecidedOnceAndOnlyForGradle() throws IOException {
        Path dir = bareRepo();
        write(dir, "settings.gradle", "plugins { id 'com.gradle.develocity' version '4.3' }");
        assertEquals(List.of("--no-scan"), Repo.detectBuild().scanArgs());
        // and a Gradle build with no scan plugin gets nothing: Gradle rejects the flag as an
        // unknown option, which would fail every invocation on every other repo
        Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = 'x'");
        assertEquals(List.of(), Repo.detectBuild().scanArgs());
    }

    @Test
    void everyGradleInvocationCarriesTheScanArgsIncludingTheFirstOneOfARun() {
        // install()'s compile is the FIRST Gradle command of every run, and it shipped without
        // the flag while the suite, coverage and PIT calls all had it — so every run still
        // began by uploading a build scan of the user's repo and blocking on it.
        List<String> argv = Repo.compileArgv(
                new Repo.Build("gradle", "./gradlew", false, true, "8.10.2", List.of("--no-scan")));
        assertEquals(List.of("./gradlew", "--no-daemon", "--no-scan", "-q", "testClasses"), argv);
        assertEquals(List.of("./mvnw", "-B", "-ntp", "-DskipTests", "test-compile"),
                Repo.compileArgv(new Repo.Build("maven", "./mvnw", true, false, null, List.of())));
    }

    // ── the test framework ────────────────────────────────────────────────────

    @Test
    void anExistingTestFileOutranksTheDependencyList() {
        // a pom that still declares junit4 beside a jupiter engine writes jupiter tests, and
        // only a real test file says which the project actually uses
        Repo.TestFramework tf = Repo.classifyTestFramework(
                "<artifactId>junit</artifactId>\njunit-jupiter:5.11.3",
                "import org.junit.jupiter.api.Test;");
        assertEquals("junit5", tf.framework());
        assertEquals("5.11.3", tf.version());
    }

    @Test
    void aJunit4SampleIsRecognisedOnlyAsAnImportNotAsAMention() {
        assertEquals("junit4", Repo.classifyTestFramework("", "import org.junit.Test;\n").framework());
        // the same words inside a comment are not an import
        assertEquals("junit5", Repo.classifyTestFramework("", "// we no longer import org.junit.Test;").framework());
    }

    @Test
    void aProjectWithNoSignalAtAllGetsJunit5() {
        Repo.TestFramework tf = Repo.classifyTestFramework("", "");
        assertEquals("junit5", tf.framework());
        assertNull(tf.version(), "a version we cannot read is not one to pin PIT's launcher to");
    }

    @Test
    void theJupiterVersionIsReadFromWhereverItIsDeclared() {
        assertEquals("5.11.3", Repo.jupiterVersion("<artifactId>junit-jupiter</artifactId><version>5.11.3</version>")
                == null ? Repo.jupiterVersion("junit-jupiter:5.11.3") : "5.11.3");
        assertEquals("5.10.2", Repo.jupiterVersion("testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'"));
        assertEquals("5.9.1", Repo.jupiterVersion("<junit.jupiter.version>5.9.1</junit.jupiter.version>"));
        assertEquals("5.11.0", Repo.jupiterVersion("org.junit:junit-bom:5.11.0"));
        assertNull(Repo.jupiterVersion("junit-jupiter"));
    }

    // ── scope ─────────────────────────────────────────────────────────────────

    @Test
    void onlyProductionJavaUnderSrcMainJavaIsInScope() {
        String lsFiles = String.join("\n",
                "src/main/java/a/Thing.java",
                "core/src/main/java/a/Deep.java",
                "src/test/java/a/ThingTest.java",
                "src/main/java/a/package-info.java",
                "src/main/java/module-info.java",
                "target/generated-sources/a/Gen.java",
                "src/main/java/generated/Stub.java",
                "README.md",
                "");   // git ls-files ends with a newline
        assertEquals(List.of("src/main/java/a/Thing.java", "core/src/main/java/a/Deep.java"),
                Repo.scopeFiles(lsFiles, "**/src/main/java/**/*.java"));
    }

    @Test
    void theScopeGlobStillNarrowsWhatIsLeft() {
        String lsFiles = "core/src/main/java/a/Deep.java\nweb/src/main/java/a/Http.java\n";
        assertEquals(List.of("core/src/main/java/a/Deep.java"),
                Repo.scopeFiles(lsFiles, "core/**/*.java"));
    }

    @Test
    void listScopeFilesRecordsWhatThePickStageWillReadBack() throws IOException {
        Path dir = fixture();
        write(dir, "src/main/java/a/Thing.java", "package a;\npublic class Thing {}\n");
        write(dir, "src/main/java/a/package-info.java", "package a;\n");
        git(dir, "add", "-A");
        git(dir, "commit", "-q", "-m", "sources");

        List<String> files = Repo.listScopeFiles();
        assertEquals(List.of("src/main/java/a/Thing.java"), files);
        Map<String, Object> record = State.STATE.files.get("src/main/java/a/Thing.java");
        assertNotNull(record, "the pick stage reads this record, not the returned list");
        assertEquals("a.Thing", record.get("fqcn"));
        assertEquals(".", record.get("module"));
        // 3, not 2: JS split keeps the empty field after the trailing newline, and every line
        // count already in a ledger was produced that way
        assertEquals(3, record.get("lines"));
    }

    @Test
    void aFileWeCannotReadRecordsAnUnknownLineCountNotZero() throws IOException {
        Path dir = fixture();
        write(dir, "src/main/java/a/Ghost.java", "package a;\n");
        git(dir, "add", "-A");
        git(dir, "commit", "-q", "-m", "sources");
        Files.delete(dir.resolve("src/main/java/a/Ghost.java"));   // tracked, but gone

        Repo.listScopeFiles();
        Map<String, Object> record = State.STATE.files.get("src/main/java/a/Ghost.java");
        assertNull(record.get("lines"), "0 lines is a reason to skip a file; unknown is not");
    }

    // ── names and modules ─────────────────────────────────────────────────────

    @Test
    void theFullyQualifiedNameComesFromThePathWhenTheLayoutIsStandard() {
        assertEquals("org.json.XML", Repo.fqcnOf("src/main/java/org/json/XML.java"));
        assertEquals("a.b.Deep", Repo.fqcnOf("core/src/main/java/a/b/Deep.java"));
    }

    @Test
    void aNonStandardLayoutFallsBackToThePackageDeclaration() {
        assertEquals("com.acme.Thing", Repo.fqcnFromSource("java/com/acme/Thing.java",
                "// header\npackage com.acme;\n\npublic class Thing {}"));
        // no package at all is the default package, and the class still has a name
        assertEquals("Thing", Repo.fqcnFromSource("java/Thing.java", "public class Thing {}"));
    }

    @Test
    void everyCommandRunsInTheModuleThatOwnsTheClass() throws IOException {
        Path dir = bareRepo();
        write(dir, "pom.xml", "<project/>");
        write(dir, "core/pom.xml", "<project/>");
        write(dir, "core/src/main/java/a/Deep.java", "package a;");
        write(dir, "src/main/java/a/Root.java", "package a;");
        assertEquals("core", Repo.moduleOf("core/src/main/java/a/Deep.java"));
        assertEquals(".", Repo.moduleOf("src/main/java/a/Root.java"));
        assertEquals(List.of(".", "core"), sorted(Repo.listModules()));
    }

    @Test
    void theModuleSelectorNamesAModuleOnlyWhenThereIsOne() {
        // it becomes `-pl <module>`; the root must not be named, because `-pl .` is not that
        assertEquals("core", Repo.moduleSelector("core"));
        assertNull(Repo.moduleSelector("."));
        assertNull(Repo.moduleSelector(""));
        assertNull(Repo.moduleSelector(null));
    }

    // ── writing tests into the repo ───────────────────────────────────────────

    @Test
    void nothingIsEverWrittenOutsideSrcTestJava() throws IOException {
        fixture();
        assertThrows(IllegalArgumentException.class,
                () -> Repo.writeTestFile("src/main/java/a/Thing.java", "hacked"));
        assertThrows(IllegalArgumentException.class,
                () -> Repo.writeTestFile("src/test/java/a/Thing.txt", "not java"));
        assertThrows(IllegalArgumentException.class,
                () -> Repo.deleteTestFile("src/main/java/a/Thing.java"));
    }

    @Test
    void aPathThatClimbsOutOfTheRepoIsRefused() throws IOException {
        fixture();
        assertThrows(IllegalArgumentException.class,
                () -> Repo.writeTestFile("src/test/java/../../../../etc/x/src/test/java/E.java", "x"));
        assertThrows(IllegalArgumentException.class, () -> Repo.readFileSafe("../../../etc/passwd"));
        assertFalse(Repo.testFileExists("../../../etc/passwd"));
    }

    @Test
    void aWrittenTestIsMeasuredInBytesNotCharacters() throws IOException {
        Path dir = fixture();
        String content = "// é\n";
        Repo.Written w = Repo.writeTestFile("src/test/java/a/UtfTest.java", content);
        assertEquals("src/test/java/a/UtfTest.java", w.path());
        assertEquals(6, w.bytes(), "é is two bytes in UTF-8, and the JS reports Buffer.byteLength");
        assertEquals(5, content.length(), "which is not the character count");
        assertEquals("// é\n", Files.readString(dir.resolve("src/test/java/a/UtfTest.java")));
    }

    @Test
    void whetherTheRoundsTestIsStillOnDiskIsAskedOfTheFileSystem() throws IOException {
        // a test deleted for breaking the suite leaves a score that looks exactly like a test
        // that ran and killed nothing, so verification asks the thing itself, not a flag
        Path dir = fixture();
        assertFalse(Repo.testFileExists("src/test/java/a/GoneTest.java"));
        write(dir, "src/test/java/a/GoneTest.java", TEST_SRC);
        assertTrue(Repo.testFileExists("src/test/java/a/GoneTest.java"));
        assertTrue(Repo.deleteTestFile("src/test/java/a/GoneTest.java"));
        assertFalse(Repo.testFileExists("src/test/java/a/GoneTest.java"));
        assertFalse(Repo.deleteTestFile("src/test/java/a/GoneTest.java"), "deleting it twice is not a success");
        // an absent path resolves to the repo root, which is not a test file
        assertFalse(Repo.testFileExists(null));
        assertFalse(Repo.testFileExists(""));
    }

    @Test
    void theProjectsOwnTestIsFoundAndTheGeneratedOnesAreNamedForThisRound() throws IOException {
        Path dir = fixture();
        assertFalse(Repo.guessTestPath("src/main/java/a/Thing.java").exists());
        write(dir, "src/test/java/a/ThingTests.java", TEST_SRC);
        Repo.TestPathGuess guess = Repo.guessTestPath("src/main/java/a/Thing.java", 2, "parse");
        assertTrue(guess.exists());
        assertEquals("src/test/java/a/ThingTests.java", guess.path(), "the style reference is the project's own");
        assertEquals("src/test/java/a/ThingParseMacCovR2Test.java", guess.covPath());
        assertEquals("src/test/java/a/ThingParseMacMutR2Test.java", guess.mutPath());
    }

    @Test
    void aClassWithNoTestGetsASiblingToImitatePreferablyFromItsOwnModule() throws IOException {
        Path dir = bareRepo();
        write(dir, "pom.xml", "<project/>");
        write(dir, "core/pom.xml", "<project/>");
        write(dir, "core/src/test/java/a/NearTest.java", "x".repeat(600));
        write(dir, "core/src/test/java/a/HugeTest.java", "x".repeat(9000));
        write(dir, "core/src/test/java/a/TinyTest.java", "x".repeat(100));
        write(dir, "web/src/test/java/a/FarTest.java", "x".repeat(500));

        Repo.StyleReference ref = Repo.findStyleReference("core/src/main/java/a/Thing.java");
        assertNotNull(ref);
        // same module, and the smallest one that is not a stub — most digestible reference
        assertEquals("core/src/test/java/a/NearTest.java", ref.path());
        assertEquals(600, ref.content().length());
    }

    @Test
    void aRepoWithNoTestsAtAllHasNoStyleReference() throws IOException {
        Path dir = bareRepo();
        write(dir, "pom.xml", "<project/>");
        assertNull(Repo.findStyleReference("src/main/java/a/Thing.java"));
    }

    // ── the method context ────────────────────────────────────────────────────

    private static final String CLASS_SRC = """
            package org.json;

            import java.util.List;
            import java.util.Map;

            public class XML {
                private final Map<String, String> opts;

                public String toJson(String xml) {
                    if (xml == null) {
                        return "{}";
                    }
                    return parse(xml);
                }

                private String parse(String xml) throws IllegalStateException {
                    return xml.trim();
                }
            }
            """;

    @Test
    void theModelIsSentTheHeaderTheSignaturesAndOneMethodNotTheWholeClass() {
        // XML.java is ~1500 lines: 12k characters of prompt to produce a 400-byte test for one
        // method. Prompt size drives both latency and cost, and most of the class is irrelevant
        // to the mutant at hand.
        Repo.MethodContext ctx = Repo.methodContextOf(CLASS_SRC, "toJson", 9);
        assertEquals("""
                package org.json;
                import java.util.List;
                import java.util.Map;
                public class XML {""", ctx.header());
        assertEquals(List.of("public String toJson(String xml);",
                "private String parse(String xml) throws IllegalStateException;"), ctx.signatures());
        assertTrue(ctx.body().startsWith("    public String toJson(String xml) {"), ctx.body());
        assertTrue(ctx.body().endsWith("    }"), ctx.body());
        assertFalse(ctx.body().contains("private String parse"), "the body stops at its matching brace");
        assertEquals(9, ctx.startLine());
    }

    @Test
    void aCoverageLineInsideTheBodyWalksBackToTheSignature() {
        // the JaCoCo line points at the first EXECUTABLE line, which is inside the method
        Repo.MethodContext ctx = Repo.methodContextOf(CLASS_SRC, "toJson", 10);
        assertEquals(9, ctx.startLine(), "the declaration, not the `if`");
        assertTrue(ctx.body().startsWith("    public String toJson(String xml) {"));
    }

    @Test
    void aMethodLineThatIsNotThereAtAllStillProducesSomething() {
        // methodLine is nullable — a unit measured before JaCoCo ran has none
        Repo.MethodContext ctx = Repo.methodContextOf(CLASS_SRC, "toJson", null);
        assertEquals(1, ctx.startLine());
        assertNotNull(ctx.body());
        assertEquals("", Repo.methodContextOf("", "toJson", 400).body(), "past the end is empty, not an error");
    }

    @Test
    void aMethodNameIsNeverReadAsARegex() {
        // the name is interpolated into a pattern; anything but word characters is stripped
        // first, and Pattern.quote keeps that true whatever the strip lets through
        assertDoesNotThrow(() -> Repo.methodContextOf(CLASS_SRC, "to(Json[", 9));
        assertDoesNotThrow(() -> Repo.methodContextOf(CLASS_SRC, "<init>", 9));
        assertDoesNotThrow(() -> Repo.methodContextOf(CLASS_SRC, null, 9));
    }

    @Test
    void anUnbalancedBodyFallsBackToAWindowRatherThanTheRestOfTheFile() {
        String src = "class A {\n" + "  // {\n".repeat(300);
        Repo.MethodContext ctx = Repo.methodContextOf(src, "A", 1);
        assertEquals(120, ctx.body().split("\n", -1).length, "120 lines, not 300");
    }

    // ── the wiring ────────────────────────────────────────────────────────────

    @Test
    void everyCollaboratorTheVerifyRouteNamesIsActuallyReachable() {
        // roundEvidence takes `exists` as a parameter, which made the logic testable and left
        // the real call site untested: repo.testFileExists was written but never added to
        // module.exports, so `node --check` passed, 285 unit tests passed, and the first live
        // Gradle round died with "repo.testFileExists is not a function" — every verification
        // in the run failing at once. Java's equivalent of that omission is a method that is
        // not public, or one whose name drifted from the call site.
        List<String> names = Arrays.stream(Repo.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .map(Method::getName).toList();
        for (String needed : List.of("testFileExists", "writeTestFile", "deleteTestFile",
                "readFileSafe", "repoDir", "buildEnv", "fqcnOf", "moduleOf", "guessTestPath")) {
            assertTrue(names.contains(needed), needed + " is used by another module and must stay public");
        }
        // The JS list had five entries across two modules, and two of them were Select's:
        // /api/verify reaches Select.attemptKey through RoundOutcome.evidence and Select.eligible
        // through the headline averages. Dropping them from the guard is how the list stops
        // covering the route it is named after.
        List<String> selectNames = Arrays.stream(Select.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .map(Method::getName).toList();
        for (String needed : List.of("eligible", "attemptKey")) {
            assertTrue(selectNames.contains(needed), needed + " is used by /api/verify and must stay public");
        }
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    /// A bare repository with one commit on `branch`, reachable over file://.
    private String upstream(Path dir, String branch) throws IOException {
        Path work = dir.resolve("work");
        Files.createDirectories(work);
        git(work, "init", "-q", "-b", branch);
        git(work, "config", "user.email", "t@example.com");
        git(work, "config", "user.name", "test");
        git(work, "config", "commit.gpgsign", "false");
        write(work, "pom.xml", "<project/>\n");
        git(work, "add", "-A");
        git(work, "commit", "-q", "-m", "base");
        Path bare = dir.resolve("upstream.git");
        git(dir, "clone", "-q", "--bare", work.toString(), bare.toString());
        return "file://" + bare;
    }

    /// Point the run at a remote, as /api/run/start would.
    private void useRemote(String url, String branch, String prBase) {
        State.STATE.run.config = State.applyOverrides(State.STATE.run.config,
                Map.of("repoUrl", url, "repoBranch", branch, "prBase", prBase));
    }

    /// A repo directory with nothing in it — enough for the detectors, which only read files.
    private Path bareRepo() throws IOException {
        Path dir = Repo.repoDir();
        deleteTree(dir);
        Files.createDirectories(dir);
        return dir;
    }

    /// A throwaway git repository at the path the run's configuration points at.
    private Path fixture() throws IOException {
        Path dir = bareRepo();
        Files.createDirectories(dir.resolve("src/test/java/a"));
        git(dir, "init", "-q", "-b", "master");
        git(dir, "config", "user.email", "t@example.com");
        git(dir, "config", "user.name", "test");
        // not in the JS, which ran in a container with no signing configured: a developer
        // machine with commit.gpgsign on globally cannot commit in a throwaway repo
        git(dir, "config", "commit.gpgsign", "false");
        write(dir, "pom.xml", "<project/>\n");
        git(dir, "add", "-A");
        git(dir, "commit", "-q", "-m", "base");
        return dir;
    }

    /// The REAL `pr.diffAgainstBase()`, which is what stages the intent-to-add entry these
    /// tests then discard over.
    ///
    /// A hand-rolled `git add -N` stood here while Pr was being ported, and it pinned the wrong
    /// thing: Pr stages only what survives `pruneEmptyTests(onlyCommittableTests(changedFiles))`,
    /// so a filter chain that stopped staging the file would leave these tests passing against a
    /// setup no production path produces. The JS said so in as many words — "the defect this
    /// pins is entirely in how git behaves, so mocking it would pin the wrong thing" — and the
    /// coupling between the two modules is exactly what makes the ordering defect reproducible.
    private static void stageIntentToAdd() {
        Pr.diffAgainstBase(Server.prIo());
    }

    private static String git(Path dir, String... args) {
        List<String> argv = new ArrayList<>();
        argv.add("git");
        argv.addAll(Arrays.asList(args));
        Exec.Result r = Exec.run(argv, Exec.Options.of().withCwd(dir).withTimeoutMs(60_000L));
        assertEquals(0, r.code(), "git " + String.join(" ", args) + " failed: " + r.stderr());
        return r.stdout();
    }

    private static void write(Path dir, String rel, String content) throws IOException {
        Path abs = dir.resolve(rel);
        Files.createDirectories(abs.getParent());
        Files.writeString(abs, content);
    }

    private static List<String> sorted(List<String> xs) {
        return xs.stream().sorted(Comparator.naturalOrder()).toList();
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
