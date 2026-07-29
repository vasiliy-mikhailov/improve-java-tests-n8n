package tech.mikhailov.ijt;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/// Child-process runner with live progress fed into state (for the dashboard).
///
/// Everything this backend does to a target repository happens through here: git, the Maven
/// or Gradle wrapper, PIT, `gh`. Two things matter beyond "start a process and read its
/// output" — the child must not inherit our secrets, and a child that overruns its deadline
/// must be killed along with everything IT started.
public final class Exec {

    private Exec() {}

    /// The JS default for `timeoutMs`. Every real caller passes its own (see {@link Timeouts}).
    public static final long DEFAULT_TIMEOUT_MS = 600_000L;

    /// How often the dashboard hears from a running child.
    static final long HEARTBEAT_MS = 3_000L;

    /// Capture caps, in chars — the JS `4e6` / `2e6`. A 40-minute Gradle build prints far more
    /// than anyone parses, and only the tail of it is ever read.
    static final int MAX_CAPTURE_CHARS = 4_000_000;
    static final int KEEP_CAPTURE_CHARS = 2_000_000;

    /// A progress line is for human eyes on a dashboard, not a log.
    static final int MAX_PROGRESS_LINE = 180;

    /// How long we wait for a killed process tree to actually die, and for its pipes to reach
    /// EOF afterwards. Neither existed in the JS, which simply waited forever for the `close`
    /// event; a grandchild holding the pipe open would hang the HTTP route that called us,
    /// which is precisely the failure {@link Timeouts} exists to prevent.
    static final long KILL_GRACE_MS = 5_000L;
    static final long DRAIN_GRACE_MS = 5_000L;

    /// The JS `/\x1b\[[0-9;]*[a-zA-Z]/g`. Written with the regex escape `\x1b` rather than a
    /// raw escape character in the literal: the compiler swallows the difference, and a
    /// control character nobody can see in the source is a comment nobody can read.
    static final Pattern ANSI = Pattern.compile("\\x1b\\[[0-9;]*[a-zA-Z]");

    /// What a finished child amounts to.
    ///
    /// @param code     exit status, normalised so a child we killed — or one whose status we
    ///                 never read — cannot read as success. Callers judge green/red on
    ///                 `code == 0` and nothing else.
    /// @param timedOut the child was still running at its deadline and was killed for it
    public record Result(int code, String stdout, String stderr, boolean timedOut) {}

    /// The JS options object.
    ///
    /// @param cwd       working directory, or null for the backend's own
    /// @param env       extra variables for the child, applied LAST so they win. A null VALUE
    ///                  removes the variable, the way `undefined` did in the JS spread.
    /// @param timeoutMs absent means {@link #DEFAULT_TIMEOUT_MS}. Boxed deliberately: an
    ///                  explicit 0 is a real instruction ("kill it at once") and a primitive
    ///                  would make it indistinguishable from "not stated".
    /// @param label     stage prefix on the dashboard's progress line, e.g. `pit`
    public record Options(Path cwd, Map<String, String> env, Long timeoutMs, String label) {

        /// Nothing stated — the JS `run(argv)`.
        public static Options of() { return new Options(null, null, null, null); }

        public Options withCwd(Path v) { return new Options(v, env, timeoutMs, label); }

        public Options withEnv(Map<String, String> v) { return new Options(cwd, v, timeoutMs, label); }

        public Options withTimeoutMs(Long v) { return new Options(cwd, env, v, label); }

        public Options withLabel(String v) { return new Options(cwd, env, timeoutMs, v); }

        public long timeoutMsOrDefault() { return timeoutMs == null ? DEFAULT_TIMEOUT_MS : timeoutMs; }
    }

    /// Where the live progress goes: `(line, elapsedSeconds)`.
    @FunctionalInterface
    public interface ProgressSink {
        void progress(String line, long elapsedSec);
    }

    private static final ProgressSink NO_SINK = (line, elapsed) -> {};

    /// The JS imported `setProgress` from state.js directly. Here it is installed instead, for
    /// one reason that is not style: this module is the one place the backend blocks for an
    /// hour, and a test must be able to watch what it reports without a state file on disk.
    /// The default is a no-op, so a run before the state store is wired reports nothing rather
    /// than failing.
    private static volatile ProgressSink sink = NO_SINK;

    /// @return the sink that was installed before, so a caller (or a test) can put it back
    public static ProgressSink setProgressSink(ProgressSink next) {
        ProgressSink previous = sink;
        sink = next == null ? NO_SINK : next;
        return previous;
    }

    /// Run a command (argv list, no shell). Streams the last output line into stage progress.
    public static Result run(List<String> argv) {
        return run(argv, Options.of());
    }

