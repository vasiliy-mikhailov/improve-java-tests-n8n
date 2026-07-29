package tech.mikhailov.ijt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/// Every Gradle invocation was uploading a build scan of the user's repo to gradle.com,
/// and blocking on it.
///
/// java-dataloader's settings.gradle applies `com.gradle.develocity` with
/// `publishing.onlyIf { true }`, so a scan is published on EVERY build. In the container
/// that upload times out, and the run sat 780 seconds inside a single `./gradlew test`
/// with "Publishing Build Scan failed due to network error ... (1 retry remaining)" as its
/// last output. The pipeline runs the suite, the coverage build and PIT once per round, so
/// the first batch spent 94 minutes to improve one three-mutant method.
///
/// Two problems, one flag. `--no-scan` stops the upload — but Gradle rejects it as an
/// unknown option on a build with no scan plugin applied, so it can only be passed when
/// one is.
class GradleScanTest {

    private static final String DEVELOCITY = """
            plugins {
                id 'com.gradle.develocity' version '4.3'
                id 'org.gradle.toolchains.foojay-resolver-convention' version '1.0.0'
            }

            develocity {
                buildScan {
                    termsOfUseUrl = "https://gradle.com/help/legal-terms-of-use"
                    termsOfUseAgree = "yes"
                    publishing.onlyIf { true }
                }
            }
            """;

    @Test
    void theDevelocityPluginIsRecognised() {
        assertTrue(GradleScan.appliesBuildScan(List.of(DEVELOCITY)));
    }

    @Test
    void soAreItsPredecessorsWhichOlderReposStillUse() {
        assertTrue(GradleScan.appliesBuildScan(List.of("id 'com.gradle.enterprise' version '3.16'")));
        assertTrue(GradleScan.appliesBuildScan(List.of("id 'com.gradle.build-scan' version '3.0'")));
        assertTrue(GradleScan.appliesBuildScan(List.of("apply plugin: \"com.gradle.build-scan\"")));
        assertTrue(GradleScan.appliesBuildScan(List.of("gradleEnterprise {\n  buildScan {\n  }\n}")));
    }

    @Test
    void aBuildWithNoScanPluginIsLeftAlone() {
        // --no-scan on a build that never applies the plugin fails the invocation outright
        assertFalse(GradleScan.appliesBuildScan(List.of("plugins { id 'java'\n id 'jacoco' }")));
        assertFalse(GradleScan.appliesBuildScan(List.of()));
        // a script that is not on disk reads as null, not as an empty file — Arrays.asList
        // rather than List.of because the nulls are the point of the case
        assertFalse(GradleScan.appliesBuildScan(Arrays.asList(null, null, "")));
        assertFalse(GradleScan.appliesBuildScan((List<String>) null));
    }

    @Test
    void theFlagIsOnlyAddedWhereItIsAccepted() {
        assertEquals(List.of("--no-scan"), GradleScan.gradleScanArgs(List.of(DEVELOCITY)));
        assertEquals(List.of(), GradleScan.gradleScanArgs(List.of("plugins { id 'java' }")));
    }

    @Test
    void aSingleScriptNeedsNoWrapping() {
        // the JS took either a list or one string; the overload keeps callers that read one
        // settings.gradle from having to know which
        assertEquals(List.of("--no-scan"), GradleScan.gradleScanArgs(DEVELOCITY));
        assertEquals(List.of(), GradleScan.gradleScanArgs((String) null));
    }

    @Test
    void aCommentMentioningBuildScansDoesNotCountAsApplyingOne() {
        assertFalse(GradleScan.appliesBuildScan(List.of("// we deliberately do not use a buildScan here")),
                "a commented line must not make us pass a flag Gradle would reject");
    }

    // ── the wiring ────────────────────────────────────────────────────────────
    @Test
    void everyGradleInvocationInTheBackendPassesTheScanArgs() throws IOException {
        // The flag is only useful where it is actually used, and the pipeline shells out to
        // Gradle from three places — the suite, the coverage build and PIT. Missing it in one
        // leaves that invocation uploading a scan and blocking on it, which is most of the
        // wall-clock. (A pure-logic test would not have noticed: repo.testFileExists was
        // written, tested through an injected parameter, and never called from anywhere.)
        // Enumerating the files I remembered is how this shipped broken the first time: the
        // guard listed tests, coverage and pit, passed, and install() — the FIRST Gradle
        // command of every run — still had no flag and blocked on the upload. So the whole
        // source tree is scanned, and a new call site cannot opt out by being new.
        String needle = "\"--no-daemon\"";
        Path base = Path.of(System.getProperty("basedir", System.getProperty("user.dir")));
        Path root = Files.isDirectory(base.resolve("src/main/java"))
                ? base.resolve("src/main/java")
                : base.resolve("backend/src/main/java");
        // not an assumption: failing to FIND the sources would silently retire the guard
        assertTrue(Files.isDirectory(root), "main sources not found from " + base);
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(root)) {
            sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        int found = 0;
        for (Path f : sources) {
            for (String line : Files.readString(f).split("\n", -1)) {
                if (!line.contains(needle)) continue;
                found += 1;
                assertTrue(line.contains("scanArgs"),
                        f.getFileName() + ": this Gradle invocation does not pass scanArgs — " + line.trim());
            }
        }
        // A floor, not an assumption. Finding FEWER call sites than the pipeline has is the
        // same failure as finding one without the flag — the guard would pass vacuously and
        // retire itself exactly when a call site was inlined, renamed or dropped. Four is what
        // the run makes: install (Repo), the suite (Tests), the coverage build (Coverage) and
        // PIT (Pit). This must fail, not skip.
        assertTrue(found >= 4, "expected every Gradle call site to be checked, saw " + found);
    }

    @Test
    void detectBuildHandsTheArgsOnAndOnlyForGradle() {
        // detectBuild decides scanArgs ONCE, because it is a property of the repo and every
        // Gradle invocation needs it — and it decides them only for Gradle, since Maven has no
        // such flag to reject.
        //
        // The JS asserted `typeof repo.detectBuild === 'function'`, which is a REACHABILITY
        // check: repo.testFileExists was written and tested and never added to module.exports,
        // and the first live Gradle round died with "repo.testFileExists is not a function".
        // Java's equivalent of not being exported is not being public, so this reads
        // getMethods() (public members, inherited included) and not getDeclaredMethods(), which
        // would go on passing after the method was made private.
        assertTrue(Arrays.stream(Repo.class.getMethods())
                        .filter(m -> m.getParameterCount() == 0)
                        .map(Method::getName).anyMatch("detectBuild"::equals),
                "Repo.detectBuild must stay public — every Gradle call site reads its scanArgs");
        // and only for Gradle: the record carries the args, and Maven's branch leaves them empty
        assertTrue(Arrays.stream(Repo.Build.class.getRecordComponents())
                        .anyMatch(c -> c.getName().equals("scanArgs")),
                "detectBuild's answer must carry scanArgs");
    }
}
