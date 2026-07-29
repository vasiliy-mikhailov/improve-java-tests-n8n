package tech.mikhailov.ijt;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Turning a model answer into files on disk. Everything here exists because the model is
/// free-form and the file system is not: a Java file must sit at the path its package and
/// class name dictate, and a wrong path either fails to compile or overwrites the team's
/// own tests.
public final class Parse {

    private Parse() {}

    private static final int MIN_TEST_BYTES = 20;

    /// One file: the one the model offered, or the one this round will write.
    ///
    /// Both fields are nullable. The answer is free-form JSON, so a `tests` entry can arrive
    /// with no content and no path at all — which is precisely what {@link #usable} and
    /// {@link #safePath} are here to absorb.
    public record TestFile(String path, String content) {}

    /// The model's reply, once the transport has parsed it. `tests` absent is not the same as
    /// `tests` empty on the wire, but both mean the same thing here: nothing to write.
    public record Answer(List<TestFile> tests) {}

    /// A raw `/api/llm/chat` result.
    ///
    /// `ok` is boxed and `json` nullable on purpose: a failed call arrives as `{ok:false}` with
    /// no body, and a call whose content would not parse as `{ok:true, json:null}`. Both have
    /// to yield no tests rather than a crash.
    public record Response(Boolean ok, Answer json) {}

    /// One entry of the prompt's `offered` list.
    ///
    /// Two prompts fill this in and they fill in different fields — the single-mutant prompt
    /// offers `{line, mutator, status}`, the batch prompt `{marker, line, method}`. Every field
    /// is therefore nullable, and `mutator` is what tells the two apart.
    public record Offer(String marker, Integer line, String method, String mutator, String status) {
        /// what the single-mutant prompt offers: the mutant it picked
        public static Offer mutant(Integer line, String mutator, String status) {
            return new Offer(null, line, null, mutator, status);
        }

        /// what the batch prompt offers: one entry per surviving line, no mutator among them
        public static Offer batch(String marker, Integer line, String method) {
            return new Offer(marker, line, method, null, null);
        }
    }

    /// The prompt this round sent, as much of it as parsing needs.
    ///
    /// @param targetPath      the one path the prompt demanded — the only path a round may write
    /// @param projectTestPath the project's OWN test for the class under test. Deliberately not
    ///                        consulted below; it is the single path the old check knew about,
    ///                        and knowing only that one is what let every other test file be
    ///                        overwritten (see {@link #safePath}).
    /// @param offered         the mutants or lines the prompt put in front of the model, or null
    public record Plan(String targetPath, String projectTestPath, List<Offer> offered) {
        public Plan(String targetPath) { this(targetPath, null, null); }
    }

    /// @param chosen the mutant this round aimed at, or null when it aimed at many
    public record Parsed(List<TestFile> tests, List<String> paths, int count, RoundOutcome.Mutant chosen) {}

    /// A repair produces no `chosen`: the round it repairs already reported what it aimed at.
    public record Repaired(List<TestFile> tests, List<String> paths, int count) {}

    /// An answer entry with enough in it to be a test at all — `class X{}` is a stub, not a test.
    static boolean usable(TestFile t) {
        return t != null && t.content() != null && t.content().trim().length() > MIN_TEST_BYTES;
    }

    /// The `tests` of a successful answer, minus the entries too short to be tests.
    private static List<TestFile> usableTests(Response resp) {
        if (resp == null || !Boolean.TRUE.equals(resp.ok())
                || resp.json() == null || resp.json().tests() == null) return List.of();
        return resp.json().tests().stream().filter(Parse::usable).toList();
    }

    private static final Pattern LEADING_DOT_SLASH = Pattern.compile("^\\.?/");

