package tech.mikhailov.ijt;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Commit, push and PR creation. Two modes:
///   - github — push branch + `gh pr create` (repos the team owns)
///   - local  — record branch + patch + PR payload under /data/prs (third-party repos)
///
/// ## The seams
///
/// The JS module reaches into three others: `exec.run` for every child process, `state` for
/// the run's config (`prMode`, `prBase`, `dryRun`), the event log and the PR ledger, and
/// `repo.repoDir()` for the working copy. All of that is {@link Io} here, for the same reason
/// {@link Tests} and {@link Coverage} do it: what this module DECIDES — which paths may be
/// committed, what `git status -z` actually said, whether `gh` reported an existing PR — is
/// then a static function over its arguments, and is tested as one, without a repository or a
/// GitHub token. Only {@link Cleanup#worthCommitting} is imported directly, exactly as the JS
/// imports it: it is a decision this module asks another module to make, not a seam.
public final class Pr {

    private Pr() {}

    // ── how long each command gets ─────────────────────────────────────────────────────────
    // pr.js's own literals, named. They are deliberately NOT in Timeouts.SUBPROCESS_CEILING_MS:
    // that table covers the three routes whose n8n node has to outlive a whole build, and none
    // of these is one. The numbers still matter — `git push --force` to a large repo really does
    // take minutes, and a diff of a branch with dozens of committed rounds is not instant.

    static final long GIT_MS = 30_000L;          // status, rev-list, add
    static final long DIFF_MS = 60_000L;         // a whole-branch diff
    static final long COMMIT_MS = 60_000L;
    static final long REV_PARSE_MS = 10_000L;
    static final long PUSH_MS = 120_000L;
    static final long GH_CREATE_MS = 60_000L;
    static final long GH_EDIT_MS = 30_000L;

    // ── the world ──────────────────────────────────────────────────────────────────────────

    /// One finished child process, as the exec wrapper reports it.
    ///
    /// `timedOut` is never read here — {@link Exec} has already normalised a killed child to a
    /// non-zero code, and every decision below is made on `code` alone — but it is carried so
    /// that one server-side adapter can serve this module and {@link Coverage} alike.
    public record Proc(int code, String stdout, String stderr, boolean timedOut) {}

    /// What this module needs from the rest of the backend, in one place.
    ///
    /// Maps onto the Node sidecar one for one: `repoDir()` → repo.repoDir(), `dataDir()` →
    /// state.DATA_DIR, `prBase()`/`prMode()`/`dryRun()` → state.run.config, `run(...)` →
    /// exec.run(argv, {cwd: repoDir, timeoutMs, label}), `event(...)` → state.event,
    /// `recordPr(...)` → state.prs.push.
    public interface Io {

        /// The checkout the run is improving. Every command below runs in it, and the working
        /// tree paths this module reads and deletes are relative to it.
        Path repoDir();

        /// DATA_DIR — the prepared-PR directory `prs/` is made under it. Purge deletes patches
        /// by exactly that path, so the two must agree (see {@link Purge#DEFAULT_PRS_DIR}).
        Path dataDir();

        /// The branch a PR opens against, and what a branch diff is taken against.
        String prBase();

        /// `github` or `local`. Anything else is local — the JS compares for equality with
        /// 'github' and everything else falls to the else branch.
        String prMode();

        /// A dry run pushes nothing and calls no `gh`; it still writes the patch and payload,
        /// so the deliverable can be inspected before a real run.
        boolean dryRun();

        /// Spawn a child in {@link #repoDir()} and wait for it.
        ///
        /// The working directory is baked in because every call in this module passes the same
        /// one, and a call site that could pass a different one could forget to pass any.
        ///
        /// @param timeoutMs after which the whole process GROUP is killed
        /// @param label     what the dashboard's progress line calls this, or null for none
        Proc run(List<String> argv, long timeoutMs, String label);

        /// Append one line to the event log the dashboard polls.
        void event(String stage, String msg);

