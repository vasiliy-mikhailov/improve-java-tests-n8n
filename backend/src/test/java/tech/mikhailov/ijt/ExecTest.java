package tech.mikhailov.ijt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The child-process runner, which is where this backend spends 99% of its wall clock.
///
/// The JS module had no unit test of its own — it was IO, and IO was left to the live run.
/// That is affordable in a language where `spawn(detached)` plus `kill(-pid)` is one obvious
/// call; it is not affordable here, because the two rules that matter (a child inherits no
/// model key; a child that overruns takes its whole tree down with it) are the two rules Java
/// does NOT give for free. So the rules recorded in the comments of exec.js are pinned here.
class ExecTest {

    /// A literal escape character, written as an octal escape so the source shows what it is.
    private static final String ESC = "\033";

    // ── the environment a child is given ───────────────────────────────────────────────────

    @Test
    void theModelKeyNeverReachesAChildProcess() {
        // LLM_API_KEY has no business in a child process: the repo's own install scripts and
        // test code inherit this environment, and they are code we did not write and are about
        // to run. GH_TOKEN stays — `gh` and the git credential helper need it.
        Map<String, String> ours = new HashMap<>(Map.of(
                "LLM_API_KEY", "sk-real-key", "GH_TOKEN", "ghp_real_token", "PATH", "/usr/bin"));

        Map<String, String> child = Exec.childEnv(ours, null);

        assertFalse(child.containsKey("LLM_API_KEY"), "the model key was handed to a child process");
        assertEquals("ghp_real_token", child.get("GH_TOKEN"));
        assertEquals("/usr/bin", child.get("PATH"));
    }

    @Test
    void everyChildRunsUnderTheCiMarkers() {
        // CI=true keeps interactive prompts out of a build nobody is watching; FORCE_COLOR=0
        // keeps escape sequences out of the log we parse and out of the dashboard.
        Map<String, String> child = Exec.childEnv(new HashMap<>(), null);

        assertEquals("true", child.get("CI"));
        assertEquals("0", child.get("FORCE_COLOR"));
    }

    @Test
    void theCallersOwnEnvironmentIsAppliedLastAndWins() {
        // The JS spread the caller's env AFTER the defaults, so a caller can override even the
        // CI markers. Repo.buildEnv() relies on exactly this to pin JAVA_HOME per project —
        // PIT's forked minion crashes under the wrong JDK.
        Map<String, String> extra = new HashMap<>();
        extra.put("JAVA_HOME", "/usr/lib/jvm/java-17");
        extra.put("CI", "false");

        Map<String, String> child = Exec.childEnv(new HashMap<>(Map.of("CI", "stale")), extra);

        assertEquals("/usr/lib/jvm/java-17", child.get("JAVA_HOME"));
        assertEquals("false", child.get("CI"));
    }

    @Test
    void aNullValueInTheCallersEnvironmentRemovesTheVariable() {
        // `{ ...env, SOME_VAR: undefined }` removed the variable in Node, which filters
        // undefined values out of the child's environment. The Java equivalent must remove it
        // too — and must not merely put null, which ProcessBuilder.environment() rejects with a
        // NullPointerException that would kill the run rather than the variable. HashMap, not
        // Map.of, for the same reason: an immutable map cannot hold the null that says "unset".
        Map<String, String> extra = new HashMap<>();
        extra.put("MAVEN_OPTS", null);

        Map<String, String> child = Exec.childEnv(new HashMap<>(Map.of("MAVEN_OPTS", "-Xmx512m")), extra);

        assertFalse(child.containsKey("MAVEN_OPTS"));
    }

    // ── the progress line the dashboard shows ──────────────────────────────────────────────

    @Test
    void theNewestNonEmptyLineIsWhatTheDashboardShows() {
        assertEquals("compiling", Exec.lastLineOf("downloading\ncompiling\n", "(starting)"));
        // The last non-empty one, not the last one: build output ends in a newline, and showing
        // the empty string after it reads as a stalled build.
        assertEquals("compiling", Exec.lastLineOf("compiling\n\n\n", "(starting)"));
    }

