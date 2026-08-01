package tech.mikhailov.ijt;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// What this repo was asked, what it produced, and what a human thought of it.
///
/// One append-only JSONL file at the ROOT of the repo being improved —
/// `ijt-feedback.jsonl` beside its pom. Not in this deployment's `.env` and not on the data
/// volume: prompts are per-repo, edited by the team that owns the repo, and the thing a feedback
/// loop rewrites. The record belongs with the code it describes.
///
/// ## Three kinds of line, one stream
///
///  - `attempt` — the code shown to the model, the tests it returned, and THE RULE LIST that was
///    in force. Written by the pipeline.
///  - `outcome` — what that round scored. Written by the pipeline.
///  - `feedback` — a human sentence. Written by a route, minutes or days later.
///
/// Nothing is ever rewritten. A run has several rounds in flight against one file and a
/// read-modify-write JSON document loses records under exactly that; append does not. It is the
/// same primitive `State.appendEventLine` already uses, for the same reason.
///
/// ## Why the RULE LIST and not the rendered prompt
///
/// The rendered prompt is mostly the source under test — fourteen kilobytes a record, and the
/// same fourteen every round. The part that VARIES, and the part GEPA mutates, is the
/// test-writing rule: "no mocks; prefer real objects". Storing that against an outcome is what
/// makes a record attributable to a prompt variant. Storing the whole prompt would be storing the
/// input three hundred times to learn about one line of it.
///
/// ## Two facts about living in a working copy
///
/// `git clean -fd` runs on every dropped round and again on `resetToBase`. It deletes untracked
/// files but NOT ignored ones, which is why {@link #ensureIgnored} writes the name into
/// `.git/info/exclude` rather than `.gitignore`: the exclude is local to the clone, so a team's
/// own ignore file is never modified and this can never appear in a PR diff. Commits are built
/// from explicit test paths, so it was never going to be committed; being ignored is what stops
/// it being DELETED.
///
/// A fresh clone takes both the file and the exclude with it, so every append is mirrored to
/// {@link #mirror} and restored by {@link #restoreInto} after a clone.
public final class Feedback {

    private Feedback() {}

    public static final String FILE_NAME = "ijt-feedback.jsonl";

    static final ObjectMapper MAPPER = new ObjectMapper();

    /// What makes two records written in the same second tellable apart.
    ///
    /// Ids used to be the epoch SECOND alone, which is coarser than the things being identified:
    /// generation and repair land inside one round milliseconds apart, and two people reviewing a
    /// unit together comment inside one second routinely. Identical ids are harmless right up to
    /// the moment something collapses duplicates — the dashboard must, because {@link #join}
    /// hands it one comment once per attempt — and then the second record vanishes from the page
    /// while sitting in the file.
    ///
    /// A process-wide counter rather than milliseconds: it cannot repeat however fast the clock
    /// is read, and it survives a clock that steps backwards.
    private static final java.util.concurrent.atomic.AtomicLong SEQ = new java.util.concurrent.atomic.AtomicLong();

    public static Path fileIn(Path repoRoot) {
        return repoRoot.resolve(FILE_NAME);
    }

    /// The durable copy, keyed by repo slug exactly as the ledgers are.
    public static Path mirror(String repoUrl) {
        return State.DATA_DIR.resolve("feedback").resolve(Util.slugify(repoUrl) + ".jsonl");
    }

    // ── writing ───────────────────────────────────────────────────────────────

    /// The code shown to the model, the tests it returned, and the rules in force.
    ///
    /// @param roundId what joins this to its outcome — see {@link #join}
    public static String attempt(Path repoRoot, String repoUrl, String roundId, String unit,
                                 Map<String, Object> code, List<Map<String, Object>> tests,
                                 List<String> writeTestRule) {
        Map<String, Object> row = new LinkedHashMap<>();
        String id = roundId + "#" + Util.nowSec() + "-" + SEQ.incrementAndGet();
        row.put("v", 1);
        row.put("kind", "attempt");
        row.put("id", id);
        row.put("ts", Util.nowSec());
        row.put("roundId", roundId);
        row.put("unit", unit);
        row.put("code", code == null ? Map.of() : code);
        row.put("tests", tests == null ? List.of() : tests);
        // THE GEPA CANDIDATE: the one thing that varies between records and can be rewritten.
        row.put("writeTestRule", writeTestRule == null ? List.of() : writeTestRule);
        append(repoRoot, repoUrl, row);
        return id;
    }