        /// `state.prs.push(record)` — the run's PR ledger. `GET /api/state` serves it and the
        /// dashboard renders each entry's url, or its patch path when there is no url.
        void recordPr(Record record);
    }

    /// {@link Exec}, adapted to {@link Io#run} — the one line a server-side `Io` needs.
    public static Proc exec(List<String> argv, Path cwd, long timeoutMs, String label) {
        Exec.Result r = Exec.run(argv, Exec.Options.of()
                .withCwd(cwd)
                .withTimeoutMs(timeoutMs)
                .withLabel(label));
        return new Proc(r.code(), r.stdout(), r.stderr(), r.timedOut());
    }

    // ── what changed in the working tree ───────────────────────────────────────────────────

    /// Everything `git status` reports as changed, repo-relative.
    public static List<String> changedFiles(Io io) {
        // -z + --porcelain: NUL-separated, unquoted paths (survives spaces); renames
        // emit "R  new\0old\0" — we keep the new path and skip the old one.
        Proc r = io.run(List.of("git", "status", "--porcelain", "-z"), GIT_MS, null);
        // No exit-status check, as in the JS: a `git status` that failed prints nothing, and
        // "nothing changed" is the answer every caller then acts on safely.
        return parsePorcelainZ(r.stdout());
    }

    /// What `-z` separates records with. Written as an escape and not as the character itself:
    /// a NUL byte in a source file is a byte nobody reading the file can see, and it makes the
    /// file binary as far as every tool that greps it is concerned.
    private static final String NUL = "\0";