    /// Run a command (argv list, no shell). Streams the last output line into stage progress.
    ///
    /// Never throws for anything the child does — a missing binary, a non-zero status and a
    /// timeout all come back as a {@link Result}, exactly as the JS resolved rather than
    /// rejected. Callers read `code`; making them also catch would lose the output they need
    /// to explain the failure.
    public static Result run(List<String> argv, Options options) {
        Options opts = options == null ? Options.of() : options;
        long start = Util.nowSec();
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        AtomicReference<String> lastLine = new AtomicReference<>("(starting)");
        boolean timedOut = false;

        ProcessBuilder pb = new ProcessBuilder(argv);
        if (opts.cwd() != null) pb.directory(opts.cwd().toFile());
        // ProcessBuilder.environment() starts as a copy of ours — the JS `{ ...process.env }`.
        applyEnv(pb.environment(), opts.env());

        Process child;
        try {
            child = pb.start();
        } catch (IOException e) {
            // The JS 'error' event: resolve with 127 and say why in stderr. `git`/`gh`/a
            // wrapper script that is not installed is a normal outcome on a strange repo, and
            // the caller's own error message is better than a stack trace from here.
            return new Result(127, stdout.toString(), stderr + "\nspawn error: " + e.getMessage(), false);
        }
        // stdio[0] was 'ignore'. Nothing writes to these children, and one that reads stdin
        // must see EOF immediately rather than inherit ours and wait forever for an answer
        // nobody is there to give (Maven asks, on occasion).
        try { child.getOutputStream().close(); } catch (IOException ignored) { /* already gone */ }

        Thread outReader = reader(child.getInputStream(), stdout, lastLine);
        Thread errReader = reader(child.getErrorStream(), stderr, lastLine);
        Thread heartbeat = heartbeat(opts.label(), lastLine, start);

        Integer code = null;
        try {
            if (child.waitFor(opts.timeoutMsOrDefault(), TimeUnit.MILLISECONDS)) {
                code = child.exitValue();
            } else {
                timedOut = true;
                killTree(child);
                if (child.waitFor(KILL_GRACE_MS, TimeUnit.MILLISECONDS)) code = child.exitValue();
            }
        } catch (InterruptedException e) {
            // Shutting down mid-run: take the tree with us rather than leave a PIT run behind,
            // and leave the status unread — which normalises to a failure, not to success.
            Thread.currentThread().interrupt();
            killTree(child);
        }

        // Drain what the child wrote before reporting. The JS resolved on 'close', which fires
        // only once the pipes are closed, so this is the same contract — with a ceiling on it,
        // because a surviving grandchild can hold the pipe open indefinitely.
        joinQuietly(outReader, DRAIN_GRACE_MS);
        joinQuietly(errReader, DRAIN_GRACE_MS);
        heartbeat.interrupt();

        report(doneLine(opts.label(), code), Util.nowSec() - start);
        return new Result(exitCode(code, timedOut), text(stdout), text(stderr), timedOut);
    }

    // ── the decisions, kept out of the IO so they can be tested ────────────────────────────

    /// The environment a child is given: ours, minus the model key, plus the CI markers, plus
    /// whatever the caller adds.
    ///
    /// LLM_API_KEY has no business in a child process: the repo's own install scripts and test
    /// code inherit this environment. GH_TOKEN stays — `gh` and the git credential helper need
    /// it.
    static void applyEnv(Map<String, String> env, Map<String, String> extra) {
        env.remove("LLM_API_KEY");
        env.put("CI", "true");
        env.put("FORCE_COLOR", "0");
        if (extra == null) return;
        for (Map.Entry<String, String> e : extra.entrySet()) {
            // The caller's map is spread LAST in the JS, so it wins even over CI and
            // FORCE_COLOR; a null value removes the variable, as `undefined` did there.
            if (e.getValue() == null) env.remove(e.getKey());
            else env.put(e.getKey(), e.getValue());
        }
    }

    /// The same rules as a value, for anyone who needs to see a child's environment without
    /// starting one.
    static Map<String, String> childEnv(Map<String, String> base, Map<String, String> extra) {
        Map<String, String> env = new LinkedHashMap<>(base);
        applyEnv(env, extra);
        return env;
    }

    /// The newest non-empty line in a chunk of child output, for the dashboard.
    ///
    /// Colour codes are stripped because Maven and Gradle emit them even under FORCE_COLOR=0
    /// when they think a TTY is watching, and an escape sequence rendered into a web page is
    /// noise. A chunk with nothing but blank lines leaves the previous line standing — an
    /// empty progress line reads as "stalled" when the build is merely between phases.
    static String lastLineOf(String chunk, String previous) {
        String out = previous;
        // split(-1) keeps trailing empties, as JS split does; they are skipped below anyway,
        // but the difference is exactly the kind that changes behaviour on a later edit.
        for (String l : chunk.split("\\r?\\n", -1)) {
            // strip() rather than trim(): the JS trim() is Unicode-aware. It is not identical
            // (JS also removes U+00A0, strip() does not), which costs at most a leading space
            // on a display line.
            String c = ANSI.matcher(l).replaceAll("").strip();
            if (!c.isEmpty()) out = c.length() > MAX_PROGRESS_LINE ? c.substring(0, MAX_PROGRESS_LINE) : c;
        }
        return out;
    }