    @Test
    void aChunkWithNothingInItLeavesThePreviousLineStanding() {
        // A build between phases writes blank lines. Wiping the display for them would report a
        // stall that is not happening.
        assertEquals("still here", Exec.lastLineOf("\n \n\t\n", "still here"));
        assertEquals("still here", Exec.lastLineOf("", "still here"));
    }

    @Test
    void colourCodesAreStrippedOutOfTheProgressLine() {
        // Maven and Gradle colour their output even under FORCE_COLOR=0 when they believe a TTY
        // is watching. An escape sequence rendered into a web page is noise at best.
        assertEquals("BUILD SUCCESSFUL",
                Exec.lastLineOf(ESC + "[32mBUILD SUCCESSFUL" + ESC + "[0m\n", "(starting)"));
        assertEquals("Tests run: 1163", Exec.lastLineOf(ESC + "[1;33mTests run: 1163" + ESC + "[m", "x"));
    }

    @Test
    void windowsLineEndingsSplitLinesToo() {
        // Gradle on any host, and a good deal of Maven plugin output, ends lines with CRLF. A
        // split on "\n" alone leaves a trailing CR that trim() happens to remove — but the JS
        // split on /\r?\n/ is what the port has to match, not what trim() rescues.
        assertEquals("second", Exec.lastLineOf("first\r\nsecond\r\n", "(starting)"));
    }

    @Test
    void anEnormousLineIsCutToSomethingAHumanCanRead() {
        // A javac error can carry a whole classpath on one line. The dashboard has one row.
        String huge = "e".repeat(500);
        assertEquals(180, Exec.lastLineOf(huge, "x").length());
    }

    @Test
    void theLabelPrefixesTheLineAndAnEmptyLabelDoesNot() {
        assertEquals("pit: running", Exec.progressLine("pit", "running"));
        assertEquals("running", Exec.progressLine(null, "running"));
        // `label ? ... : ''` in the JS: an empty string is falsy there, so it added no prefix.
        // A Java null-check alone would emit ": running", which names no stage at all.
        assertEquals("running", Exec.progressLine("", "running"));
    }

    @Test
    void theClosingLineCarriesTheRawStatusIncludingNone() {
        assertEquals("tests: done (exit 0)", Exec.doneLine("tests", 0));
        assertEquals("done (exit 1)", Exec.doneLine(null, 1));
        // The JS printed `done (exit null)` for a child that died on a signal, and that line is
        // the one thing that tells a human the run was killed rather than finished. The
        // normalisation to a failure code belongs to the Result, not to this line.
        assertEquals("pit: done (exit null)", Exec.doneLine("pit", null));
    }

    // ── the exit status ────────────────────────────────────────────────────────────────────

    @Test
    void aStatusWeNeverReadIsAFailure() {
        // `code == null ? 1 : code`. Every caller decides green/red on `code === 0`, so an
        // unknown status that defaulted to 0 would report a suite as passing that never ran.
        assertEquals(1, Exec.exitCode(null, false));
        assertEquals(1, Exec.exitCode(null, true));
    }

    @Test
    void aRunWeCutShortNeverReportsSuccess() {
        // Java, unlike Node, hands back a real status for a killed child (128+signal), and
        // there is a window Node did not have: the child can exit 0 a microsecond before the
        // kill lands, and `waitFor` then reports that 0. A run we killed at its deadline did
        // not pass, whatever status the race left behind.
        assertEquals(1, Exec.exitCode(0, true));
        assertEquals(137, Exec.exitCode(137, true));
    }

    @Test
    void anOrdinaryStatusIsPassedThroughUntouched() {
        assertEquals(0, Exec.exitCode(0, false));
        assertEquals(1, Exec.exitCode(1, false));
        assertEquals(127, Exec.exitCode(127, false));
    }