    /// The parse, without the process — see {@link #changedFiles}.
    static List<String> parsePorcelainZ(String stdout) {
        // split(-1) keeps the empty field the final NUL leaves behind, exactly as JS split does.
        // Those are dropped below with every other empty, but the INDEX arithmetic that skips a
        // rename's source path counts fields — and Java's default split renumbers them silently.
        String[] parts = (stdout == null ? "" : stdout).split(NUL, -1);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            String rec = parts[i];
            if (rec.isEmpty()) continue;
            char x = rec.charAt(0);                     // `xy[0]`, the index status
            // JS slice(3) answers "" for a record shorter than that, where substring(3) throws.
            // A truncated record is dropped by the empty filter below, as it is there.
            out.add(rec.length() > 3 ? rec.substring(3) : "");
            if (x == 'R' || x == 'C') i += 1;           // consume the source path
        }
        return out.stream().filter(s -> !s.isEmpty()).toList();
    }

    /// Test files this branch touched — committed rounds AND uncommitted edits.
    public static List<String> changedTestFiles(Io io) {
        Proc committed = io.run(List.of("git", "diff", "--name-only", io.prBase() + "...HEAD"), GIT_MS, null);
        return testFilesIn(committed.stdout(), changedFiles(io));
    }

    /// The union of the two lists, first-seen order, keeping only committable tests.
    ///
    /// Committed names are trimmed and working-tree names are not, which is what the JS does:
    /// `git diff --name-only` is newline-separated text, and `git status -z` has already given
    /// us exact bytes that a trim could only damage — a file name may legally end in a space.
    static List<String> testFilesIn(String committedNames, List<String> working) {
        // insertion-ordered: committed rounds first, then what is still uncommitted
        Set<String> all = new LinkedHashSet<>();
        for (String s : (committedNames == null ? "" : committedNames).split("\n", -1)) {
            String t = s.strip();
            if (!t.isEmpty()) all.add(t);
        }
        if (working != null) {
            for (String s : working) if (s != null && !s.isEmpty()) all.add(s);
        }
        return all.stream().filter(Pr::isCommittableTest).toList();
    }

    // ── the diff ───────────────────────────────────────────────────────────────────────────

    /// Build output and the wiring this pipeline injects, as git pathspecs.
    ///
    /// `:!x` excludes. pom.xml carries the PIT/JaCoCo plugin block this pipeline adds and
    /// `.ijt-*` is the Gradle init script it drops in the repo root; a diff showing either
    /// would put our own scaffolding in front of a reviewer as part of the proposed change.
    private static final List<String> EXCLUDES =
            List.of(":!target", ":!build", ":!.gradle", ":!.ijt-*", ":!pom.xml", ":!**/pom.xml");

    /// The diff since a specific commit — what THIS unit added, not what the whole branch
    /// holds. A branch is per FILE and a unit is per METHOD, so the second method of a class
    /// inherits the first method's committed tests, and a whole-branch diff counted them
    /// again: one unit's timesheet claimed four test cases for the one test it wrote.
    public static String diffSince(Io io, String sha) {
        // Falsy, not merely null: a unit whose startSha was never recorded arrives with an
        // empty string, and `git diff ""` is not a diff against the base — it is an error.
        if (sha == null || sha.isEmpty()) return diffAgainstBase(io);
        return diffAgainst(io, sha);
    }

    public static String diffAgainstBase(Io io) {
        return diffAgainst(io, io.prBase());
    }

    private static String diffAgainst(Io io, String rev) {
        // intent-to-add new test files so they show up in the diff
        List<String> newTests = pruneEmptyTests(io, onlyCommittableTests(changedFiles(io)));
        if (!newTests.isEmpty()) io.run(argv(List.of("git", "add", "-N", "--"), newTests), GIT_MS, null);
        return io.run(argv(List.of("git", "diff", rev, "--"), EXCLUDES), DIFF_MS, null).stdout();
    }

    // ── which files may be committed ───────────────────────────────────────────────────────

    /// Only Java test sources are ever committed: never the PIT/JaCoCo wiring we inject into
    /// pom.xml or the init scripts, and never build output.
    ///
    /// `\z` and not `$`: Java's `$` matches before a trailing line terminator as well as at the
    /// end of input, where JS's `$` without /m matches only the end. A path that still had its
    /// line ending attached would pass this gate and reach `git add` as a file that is not there.
    private static final Pattern TEST_PATH = Pattern.compile("(^|/)src/test/java/.+\\.java\\z");
    private static final Pattern ARTIFACT = Pattern.compile("(^|/)(target|build|\\.gradle|\\.ijt-)");

    /// find(), not matches(): both patterns are anchored where they mean to be — `(^|/)` at a
    /// path segment boundary — and say nothing about the rest of the string. A whole-string
    /// match, the obvious-looking translation of JS `.test()`, would reject every real path.
    public static boolean isCommittableTest(String p) {
        // JS coerces null to the string "null", which matches neither pattern
        if (p == null) return false;
        return TEST_PATH.matcher(p).find() && !ARTIFACT.matcher(p).find();
    }

    private static List<String> onlyCommittableTests(List<String> paths) {
        return paths.stream().filter(Pr::isCommittableTest).toList();
    }

    /// Test files with something in them. An empty or test-less file is dead weight in a PR —
    /// a zero-byte generated test reached a prepared patch as a new file with an empty blob —
    /// so it is removed from the tree rather than carried into the deliverable.
    public static List<String> pruneEmptyTests(Io io, List<String> paths) {
        Path dir = io.repoDir();
        List<String> kept = new ArrayList<>();
        for (String rel : paths == null ? List.<String>of() : paths) {
            String content;
            try {
                // Decoded the way node's 'utf8' decodes it: leniently. Files.readString REPORTS a
                // malformed byte by throwing, which would send a real generated test down the
                // "cannot read it" path below and leave it in the tree, unexamined, for the commit.
                content = new String(Files.readAllBytes(dir.resolve(rel)), StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                continue;   // cannot read it: not ours to keep, and not ours to delete
            }
            if (Cleanup.worthCommitting(content)) {
                kept.add(rel);
                continue;
            }
            try {
                Files.deleteIfExists(dir.resolve(rel));
            } catch (IOException e) {
                // already gone
            }
            io.event("preparing_pr", "dropped " + rel + ": " + dropReason(content));
        }
        return kept;
    }

    /// Why a file was dropped — the two cases are worth telling apart in the event log: an
    /// empty file is a completion that produced nothing, a file with text but no `@Test` is a
    /// model that answered with prose.
    static String dropReason(String content) {
        // strip(), the nearest thing to JS trim(): both are Unicode-aware, and a file of nothing
        // but whitespace has to read as empty rather than as a test-less class
        return (content != null && !content.strip().isEmpty()) ? "no test methods in it" : "empty file";
    }

    // ── committing ─────────────────────────────────────────────────────────────────────────

    /// @param sha the commit the branch now points at
    public record Committed(String sha) {}

    private static final Pattern NOTHING_TO_COMMIT = Pattern.compile("nothing to commit");

    /// Did `git commit` actually fail?
    ///
    /// A non-zero status that only says "nothing to commit" is not a failure: the round's tests
    /// were already committed by an earlier round on this branch, and the PR still has
    /// something to open. Both streams are searched because git writes that line to stdout and
    /// its errors to stderr.
    static boolean commitFailed(Proc r) {
        if (r.code() == 0) return false;
        return !NOTHING_TO_COMMIT.matcher(nullToEmpty(r.stdout()) + nullToEmpty(r.stderr())).find();
    }

    /// `parseInt(stdout.trim(), 10) > 0` over `git rev-list --count base..HEAD`.
    ///
    /// A count we could not read is NaN in JS, and `NaN > 0` is false — so a `rev-list` that
    /// failed means "no commits ahead" and the caller refuses to open a PR. `Integer.parseInt`
    /// would throw on the same input and end the route with a stack trace instead.
    static boolean aheadOfBase(String stdout) {
        String s = nullToEmpty(stdout).strip();
        int i = 0;
        if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
        int firstDigit = i;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
        if (i == firstDigit) return false;                  // NaN
        try {
            return Long.parseLong(s.substring(0, i)) > 0;
        } catch (NumberFormatException e) {
            return s.charAt(0) != '-';                      // more commits than a long holds
        }
    }

    public static Committed commit(Io io, String message) {
        // Commit ONLY test sources — never the injected PIT plugin block in pom.xml, the
        // .ijt-* init scripts or target/build output; committing those poisons the base branch.
        List<String> changed = changedFiles(io);
        List<String> testish = pruneEmptyTests(io, onlyCommittableTests(changed));
        if (testish.isEmpty()) {
            // rounds may already be committed on this branch — that's fine for PR creation
            Proc ahead = io.run(List.of("git", "rev-list", "--count", io.prBase() + "..HEAD"), GIT_MS, null);
            if (aheadOfBase(ahead.stdout())) return new Committed(head(io));
            // The wording is a contract: /api/pr/create matches /no changed test files/ on this
            // message to turn a unit that improved nothing into a skipped PR rather than a
            // failed run. Rewording it turns every such unit into a 500.
            throw new IllegalStateException("no changed test files to commit");
        }
        io.run(argv(List.of("git", "add", "--"), testish), GIT_MS, null);
        // --no-verify: repo pre-commit hooks (husky lint etc.) must not abort the
        // pipeline mid-run — the PR's own CI still judges the final result
        Proc r = io.run(List.of("git", "commit", "--no-verify", "-m", message), COMMIT_MS, null);
        if (commitFailed(r)) {
            throw new IllegalStateException("git commit failed: " + tail(preferStderr(r), 300));
        }
        return new Committed(head(io));
    }

    /// `git rev-parse HEAD`, trimmed. The status is not checked, exactly as in the JS: this
    /// runs immediately after a successful commit, or on a branch we have just counted commits
    /// on, so a failure here would mean the repository itself is gone.
    private static String head(Io io) {
        return io.run(List.of("git", "rev-parse", "HEAD"), REV_PARSE_MS, null).stdout().strip();
    }

    // ── the pull request ───────────────────────────────────────────────────────────────────

    /// What the caller asks for. `labels` may be null — the JS parameter defaults to `[]`.
    ///
    /// `title` and `body` are handed to `gh` as arguments, so neither may be null: the JS
    /// throws a TypeError from child_process on an undefined argv entry, and the route that
    /// calls this passes `body.body || ''` for exactly that reason.
    public record Request(String file, String branch, String title, String body, List<String> labels) {}

    /// One prepared or opened PR — the object pushed onto `state.prs` and, in local mode,
    /// written beside the patch.
    ///
    /// The component order IS the JSON key order of the payload file, which is the Node
    /// sidecar's insertion order. `url` and `patchPath` are nullable and start null: exactly
    /// one of them is filled in, and which one says whether the PR was opened or prepared.
    ///
    /// @param createdAt milliseconds, and serialised as a plain integer — `Date.now()`. A
    ///                  double here would have reached the file as 1.7e12.
    public record Record(String file, String branch, String title, String body, List<String> labels,
                         String mode, long createdAt, String url, String patchPath) {

        Record withUrl(String v) {
            return new Record(file, branch, title, body, labels, mode, createdAt, v, patchPath);
        }

        Record withPatchPath(String v) {
            return new Record(file, branch, title, body, labels, mode, createdAt, url, v);
        }
    }

    private static final Pattern PR_URL = Pattern.compile("https://github\\.com/[^\\s\"]+/pull/\\d+");
    private static final Pattern ALREADY_EXISTS = Pattern.compile("already exists", Pattern.CASE_INSENSITIVE);

    /// The PR url `gh` printed, or null.
    ///
    /// stdout AND stderr are searched by the caller, because `gh` writes the url to one and its
    /// warnings to the other and which is which has moved between versions.
    static String prUrlIn(String said) {
        Matcher m = PR_URL.matcher(nullToEmpty(said));
        return m.find() ? m.group() : null;     // the JS `?.[0] || null`
    }

    /// `gh pr create` refusing because the branch already has a PR — the normal case on a
    /// second attempt for the same unit, and not an error.
    static boolean alreadyExists(String said) {
        return ALREADY_EXISTS.matcher(nullToEmpty(said)).find();
    }

    /// The patch file's name: the branch, with everything a file name cannot carry replaced.
    ///
    /// A global replace, so EVERY run of unsafe characters collapses to one dash.
    /// `replaceFirst` would leave the slash of `tests/improve-…` in the middle of the name and
    /// write the patch into a directory that does not exist.
    static String slugOf(String branch) {
        return branch.replaceAll("[^a-zA-Z0-9._-]+", "-");
    }

    public static Record createPr(Io io, Request request) {
        // `labels = []` — the JS default parameter, which fires on undefined and on nothing else
        List<String> labels = request.labels() == null ? List.of() : List.copyOf(request.labels());
        String branch = request.branch();
        Record record = new Record(request.file(), branch, request.title(), request.body(), labels,
                io.prMode(), System.currentTimeMillis(), null, null);

        if ("github".equals(io.prMode()) && !io.dryRun()) {
            Proc r = io.run(List.of("git", "push", "--force", "--set-upstream", "origin", branch, "--no-verify"),
                    PUSH_MS, "git push");
            if (r.code() != 0) throw new IllegalStateException("git push failed: " + tail(preferStderr(r), 400));

            r = io.run(List.of("gh", "pr", "create", "--base", io.prBase(), "--head", branch,
                    "--title", request.title(), "--body", request.body()), GH_CREATE_MS, "gh pr create");
            String said = nullToEmpty(r.stdout()) + nullToEmpty(r.stderr());
            String url = prUrlIn(said);
            if (url == null && alreadyExists(said)) {
                Proc v = io.run(List.of("gh", "pr", "view", branch, "--json", "url", "-q", ".url"), GH_EDIT_MS, null);
                url = emptyToNull(nullToEmpty(v.stdout()).strip());
                // keep PR fresh
                io.run(List.of("gh", "pr", "edit", branch, "--title", request.title(), "--body", request.body()),
                        GH_EDIT_MS, null);
            }
            // Reported against the CREATE call's output even when the view/edit pair has just
            // run — that is the output naming the reason, and the JS keeps the same `r`.
            if (url == null) throw new IllegalStateException("gh pr create failed: " + tail(preferStderr(r), 400));
            for (String label : labels) {
                // One call per label, as the JS loops. `--add-label a,b` would be a single call,
                // but a label the repository does not carry fails it — and takes the rest with it.
                io.run(List.of("gh", "pr", "edit", branch, "--add-label", label), GH_EDIT_MS, null);
            }
            record = record.withUrl(url);
        } else {
            // local mode: keep the branch, save patch + PR payload as the deliverable
            Path prDir = io.dataDir().resolve("prs");
            mkdirs(prDir);
            String slug = slugOf(branch);
            String patch = diffAgainstBase(io);
            Path patchPath = prDir.resolve(slug + ".patch");
            write(patchPath, patch);
            // NOTE the order, which is the Node sidecar's: the payload is serialised BEFORE
            // patchPath is assigned, so the file on disk carries `"patchPath": null` while the
            // record in state.prs carries the path. Preserved deliberately — every prepared PR
            // in /data/prs has this shape, and the shape is what a reader (or a later tool)
            // would be written against.
            write(prDir.resolve(slug + ".json"), stringify(record));
            record = record.withPatchPath(patchPath.toString());
        }

        io.recordPr(record);
        io.event("preparing_pr", record.url() != null
                ? "PR created: " + record.url()
                : "PR prepared locally: " + record.patchPath());
        return record;
    }

    // ── the payload file ───────────────────────────────────────────────────────────────────

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /// `JSON.stringify(record, null, 2)`, to the character.
    ///
    /// Jackson's own pretty printer writes `"key" : value`, `[ ]` and `{ }` where node writes
    /// `"key": value`, `[]` and `{}`. This file is a deliverable — a human opens it, and purge
    /// deletes it by name next to its patch — so it is written exactly as the Node sidecar
    /// wrote it rather than nearly.
    private static final DefaultPrettyPrinter JSON_STRINGIFY = jsonStringifyPrinter();

    private static DefaultPrettyPrinter jsonStringifyPrinter() {
        Separators separators = Separators.createDefaultInstance()
                .withObjectFieldValueSpacing(Separators.Spacing.AFTER)
                .withObjectEmptySeparator("")
                .withArrayEmptySeparator("");
        DefaultPrettyPrinter pp = new DefaultPrettyPrinter(separators);
        // Two spaces and a bare LF on every platform. The default indenter asks the system for
        // its line separator, and the container this runs in is not the machine that reads it.
        DefaultIndenter indent = new DefaultIndenter("  ", "\n");
        pp.indentObjectsWith(indent);
        pp.indentArraysWith(indent);
        return pp;
    }

    static String stringify(Record record) {
        try {
            return MAPPER.writer(JSON_STRINGIFY).writeValueAsString(record);
        } catch (IOException e) {
            throw new UncheckedIOException("PR payload is not serialisable", e);
        }
    }

    // ── small helpers ──────────────────────────────────────────────────────────────────────

    /// A fixed command plus a variable tail — `['git', 'add', '--', ...paths]`.
    private static List<String> argv(List<String> head, List<String> tail) {
        List<String> out = new ArrayList<>(head);
        out.addAll(tail);
        return out;
    }

    /// The JS `(r.stderr || r.stdout)`: a FALSY stderr — which for a command that said nothing
    /// on it means the empty string — falls through to stdout, where git puts "nothing to
    /// commit" and `gh` puts most of what it has to say.
    private static String preferStderr(Proc r) {
        String err = nullToEmpty(r.stderr());
        return err.isEmpty() ? nullToEmpty(r.stdout()) : err;
    }

    /// JS `String.prototype.slice(-n)`: the last n characters, or all of them. What a failing
    /// command says at the END is what names the failure.
    static String tail(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(s.length() - n);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static void mkdirs(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Bytes, not `Files.writeString`: that one REPORTS an unencodable character by throwing,
    /// and a patch is arbitrary text out of a repository we did not write. node's
    /// `fs.writeFileSync(path, string)` substitutes and carries on, which is what `getBytes`
    /// does — losing one character of a patch is better than losing the deliverable.
    private static void write(Path file, String content) {
        try {
            Files.write(file, nullToEmpty(content).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
