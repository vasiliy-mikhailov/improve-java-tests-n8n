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
        return attempt(repoRoot, repoUrl, roundId, unit, code, tests, writeTestRule, null);
    }

    /// @param prompts every stage's rule text as it stood for this round, keyed by stage
    ///                (`post_clone`, `pre_pick`, `pick_file`, `write_test`, `check_changes`,
    ///                `make_pr`). Storing only `write_test` was a bet that it is the only stage
    ///                worth evolving — but `pick_file` chooses WHICH unit is attempted at all and
    ///                `check_changes` decides whether a test survives, and a record that cannot
    ///                attribute an outcome to those cannot be used to improve them.
    public static String attempt(Path repoRoot, String repoUrl, String roundId, String unit,
                                 Map<String, Object> code, List<Map<String, Object>> tests,
                                 List<String> writeTestRule, Map<String, Object> prompts) {
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
        // Kept BESIDE writeTestRule rather than replacing it: 1546 records already written carry
        // the old field, and a reader that has to know which vintage it is holding is a reader
        // that will get it wrong.
        row.put("prompts", prompts == null ? Map.of() : prompts);
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
        String scoped = target == null || target.isEmpty() ? null : target;
        row.put("target", scoped);
        // WHICH TEST THIS IS ABOUT. A unit is rewritten every round, so "this asserts nothing"
        // is true of the output that existed when it was typed and says nothing about the one
        // that replaces it. Resolving the attempt HERE, at write time, is what stops the
        // critique following the unit forwards — see the attachment rule in join().
        // Null when nothing had been generated yet: that comment is about whatever comes next.
        row.put("about", scoped == null ? null : anchorFor(repoRoot, scoped));
        row.put("apply", apply && (target == null || target.isEmpty()));
        row.put("rating", rating);
        if (author != null && !author.isBlank()) row.put("author", author);
        row.put("text", text == null ? "" : text);
        append(repoRoot, repoUrl, row);
        return row;
    }

    /// The attempt a comment on `target` is about: the last one written for it, or null.
    ///
    /// File order, not timestamps. Records are appended within the same second routinely —
    /// generation and repair are milliseconds apart — and `ts` has one-second resolution, so
    /// ordering by it cannot tell an attempt written before a comment from one written after.
    /// The append order is exact and is already the order {@link #lines} returns.
    static String anchorFor(Path repoRoot, String target) {
        String last = null;
        for (Map<String, Object> row : lines(repoRoot)) {
            if (!"attempt".equals(row.get("kind"))) continue;
            if (target.equals(row.get("id"))) return target;   // already names one attempt
            if (target.equals(row.get("unit"))) last = String.valueOf(row.get("id"));
        }
        return last;
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
            Map<String, Object> onto = attemptFor(fb, attempts);
            if (onto == null) continue;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> into = (List<Map<String, Object>>) onto.get("feedback");
            into.add(fb);
        }
        return attempts;
    }

    /// The ONE attempt a comment belongs to, or null.
    ///
    /// Exactly one, and this is the whole point. A unit-targeted comment used to be attached to
    /// every attempt for that unit — so a critique of round 3's test was served alongside round
    /// 4's rewrite as though it described it. For GEPA that is a label on the wrong artefact;
    /// for a reader it is a comment sitting under code it was never about.
    ///
    /// Three cases, in order:
    ///
    ///  - `about` set — written since the anchor existed. Exact.
    ///  - target names an attempt directly. Exact.
    ///  - a unit-targeted line written BEFORE `about` existed. Reconstructed from append order:
    ///    the last attempt for that unit preceding the comment, or, when the comment came first,
    ///    the next one after it — a comment made before anything was generated is an instruction
    ///    for what gets written next, and the alternative is that it belongs to nothing and is
    ///    never seen again.
    static Map<String, Object> attemptFor(Map<String, Object> fb, List<Map<String, Object>> attempts) {
        Object target = fb.get("target");
        if (target == null) return null;                       // guidance, not per-record
        Object about = fb.get("about");
        if (about != null) {
            for (Map<String, Object> a : attempts) if (about.equals(a.get("id"))) return a;
            return null;                                       // its attempt is gone
        }
        Map<String, Object> before = null, after = null;
        boolean past = false;
        for (Map<String, Object> a : attempts) {
            if (target.equals(a.get("id"))) return a;
            if (a == fb) continue;
            if (target.equals(a.get("unit"))) {
                if (!past) before = a;
                else if (after == null) after = a;
            }
            if (Long.compare(asTs(a), asTs(fb)) > 0) past = true;
        }
        return before != null ? before : after;
    }

    private static long asTs(Map<String, Object> row) {
        Object t = row.get("ts");
        return t instanceof Number n ? n.longValue() : 0L;
    }

    /// The corpus, flat: one row per attempt, in the shape a prompt optimiser consumes.
    ///
    /// `join()` returns the stored records — three kinds of line stitched together, carrying
    /// bookkeeping (`roundId`, `kind`, `v`) that matters to this file and to nothing downstream.
    /// This is the same data as the training example it is: what the model was SHOWN (source),
    /// what it was TOLD (prompts, every stage), what it PRODUCED (tests), what that SCORED
    /// (outcome), and what a human said about it (feedback).
    ///
    /// `macGain` is computed rather than stored, because the objective is the reader's to choose
    /// — absolute MAC, gain, kills per token — and freezing one into the file produces a corpus
    /// that has to be rewritten the first time the objective moves. It is derived here because
    /// every consumer wants it and none should have to know that `before`/`after` are the
    /// ROUND's, not the unit's lifetime.
    ///
    /// Rows with no outcome are INCLUDED, with `outcome: null`. An attempt whose measurement
    /// never happened is not a missing row; it is a row about a round that broke, which is a
    /// third of them, and dropping those would teach an optimiser that everything it writes gets
    /// measured.
    public static List<Map<String, Object>> training(Path repoRoot) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> a : join(repoRoot)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("unit", a.get("unit"));
            row.put("ts", a.get("ts"));
            row.put("source", a.get("code"));
            // `prompts` when the record has it; older records carry only the write_test list,
            // and saying so explicitly beats an empty map that reads as "no rules were in force"
            Object prompts = a.get("prompts");
            row.put("prompts", prompts instanceof Map<?, ?> m && !m.isEmpty() ? prompts
                    : Map.of("write_test", firstOf(a.get("writeTestRule"))));
            row.put("output", Map.of("tests", a.get("tests") == null ? List.of() : a.get("tests")));
            row.put("outcome", outcomeRow(a.get("outcome")));
            row.put("feedback", humanRows(a.get("feedback")));
            out.add(row);
        }
        return out;
    }

    /// One row, as the line it is written as.
    ///
    /// Here rather than at the caller because this class owns the mapper that wrote every other
    /// line in the file; a second mapper configured differently is how an exported corpus ends
    /// up spelled differently from the stored one.
    public static String toJsonLine(Object row) {
        try {
            return MAPPER.writeValueAsString(row);
        } catch (Exception e) {
            throw new IllegalStateException("training row could not be serialised: " + e, e);
        }
    }

    private static String firstOf(Object list) {
        return list instanceof List<?> l && !l.isEmpty() ? String.valueOf(l.get(0)) : "";
    }

    private static Map<String, Object> outcomeRow(Object outcome) {
        if (!(outcome instanceof Map<?, ?> o)) return null;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("verdict", o.get("verdict"));
        row.put("broken", o.get("broken"));
        row.put("before", o.get("before"));
        row.put("after", o.get("after"));
        row.put("macGain", gain(o.get("before"), o.get("after")));
        return row;
    }

    private static Double gain(Object before, Object after) {
        Double b = mac(before), a = mac(after);
        return b == null || a == null ? null : Math.round((a - b) * 100.0) / 100.0;
    }

    private static Double mac(Object side) {
        if (!(side instanceof Map<?, ?> m)) return null;
        return m.get("mac") instanceof Number n ? n.doubleValue() : null;
    }

    /// Only what a person said, not the routing that got it here: `target`/`about` name records
    /// this row already IS.
    private static List<Map<String, Object>> humanRows(Object feedback) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(feedback instanceof List<?> l)) return out;
        for (Object o : l) {
            if (!(o instanceof Map<?, ?> fb)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("text", fb.get("text"));
            row.put("author", fb.get("author"));
            row.put("rating", fb.get("rating"));
            row.put("ts", fb.get("ts"));
            out.add(row);
        }
        return out;
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