    // ── the capture buffer ─────────────────────────────────────────────────────────────────

    @Test
    void aRunawayLogIsCutDownToItsTailNotItsHead() {
        // `stdout.slice(-2e6)`: the END of a build log holds the failures, the counts and the
        // PIT summary. Keeping the beginning would keep dependency resolution and throw away
        // everything the caller parses.
        StringBuilder sb = new StringBuilder("a".repeat(Exec.MAX_CAPTURE_CHARS + 10));
        sb.replace(sb.length() - 3, sb.length(), "END");

        Exec.capBuffer(sb);

        assertEquals(Exec.KEEP_CAPTURE_CHARS, sb.length());
        assertTrue(sb.toString().endsWith("END"), "the tail was thrown away instead of the head");
    }

    @Test
    void aLogThatFitsIsLeftExactlyAsItIs() {
        // The JS trimmed on `> 4e6`, so a log of exactly the cap survives whole. Trimming at
        // `>=` would silently halve an output that was never too big.
        StringBuilder sb = new StringBuilder("a".repeat(Exec.MAX_CAPTURE_CHARS));
        Exec.capBuffer(sb);
        assertEquals(Exec.MAX_CAPTURE_CHARS, sb.length());

        StringBuilder small = new StringBuilder("BUILD SUCCESSFUL");
        Exec.capBuffer(small);
        assertEquals("BUILD SUCCESSFUL", small.toString());
    }

    // ── the options ────────────────────────────────────────────────────────────────────────

    @Test
    void anUnstatedTimeoutIsTenMinutesAndAnExplicitZeroIsZero() {
        assertEquals(600_000L, Exec.Options.of().timeoutMsOrDefault());
        assertEquals(600_000L, Exec.DEFAULT_TIMEOUT_MS);
        // Boxed on purpose. A primitive long would make "not stated" and "kill it at once"
        // the same value, and the JS destructuring default fires only on undefined.
        assertEquals(0L, Exec.Options.of().withTimeoutMs(0L).timeoutMsOrDefault());
        assertEquals(Timeouts.SUITE_RUN_MS, Exec.Options.of().withTimeoutMs(Timeouts.SUITE_RUN_MS).timeoutMsOrDefault());
    }