    /// What the round scored. Closes the attempts sharing its `roundId`.
    ///
    /// `before` is the ROUND's base, not the unit's original baseline, so round three is credited
    /// with round three's gain alone. No `score` is computed: whoever runs GEPA chooses the
    /// objective — absolute MAC, headroom-normalised gain, kills per token — and freezing that
    /// choice here produces a corpus that has to be rewritten the first time the objective moves.
    public static void outcome(Path repoRoot, String repoUrl, String roundId, String unit,
                               String verdict, Map<String, Object> before, Map<String, Object> after,
                               Boolean broken) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("v", 1);
        row.put("kind", "outcome");
        row.put("ts", Util.nowSec());
        row.put("roundId", roundId);
        row.put("unit", unit);
        row.put("verdict", verdict);
        row.put("before", before == null ? Map.of() : before);
        row.put("after", after == null ? Map.of() : after);
        row.put("broken", broken);
        append(repoRoot, repoUrl, row);
    }

    /// A human's sentence.
    ///
    /// `target` carries the scope by its SHAPE, so there is no enum to keep in step:
    ///
    ///   null                 standing guidance for the whole repo
    ///   `path::method`       about one unit
    ///   an attempt id        about one generated test
    ///
    /// `apply` is honoured ONLY when target is null. A critique of one test is not a policy, and
    /// promoting it to a standing rule is the modelling error that would poison both the short
    /// loop and the GEPA corpus.
    public static Map<String, Object> feedback(Path repoRoot, String repoUrl, String target,
                                               String text, boolean apply, Integer rating) {
        return feedback(repoRoot, repoUrl, target, text, apply, rating, null);
    }

    /// @param author who said it, or null. Persisted WITH the line — it used to be attached to
    ///               the response map after the line had already been appended, so every stored
    ///               comment read as "someone" while the caller was told their name was recorded.
    public static Map<String, Object> feedback(Path repoRoot, String repoUrl, String target,
                                               String text, boolean apply, Integer rating,
                                               String author) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("v", 1);
        row.put("kind", "feedback");
        row.put("id", "fb-" + Util.nowSec() + "-" + SEQ.incrementAndGet());
        row.put("ts", Util.nowSec());
        row.put("target", target == null || target.isEmpty() ? null : target);
        row.put("apply", apply && (target == null || target.isEmpty()));
        row.put("rating", rating);
        if (author != null && !author.isBlank()) row.put("author", author);
        row.put("text", text == null ? "" : text);
        append(repoRoot, repoUrl, row);
        return row;
    }

    /// One line, appended, never rewritten. Never throws: losing a record is not worth a run.
    static synchronized void append(Path repoRoot, String repoUrl, Map<String, Object> row) {
        try {
            Files.createDirectories(repoRoot);
            String line = MAPPER.writeValueAsString(row) + "\n";
            Files.writeString(fileIn(repoRoot), line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            ensureIgnored(repoRoot);
            if (repoUrl != null && !repoUrl.isEmpty()) {
                Path m = mirror(repoUrl);
                Files.createDirectories(m.getParent());
                Files.writeString(m, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Exception e) {
            System.err.println("feedback line not stored: " + e);
        }
    }

    // ── reading ───────────────────────────────────────────────────────────────

    /// Every line that parses. A truncated last line — a crash mid-append — is skipped, not fatal.
    public static List<Map<String, Object>> lines(Path repoRoot) {
        List<Map<String, Object>> out = new ArrayList<>();
        Path f = fileIn(repoRoot);
        try {
            if (!Files.exists(f)) return out;
            for (String l : Files.readAllLines(f)) {
                if (l.isBlank()) continue;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> row = MAPPER.readValue(l, Map.class);
                    out.add(row);
                } catch (Exception ignored) {
                    // one bad line is one lost record, not a lost file
                }
            }
        } catch (Exception e) {
            System.err.println(FILE_NAME + " could not be read: " + e);
        }
        return out;
    }

    /// The standing guidance: repo-scoped feedback the user marked `apply`.
    ///
    /// This is the SHORT loop — typed once, and the next round's prompt carries it, without
    /// waiting for anyone to run GEPA. Newest last, so a later instruction wins a contradiction
    /// the way the person writing it would expect.
    public static List<String> guidance(Path repoRoot) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> row : lines(repoRoot)) {
            if (!"feedback".equals(row.get("kind"))) continue;
            if (row.get("target") != null) continue;          // a per-test critique is not a policy
            if (!Boolean.TRUE.equals(row.get("apply"))) continue;
            String text = row.get("text") == null ? "" : String.valueOf(row.get("text"));
            if (!text.isBlank()) out.add(text);
        }
        return out;
    }

    /// Attempts, each with its outcome and any feedback aimed at it — the GEPA input.
    ///
    /// An outcome closes every preceding attempt sharing its `roundId` that an earlier outcome has
    /// not already closed. That handles generation-then-repair, which is two attempts and one
    /// outcome, and it handles two consecutive missed rounds legitimately sharing a roundId
    /// because `rounds` does not increment on a miss.
    public static List<Map<String, Object>> join(Path repoRoot) {
        List<Map<String, Object>> attempts = new ArrayList<>();
        Map<String, List<Map<String, Object>>> openByRound = new LinkedHashMap<>();
        List<Map<String, Object>> feedback = new ArrayList<>();

        for (Map<String, Object> row : lines(repoRoot)) {
            String kind = String.valueOf(row.get("kind"));
            if ("attempt".equals(kind)) {
                Map<String, Object> a = new LinkedHashMap<>(row);
                a.put("feedback", new ArrayList<Map<String, Object>>());
                attempts.add(a);
                openByRound.computeIfAbsent(String.valueOf(row.get("roundId")), k -> new ArrayList<>()).add(a);
            } else if ("outcome".equals(kind)) {
                List<Map<String, Object>> open = openByRound.remove(String.valueOf(row.get("roundId")));
                if (open != null) for (Map<String, Object> a : open) a.put("outcome", row);
            } else if ("feedback".equals(kind)) {
                feedback.add(row);
            }
        }
        for (Map<String, Object> fb : feedback) {
            Object target = fb.get("target");
            if (target == null) continue;                     // guidance, not per-record
            for (Map<String, Object> a : attempts) {
                if (target.equals(a.get("id")) || target.equals(a.get("unit"))) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> into = (List<Map<String, Object>>) a.get("feedback");
                    into.add(fb);
                }
            }
        }
        return attempts;
    }

    // ── surviving the working copy ────────────────────────────────────────────

    /// Keep the file out of `git clean -fd`, and out of every diff.
    static void ensureIgnored(Path repoRoot) {
        try {
            Path exclude = repoRoot.resolve(".git").resolve("info").resolve("exclude");
            if (!Files.isDirectory(exclude.getParent())) return;   // not a git working copy
            String current = Files.exists(exclude) ? Files.readString(exclude) : "";
            if (current.lines().anyMatch(l -> l.strip().equals(FILE_NAME))) return;
            Files.writeString(exclude,
                    current + (current.isEmpty() || current.endsWith("\n") ? "" : "\n")
                            + "# improve-java-tests: this file lives in the repo root but is never\n"
                            + "# committed. Being ignored is what stops `git clean -fd` deleting it\n"
                            + "# between rounds.\n"
                            + FILE_NAME + "\n");
        } catch (Exception e) {
            System.err.println("could not add " + FILE_NAME + " to .git/info/exclude: " + e);
        }
    }

    /// Put the mirrored copy back after a clone, which takes the working copy with it.
    public static void restoreInto(Path repoRoot, String repoUrl) {
        try {
            Path m = mirror(repoUrl);
            if (Files.exists(m) && !Files.exists(fileIn(repoRoot))) {
                Files.createDirectories(repoRoot);
                Files.copy(m, fileIn(repoRoot));
            }
            ensureIgnored(repoRoot);
        } catch (Exception e) {
            System.err.println("feedback file not restored: " + e);
        }
    }
}