    /// Keep the tail of a runaway log. Only the end of a build log is ever parsed, and holding
    /// all of a multi-hundred-megabyte one costs the whole backend its heap.
    static void capBuffer(StringBuilder sb) {
        if (sb.length() > MAX_CAPTURE_CHARS) sb.delete(0, sb.length() - KEEP_CAPTURE_CHARS);
    }

    /// `label: line`, or just the line. An absent OR empty label adds no prefix — the JS
    /// `label ? label + ': ' : ''` treats `''` as falsy, and `': git status'` helps nobody.
    static String progressLine(String label, String lastLine) {
        return (label == null || label.isEmpty() ? "" : label + ": ") + lastLine;
    }

    /// The line the dashboard is left showing once the child is gone.
    ///
    /// The RAW status on purpose: the JS printed `done (exit null)` for a child that died on a
    /// signal, and that line is the one thing telling a human the run was killed rather than
    /// finished. Normalising to a failure code is the {@link Result}'s job, not this line's.
    static String doneLine(String label, Integer code) {
        return progressLine(label, "done (exit " + code + ")");
    }

    /// A status we could not read, or one belonging to a child we killed, is a FAILURE.
    ///
    /// The JS got half of this free: a child killed by a signal reports a null code, and
    /// `code == null ? 1 : code` turned that into a failure. Java has no null status — a
    /// signalled child reports 128+signal, non-zero already — but it does open a window the JS
    /// never had. Kill a child at its deadline and it may have exited 0 a microsecond earlier;
    /// `waitFor` then hands back 0 and every caller decides green/red on `code == 0`. A run we
    /// cut short did not pass, so it does not get to say it did.
    static int exitCode(Integer raw, boolean timedOut) {
        if (raw == null) return 1;
        if (timedOut && raw == 0) return 1;
        return raw;
    }

    // ── the IO ─────────────────────────────────────────────────────────────────────────────

    /// Kill the child AND everything it started.
    ///
    /// This is the part that does not translate. The JS spawned with `detached: true`, which
    /// puts the child in its own process GROUP, and `process.kill(-pid, 'SIGKILL')` then takes
    /// the entire group in one atomic call. Java has no process-group API at all.
    /// {@link ProcessHandle#descendants()} is the nearest thing and is weaker: it walks parent
    /// links at the moment it is called, so a grandchild whose parent has already exited has
    /// been re-parented and is no longer reachable from us.
    ///
    /// It matters because these children fork: Maven and Gradle fork Surefire, and PIT forks a
    /// minion JVM per unit of work. Orphaning those does not merely leak processes — they go
    /// on burning every core the container has, under the next unit's measurement.
    ///
    /// Hence the order: snapshot the tree while the child still holds it, kill the descendants
    /// first, then the child, then sweep the same snapshot again for anything that survived.
    static void killTree(Process child) {
        List<ProcessHandle> tree = child.descendants().toList();
        tree.forEach(ProcessHandle::destroyForcibly);
        child.destroyForcibly();
        // The second pass reads from the snapshot, not from descendants(): killing the parent
        // re-parents whatever is left, and by now descendants() would return an empty stream
        // while the minions are still running.
        for (ProcessHandle h : tree) if (h.isAlive()) h.destroyForcibly();
    }

    private static Thread reader(InputStream in, StringBuilder buf, AtomicReference<String> lastLine) {
        return Thread.ofVirtual().start(() -> {
            // Decoding lives in the Reader, not in a per-chunk `new String(bytes)`: a UTF-8
            // character split across two pipe reads decodes correctly here, and would decode as
            // two replacement characters if each chunk were decoded on its own.
            char[] cbuf = new char[8192];
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                int n;
                while ((n = r.read(cbuf)) != -1) {
                    String text = new String(cbuf, 0, n);
                    // Both this thread and the caller touch the buffer; the caller only after
                    // joining, but the join has a ceiling and may return with this still live.
                    synchronized (buf) {
                        buf.append(text);
                        capBuffer(buf);
                    }
                    // stdout and stderr race for this the way the JS handlers interleaved. It is
                    // one line of display text; last writer wins, and either is true.
                    lastLine.set(lastLineOf(text, lastLine.get()));
                }
            } catch (IOException e) {
                // the pipe died with the process — there is nothing further to read
            }
        });
    }

    private static Thread heartbeat(String label, AtomicReference<String> lastLine, long start) {
        return Thread.ofVirtual().start(() -> {
            try {
                while (true) {
                    Thread.sleep(HEARTBEAT_MS);
                    report(progressLine(label, lastLine.get()), Util.nowSec() - start);
                }
            } catch (InterruptedException e) {
                // the child is done; the caller writes the closing line itself
            }
        });
    }

    private static void report(String line, long elapsedSec) {
        try {
            sink.progress(line, elapsedSec);
        } catch (RuntimeException e) {
            // A progress line is a courtesy to the dashboard. Losing the output of an hour-long
            // build because the state file could not be written is not a trade worth making.
        }
    }

    private static void joinQuietly(Thread t, long millis) {
        try {
            t.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String text(StringBuilder sb) {
        synchronized (sb) { return sb.toString(); }
    }
}