    /// The round writes exactly one file, at the path the prompt demanded. Anything else is
    /// redirected there.
    ///
    /// The old check merely required the path to be inside src/test/java and not equal to the
    /// one existing test it knew about — so the model could name any OTHER real test file in
    /// the repo and have it overwritten with generated content. If the suite then went red,
    /// the workflow's Delete Broken Tests step removed the team's file from the repo.
    static String safePath(String raw, Plan plan) {
        String p = raw == null ? "" : LEADING_DOT_SLASH.matcher(raw).replaceFirst("");
        String want = plan == null ? null : plan.targetPath();
        return p.equals(want) ? p : want;
    }

    private static final Pattern PUBLIC_CLASS =
            Pattern.compile("public\\s+(?:final\\s+|abstract\\s+)?class\\s+(\\w+)");
    private static final Pattern DOT_JAVA = Pattern.compile("\\.java$");

    /// javac refuses a public class whose name differs from the file name.
    static String alignClassName(String content, String path) {
        if (content == null || path == null || path.isEmpty()) return content;
        String want = DOT_JAVA.matcher(path.substring(path.lastIndexOf('/') + 1)).replaceFirst("");
        Matcher m = PUBLIC_CLASS.matcher(content);
        if (!m.find()) return content;
        String declared = m.group(1);
        if (declared.equals(want)) return content;
        // every occurrence, not only the declaration — constructors, javadoc and self-references
        // carry the old name too, and half a rename does not compile either
        return content.replaceAll("\\b" + Pattern.quote(declared) + "\\b", Matcher.quoteReplacement(want));
    }

    /// @param resp raw `/api/llm/chat` result
    /// @param plan the prompt this round sent
    public static Parsed parseGeneratedTests(Response resp, Plan plan) {
        // One file per round. The prompt asks for one; more than one means the model drifted,
        // and a second file only widens what has to compile.
        List<TestFile> tests = usableTests(resp).stream().limit(1)
                .map(t -> {
                    String path = safePath(t.path(), plan);
                    return new TestFile(path, alignClassName(t.content(), path));
                })
                .toList();
        List<Offer> offered = (plan == null || plan.offered() == null) ? List.of() : plan.offered();
        Offer first = offered.isEmpty() ? null : offered.get(0);
        // Carried so the kill check knows which mutant this round was aiming at — read from
        // THIS phase's plan, not from a sibling phase's node.
        //
        // Only when the plan really did aim at one. A batch round offers `{marker, line,
        // method}` for every surviving line, and reading offered[0] out of that produced
        // `{line: 78, mutator: undefined}`: the workflow printed "targeting undefined at line
        // 78", and the kill check — which matches on mutator AND line — could never match
        // anything, so no batch round could report a kill. A round aimed at many lines is
        // aimed at no single mutant, and RoundOutcome already handles being told that.
        RoundOutcome.Mutant chosen = (first != null && first.mutator() != null && !first.mutator().isEmpty())
                // a missing line becomes 0, which is what RoundOutcome already reads as "no line
                // to match PIT's output against" — the verdict it gives for an absent line
                ? new RoundOutcome.Mutant(first.mutator(), first.line() == null ? 0 : first.line())
                : null;
        return new Parsed(tests, tests.stream().map(TestFile::path).toList(), tests.size(), chosen);
    }

    /// A repair rewrites the same files; a new path here would leave the broken one on disk.
    ///
    /// @param prev what the generation phase parsed — only its paths are read
    public static Repaired parseRepairedTests(Response resp, Parsed prev) {
        List<String> paths = (prev == null || prev.paths() == null) ? List.of() : prev.paths();
        List<TestFile> usable = usableTests(resp);
        List<TestFile> tests = new ArrayList<>();
        int n = Math.min(usable.size(), paths.size());
        for (int i = 0; i < n; i++) {
            String path = paths.get(i);
            // no path means no file to rewrite; the answer is dropped rather than given
            // somewhere invented to live
            if (path == null || path.isEmpty()) continue;
            tests.add(new TestFile(path, alignClassName(usable.get(i).content(), path)));
        }
        return new Repaired(List.copyOf(tests), tests.stream().map(TestFile::path).toList(), tests.size());
    }
}