    // ── actually running things ────────────────────────────────────────────────────────────

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void stdoutAndStderrAreKeptApart() {
        // Both are parsed, and by different rules: a green Maven build writes its counts to
        // stdout while the wrapper writes JVM warnings to stderr. Merging them was how a
        // warning once read as a test failure.
        Exec.Result r = sh("echo out; echo err 1>&2");

        assertEquals(0, r.code());
        assertFalse(r.timedOut());
        assertTrue(r.stdout().contains("out"), r.stdout());
        assertFalse(r.stdout().contains("err"), r.stdout());
        assertTrue(r.stderr().contains("err"), r.stderr());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aFailingCommandComesBackAsAResultRatherThanAnException() {
        // The JS resolved, never rejected. A red suite is the normal case here — it is the
        // gate every round runs through — and it must arrive with its output attached.
        Exec.Result r = sh("echo nearly; exit 3");

        assertEquals(3, r.code());
        assertTrue(r.stdout().contains("nearly"));
    }

    @Test
    void aMissingBinaryIsExitOneTwoSevenAndSaysSoInStderr() {
        // A repo with no wrapper, a host with no `gh`. ProcessBuilder.start() throws where Node
        // emitted an 'error' event; either way the caller gets a result it can report, not an
        // exception that ends the route.
        Exec.Result r = Exec.run(List.of("ijt-definitely-not-a-real-binary"));

        assertEquals(127, r.code());
        assertTrue(r.stderr().contains("spawn error:"), r.stderr());
        assertFalse(r.timedOut());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void theChildRunsWhereItWasTold(@TempDir Path tmp) throws IOException {
        // Every git and build invocation depends on this: the backend's own working directory
        // is not the cloned repo.
        Exec.Result r = Exec.run(List.of("/bin/sh", "-c", "pwd"),
                Exec.Options.of().withCwd(tmp).withTimeoutMs(30_000L));

        assertEquals(0, r.code());
        // toRealPath: the temp dir is reached through a symlink on macOS (/var → /private/var)
        // and the child reports the physical path.
        assertEquals(tmp.toRealPath().toString(), r.stdout().strip());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void theChildReallyReceivesTheEnvironmentWeComposed() {
        Map<String, String> extra = new HashMap<>();
        extra.put("IJT_MARKER", "present");
        extra.put("IJT_REMOVED", null);   // must not reach ProcessBuilder as a null value

        Exec.Result r = Exec.run(
                List.of("/bin/sh", "-c", "echo \"$CI/$FORCE_COLOR/$IJT_MARKER/${IJT_REMOVED:-gone}\""),
                Exec.Options.of().withEnv(extra).withTimeoutMs(30_000L));

        assertEquals(0, r.code());
        assertEquals("true/0/present/gone", r.stdout().strip());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aChildThatReadsStdinSeesEndOfFileRatherThanOurs() {
        // stdio[0] was 'ignore'. A child that inherited the backend's stdin and then asked a
        // question would wait for an answer nobody is there to give, for the full hour its
        // timeout allows.
        Exec.Result r = sh("cat");

        assertEquals(0, r.code());
        assertFalse(r.timedOut());
        assertEquals("", r.stdout());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aTimeoutKillsTheChildAndEverythingItForked(@TempDir Path tmp) throws Exception {
        // THE reason this module exists. Maven and Gradle fork Surefire; PIT forks a minion JVM
        // per unit of work. Killing only the process we started leaves those running — and they
        // do not idle, they saturate every core in the container, under the NEXT unit's
        // measurement, for as long as the run lasts.
        //
        // The JS had this for nothing: detached:true put the child in its own process group and
        // `process.kill(-pid)` took the group. Java has no process-group API, so the tree is
        // walked by hand, and this test is what says the walk actually reaches a grandchild.
        Path pidFile = tmp.resolve("grandchild.pid");
        Exec.Result r = Exec.run(
                List.of("/bin/sh", "-c", "sleep 60 & echo $! > '" + pidFile + "'; sleep 60"),
                Exec.Options.of().withTimeoutMs(1_000L).withLabel("pit"));

        assertTrue(r.timedOut(), "the run outlived its deadline without being marked as timed out");
        assertNotEquals(0, r.code(), "a killed run reported success");

        long grandchild = Long.parseLong(Files.readString(pidFile).strip());
        assertFalse(aliveWithin(grandchild, 5_000L),
                "pid " + grandchild + " survived the kill — this is the orphaned Surefire/PIT minion");
    }

    /// A KNOWN GAP, pinned so it is visible rather than discovered during an incident.
    ///
    /// `killTree` says in its own comment that `descendants()` "walks parent links at the moment
    /// it is called, so a grandchild whose parent has already exited has been re-parented and is
    /// no longer reachable from us". This asserts that this is in fact what happens. It documents
    /// current behaviour; it does NOT endorse it.
    ///
    /// Measured directly: fork a grandchild, let its parent exit, kill the outer process — the
    /// grandchild is not among the outer's children and survives. In production that is a PIT
    /// minion whose Maven parent finished, still holding every core it had, under the next unit's
    /// measurement.
    ///
    /// It has never fired: no run has timed out, so this path has only ever executed in tests.
    /// That is luck, not a design, because PIT runs have a one-hour ceiling and hit it eventually.
    ///
    /// THE FIX, when someone takes it: give the child its own process group and signal the group,
    /// which is exactly what the JS got free from `detached: true` plus `kill(-pid)`. On Linux
    /// that is `setsid --wait` in front of the argv and `kill -9 -<pid>` on timeout. The trap to
    /// avoid: plain `setsid` without `--wait` returns as soon as it has forked, which would break
    /// exit-code capture and output streaming for every child this module runs — Maven, PIT, git.
    /// So it needs `--wait`, a probe for whether the flag exists, and a fallback to today's
    /// behaviour where it does not (macOS has no setsid at all).
    ///
    /// When it is fixed, this test flips to asserting the orphan dies.
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void anOrphanedGrandchildOutlivesTheKill_knownGap(@TempDir Path tmp) throws Exception {
        Path pidFile = tmp.resolve("orphan.pid");
        // the inner shell forks a sleeper and EXITS, so the sleeper is re-parented away from us
        Exec.Result r = Exec.run(
                List.of("/bin/sh", "-c",
                        "/bin/sh -c 'sleep 45 & echo $! > \"" + pidFile + "\"' ; sleep 45"),
                Exec.Options.of().withTimeoutMs(1_500L).withLabel("pit"));

        assertTrue(r.timedOut());
        long orphan = Long.parseLong(Files.readString(pidFile).strip());
        try {
            assertTrue(aliveWithin(orphan, 1_500L),
                    "the orphan died — descendants() reached it after all, and this gap is closed:"
                            + " flip this assertion and delete the note above");
        } finally {
            // never leave a 45-second sleeper behind for the next test to trip over
            ProcessHandle.of(orphan).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void theDashboardIsToldHowTheRunEnded() {
        List<String> lines = new CopyOnWriteArrayList<>();
        Exec.ProgressSink previous = Exec.setProgressSink((line, elapsed) -> lines.add(line));
        try {
            Exec.run(List.of("/bin/sh", "-c", "exit 4"), Exec.Options.of().withLabel("tests"));
        } finally {
            Exec.setProgressSink(previous);
        }

        assertEquals(List.of("tests: done (exit 4)"), lines);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aSlowChildIsHeardFromWhileItIsStillRunning() {
        // A stage that reports nothing for forty minutes is indistinguishable from a stage that
        // has hung, which is how a dead run went unnoticed for 6h47m. The heartbeat is the whole
        // difference, so it is worth the four seconds this test costs.
        List<String> lines = new CopyOnWriteArrayList<>();
        List<Long> elapsed = new CopyOnWriteArrayList<>();
        Exec.ProgressSink previous = Exec.setProgressSink((line, sec) -> { lines.add(line); elapsed.add(sec); });
        try {
            Exec.run(List.of("/bin/sh", "-c", "echo compiling; sleep " + (Exec.HEARTBEAT_MS / 1000 + 1)),
                    Exec.Options.of().withLabel("pit").withTimeoutMs(60_000L));
        } finally {
            Exec.setProgressSink(previous);
        }

        assertTrue(lines.size() >= 2, "nothing was reported before the run finished: " + lines);
        assertEquals("pit: compiling", lines.get(0), "the live line is the child's newest output");
        assertEquals("pit: done (exit 0)", lines.get(lines.size() - 1));
        // The heartbeat reports how long the stage has been running, not the wall clock.
        assertTrue(elapsed.get(0) >= 2, "reported elapsed " + elapsed.get(0) + "s");
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────

    private static Exec.Result sh(String script) {
        return Exec.run(List.of("/bin/sh", "-c", script), Exec.Options.of().withTimeoutMs(30_000L));
    }

    /// Is the process still there? Polled, because a kill is asynchronous: the signal is
    /// delivered at once but the exit and its reaping are not.
    private static boolean aliveWithin(long pid, long budgetMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + budgetMs;
        while (System.currentTimeMillis() < deadline) {
            // An absent handle means the pid is gone entirely, which is as dead as it gets.
            if (!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) return false;
            Thread.sleep(50);
        }
        return true;
    }
}
