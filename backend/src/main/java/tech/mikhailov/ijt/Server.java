package tech.mikhailov.ijt;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Sidecar HTTP API (:3000). Everything the n8n workflow needs, so the workflow itself stays
/// 100% native n8n nodes (HTTP Request / Code / IF / SplitInBatches).
///
/// **The route paths and the JSON field names below are a contract.** The workflow calls the
/// routes by path and reads their answers by name, and it is not being changed — so every
/// spelling here is the Node sidecar's spelling, including the ones that read oddly.
///
/// This is also the composition root. The pure modules (Select, Rounds, Measure, Prompts, …)
/// know nothing about each other; the seams the IO modules declare — {@link Exec.ProgressSink},
/// {@link Coverage.Io}, {@link Tests.Io}, {@link Pr.Io} — and the three collaborators the routes
/// name ({@link LlmClient}, {@link PrGateway}, {@link RulesEngine}) are wired here and nowhere
/// else. See {@link #installCollaborators}: a seam nobody installs is a route that throws.
public final class Server {

    private Server() {}

    /// `parseInt(process.env.SIDECAR_PORT || '3000', 10)`.
    public static int port() {
        Integer p = State.jsParseInt(env("SIDECAR_PORT", "3000"));
        return p == null ? 3000 : p;
    }

    /// The dashboard's static files.
    ///
    /// The JS used `path.join(__dirname, 'dashboard')`, and Java has no `__dirname` — a jar has
    /// no directory to be relative to. The dashboard is not being ported (it is plain HTML/JS
    /// served as-is), so its location is stated instead, defaulting to where the image already
    /// puts it.
    public static Path dashboardDir() {
        return Path.of(env("DASHBOARD_DIR", "/app/dashboard"));
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isEmpty()) ? fallback : v;
    }

    /// Lenient on purpose: request bodies are written by an n8n node and carry whatever the
    /// workflow put in them, which is regularly more than the record being bound needs.
    static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // ── the three collaborators the routes call ────────────────────────────────
    //
    // The routes name what they need of llm.js, pr.js and rules.js rather than the classes
    // themselves. That is the house style for every IO module here (Repo.Shell, Coverage.Io,
    // Tests.Io, Pr.Io, Exec.ProgressSink) and it is what lets the decisions below be tested
    // without a model, a git checkout or a network.
    //
    // The real implementations are {@link Llm}, {@link Pr} and {@link Rules}, and they are
    // installed by {@link #installCollaborators} — which main() calls before start(). The
    // defaults below are what an UNINSTALLED seam does, and they are a legible failure rather
    // than a configuration: nothing is meant to run on them.

    /// llm.js `chat({system, prompt, messages, stage, stageDetail, maxTokens, temperature, json})`.
    ///
    /// Every field nullable, exactly as the JS options object is: `messages` REPLACES the
    /// system+prompt pair when it is given, and `temperature` absent is the client's own 0.3
    /// rather than a caller's zero.
    public record ChatRequest(String system, String prompt, List<Map<String, Object>> messages,
                              String stage, String stageDetail, Integer maxTokens,
                              Double temperature, boolean json) {}

    /// `{text, json}` — `json` is null unless the call asked for JSON and got it.
    public record ChatReply(String text, Object json) {}

    public interface LlmClient {
        ChatReply chat(ChatRequest request);
    }

    /// What pr.js records once a PR exists — and pushes onto `state.prs`, which the dashboard
    /// renders, so every field the JS object literal carries is here.
    ///
    /// `url` is set in github mode and `patchPath` in local mode; exactly one of them is ever
    /// non-null, which is how the caller tells a real PR from a prepared one.
    public record PrRecord(String file, String branch, String title, String body, List<String> labels,
                           String mode, long createdAt, String url, String patchPath) {}

    /// pr.js, as the routes use it.
    public interface PrGateway {
        /// Test files this branch touched — committed rounds AND uncommitted edits.
        List<String> changedTestFiles();

        /// Commit only test sources. Throws when there is nothing to commit.
        void commit(String message);

        /// The cumulative diff against the PR base.
        String diffAgainstBase();

        /// The diff since one commit — what THIS unit added, not what the whole branch holds.
        String diffSince(String sha);

        PrRecord createPr(String file, String branch, String title, String body, List<String> labels);
    }

    /// rules.js, as the routes use it.
    public interface RulesEngine {
        /// Apply the team rule for one stage. The answer is free-form JSON — its shape is the
        /// stage's business, not this file's.
        Object apply(String stage, Map<String, Object> context);

        /// Free-text guidance assembled for test-writing prompts.
        List<String> testWritingConstraints();
    }

    private static volatile LlmClient llm = request -> {
        throw new IllegalStateException("no LLM client installed");
    };
    private static volatile PrGateway pr = new PrGateway() {
        public List<String> changedTestFiles() { throw notWired(); }
        public void commit(String message) { throw notWired(); }
        public String diffAgainstBase() { throw notWired(); }
        public String diffSince(String sha) { throw notWired(); }
        public PrRecord createPr(String f, String b, String t, String d, List<String> l) { throw notWired(); }
        private IllegalStateException notWired() { return new IllegalStateException("no PR gateway installed"); }
    };
    private static volatile RulesEngine rules = new RulesEngine() {
        public Object apply(String stage, Map<String, Object> context) {
            throw new IllegalStateException("no rules engine installed");
        }
        /// An absent rules engine contributes no constraints — a prompt is still worth building
        /// without the team's guidance, and refusing here would take the whole round with it.
        public List<String> testWritingConstraints() { return List.of(); }
    };

    public static void llm(LlmClient v) { llm = v; }

    public static LlmClient llm() { return llm; }

    public static void pr(PrGateway v) { pr = v; }

    public static PrGateway pr() { return pr; }

    public static void rules(RulesEngine v) { rules = v; }

    public static RulesEngine rules() { return rules; }

    // ── helpers ────────────────────────────────────────────────────────────────

    /// The store, spelled as the JS spelled it. Everything below reads and writes this object.
    static State.Store state() { return State.STATE; }

    /// A unit key is `<path>::<method>`; everything git-facing needs the path alone.
    ///
    /// `||`, not `??`: a record whose `path` is the empty string falls through to the split, as
    /// it does in the JS.
    public static String unitPath(String key) {
        String stored = str(field(key, "path"));
        if (stored != null && !stored.isEmpty()) return stored;
        String k = key == null ? "null" : key;
        int at = k.indexOf("::");
        return at == -1 ? k : k.substring(0, at);
    }

    public static String unitMethod(String key) {
        String stored = str(field(key, "method"));
        if (stored != null && !stored.isEmpty()) return stored;
        String k = key == null ? "null" : key;
        int at = k.indexOf("::");
        String tail = at == -1 ? "" : k.substring(at + 2);
        return tail.isEmpty() ? null : tail;
    }

    /// Human label for events and PR titles: `Class#method`.
    ///
    /// The split is on `[./]` and takes the LAST segment, so a unit with a recorded `fqcn`
    /// reads `XML#parse()` — and one without, falling back to the source path, reads `java`,
    /// because `src/main/java/org/json/XML.java` ends in a `.java` segment. That is what the
    /// Node sidecar prints and it only ever reaches an event line, so it is kept rather than
    /// quietly corrected: the trailing `.replace(/\.java$/, '')` can never fire after a split
    /// that has already removed the dot.
    public static String unitLabel(String key) {
        Map<String, Object> f = fileRecord(key);
        String fqcn = str(f.get("fqcn"));
        String basis = (fqcn != null && !fqcn.isEmpty()) ? fqcn : unitPath(key);
        String[] parts = basis.split("[./]", -1);
        String cls = parts[parts.length - 1].replaceFirst("\\.java$", "");
        String method = str(f.get("method"));
        return (method != null && !method.isEmpty()) ? cls + "#" + method + "()" : cls;
    }

    static void needRun() {
        if (state().run == null) throw new IllegalStateException("no active run — POST /api/run/start first");
    }

    /// The improvement ledger for THIS run's repo, created on first use.
    static Map<String, Map<String, Object>> ledger() {
        String slug = Util.slugify(state().run.config.repoUrl());
        synchronized (State.STATE) {
            return state().improvedLedger.computeIfAbsent(slug, k -> new LinkedHashMap<>());
        }
    }

    /// Per-repo record of every measurement taken, regardless of outcome.
    static Map<String, Map<String, Object>> measured() {
        String slug = Util.slugify(state().run.config.repoUrl());
        synchronized (State.STATE) {
            return state().measureLedger.computeIfAbsent(slug, k -> new LinkedHashMap<>());
        }
    }

    static void recordMeasurement(String file, Map<String, Object> patch) {
        Map<String, Map<String, Object>> m = measured();
        String key = Measure.normalizeUnitKey(file);
        Map<String, Object> merged = new LinkedHashMap<>();
        synchronized (State.STATE) {
            Map<String, Object> prev = m.get(key);
            if (prev != null) merged.putAll(prev);
            if (patch != null) merged.putAll(patch);
            merged.put("ts", System.currentTimeMillis());
            // stamped with the measurement semantics in force, so a later change to what a number
            // MEANS invalidates it instead of silently replaying it into a new run
            m.put(key, Measure.stamp(merged));
        }
        State.save();
    }

    /// Close the current attempt's stopwatch into the file's cumulative machine time.
    static long accrueSpent(String file) {
        Map<String, Object> f;
        synchronized (State.STATE) { f = state().files.get(file); }
        if (f == null) return 0;
        Object started = f.get("attemptStartedAt");
        if (!State.jsTruthy(started)) return State.asLong(f.get("spentSec"));
        long spent = State.asLong(f.get("spentSec")) + (Util.nowSec() - State.asLong(started));
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("spentSec", spent);
        patch.put("attemptStartedAt", null);
        State.upsertFile(file, patch);
        return spent;
    }

    /// Repo-level mutation/MAC totals = the MEAN over every file whose mutation score we
    /// actually measured (the dashboard labels them "picked files"). Reporting the last file's
    /// score as the repo's number — as this used to — makes the headline swing wildly with
    /// whatever file happened to be measured last.
    static void refreshTotals() {
        if (state().run == null) return;
        // A unit with no mutation surface has no mutation score — averaging its 0 % into the
        // repo's headline says the repo is weaker than it is, on the strength of methods that
        // cannot be mutated at all.
        List<Map<String, Object>> files = allFiles().stream()
                .filter(f -> num(f.get("mutationBefore")) != null && !"no_mutants".equals(str(f.get("status"))))
                .toList();
        if (files.isEmpty()) return;
        double before = mean(files.stream().map(f -> num(f.get("mutationBefore"))).toList());
        double after = mean(files.stream()
                .map(f -> nullish(num(f.get("mutationAfter")), num(f.get("mutationBefore")))).toList());
        State.Run run = state().run;
        run.baseline.mutationPct = before;
        run.baseline.mac = Util.mac(run.baseline.coveragePct, before);
        run.result.mutationPct = after;
        run.result.mac = Util.mac(nullish(run.result.coveragePct, run.baseline.coveragePct), after);
        run.measuredFiles = files.size();
    }

    /// `round2(xs.reduce(sum) / xs.length)` — the caller has already checked the list is not
    /// empty, exactly as the JS guard does.
    static double mean(List<Double> xs) {
        double s = 0;
        for (Double x : xs) s += (x == null ? 0 : x);
        return Util.round2(s / xs.size());
    }

    /// The same average, but null for an empty list: no data is not an average of zero.
    static Double meanOrNull(List<Double> xs) {
        return xs.isEmpty() ? null : mean(xs);
    }

    /// Ranking and eligibility live in Select, under unit test.
    static List<Map<String, Object>> rankSurvivors(List<Map<String, Object>> list) {
        return reorder(list, Select.rankSurvivors(list.stream().map(Server::selectMutant).toList(), mutatorStats()));
    }

    /// Survivors we have not already attacked, in PIT's order.
    static List<Map<String, Object>> eligible(List<Map<String, Object>> list, List<String> attempted) {
        return reorder(list, Select.eligible(list.stream().map(Server::selectMutant).toList(), attempted));
    }

    /// Put the raw mutant records back in the order (and the subset) that {@link Select}
    /// decided on.
    ///
    /// {@link Select.Mutant} carries only what selection reads — kind, line, index, difficulty
    /// — while a prompt also needs PIT's status and description, so the full records cannot be
    /// handed to the ranker and cannot be reconstructed from what it returns. Matching each
    /// ranked key to the FIRST raw record still unclaimed is exact rather than approximate:
    /// Select's sort is stable, so equal keys come back in the order they went in.
    private static List<Map<String, Object>> reorder(List<Map<String, Object>> raw, List<Select.Mutant> ordered) {
        List<Map<String, Object>> pool = new ArrayList<>(raw);
        List<Select.Mutant> keys = new ArrayList<>(pool.stream().map(Server::selectMutant).toList());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Select.Mutant m : ordered) {
            int at = keys.indexOf(m);
            if (at < 0) continue;   // cannot happen: every ranked key came out of this list
            out.add(pool.remove(at));
            keys.remove(at);
        }
        return out;
    }

    /// Public so the Spring orchestrator can serve `GET /api/metrics` from the same code.
    /// The alternative was a second implementation in another module, and a metrics payload
    /// that agrees with itself only until someone edits one of them.
    public static Map<String, Object> metricsPayload() {
        refreshTotals();
        List<Map<String, Object>> files = new ArrayList<>(allFiles());
        // `(a.mac ?? 999) - (b.mac ?? 999)`, as a comparison rather than a subtraction — the
        // difference of two doubles cannot overflow, but the habit is what keeps the integer
        // comparators next door safe. Java's sort is stable like the JS one.
        files.sort(Comparator.comparingDouble((Map<String, Object> f) -> nullish(num(f.get("mac")), 999.0)));
        List<Map<String, Object>> targeted = files.stream().filter(f -> Select.isTargeted(unitState(f))).toList();

        // work accounting: human-equivalent hours, machine time, ETA, FTE, progress
        List<Map<String, Object>> settled = files.stream()
                .filter(f -> List.of("improved", "no_improvement", "failed").contains(String.valueOf(f.get("status"))))
                .toList();
        long now = Util.nowSec();
        double humanMin = files.stream().mapToDouble(f -> State.asLong(timesheetField(f, "totalMin"))).sum();
        double machineSec = files.stream().mapToDouble(f -> State.asLong(f.get("spentSec"))).sum();
        // only the file currently being worked on has a live stopwatch; a crashed attempt must
        // not keep accruing time forever
        for (Map<String, Object> f : files) {
            if (State.jsTruthy(f.get("attemptStartedAt")) && "picked".equals(str(f.get("status")))) {
                machineSec += now - State.asLong(f.get("attemptStartedAt"));
            }
        }
        // clone + install + baseline measurement are real machine time too, and they dominate
        // short batches — without them the FTE ratio flatters the pipeline
        String repoUrl = state().run == null ? "" : nullish(state().run.config.repoUrl(), "");
        machineSec += State.asLong(state().overheadLedger.get(Util.slugify(repoUrl)));

        List<Map<String, Object>> timedSettled = settled.stream()
                .filter(f -> State.asLong(f.get("spentSec")) > 0).toList();
        long remaining = files.stream()
                .filter(f -> "candidate".equals(str(f.get("status"))) || "picked".equals(str(f.get("status"))))
                .count();
        Double avgSecPerFile = timedSettled.isEmpty() ? null
                : timedSettled.stream().mapToDouble(f -> State.asLong(f.get("spentSec"))).sum() / timedSettled.size();
        // FTE must compare like with like: only files whose machine time we actually measured.
        // (Files improved before the stopwatch existed carry human-equivalent hours but no
        // machine time, and would inflate the ratio.)
        List<Map<String, Object>> comparable = files.stream()
                .filter(f -> State.asLong(timesheetField(f, "totalMin")) > 0 && State.asLong(f.get("spentSec")) > 0)
                .toList();
        double comparableHumanMin = comparable.stream()
                .mapToDouble(f -> State.asLong(timesheetField(f, "totalMin"))).sum();
        double comparableMachineSec = comparable.stream()
                .mapToDouble(f -> State.asLong(f.get("spentSec"))).sum();
        State.Tokens tokens = state().run == null || state().run.tokens == null
                ? new State.Tokens() : state().run.tokens;
        long improvedCount = settled.stream().filter(f -> "improved".equals(str(f.get("status")))).count();

        Map<String, Object> work = new LinkedHashMap<>();
        work.put("tokensIn", tokens.in);
        work.put("tokensOut", tokens.out);
        work.put("llmCalls", tokens.calls);
        work.put("tokensPerImproved", improvedCount > 0
                ? Math.round((double) (tokens.in + tokens.out) / improvedCount) : null);
        // 60.0 and 3600.0, never the integer literals: integer division would report every
        // sub-hour total as a flat zero
        work.put("humanHours", Util.round2(humanMin / 60.0));
        work.put("machineHours", Util.round2(machineSec / 3600.0));
        work.put("fte", comparableMachineSec > 600
                ? Util.round2((comparableHumanMin * 60) / comparableMachineSec) : null);
        work.put("fteBasis", comparable.size());
        work.put("etaSec", avgSecPerFile != null ? Math.round(remaining * avgSecPerFile) : null);
        work.put("settled", settled.size());
        work.put("improved", improvedCount);
        work.put("totalFiles", files.size());
        work.put("remaining", remaining);

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("baseline", state().run == null ? Map.of() : state().run.baseline);
        totals.put("current", state().run == null ? Map.of() : state().run.result);
        totals.put("targetedFiles", targeted.size());
        totals.put("measuredFiles", state().run == null || state().run.measuredFiles == null
                ? 0 : state().run.measuredFiles);
        totals.put("improvedFiles", targeted.stream().filter(f -> "improved".equals(str(f.get("status")))).count());
        totals.put("avgMacBefore", meanOrNull(targeted.stream()
                .map(f -> num(f.get("macBefore"))).filter(java.util.Objects::nonNull).toList()));
        totals.put("avgMacAfter", meanOrNull(targeted.stream()
                .map(f -> nullish(num(f.get("macAfter")), num(f.get("macBefore"))))
                .filter(java.util.Objects::nonNull).toList()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("work", work);
        out.put("stage", state().stage);
        out.put("run", state().run);
        out.put("runner", state().runner);
        out.put("totals", totals);
        out.put("files", projectedFiles(files));
        out.put("prs", state().prs);
        out.put("decisions", state().decisions);
        out.put("events", lastN(state().events, 60));
        return out;
    }

    /// Project only what the dashboard renders, and only as many rows as it shows.
    ///
    /// Full records for ~500 files made the metrics route a ~130 KB poll every 2 s. Files the
    /// pipeline has touched always win a slot; untouched candidates fill the rest.
    static List<Map<String, Object>> projectedFiles(List<Map<String, Object>> files) {
        List<Map<String, Object>> ordered = new ArrayList<>();
        files.stream().filter(f -> !"candidate".equals(str(f.get("status")))).forEach(ordered::add);
        files.stream().filter(f -> "candidate".equals(str(f.get("status")))).forEach(ordered::add);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> f : ordered.subList(0, Math.min(250, ordered.size()))) {
            String method = str(f.get("method"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", (method != null && !method.isEmpty()) ? f.get("path") + "::" + method : f.get("path"));
            row.put("sourcePath", f.get("path"));
            row.put("method", (method != null && !method.isEmpty()) ? method : null);
            row.put("status", f.get("status"));
            row.put("attempts", f.get("attempts"));
            row.put("rounds", State.asLong(f.get("rounds")));
            row.put("coverage", f.get("coverage"));
            row.put("mutation", f.get("mutation"));
            row.put("mac", f.get("mac"));
            row.put("coverageBefore", f.get("coverageBefore"));
            row.put("mutationBefore", f.get("mutationBefore"));
            row.put("macBefore", f.get("macBefore"));
            row.put("coverageAfter", f.get("coverageAfter"));
            row.put("mutationAfter", f.get("mutationAfter"));
            row.put("macAfter", f.get("macAfter"));
            // what the best attempt reached even when the result was not kept
            row.put("attemptCoverage", f.get("attemptCoverage"));
            row.put("attemptMutation", f.get("attemptMutation"));
            row.put("attemptMac", f.get("attemptMac"));
            row.put("failure", f.get("failure"));
            row.put("tokensIn", State.asLong(f.get("tokensIn")));
            row.put("tokensOut", State.asLong(f.get("tokensOut")));
            row.put("llmCalls", State.asLong(f.get("llmCalls")));
            row.put("prUrl", f.get("prUrl"));
            row.put("prPatch", f.get("prPatch"));
            // The JS is `f.timesheet && {…}`: a unit that never had one omits the key entirely,
            // and one whose estimate failed carries an explicit null. Both are kept apart —
            // `JSON.stringify` drops the first and writes the second.
            if (f.containsKey("timesheet")) {
                Map<String, Object> ts = asMap(f.get("timesheet"));
                if (ts == null) {
                    row.put("timesheet", null);
                } else {
                    Map<String, Object> proj = new LinkedHashMap<>();
                    for (String k : List.of("hours", "totalMin", "analysisMin", "testsMin", "mutationMin", "verifyMin")) {
                        proj.put(k, ts.get(k));
                    }
                    row.put("timesheet", proj);
                }
            }
            out.add(row);
        }
        return out;
    }

    /// Turn the scanned FILES into the pipeline's real unit of work: one entry per METHOD.
    ///
    /// A class is the wrong granularity for this job. PIT costs (mutants x suite time), so a
    /// class-wide run is slow and mostly wasted — the weakness is almost always concentrated in
    /// a few methods, and the tests that fix it are method-shaped. JaCoCo already reports
    /// per-method counters, so the whole method list, with its line coverage, comes free from
    /// the baseline run — no extra measurement to enumerate them.
    ///
    /// Key: `<path>::<method>`. The workflow passes that string around opaquely, so a "file"
    /// everywhere downstream is really a method unit; `f.path` is still the source file it lives
    /// in, which is what git, the diff and the PR care about.
    static void expandFilesIntoMethodUnits(Map<String, Coverage.ClassCov> classes) {
        List<Map<String, Object>> files = allFiles().stream()
                .filter(f -> !State.jsTruthy(f.get("method"))).toList();
        Integer configured = state().run.config.minUnitLines();
        int minUnitLines = configured == null ? 2 : configured;
        int units = 0, skipped = 0, trivial = 0;
        for (Map<String, Object> f : files) {
            String path = str(f.get("path"));
            String fq = State.jsTruthy(f.get("fqcn")) ? str(f.get("fqcn")) : Repo.fqcnOf(path);
            Coverage.ClassCov c = classes == null ? null : classes.get(fq);
            synchronized (State.STATE) { state().files.remove(path); }
            if (c == null || c.methods.isEmpty()) { skipped += 1; continue; }
            for (Map.Entry<String, Coverage.MethodCov> e : c.methods.entrySet()) {
                String name = e.getKey();
                Coverage.MethodCov m = e.getValue();
                int executable = m.missed + m.covered;
                if (executable <= 0) continue;                 // nothing to test in there
                // Skip units with no behaviour to verify. A one-line private constructor, a
                // getter that returns a field, a static initialiser: PIT finds nothing worth
                // mutating and a run spent there buys nothing. The first Gradle run picked
                // `Assertions#<init>()` — a private utility-class constructor — and failed on it.
                if ("<clinit>".equals(name)) continue;
                if (executable < minUnitLines) { trivial += 1; continue; }
                String key = path + "::" + name;
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("path", path);
                rec.put("method", name);
                rec.put("fqcn", fq);
                rec.put("module", f.get("module"));
                rec.put("lines", f.get("lines"));
                rec.put("key", key);
                rec.put("methodLine", m.line != 0 ? m.line : null);
                rec.put("coverage", Util.round2((m.covered * 100.0) / executable));
                rec.put("coveredLines", m.covered);
                rec.put("missedLines", m.missed);
                rec.put("executableLines", executable);
                rec.put("mutation", null);
                rec.put("mac", null);
                rec.put("macBefore", null);
                rec.put("macAfter", null);
                rec.put("status", "candidate");
                rec.put("attempts", 0);
                rec.put("branch", null);
                rec.put("prUrl", null);
                rec.put("prPatch", null);
                rec.put("updatedAt", Util.nowSec());
                synchronized (State.STATE) { state().files.put(key, rec); }
                units += 1;
            }
        }
        if (units > 0) {
            State.event("measuring_baseline", "unit of work is the METHOD: " + files.size()
                    + " class(es) → " + units + " method(s)"
                    + (trivial > 0 ? "; skipped " + trivial + " trivial method(s) (< " + minUnitLines
                            + " executable lines)" : "")
                    + (skipped > 0 ? "; " + skipped + " class(es) had no executable method" : ""));
        }
        State.save();
    }

    static boolean isCtor(String m) { return "<init>".equals(m) || "<clinit>".equals(m); }

    /// Fold what previous runs established into this one: units already settled are not
    /// re-picked, and measurements still valid under the current semantics are restored.
    /// Must run AFTER {@link #expandFilesIntoMethodUnits} — see {@link Measure#planReplay}.
    static void replayLedgers() {
        Map<String, Measure.Improvement> improved = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : ledger().entrySet()) {
            improved.put(e.getKey(), improvementOf(e.getValue()));
        }
        Measure.Plan plan = Measure.planReplay(improved, measured(),
                k -> { synchronized (State.STATE) { return state().files.containsKey(k); } });
        int maxAttempts = orDefault(state().run.config.maxAttemptsPerFile(), 3);
        for (Measure.Settled s : plan.settle()) {
            Map<String, Object> patch = new LinkedHashMap<>();
            if ("improved".equals(s.record().state())) {
                patch.put("status", "improved");
                patch.put("fromLedger", true);
                patch.put("prUrl", s.record().prUrl());
                patch.put("prPatch", s.record().patchPath());
            } else {
                patch.put("status", "no_mutants".equals(s.record().state()) ? "no_mutants" : "no_improvement");
                patch.put("fromLedger", true);
                patch.put("attempts", maxAttempts);
            }
            // effort (timesheet, spentSec) comes back whatever the measurement version: it is what
            // the work COST, and the headline reads "Human-equivalent work 0" beside "2 improved"
            // without it
            patch.putAll(s.effort().toMap());
            if (s.metrics() != null) patch.putAll(s.metrics());
            State.upsertFile(s.key(), patch);
        }
        for (Measure.Restored r : plan.restore()) {
            Map<String, Object> entry = r.entry();
            Map<String, Object> patch = new LinkedHashMap<>();
            for (String k : List.of("coverageBefore", "mutationBefore", "macBefore",
                    "attemptCoverage", "attemptMutation", "attemptMac", "failure",
                    // what earlier runs learned about whether a test can execute this unit at all,
                    // and which of its mutants have already been attacked and survived
                    "everReached", "missesEver", "attemptedMutants")) {
                patch.put(k, entry.get(k));
            }
            State.upsertFile(r.key(), patch);
        }
        if (!plan.settle().isEmpty() || !plan.restore().isEmpty() || plan.stale() > 0) {
            State.event("measuring_baseline", "ledger: " + plan.settle().size()
                    + " unit(s) already settled in previous runs — skipping them; "
                    + plan.restore().size() + " restored earlier measurements"
                    + (plan.stale() > 0 ? "; " + plan.stale()
                            + " discarded as measured under older semantics (v<" + Measure.MEASURE_VERSION + ")" : "")
                    + (plan.retryable() > 0 ? "; " + plan.retryable()
                            + " left open — they failed to measure, which settles nothing" : ""));
        }
        State.save();
    }

    // ── reachability, memoised per file per run ────────────────────────────────
    //
    // The JS keeps both memos in ONE Map, keyed by `path::method` for the answer and by `path`
    // for the source; the two can never collide because a repo-relative path has no `::` in it.
    // Two maps here, because one of them holds source text and the other a verdict, and Java
    // would have to erase both to Object to say so.

    static final Map<String, String> REACH_CACHE = new ConcurrentHashMap<>();
    static final Map<String, String> SOURCE_CACHE = new ConcurrentHashMap<>();

    static void clearReachCache() {
        REACH_CACHE.clear();
        SOURCE_CACHE.clear();
    }

    /// Can a test get at this method, and how easily? Memoised per file per run: the analysis
    /// reads the whole class, and a repo has hundreds of units spread over a few dozen files.
    static String reachOf(String srcPath, String method) {
        if (method == null || method.isEmpty() || isCtor(method)) return "public";
        String key = srcPath + "::" + method;
        String cached = REACH_CACHE.get(key);
        if (cached != null) return cached;
        String reach;
        try {
            String src = SOURCE_CACHE.computeIfAbsent(srcPath, p -> {
                String s = Repo.readFileSafe(p, 2000000);
                return s == null ? "" : s;
            });
            reach = reachFor(src, method);
        } catch (RuntimeException e) {
            reach = "public";
        }
        REACH_CACHE.put(key, reach);
        return reach;
    }

    /// The decision the memo caches, over source text rather than a repository.
    ///
    /// Generated tests live in the SAME package as the class (src/test/java mirrors
    /// src/main/java), so package-private and protected members are callable directly —
    /// treating them as hidden dropped ordinary, testable methods from the queue. Only `private`
    /// genuinely needs a route in. Unknown visibility (inherited, generated, lambda) counts as
    /// callable: a guess must never cost a real unit.
    static String reachFor(String src, String method) {
        String vis = JavaSrc.methodVisibility(src, method);
        if (!"private".equals(vis)) return "public";
        return JavaSrc.routesTo(src, method).isEmpty() ? "none" : "route";
    }

    /// Everything the model needs to know about one unit, twice over: once as the JSON the
    /// `/api/files/gaps` route answers with, and once as the record the prompt builders read.
    ///
    /// Both are built from the same values in one pass, so they cannot drift — which is the
    /// only reason the wire shape is a map at all. It carries forty fields, several of them
    /// present purely because a workflow node reads them.
    public record UnitGaps(Prompts.Gaps prompts, Map<String, Object> wire) {}

    /// Everything the model needs to know about one unit (`path::method`): the method's source,
    /// what is uncovered, the ranked survivors, the target test paths, the team's constraints.
    /// Read fresh on every call — a prompt built from a stale copy asks for a mutant that the
    /// last round already killed.
    static UnitGaps gapsFor(String p) {
        needRun();
        if (p == null || p.isEmpty()) throw new IllegalArgumentException("path required");
        String srcPath = unitPath(p);
        // Two different reads on purpose. `source` is clipped for the prompt; `whole` is the
        // file. Analysing the clipped copy is why the reachability hint silently did nothing on
        // JSONObject: the method is declared at line 2071, past the 24 000-character clip, so its
        // visibility read back as null and the route was never named.
        String source = Repo.readFileSafe(srcPath, 24000);
        String whole = Repo.readFileSafe(srcPath, 2000000);
        Map<String, Object> f = fileRecord(p);
        int rounds = (int) State.asLong(f.get("rounds"));
        int round = rounds + 1;
        String method = str(f.get("method"));
        // each round writes its OWN test classes — Java forbids two public classes of the same
        // name, and append-only additions keep earlier rounds' commits intact
        Repo.TestPathGuess guess = Repo.guessTestPath(srcPath, round, method == null ? "" : method);
        String existingTest = guess.exists() ? Repo.readFileSafe(guess.path(), 12000) : null;
        if (existingTest == null || existingTest.isEmpty()) {
            // no test for this class yet → hand the LLM a sibling test to imitate (assertion
            // library, base classes, naming and package conventions)
            Repo.StyleReference ref = Repo.findStyleReference(p);
            existingTest = ref == null ? null
                    : "// STYLE REFERENCE — an existing test from this repo (" + ref.path() + ").\n"
                    + "// Imitate its imports, assertion style and conventions. Do not modify it.\n"
                    + ref.content();
        }
        String fqcn = State.jsTruthy(f.get("fqcn")) ? str(f.get("fqcn")) : Repo.fqcnOf(srcPath);
        // only the part of the class the model needs for THIS method — plus, when the survivors
        // sit in OTHER overloads of the same name, those too. A unit is `path::name` and PIT
        // scores every overload under it, so a prompt built from `methodLine` alone asks the
        // model to kill mutants on code it cannot see. See Repo.methodContextOf.
        List<Integer> survivorLines = mutantMaps(f.get("lastSurvived")).stream()
                .map(m -> intOrNull(m.get("line")))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Repo.MethodContext mctx = (method != null && !method.isEmpty())
                ? Repo.methodContext(srcPath, method, intOrNull(f.get("methodLine")), survivorLines) : null;

        String visibility = (method != null && !method.isEmpty()) ? JavaSrc.methodVisibility(whole, method) : null;
        List<List<JavaSrc.MethodRef>> routes = (method != null && !method.isEmpty())
                ? JavaSrc.routesTo(whole, method) : List.of();
        JsonNode uncovered = Coverage.uncoveredLines(coverageIo(), p);
        List<String> attempted = strings(f.get("attemptedMutants"));
        // Ordering comes from PIT's own data (mutator kind, SURVIVED vs NO_COVERAGE) and from
        // what has ACTUALLY been killed in this run — never from the model's opinion about what
        // it can kill, which was confidently wrong four rounds running.
        // Never offer a mutant we have already tried and failed to kill. Each round re-measures,
        // so the list is fresh — a kill often takes neighbours with it and those simply
        // disappear — but a survivor we already attacked and left alive is a known dead end for
        // this approach, and re-picking it burns the round.
        List<Map<String, Object>> survived = rankSurvivors(eligible(mutantMaps(f.get("lastSurvived")), attempted));
        // the mutant this round is aiming at, so a repair does not flip an assertion and abandon
        // the very thing the test was written for
        Map<String, Object> storedTarget = asMap(f.get("targetMutant"));
        Rounds.TargetMutant target = Rounds.targetForRound(targetMutantOf(storedTarget), round,
                (int) State.asLong(f.get("attempts")));

        Double coverage = num(f.get("coverage"));
        Double covPhaseMaxPct = nullish(state().run.config.covPhaseMaxPct(), 0.0);
        int mutantsPerRound = Rounds.mutantsForRound(state().run.config.mutantsPerRound(),
                (int) State.asLong(f.get("consecutiveMisses")));
        int mutantChoices = nullish(state().run.config.mutantChoices(), 20);
        // whether the last round's test even executed this method — a missed round whose
        // coverage never moved off zero was never about the assertion
        boolean sayLastRound = Boolean.FALSE.equals(f.get("lastTargetKilled"))
                || State.jsTruthy(f.get("consecutiveMisses"))
                || State.jsTruthy(f.get("lastRoundBroken"));
        boolean broken = State.jsTruthy(f.get("lastRoundBroken"));
        Map<String, Object> lastRoundWire = null;
        Prompts.LastRound lastRound = null;
        if (sayLastRound) {
            double cov = coverage == null ? 0 : coverage;
            String error = broken ? nullish(str(f.get("lastSuiteFailure")), "") : "";
            lastRoundWire = new LinkedHashMap<>();
            lastRoundWire.put("reached", cov > 0);
            lastRoundWire.put("coverage", cov);
            // a third outcome: the test never compiled, so neither "it did not reach the method"
            // nor "its assertion was wrong" describes what happened
            lastRoundWire.put("broken", broken);
            lastRoundWire.put("error", error);
            lastRound = new Prompts.LastRound(broken, error, cov > 0, cov);
        }
        List<String> constraints = rules.testWritingConstraints();
        String pkg = fqcn.contains(".") ? fqcn.substring(0, fqcn.lastIndexOf('.')) : "";
        String className = fqcn.substring(fqcn.lastIndexOf('.') + 1);
        String module = State.jsTruthy(f.get("module")) ? str(f.get("module")) : Repo.moduleOf(srcPath);

        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("ok", true);
        wire.put("path", srcPath);
        wire.put("unit", p);
        wire.put("source", source);
        // split(-1), because JS split keeps the trailing empty field
        wire.put("sourceLines", (source == null || source.isEmpty()) ? 0 : source.split("\n", -1).length);
        wire.put("methodSource", mctx == null ? null : mctx.body());
        wire.put("classHeader", mctx == null ? null : mctx.header());
        wire.put("siblingSignatures", mctx == null ? null : mctx.signatures());
        // a test cannot call a private method, so the prompt has to name the public route in —
        // and a private method with no caller at all is not worth a round
        wire.put("visibility", visibility);
        // the path a test must travel from public API to this method, when it is not public
        wire.put("routes", routes);
        wire.put("uncovered", uncovered);
        wire.put("coverage", coverage);
        wire.put("missedLines", f.get("missedLines"));
        wire.put("executableLines", f.get("executableLines"));
        wire.put("covPhaseMaxPct", covPhaseMaxPct);
        // a batch by default, one mutant after a miss — see Rounds.mutantsForRound
        wire.put("mutantsPerRound", mutantsPerRound);
        wire.put("mutantChoices", mutantChoices);
        wire.put("totalMutants", f.get("totalMutants"));
        // the method this unit is about: the tests must concentrate here
        wire.put("method", (method == null || method.isEmpty()) ? null : method);
        wire.put("methodLine", State.jsTruthy(f.get("methodLine")) ? f.get("methodLine") : null);
        wire.put("rounds", rounds);
        wire.put("survived", survived);
        wire.put("attemptedMutants", attempted.size());
        wire.put("targetMethod", State.jsTruthy(f.get("targetMethod")) ? f.get("targetMethod") : null);
        // the STORED object, not the reconstructed record: `targetForRound` only decides whether
        // this round may claim it, and the workflow reads fields the record does not carry
        wire.put("targetMutant", target == null ? null : storedTarget);
        wire.put("lastRound", lastRoundWire);
        wire.put("fqcn", fqcn);
        wire.put("package", pkg);
        wire.put("className", className);
        wire.put("module", module);
        // both generated class names for THIS round, already Surefire/Gradle-includable
        wire.put("covTestPath", guess.covPath());
        wire.put("mutTestPath", guess.mutPath());
        wire.put("projectTestPath", guess.exists() ? guess.path() : null);
        wire.put("testExists", guess.exists());
        wire.put("round", round);
        wire.put("existingTest", existingTest);
        wire.put("buildTool", runnerField("tool"));
        wire.put("testFramework", runnerField("testFramework"));
        wire.put("testFrameworkVersion", runnerField("testFrameworkVersion"));
        wire.put("jdk", runnerJdk("chosen"));
        wire.put("constraints", constraints);

        Prompts.Gaps gaps = Prompts.Gaps.builder()
                .fqcn(fqcn).path(srcPath).module(module).jdk(intOrNull(runnerJdk("chosen"))).pkg(pkg)
                .method((method == null || method.isEmpty()) ? null : method)
                .methodLine(intOrNull(f.get("methodLine")))
                .testFramework(str(runnerField("testFramework")))
                .source(source)
                .methodSource(mctx == null ? null : mctx.body())
                .classHeader(mctx == null ? null : mctx.header())
                .siblingSignatures(mctx == null ? null : mctx.signatures())
                .visibility(visibility).routes(routes)
                .coverage(coverage).covPhaseMaxPct(covPhaseMaxPct)
                .uncovered(uncoveredOf(uncovered))
                .survived(survived.stream().map(Server::promptMutant).toList())
                .attemptedMutants(attempted.size())
                .mutantsPerRound(mutantsPerRound)
                .covTestPath(guess.covPath()).mutTestPath(guess.mutPath())
                .projectTestPath(guess.exists() ? guess.path() : null)
                .existingTest(existingTest).constraints(constraints)
                .lastRound(lastRound)
                .targetMutant(target == null ? null : new RoundOutcome.Mutant(target.mutator(), target.line()))
                .build();
        return new UnitGaps(gaps, wire);
    }

    /// Would both phases skip for this unit — i.e. is there no work a round could do?

    /// Every test source that could already carry a target's marker, for one unit.
    ///
    /// Rounds 1..rounds — the generated files that EXIST — plus the project's own test. Not
    /// this round's paths: those are what the round is about to write, so reading them finds
    /// nothing and the dedup silently becomes a no-op from round 2 onward.
    static List<String> writtenTestSources(String file) {
        Map<String, Object> f = fileRecord(file);
        List<String> sources = new ArrayList<>();
        String srcRel = str(f.get("path"));
        String unitMethod = str(f.get("method"));
        int roundsDone = (int) State.asLong(f.get("rounds"));
        for (int past = 1; past <= roundsDone; past++) {
            Repo.TestPathGuess g = Repo.guessTestPath(srcRel, past, unitMethod == null ? "" : unitMethod);
            for (String rel : new String[]{g.mutPath(), g.covPath()}) {
                if (rel == null || rel.isEmpty()) continue;
                String c = Repo.readFileSafe(rel, 200000);
                if (c != null && !c.isEmpty()) sources.add(c);
            }
        }
        Repo.TestPathGuess own = Repo.guessTestPath(srcRel, 1, unitMethod == null ? "" : unitMethod);
        if (own.exists()) {
            String c = Repo.readFileSafe(own.path(), 200000);
            if (c != null && !c.isEmpty()) sources.add(c);
        }
        return sources;
    }

    /// Surviving lines this unit has no test for yet — what a batch round would actually ask.
    ///
    /// ONE computation, used by both the route that builds the prompt and the settle test that
    /// decides whether the unit is finished. They used to predict it separately and disagree:
    /// the settle test asked the retired one-mutant prompt, which sees survivors and says
    /// "work remains" exactly when every marker is already written and the batch prompt would
    /// skip. The unit then produced no test, missed, and paid a full verify — a JaCoCo build
    /// plus a PIT run — three times before stopping.
    static List<Targets.Target> pendingTargetsFor(String file) {
        Map<String, Object> f = fileRecord(file);
        List<Map<String, Object>> survivors = mutantMaps(f.get("lastSurvived"));
        return Targets.targetsFor(survivors.stream().map(Server::targetsMutant).toList(),
                writtenTestSources(file)).pending();
    }

    static boolean nothingToAsk(String file) {
        try {
            Prompts.Gaps gaps = gapsFor(file).prompts();
            // mutationPrompt is the RETIRED one-mutant prompt; the mutation phase builds
            // batchPrompt. They skip on different conditions and disagree in exactly the case
            // that matters: when every surviving line already carries its marker, batchPrompt
            // skips and writes nothing, while mutationPrompt sees survivors and does not — so
            // the unit was judged "still has work", produced no test, missed, and paid a full
            // verify (a JaCoCo build plus a PIT run) three times over before stopping.
            return Boolean.TRUE.equals(Prompts.coveragePrompt(gaps).skip())
                    && pendingTargetsFor(file).isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /// How a unit PIT could not measure is settled.
    ///
    /// NOT `failed`, which is what this used to write. `candidates()` carries a circuit breaker
    /// that ends the run at ten failures, and its own message says what it is for — "the repo or
    /// its toolchain is the problem, not the units". An empty constructor at 100% line coverage
    /// that PIT emits no mutants for is not evidence about the toolchain, and on
    /// run-1785455296408 ten of them ended the run with 34 improvable units still on the list,
    /// under the word "done".
    ///
    /// The status is the verdict's own label, so the two cannot drift. `attempts` is still pinned
    /// to the cap: settling it is right — Property#<init> spent three attempts relearning the
    /// same thing — and only calling it a failure was wrong.
    ///
    /// Extracted so this is testable at all. Reaching the branch it came from needs a real PIT
    /// run, which is exactly how the old behaviour survived: the consumer was easy to test with a
    /// status the producer never wrote, and the producer was not tested.
    static Map<String, Object> unmeasuredPatch(String reason, int maxAttempts) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("status", Rounds.Kind.UNMEASURED.label());
        patch.put("failure", reason);
        patch.put("attempts", maxAttempts);
        return patch;
    }

    /// The candidate queue and whether the batch is finished — the body of
    /// `GET /api/files/candidates`.
    static Map<String, Object> candidates() {
        State.Config cfg = state().run.config;
        int maxAttempts = orDefault(cfg.maxAttemptsPerFile(), 3);
        List<Map<String, Object>> all = allFiles();
        // ledger-replayed units were settled in PREVIOUS batches — they must not consume this
        // batch's quota. Neither do FAILURES: a unit we could not even measure says nothing about
        // the repo, and letting one broken measurement spend the whole quota is how java-dataloader
        // produced 0 PRs from its first pick. Failures are bounded separately so a repo that fails
        // on everything still terminates.
        long processed = all.stream()
                .filter(f -> List.of("improved", "no_improvement").contains(String.valueOf(f.get("status")))
                        && !State.jsTruthy(f.get("fromLedger")))
                .count();
        long failed = all.stream()
                .filter(f -> "failed".equals(str(f.get("status"))) && !State.jsTruthy(f.get("fromLedger")))
                .count();
        int maxFailures = orDefault(cfg.maxFailures(), 10);

        List<Map<String, Object>> candidateWire = new ArrayList<>();
        List<Select.Unit> ranked = new ArrayList<>();
        for (Map<String, Object> f : all) {
            if (!"candidate".equals(str(f.get("status")))) continue;
            if (State.asLong(f.get("attempts")) >= maxAttempts) continue;
            // a class JaCoCo reports no executable lines for cannot be tested or mutated
            Double executable = num(f.get("executableLines"));
            if (executable != null && executable <= 0) continue;
            // `path` here is the UNIT KEY (`<file>::<method>`) — it is what every downstream
            // endpoint expects back from the pick. The source file and method are carried
            // alongside so the pick prompt can show something a human (or an LLM) can reason about.
            String key = State.jsTruthy(f.get("key")) ? str(f.get("key")) : str(f.get("path"));
            String file = str(f.get("path"));
            String method = State.jsTruthy(f.get("method")) ? str(f.get("method")) : null;
            String reach = reachOf(file, method);

            // Can this unit yield a kill at all? Skip it here, before it becomes a candidate,
            // or it costs a baseline coverage run, a PIT run, a model call and a verification
            // to discover it never could.
            //
            // Observed on a from-scratch JSON-java run: Cookie#<init>, CookieList#<init>,
            // Property#<init>, StringBuilderWriter#close and #flush are all empty bodies, so
            // PIT emits nothing and reports "no tests to run" — and every one of them was
            // picked, measured and written off in turn.
            //
            // The predicate existed and was tested before this call site did, which is the
            // whole reason those units were still being picked: a filter nothing calls filters
            // nothing. CandidateEligibilityTest asserts against THIS list, not the predicate.
            Select.Unit candidate = new Select.Unit(key, method, num(f.get("mac")), num(f.get("coverage")), reach,
                    intOrNull(f.get("executableLines")), intOrNull(f.get("lines")),
                    (int) State.asLong(f.get("missesEver")), boolOrNull(f.get("everReached")),
                    // the baseline this run measured, plus the completed attempts, so the
                    // ranking can see a unit that has been taken on and has not moved
                    num(f.get("macBefore")), (int) State.asLong(f.get("attempts")));
            int mutants = (int) State.asLong(f.get("totalMutants"));
            boolean measured = f.get("totalMutants") != null;
            // Only judge a unit PIT has actually measured. An unmeasured one has no mutant
            // count yet, and reading that absence as "zero mutants" would drop every candidate
            // before the first baseline ever ran.
            if (measured && !Select.eligibleUnit(candidate, mutants, Select.exhausted(unitState(f)))) {
                continue;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", key);
            row.put("file", file);
            row.put("method", method);
            row.put("coverage", f.get("coverage"));
            row.put("mutation", f.get("mutation"));
            row.put("mac", f.get("mac"));
            row.put("attempts", f.get("attempts"));
            row.put("lines", f.get("lines"));
            row.put("mutants", f.get("totalMutants"));
            row.put("executableLines", f.get("executableLines"));
            // what the RUNS have shown about getting at this unit, as opposed to what the call
            // graph promises: rounds that wrote a passing test and still executed none of it
            row.put("misses", State.asLong(f.get("missesEver")));
            row.put("everReached", f.get("everReached"));
            row.put("reach", reach);
            candidateWire.add(row);
            ranked.add(candidate);
        }
        // Weakest first, constructors last among equals (usually field assignment with nothing to
        // assert — they kept winning the pick on real repos), a directly callable method ahead of
        // one behind private hops, and the most executable code to break the rest. Units no public
        // path reaches are ranked last: see Select.rankUnits.
        List<Map<String, Object>> list = reorderUnits(candidateWire, Select.rankUnits(ranked).units());

        boolean done = false;
        String reason = "";
        if (orDefault(cfg.maxIterations(), 0) > 0 && state().run.iteration >= cfg.maxIterations()) {
            done = true;
            reason = "max iterations (" + cfg.maxIterations() + ") reached";
        } else if (orDefault(cfg.scopeLimit(), 0) > 0 && processed >= cfg.scopeLimit()) {
            done = true;
            reason = "scope limit (" + cfg.scopeLimit() + " units) reached";
        } else if (failed >= maxFailures) {
            done = true;
            reason = failed + " units failed to measure (limit " + maxFailures
                    + ") — the repo or its toolchain is the problem, not the units";
        } else if (list.isEmpty()) {
            done = true;
            reason = "no remaining candidate units";
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("done", done);
        out.put("reason", reason);
        out.put("iteration", state().run.iteration);
        out.put("processed", processed);
        out.put("failed", failed);
        out.put("candidates", list.subList(0, Math.min(100, list.size())));
        return out;
    }

    /// The candidate rows in the order {@link Select#rankUnits} put their keys in — the same
    /// reconstruction {@link #reorder} performs for mutants, and for the same reason.
    private static List<Map<String, Object>> reorderUnits(List<Map<String, Object>> rows, List<Select.Unit> ordered) {
        Map<String, List<Map<String, Object>>> byPath = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            byPath.computeIfAbsent(String.valueOf(r.get("path")), k -> new ArrayList<>()).add(r);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Select.Unit u : ordered) {
            List<Map<String, Object>> bucket = byPath.get(String.valueOf(u.path()));
            if (bucket != null && !bucket.isEmpty()) out.add(bucket.remove(0));
        }
        return out;
    }

    // ── route table ────────────────────────────────────────────────────────────

    /// One route. The two arguments are the JS `(q, body)`: the query string and the parsed
    /// request body, the latter always an empty map for GET.
    @FunctionalInterface
    public interface Route {
        Object handle(Query query, Map<String, Object> body) throws IOException;
    }

    /// A response the caller has already serialised — `GET /api/state`, which must be the store
    /// byte for byte and takes the store's own monitor to say so.
    record RawJson(String json) {}

    /// A failure that is not a run failure: it carries the status the response must have.
    static final class HttpFailure extends RuntimeException {
        final int statusCode;

        HttpFailure(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    /// Every route the workflow calls, keyed exactly as it calls them: `"<METHOD> <path>"`.
    public static Map<String, Route> routes() {
        Map<String, Route> r = new LinkedHashMap<>();

        r.put("GET /api/health", (q, body) -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("service", "improve-java-tests");
            out.put("stage", state().stage.name());
            out.put("ts", System.currentTimeMillis());
            return out;
        });

        r.put("GET /api/state", (q, body) -> new RawJson(State.snapshotJson()));

        r.put("GET /api/metrics", (q, body) -> metricsPayload());

        // the live model dialog, served separately so the 2-second metrics poll stays small
        r.put("GET /api/llm/log", (q, body) -> {
            List<Map<String, Object>> entries;
            synchronized (State.STATE) { entries = new ArrayList<>(state().llmLog); }
            java.util.Collections.reverse(entries);
            return Map.of("entries", entries);
        });

        r.put("GET /api/rules", (q, body) -> {
            State.Rules cfgRules = state().run == null || state().run.config.rules() == null
                    ? State.envConfig().rules() : state().run.config.rules();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("rules", cfgRules);
            out.put("decisions", state().decisions);
            return out;
        });

        r.put("GET /api/events", (q, body) -> {
            Integer afterParsed = State.jsParseInt(or(q.get("after"), "0"));
            long after = afterParsed == null ? 0 : afterParsed;
            synchronized (State.STATE) {
                return Map.of("events", state().events.stream().filter(e -> e.seq() > after).toList());
            }
        });

        r.put("POST /api/stage", (q, body) -> {
            State.setStage(or(body.get("stage"), "idle"),
                    or(body.get("detail"), ""));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("stage", state().stage);
            return out;
        });

        r.put("POST /api/run/start", (q, body) -> {
            // one git worktree + one run state: overlapping executions would corrupt both.
            // `force` (used by the batch driver, which owns execution lifecycle) and a staleness
            // window let a genuinely dead run be taken over.
            // 'interrupted' means the sidecar restarted: whatever execution owned that run is
            // gone, so it can never be a real concurrency conflict.
            boolean active = state().run != null && "running".equals(state().run.status)
                    && !"interrupted".equals(state().stage.name());
            long idleSec = Util.nowSec() - state().stage.since();
            if (active && !State.jsTruthy(body.get("force")) && idleSec < 900) {
                throw new HttpFailure(409, "a run is already active (" + state().run.id + ", stage "
                        + state().stage.name() + ", " + idleSec + "s since last stage change) — "
                        + "stop it first or pass force:true");
            }
            // Held, not logged yet: the reset below clears the event log, and a line written
            // before it would be wiped. It belongs to the NEW run's log anyway — it explains
            // why this run exists.
            String takeover = active
                    ? "taking over stale/forced run " + state().run.id + " (idle " + idleSec + "s)"
                    : null;
            synchronized (State.STATE) {
                state().run = State.freshRun(body);
                // what this run learns about mutator kinds is about THIS repo and THIS run:
                // carrying it over demotes a mutator in run B for misses that happened in run A
                state().mutatorStats = new LinkedHashMap<>();
                state().currentUnit = null;
                state().files = new LinkedHashMap<>();
                state().decisions = new LinkedHashMap<>();
                state().prs = new ArrayList<>();
                state().pickFailures = 0;
                // The event log belongs to a run, like everything else reset here.
                //
                // It was the one thing left behind, and because it is a ring buffer persisted in
                // state.json it survived restarts too: a live run against stleary/JSON-java
                // opened with entries naming DelegatingDataLoader#clear and Assertions#nonNull —
                // units of java-dataloader, from a different run hours earlier against a
                // different repository. Every unit this run actually measured was org/json.
                //
                // The dashboard's log is what a person reads to judge what a run is doing, so a
                // reader could not tell which lines belonged to the run in front of them. Same
                // family as the rest of this project's scars: the work was real, the record
                // describing it was not.
                //
                // `seq` deliberately does NOT reset — GET /api/events?after= pages on it, and
                // rewinding it would make a new run's events look older than ones a polling
                // client already holds, so they would never be delivered.
                state().events.clear();
                if (State.jsTruthy(body.get("clearLedger"))) {
                    String slug = Util.slugify(state().run.config.repoUrl());
                    state().improvedLedger.remove(slug);
                    // AND in the store. Removing it from memory alone left the rows in H2, where
                    // nothing ever deletes them, so the next restart restored every cleared entry
                    // and the run went quietly back to skipping units it had been told to redo.
                    State.persistence().clearImprovedLedger(slug);
                }
            }
            if (takeover != null) State.event("starting", takeover);
            // the reachability memo is keyed by repo-RELATIVE path, so it would answer for the
            // previous repo's file of the same name
            clearReachCache();
            State.setStage("starting", "run " + state().run.id + " on " + state().run.config.repoUrl()
                    + "#" + state().run.config.repoBranch());
            State.save();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("run", state().run);
            return out;
        });

        r.put("POST /api/run/finish", (q, body) -> {
            needRun();
            state().run.status = or(body.get("status"), "done");
            state().run.finishedAt = Util.nowSec();
            State.setStage("done", "run finished: " + state().run.status + "; "
                    + state().prs.size() + " PR(s)");
            try {
                Repo.resetToBase();
            } catch (RuntimeException e) {
                // `.catch(() => {})`: the run is over either way, and a checkout that will not
                // reset must not turn a finished run into a failed one
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("run", state().run);
            out.put("prs", state().prs);
            return out;
        });

        r.put("POST /api/repo/clone", (q, body) -> {
            needRun();
            State.setStage("cloning", state().run.config.repoUrl());
            Repo.Cloned c = Repo.cloneRepo();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("dir", c.dir().toString());
            out.put("head", c.head());
            return out;
        });

        r.put("POST /api/repo/prepare", (q, body) -> {
            needRun();
            State.setStage("installing", "installing dependencies + tooling");
            Map<String, Object> det = Repo.install();
            List<String> files = Repo.listScopeFiles();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("runner", det);
            out.put("scopeFiles", files.size());
            return out;
        });

        r.put("POST /api/coverage/run", (q, body) -> {
            needRun();
            State.setStage(or(body.get("stage"), "improving_coverage"),
                    "running test suite with coverage");
            Coverage.Result res = Coverage.runCoverage(coverageIo());
            if ("baseline".equals(str(body.get("phase")))) {
                state().run.baseline.coveragePct = res.totalPct();
                expandFilesIntoMethodUnits(res.classes());
                // only now do `path::method` units exist — replaying the ledgers any earlier could
                // only ever match FILE keys, so every settled unit came back as a fresh candidate
                replayLedgers();
                // per-unit mac recompute
                synchronized (State.STATE) {
                    for (Map<String, Object> f : state().files.values()) {
                        f.put("mac", Util.mac(num(f.get("coverage")), num(f.get("mutation"))));
                    }
                }
            }
            state().run.result.coveragePct = res.totalPct();
            State.save();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("totalPct", res.totalPct());
            out.put("exitCode", res.exitCode());
            return out;
        });

        r.put("GET /api/files/candidates", (q, body) -> {
            needRun();
            State.setStage("picking_file", "evaluating candidate files");
            return candidates();
        });

        r.put("POST /api/rules/apply", (q, body) -> {
            needRun();
            String stage = str(body.get("stage"));
            Map<String, String[]> stageNames = new LinkedHashMap<>();
            stageNames.put("post_clone", new String[]{"applying_rules", "post-clone rules"});
            stageNames.put("pre_pick", new String[]{"applying_rules", "pre-pick rules"});
            stageNames.put("pick_file", new String[]{"picking_file", "picking a file to mutate"});
            stageNames.put("write_test", new String[]{"improving_coverage", "assembling test-writing constraints"});
            stageNames.put("check_changes", new String[]{"improving_mac", "checking whether changes are good"});
            stageNames.put("make_pr", new String[]{"preparing_pr", "composing PR per team rules"});
            String[] sn = stageNames.getOrDefault(stage, new String[]{"applying_rules", stage});
            State.setStage(sn[0], sn[1]);
            Map<String, Object> context = asMap(body.get("context"));
            if (context == null) context = new LinkedHashMap<>();
            if ("pick_file".equals(stage) && !State.jsTruthy(context.get("candidates"))) {
                context = new LinkedHashMap<>(Map.of("candidates", candidates().get("candidates")));
            }
            Object result = rules.apply(stage, context);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("stage", stage);
            out.put("result", result);
            return out;
        });

        r.put("POST /api/iteration/start", (q, body) -> {
            needRun();
            String file = str(body.get("file"));
            if (file == null || !hasFile(file)) throw new IllegalStateException("unknown file: " + file);
            if (!Boolean.TRUE.equals(state().run.overheadCharged)) {
                // First pick of this batch: everything before it was setup overhead. Keyed on a
                // flag, not on `iteration === 0` — a unit skipped for having no mutation surface
                // REFUNDS its iteration, so the counter returned to 0 and the next pick charged the
                // whole elapsed time again, growing each round. Three skips in a row reported
                // roughly three times the real setup cost as machine hours.
                state().run.overheadCharged = true;
                String slug = Util.slugify(state().run.config.repoUrl());
                synchronized (State.STATE) {
                    long prev = State.asLong(state().overheadLedger.get(slug));
                    state().overheadLedger.put(slug, prev + Math.max(0, Util.nowSec() - state().run.startedAt));
                }
            }
            // where this unit's own work begins on the branch — the timesheet must not count a
            // sibling method's already-committed tests as this one's effort
            try {
                Exec.Result head = Exec.run(List.of("git", "rev-parse", "HEAD"),
                        Exec.Options.of().withCwd(Repo.repoDir()).withTimeoutMs(10000L));
                if (head.code() == 0) {
                    State.upsertFile(file, mapOf("startSha", head.stdout().trim()));
                }
            } catch (RuntimeException e) {
                // a repo without commits cannot mislead the timesheet
            }
            // A re-picked unit starts its miss budget again. It used to carry the previous
            // attempt's count, so an attempt that began at 3/3 was finalised after a single
            // successful round however much was left to kill.
            State.upsertFile(file, mapOf("consecutiveMisses", 0));
            // close any stopwatch left running by a previous, abandoned attempt
            for (Map<String, Object> other : allFiles()) {
                if (State.jsTruthy(other.get("attemptStartedAt")) && !file.equals(str(other.get("path")))) {
                    accrueSpent(str(other.get("path")));
                }
            }
            state().run.iteration += 1;
            String template = branchTemplate();
            // branch (and therefore the PR) is per SOURCE FILE — the brief asks for a PR per file
            // — while the unit of work below it is the method
            String branch = template.replace("{file}", Util.fileSlug(unitPath(file)));
            State.setStage("picking_file", "iteration " + state().run.iteration + ": picked " + file);
            Repo.createBranch(branch);
            state().currentUnit = file;      // token usage is attributed to this unit
            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("status", "picked");
            patch.put("branch", branch);
            patch.put("attempts", State.asLong(fileRecord(file).get("attempts")) + 1);
            patch.put("rounds", 0);
            patch.put("roundBase", null);
            patch.put("lastSurvived", null);
            patch.put("attemptStartedAt", Util.nowSec());
            State.upsertFile(file, patch);
            State.save();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("file", file);
            out.put("branch", branch);
            out.put("iteration", state().run.iteration);
            return out;
        });

        r.put("POST /api/pit/run", (q, body) -> pitRun(body));

        r.put("GET /api/files/gaps", (q, body) -> {
            String p = q.get("path");
            if (p == null || p.isEmpty()) throw new IllegalArgumentException("path required");
            State.setStage("improving_coverage", "analysing coverage gaps in " + p);
            return gapsFor(p).wire();
        });

        // Prompt building lives in Prompts (unit-tested) rather than in a Code node, so the
        // workflow orchestrates and this decides what to ask for. The unit is named, never passed
        // in as a blob: the prompt must be built from the freshest state, not from whatever a node
        // captured several minutes and one PIT run ago.
        r.put("POST /api/prompt/coverage", (q, body) -> {
            needRun();
            return Prompts.coveragePrompt(gapsFor(unitArg(body)).prompts());
        });

        r.put("POST /api/prompt/mutation", (q, body) -> {
            needRun();
            return Prompts.mutationPrompt(gapsFor(unitArg(body)).prompts());
        });

        // One call per class instead of one per mutant per round. Survivors collapse to one
        // target per line, anything already named in the test sources is dropped without a model
        // call, and what is left is asked for in a single batch — then measured once.
        r.put("POST /api/prompt/batch", (q, body) -> {
            needRun();
            String file = unitArg(body);
            UnitGaps gaps = gapsFor(file);
            Map<String, Object> f = fileRecord(file);
            // The filter is only as good as the sources it reads, and it was reading the wrong
            // ones. `mutTestPath`/`covTestPath` on the wire are THIS round's paths — the files
            // the round is about to write — so they do not exist yet and contribute nothing.
            // The generated tests that DO exist are rounds 1..rounds, and those were never
            // read, so from round 2 onward the dedup was a no-op and every round re-asked the
            // model for every surviving line the earlier rounds had already covered.
            //
            // That is the whole point of the marker design (Targets.targetMarker): a round is
            // supposed to be self-limiting because the next one greps the file this one wrote
            // and finds nothing left to ask for.
            List<String> sources = writtenTestSources(file);
            List<Map<String, Object>> survivors = mutantMaps(f.get("lastSurvived"));
            Targets.Plan t = Targets.targetsFor(survivors.stream().map(Server::targetsMutant).toList(), sources);
            State.event("improving_mutation", unitLabel(file) + ": " + t.all().size() + " surviving line(s), "
                    + t.covered() + " already have a named test, asking for " + t.pending().size());
            // The pending set is decided by Targets and the prompt's own targets are grouped by
            // Prompts.groupTargets — which delegates to the same Targets.groupTargets — so the
            // markers on both sides are the same strings by construction, never by coincidence.
            Set<String> pendingMarkers = new LinkedHashSet<>(t.pending().stream().map(Targets.Target::marker).toList());
            List<Prompts.Target> pending = Prompts.groupTargets(survivors.stream().map(Server::promptMutant).toList())
                    .stream().filter(x -> pendingMarkers.contains(x.marker())).toList();
            Map<String, Object> out = toMap(Prompts.batchPrompt(gaps.prompts(), pending));
            out.put("targetsAll", t.all().size());
            out.put("targetsCovered", t.covered());
            out.put("targetsPending", t.pending().size());
            return out;
        });

        r.put("POST /api/prompt/repair", (q, body) -> {
            needRun();
            return Prompts.repairPrompt(gapsFor(unitArg(body)).prompts(),
                    new Prompts.Failure(str(body.get("summary"))),
                    convert(body.get("tests"), listOf(Prompts.TestFile.class), List.of()),
                    or(body.get("stage"), "improving_mutation"));
        });

        r.put("POST /api/tests/parse", (q, body) -> Parse.parseGeneratedTests(
                convert(body.get("resp"), Parse.Response.class, null),
                convert(body.get("plan"), Parse.Plan.class, new Parse.Plan(null, null, null))));

        r.put("POST /api/tests/parse-repair", (q, body) -> Parse.parseRepairedTests(
                convert(body.get("resp"), Parse.Response.class, null),
                convert(body.get("prev"), Parse.Parsed.class, null)));

        r.put("POST /api/test/write", (q, body) -> {
            needRun();
            try {
                Repo.Written w = Repo.writeTestFile(str(body.get("path")),
                        or(body.get("content"), ""));
                State.event("improving_coverage", "wrote " + w.path() + " (" + w.bytes() + " bytes)");
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", true);
                out.put("path", w.path());
                out.put("bytes", w.bytes());
                return out;
            } catch (RuntimeException e) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", false);
                out.put("error", message(e));
                return out;
            }
        });

        r.put("POST /api/test/delete", (q, body) -> {
            needRun();
            return Map.of("ok", Repo.deleteTestFile(str(body.get("path"))));
        });

        r.put("POST /api/test/write-many", (q, body) -> writeMany(body));

        r.put("POST /api/test/delete-many", (q, body) -> deleteMany(body));

        r.put("POST /api/test/run", (q, body) -> {
            needRun();
            if (State.jsTruthy(body.get("stage"))) {
                State.setStage(str(body.get("stage")),
                        "running tests " + (State.jsTruthy(body.get("path")) ? str(body.get("path")) : "(full suite)"));
            }
            try {
                Tests.Result res = Tests.runTests(testsIo(), State.jsTruthy(body.get("path")) ? str(body.get("path")) : null);
                // Keep the compiler's own words. When the round's test is deleted for breaking the
                // build, this is the only remaining account of WHY — and the next round was being
                // told to fix an assertion in a file that never compiled.
                if (!res.passed() && state().currentUnit != null) {
                    State.upsertFile(state().currentUnit,
                            mapOf("lastSuiteFailure", State.clip(nullish(res.summary(), ""), 600)));
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", true);
                out.put("passed", res.passed());
                out.put("exitCode", res.exitCode());
                out.put("summary", res.summary());
                return out;
            } catch (RuntimeException e) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", false);
                out.put("passed", false);
                out.put("error", message(e));
                return out;
            }
        });

        r.put("POST /api/llm/chat", (q, body) -> {
            if (State.jsTruthy(body.get("stage"))) {
                State.setStage(str(body.get("stage")),
                        or(body.get("stageDetail"), "consulting LLM"));
            }
            try {
                Integer asked = State.jsParseInt(or(body.get("maxTokens"), "4096"));
                ChatReply res = llm.chat(new ChatRequest(
                        str(body.get("system")), str(body.get("prompt")),
                        convert(body.get("messages"), listOf(mapType()), null),
                        str(body.get("stage")), str(body.get("stageDetail")),
                        Util.clamp(asked == null ? 4096 : asked, 64, 12000),
                        num(body.get("temperature")), State.jsTruthy(body.get("json"))));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", true);
                out.put("text", res.text());
                out.put("json", res.json());
                return out;
            } catch (RuntimeException e) {
                State.event("llm", "LLM error: " + message(e));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", false);
                out.put("error", message(e));
                return out;
            }
        });

        r.put("POST /api/test/cleanup", (q, body) -> cleanup(body));

        r.put("POST /api/verify", (q, body) -> verify(body));

        r.put("POST /api/round/miss", (q, body) -> {
            needRun();
            String file = str(body.get("file"));
            if (!hasFile(file)) throw new IllegalStateException("unknown unit: " + file);
            Map<String, Object> f = fileRecord(file);
            // throw away only this round's uncommitted test; every accepted round before it stays
            // committed on the branch
            Repo.discardUncommitted();
            // Decide here. This used to echo f.continueRounds — the PREVIOUS round's answer — so a
            // verify that failed to measure sent back a stale `true` and the workflow looped for
            // ever, spending a suite, a JaCoCo run and a PIT run per cycle with nothing advancing.
            Rounds.MissOutcome outcome = Rounds.missOutcome(
                    (int) State.asLong(f.get("consecutiveMisses")),
                    nullish(state().run.config.maxConsecutiveMisses(), 3),
                    // the COUNT of what is still worth attacking. `|| null` turned the meaningful
                    // answer 0 into "unknown", so "no surviving mutants left — stop" could never
                    // fire and the loop kept ordering rounds that had nothing to target
                    eligible(mutantMaps(f.get("lastSurvived")), strings(f.get("attemptedMutants"))).size(),
                    // Did this round have anything to ask the model for at all? Both phases
                    // skipping means no test was written — nothing to retry, and nothing a further
                    // attempt would change either.
                    nothingToAsk(file));
            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("consecutiveMisses", outcome.consecutiveMisses());
            patch.put("continueRounds", outcome.continueRounds());
            // THE ROUND IS OVER, so its file list ends with it. The only reset used to sit in the
            // `targetMutant != null` branch of writeTests, and a batch round never carries a
            // target — so the list accumulated across an attempt and `testsPresent` was answering
            // about a mixture of this round's files and earlier ones. That is the fact the next
            // prompt's "it did not compile" depends on. Reset at the BOUNDARY, not per write:
            // generation and repair are two writes inside one round and must still accumulate.
            patch.put("roundTestPaths", List.of());
            State.upsertFile(file, patch);
            // and do not spend further attempts on it
            if (outcome.settle()) {
                State.upsertFile(file, mapOf("attempts", orDefault(state().run.config.maxAttemptsPerFile(), 3)));
            }
            State.event("improving_mac", "dropped the round's test for " + unitLabel(file)
                    + " — " + outcome.verdict());
            State.save();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("file", file);
            out.put("continueRounds", outcome.continueRounds());
            out.put("consecutiveMisses", outcome.consecutiveMisses());
            out.put("rounds", State.asLong(f.get("rounds")));
            return out;
        });

        r.put("POST /api/round/accept", (q, body) -> {
            needRun();
            String file = str(body.get("file"));
            if (!hasFile(file)) throw new IllegalStateException("unknown file: " + file);
            Map<String, Object> f = fileRecord(file);
            long rounds = State.asLong(f.get("rounds")) + 1;
            // commit this round's tests so a later degrading round can be dropped alone
            try {
                pr.commit("test: improve MAC of " + file + " (round " + rounds + ")");
            } catch (RuntimeException e) {
                State.event("improving_mac", "round " + rounds + " commit note: " + message(e));
            }
            Map<String, Object> roundBase = new LinkedHashMap<>();
            roundBase.put("coverage", f.get("coverageAfter"));
            roundBase.put("mutation", f.get("mutationAfter"));
            roundBase.put("mac", f.get("macAfter"));
            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("rounds", rounds);
            patch.put("roundBase", roundBase);
            // the round ended, and it ended well — its file list ends with it either way, so the
            // next round's `testsPresent` is about the next round's files. See /api/round/miss.
            patch.put("roundTestPaths", List.of());
            State.upsertFile(file, patch);
            // showMetric for the reason the round-verdict line below gives: macAfter is a boxed
            // Double, so plain concatenation prints "mac now 100.0" where the JS template
            // literal printed "mac now 100", and this line is read by people.
            State.event("improving_mac", "round " + rounds + " accepted for " + file
                    + " (mac now " + Util.showMetric(num(f.get("macAfter"))) + ")");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("file", file);
            out.put("rounds", rounds);
            out.put("continueRounds", State.jsTruthy(f.get("continueRounds")));
            return out;
        });

        r.put("POST /api/round/drop", (q, body) -> roundDrop(body));

        r.put("POST /api/pr/create", (q, body) -> prCreate(body));

        r.put("POST /api/iteration/discard", (q, body) -> discard(body));

        r.put("POST /api/admin/purge-repo", (q, body) -> purgeRepo(body));

        r.put("POST /api/admin/reset", (q, body) -> {
            synchronized (State.STATE) {
                state().run = null;
                state().files = new LinkedHashMap<>();
                state().decisions = new LinkedHashMap<>();
                state().prs = new ArrayList<>();
                state().events = new ArrayList<>();
                state().seq = 0;
            }
            State.setStage("idle", "state reset");
            return Map.of("ok", true);
        });

        return r;
    }

    /// The route table, built once. The JS object literal is evaluated once too.
    private static final Map<String, Route> ROUTES = routes();

    // ── the long routes, kept out of the table so it stays readable ────────────

    static Object pitRun(Map<String, Object> body) {
        needRun();
        String file = str(body.get("file"));
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("file required");
        boolean baseline = "baseline".equals(str(body.get("phase")));
        State.setStage(or(body.get("stage"), "improving_mutation"),
                "mutation testing " + unitLabel(file));
        Pit.PitResult res;
        try {
            res = Pit.runPit(file);
        } catch (RuntimeException e) {
            if (baseline) {
                // one broken file must not sink a full-repo run: record and move on
                State.event("improving_mutation", "PIT failed on " + file + " — marking file failed: "
                        + State.clip(message(e), 250));
                long spentSec = accrueSpent(file);
                Double cov = num(fileRecord(file).get("coverage"));
                String failure = State.clip(message(e), 200);
                Map<String, Object> patch = new LinkedHashMap<>();
                patch.put("status", "failed");
                patch.put("coverageBefore", cov);
                patch.put("failure", failure);
                State.upsertFile(file, patch);
                recordMeasurement(file, mapOf("coverageBefore", cov, "failure", failure));
                Map<String, Object> metrics = new LinkedHashMap<>();
                metrics.put("spentSec", spentSec);
                metrics.put("coverageBefore", cov);
                metrics.put("failure", failure);
                putLedger(file, "failed", Measure.stamp(metrics));
                State.save();
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", false);
                out.put("failed", true);
                out.put("score", 0);
                out.put("survived", List.of());
                out.put("totalMutants", 0);
                out.put("error", State.clip(message(e), 500));
                return out;
            }
            throw e;
        }
        Map<String, Object> f = State.upsertFile(file, Map.of());
        Double fileMac = Util.mac(num(f.get("coverage")), res.score());
        // keep as many survivors as the model is offered to choose from, or it silently picks
        // from a shorter list than MUTANT_CHOICES promises
        int keepSurvivors = Math.max(10, nullish(state().run.config.mutantChoices(), 20));
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("mutation", res.score());
        patch.put("mac", fileMac);
        patch.put("totalMutants", res.totalMutants());
        patch.put("lastSurvived", storedMutants(res.survived(), keepSurvivors));
        State.upsertFile(file, patch);

        // Nothing to mutate → nothing to improve, and MAC is pinned at 0 for ever. Constants
        // holders, marker interfaces and annotations land here. Settle the file immediately
        // WITHOUT charging it to the batch's file quota, and let the workflow pick another:
        // spending a whole run on such a class is how the first real-repo sweep produced zero PRs
        // across ten repositories.
        int minMutants = nullish(state().run.config.minMutantsPerClass(), 3);
        Rounds.Baseline verdict = Rounds.classifyBaseline(
                new Rounds.PitResult(res.totalMutants(), res.noTests(), res.unmeasured()), minMutants);

        if (baseline && verdict.kind() == Rounds.Kind.UNTESTED) {
            // nothing tests it yet: that is a starting point, not a failure. Leave its numbers
            // unset and let the coverage phase write the first test.
            State.event("improving_mutation", unitLabel(file) + ": " + verdict.reason());
            State.save();
            Map<String, Object> out = spread(res);
            out.put("untested", true);
            out.put("reason", verdict.reason());
            return out;
        }
        if (baseline && verdict.kind() == Rounds.Kind.UNMEASURED) {
            // not measuring is not measuring zero: record nothing, claim nothing
            accrueSpent(file);
            // Do not retry it. A unit whose measurement is broken — PIT finding no tests for a
            // class JaCoCo says is covered — fails the same way every time, and each attempt costs
            // a coverage run, a PIT run and a model call. Property#<init> spent three.
            State.upsertFile(file, unmeasuredPatch(verdict.reason(),
                    orDefault(state().run.config.maxAttemptsPerFile(), 3)));
            State.event("improving_mutation", unitLabel(file) + ": " + verdict.reason()
                    + " — leaving its numbers unset");
            State.save();
            Map<String, Object> out = spread(res);
            out.put("skip", true);
            out.put("unmeasured", true);
            out.put("reason", verdict.reason());
            return out;
        }
        if (baseline && verdict.kind() == Rounds.Kind.NO_MUTANTS) {
            long spentSec = accrueSpent(file);
            // Skipping is FREE in both budgets. /api/iteration/start already charged an iteration
            // for this pick; give it back, or a couple of duds in a row end the run before a single
            // improvable class is ever attempted (which is exactly what happened to jackson-core,
            // json-path and concurrency-limits: two skips, done).
            state().run.iteration = Math.max(0, state().run.iteration - 1);
            Map<String, Object> p3 = new LinkedHashMap<>();
            p3.put("status", "no_mutants");
            p3.put("coverageBefore", f.get("coverage"));
            p3.put("mutationBefore", res.score());
            p3.put("macBefore", fileMac);
            State.upsertFile(file, p3);
            Map<String, Object> m = new LinkedHashMap<>(p3);
            m.remove("status");
            m.put("noMutants", true);
            recordMeasurement(file, m);
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("spentSec", spentSec);
            metrics.put("totalMutants", nullish(res.totalMutants(), 0));
            putLedger(file, "no_mutants", Measure.stamp(metrics));
            State.event("improving_mutation", unitLabel(file) + ": " + verdict.reason()
                    + ", skipping to the next method");
            State.save();
            Map<String, Object> out = spread(res);
            out.put("skip", true);
            out.put("reason", "too few mutants to improve");
            return out;
        }
        if (baseline) {
            Map<String, Object> p4 = new LinkedHashMap<>();
            p4.put("macBefore", fileMac);
            p4.put("coverageBefore", f.get("coverage"));
            p4.put("mutationBefore", res.score());
            State.upsertFile(file, p4);
            // survives batches even if this unit is never improved
            recordMeasurement(file, p4);
            refreshTotals();
            State.save();
        }
        return spread(res);
    }

    static Object writeMany(Map<String, Object> body) {
        needRun();
        String stage = str(body.get("stage"));
        if (State.jsTruthy(stage)) State.setStage(stage, "writing generated tests");
        // the model's own account of which mutant it chose and why — worth seeing on the
        // dashboard, since the choice drives the whole round
        if (State.jsTruthy(body.get("note"))) {
            State.event(State.jsTruthy(stage) ? stage : "improving_mutation",
                    State.clip(String.valueOf(body.get("note")), 300));
        }
        // remember WHICH mutant this round is trying to kill, so the next measurement can report
        // whether it actually died instead of leaving us to infer it from a score
        Map<String, Object> target = asMap(body.get("targetMutant"));
        if (target != null && state().currentUnit != null) {
            Map<String, Object> u = fileRecord(state().currentUnit);
            List<String> tried = new ArrayList<>(strings(u.get("attemptedMutants")));
            String key = Select.attemptKey(selectMutant(target));
            if (State.jsTruthy(target.get("line")) && !tried.contains(key)) tried.add(key);
            // stamp the round: a later round that targets nothing must not inherit this one
            Map<String, Object> stamped = new LinkedHashMap<>(target);
            stamped.put("round", State.asLong(u.get("rounds")) + 1);
            stamped.put("attempt", State.asLong(u.get("attempts")));
            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("targetMutant", stamped);
            patch.put("attemptedMutants", tried);
            // and neither must it inherit the last round's test files: those are still on disk
            // from a committed round, so verification would find them present and report this
            // round's mutant as having resisted a test this round never wrote
            patch.put("roundTestPaths", List.of());
            State.upsertFile(state().currentUnit, patch);
        }
        List<String> written = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        List<Map<String, Object>> tests = maps(body.get("tests"));
        for (Map<String, Object> t : tests.subList(0, Math.min(5, tests.size()))) {
            try {
                Repo.Written w = Repo.writeTestFile(str(t.get("path")),
                        or(t.get("content"), ""));
                written.add(w.path());
                State.event(State.jsTruthy(stage) ? stage : "improving_coverage",
                        "wrote " + w.path() + " (" + w.bytes() + " bytes)");
            } catch (RuntimeException e) {
                errors.add(mapOf("path", t.get("path"), "error", message(e)));
            }
        }
        // Remember what this round put on disk, so verification can check the files are still
        // there when PIT runs. A round whose test broke the suite gets it DELETED before
        // verification, and the resulting unchanged score is indistinguishable from a test that ran
        // and killed nothing — which is exactly how it was read, 41 times.
        //
        // Accumulated, not replaced: generation and repair are two calls within one round, and a
        // repair that writes nothing must not erase the file generation left behind.
        if (!written.isEmpty() && state().currentUnit != null) {
            Set<String> had = new LinkedHashSet<>(strings(fileRecord(state().currentUnit).get("roundTestPaths")));
            had.addAll(written);
            State.upsertFile(state().currentUnit, mapOf("roundTestPaths", new ArrayList<>(had)));
            // and into the repo's own record: the code, the tests, and the rule that asked for
            // them. Written here because this is the one place the generated content exists
            // before it is measured, repaired or deleted.
            captureAttempt(state().currentUnit, tests);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("written", written);
        out.put("errors", errors);
        return out;
    }

    // ── the repo's feedback file, as the orchestrator's controller calls it ────
    //
    // Public methods rather than entries in `routes()`. That table is reached in-process by
    // `Phase`; NOTHING serves it over HTTP, so a route added there answers 404 from outside the
    // container while every unit test passes — the inventory test asserts the map holds the key,
    // not that anyone serves it. Found by curling the deployed container, which is also how the
    // four routes in ApiController were found.

    /// Everything the repo has recorded, joined: each attempt with its outcome and any feedback
    /// aimed at it, plus the standing guidance. The shape GEPA consumes.
    public static Map<String, Object> feedbackRead() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        try {
            Path repo = Repo.repoDir();
            out.put("file", Feedback.fileIn(repo).toString());
            out.put("guidance", Feedback.guidance(repo));
            out.put("records", Feedback.join(repo));
        } catch (RuntimeException e) {
            // no run configured yet: an empty answer, not a 500. The dashboard asks on load,
            // before anyone has started anything.
            out.put("guidance", List.of());
            out.put("records", List.of());
        }
        return out;
    }

    /// Say something about the tests.
    ///
    /// `target` decides the scope by its shape: absent means the repo as a whole ("i don't like
    /// too many mocks in these tests"), a `path::method` means one unit, an attempt id means one
    /// generated test. `apply` additionally feeds it to the next round's write_test rule, and is
    /// honoured only for repo-scoped feedback — a critique of one test is not a policy.
    public static Map<String, Object> feedbackWrite(Map<String, Object> body) {
        String text = str(body.get("text"));
        if (text == null || text.isBlank()) throw new IllegalArgumentException("text required");
        Path repo = Repo.repoDir();
        String repoUrl = state().run == null ? "" : state().run.config.repoUrl();
        Map<String, Object> item = Feedback.feedback(repo, repoUrl,
                str(body.get("target")), text,
                State.jsTruthy(body.get("apply")), intOrNull(body.get("rating")));
        // NO State.event HERE, and this is not a style preference — it killed a run.
        //
        // This method runs on a WEB thread while the batch thread is writing events of its own.
        // `State.event` assigns a seq from the in-memory counter and the store assigns one from
        // IJT_STORE.last_seq; under concurrency those two disagree, and the insert failed:
        //
        //   Unique index or primary key violation: PRIMARY KEY ON IJT_EVENT(SEQ)
        //     (48448, 'feedback recorded: i do not like too many mocks in these tests', …)
        //
        // H2StatePersistence.appendEvent catches that — and catching a JPA constraint violation
        // does not un-poison the transaction it happened in. It surfaced at commit, inside the
        // batch step, and failed improveJob outright: "status: [FAILED] in 12s939ms". The run sat
        // dead for two hours with its status still reading `running`.
        //
        // Feedback is not a run event. It belongs to the repo's file, which is where it now goes
        // and nowhere else; the caller gets the confirmation in this response.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("feedback", item);
        // echoed, so a caller sees at once whether it will reach the next prompt
        out.put("guidance", Feedback.guidance(repo));
        return out;
    }

    /// What joins an attempt to the outcome that judges it.
    ///
    /// Attempt and round, not a counter: generation and repair are two writes inside one round
    /// and must share it, and `rounds` deliberately does not increment on a miss — so two
    /// consecutive missed rounds legitimately share an id, and the outcome closes whichever
    /// attempts are still open. See Feedback.join.
    static String roundIdFor(String unit) {
        Map<String, Object> f = fileRecord(unit);
        return (state().run == null ? "run" : state().run.id) + "|" + unit
                + "|a" + State.asLong(f.get("attempts")) + "|r" + State.asLong(f.get("rounds"));
    }

    /// Record the code, the generated tests and the rule that produced them.
    ///
    /// Never throws. Capturing a training example is not worth failing a round for, and this runs
    /// inside the write path of every round of every unit.
    static void captureAttempt(String unit, List<Map<String, Object>> tests) {
        try {
            Map<String, Object> f = fileRecord(unit);
            Map<String, Object> code = new LinkedHashMap<>();
            code.put("path", f.get("path"));
            code.put("method", f.get("method"));
            code.put("fqcn", f.get("fqcn"));
            code.put("methodLine", f.get("methodLine"));
            // the source the model was actually shown, not the whole file
            Repo.MethodContext ctx = str(f.get("method")) == null ? null
                    : Repo.methodContext(str(f.get("path")), str(f.get("method")),
                            intOrNull(f.get("methodLine")));
            if (ctx != null) {
                code.put("classHeader", ctx.header());
                code.put("methodSource", ctx.body());
            }
            List<Map<String, Object>> written = new ArrayList<>();
            for (Map<String, Object> t : tests) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("path", t.get("path"));
                one.put("content", t.get("content"));
                written.add(one);
            }
            Feedback.attempt(Repo.repoDir(), state().run == null ? "" : state().run.config.repoUrl(),
                    roundIdFor(unit), unit, code, written,
                    // THE candidate GEPA rewrites: the live write_test rule, guidance included
                    List.of(Rules.forServer().io().rule("write_test")));
        } catch (RuntimeException e) {
            System.err.println("feedback attempt not captured: " + e);
        }
    }

    /// Record what the round scored, closing the attempts that share its id.
    static void captureOutcome(String unit, String verdict, Map<String, Object> before,
                               Map<String, Object> after, Boolean broken) {
        try {
            Feedback.outcome(Repo.repoDir(), state().run == null ? "" : state().run.config.repoUrl(),
                    roundIdFor(unit), unit, verdict, before, after, broken);
        } catch (RuntimeException e) {
            System.err.println("feedback outcome not captured: " + e);
        }
    }

    static Object deleteMany(Map<String, Object> body) {
        needRun();
        // A batch round writes N tests into one file, and deleting the file for one bad assertion
        // costs all N — which is exactly what happened to the first unit of the v2 run: three
        // targets, two asserting wrongly, three tests lost and the round recorded as cov 0→0. When
        // the file COMPILED and the runner named the methods that failed, the bad ones can be cut
        // out instead. Only possible because the names were decided up front by Targets rather
        // than left to the model.
        String summary = State.jsTruthy(body.get("summary")) ? str(body.get("summary"))
                : (state().currentUnit == null ? "" : nullish(str(fileRecord(state().currentUnit).get("lastSuiteFailure")), ""));
        Set<String> failing = Salvage.failedTestNames(summary);
        List<String> deleted = new ArrayList<>();
        List<String> salvaged = new ArrayList<>();
        // the paths themselves, not the human-readable "(kept N passing test(s))" strings — the
        // red-suite guard below has to be able to delete them
        List<String> salvagedPaths = new ArrayList<>();
        for (String p : new LinkedHashSet<>(strings(body.get("paths")))) {
            // the same decision Pit makes when PIT refuses a red suite — one function, or the two
            // callers drift and one undoes the other's salvage
            String kept = Salvage.salvageSource(Repo.readFileSafe(p, 400000), failing);
            if (kept != null && !kept.isEmpty()) {
                Repo.writeTestFile(p, kept);
                salvaged.add(p + " (kept " + countOf(kept, "@Test") + " passing test(s))");
                salvagedPaths.add(p);
                continue;
            }
            if (Repo.deleteTestFile(p)) deleted.add(p);
        }
        String stage = State.jsTruthy(body.get("stage")) ? str(body.get("stage")) : "improving_coverage";
        if (!salvaged.isEmpty()) {
            // PROVE IT, then keep it. A salvage that leaves the suite red is worse than no
            // salvage at all: `verify` returns early on a red suite — "full suite red", before
            // the consecutiveMisses increment — so the round is never booked, the miss budget
            // never advances, and the unit rounds for ever. That was harmless only while salvage
            // could not fire; it fires now, so the guarantee has to be enforced rather than
            // assumed. The names came from a runner that may not have listed every failure, and
            // a file that also fails to compile has no names to give at all.
            Tests.Result after = Tests.runTests(testsIo(), null);
            if (after.passed()) {
                State.event(stage, "kept the passing tests and cut only the failing ones: "
                        + String.join(", ", salvaged));
            } else {
                for (String p : new ArrayList<>(salvagedPaths)) {
                    if (Repo.deleteTestFile(p)) deleted.add(p);
                }
                salvaged.clear();
                State.event(stage, "salvage left the suite red — deleted the file(s) outright: "
                        + String.join(", ", salvagedPaths)
                        + " — " + Salvage.whyItBroke(after.summary()));
            }
        }
        if (!deleted.isEmpty()) {
            // WITH THE REASON. This event fired 814 times in run-1785444794893 — 58% of every
            // miss the pipeline has ever recorded — and said only that something broke. The text
            // was in hand the whole time: `summary` above is lastSuiteFailure, read three lines
            // up to decide what to salvage. A test that will not compile and a test that asserts
            // the wrong thing need opposite remedies, and this is the only line that can tell
            // them apart. Empty means not captured, and reads that way rather than as a guess.
            String why = Salvage.whyItBroke(summary);
            State.event(stage, "deleted generated tests that broke the suite: " + String.join(", ", deleted)
                    + (why.isEmpty() ? " (no reason captured)" : " — " + why));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("deleted", deleted);
        out.put("salvaged", salvaged);
        return out;
    }

    /// The one prompt this file owns, because it is not a round's prompt: a strict editor pass
    /// over a generated test file, verified afterwards by re-running the suite and PIT.
    static final String CLEANUP_SYSTEM =
            "You are a strict test-code editor. Clean this generated test file: (1) REMOVE all "
            + "scratch/chain-of-thought comments — anything reasoning aloud (\"Wait\", \"Let's try\", "
            + "exploratory strategy essays, self-corrections). Keep at most ONE short comment per test "
            + "stating which mutant/behavior it verifies. (2) REMOVE tests that are vacuous (cannot "
            + "fail, assert nothing meaningful, or admit in comments they kill nothing). (3) Do NOT "
            + "change, weaken, or reorder any remaining test logic, imports, or setup. Reply with ONLY "
            + "the complete cleaned file content — no markdown fences, no explanation.";

    static Object cleanup(Map<String, Object> body) {
        needRun();
        String file = str(body.get("file"));
        Map<String, Object> f = fileRecord(file);
        State.setStage("preparing_pr", "cleaning up generated tests for " + file);
        // Accepted rounds are already COMMITTED, so the working tree is usually clean here —
        // select against the base branch, not `git status`.
        // Only this unit's own files: the verification below re-measures THIS unit, so a strip of
        // an earlier method's test would be judged by a score that cannot see it.
        Repo.TestPathGuess guess = Repo.guessTestPath(unitPath(file),
                (int) State.asLong(f.get("rounds")) + 1, nullish(unitMethod(file), ""));
        List<String> changed = Cleanup.ownedByUnit(pr.changedTestFiles(),
                new TestPaths.Generated(guess.covPath(), guess.mutPath()));
        if (changed.isEmpty()) {
            State.event("preparing_pr", "cleanup: no generated test files found for " + file);
        }
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, String> originals = new LinkedHashMap<>();
        for (String p : changed.subList(0, Math.min(5, changed.size()))) {
            String original = Repo.readFileSafe(p, 100000);
            if (original == null || original.isEmpty()) continue;
            try {
                ChatReply res = llm.chat(new ChatRequest(CLEANUP_SYSTEM, original, null, null, null,
                        9000, 0.1, false));
                String cleaned = Cleanup.extractCleanedFile(res.text());
                // the gate used to ask whether the result contained `it(` / `describe(` — vitest
                // shapes no Java file has, so every cleanup on a Java repo was rejected unread
                Cleanup.Verdict verdict = Cleanup.plausibleCleanup(original, cleaned);
                if (!verdict.ok()) {
                    State.event("preparing_pr", "cleanup of " + p + " not applied: " + verdict.reason());
                    results.add(mapOf("path", p, "kept", "original", "reason", verdict.reason()));
                    continue;
                }
                State.event("preparing_pr", "cleanup of " + p + ": " + verdict.testsBefore()
                        + " test(s) → " + verdict.testsAfter() + ", " + original.length() + "→"
                        + cleaned.length() + " bytes (pending re-measurement)");
                Repo.writeTestFile(p, cleaned);
                originals.put(p, original);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("path", p);
                row.put("kept", "cleaned");
                row.put("bytesBefore", original.length());
                row.put("bytesAfter", cleaned.length());
                results.add(row);
            } catch (RuntimeException e) {
                results.add(mapOf("path", p, "kept", "original", "reason", State.clip(message(e), 200)));
            }
        }
        List<Map<String, Object>> touched = results.stream()
                .filter(x -> "cleaned".equals(x.get("kept"))).toList();
        // The JS strips the `_original` it stashed on each row before answering. Here the
        // originals never join the response at all — a private map instead of a private field —
        // so there is no key to forget to remove.
        if (touched.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("cleaned", 0);
            out.put("results", results);
            return out;
        }
        // verified cleanup: suite must stay green AND mutation score must not drop
        Tests.Result suite = Tests.runTests(testsIo(), null);
        Double newScore = null;
        boolean scoreOk = false;
        if (suite.passed()) {
            try {
                Pit.PitResult st = Pit.runPit(file);
                newScore = st.score();
                double floor = nullish(num(f.get("mutationAfter")), nullish(num(f.get("mutation")), 0.0));
                scoreOk = newScore != null && newScore >= floor;
            } catch (RuntimeException e) {
                scoreOk = false;
            }
        }
        if (!suite.passed() || !scoreOk) {
            for (Map<String, Object> t : touched) Repo.writeTestFile(str(t.get("path")), originals.get(str(t.get("path"))));
            State.event("preparing_pr", "cleanup reverted: "
                    + (!suite.passed() ? "suite went red" : "mutation score dropped to " + newScore));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("cleaned", 0);
            out.put("reverted", true);
            out.put("results", results);
            return out;
        }
        Double cov = nullish(num(f.get("coverageAfter")), num(f.get("coverage")));
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("mutation", newScore);
        patch.put("mutationAfter", newScore);
        patch.put("mac", Util.mac(cov, newScore));
        patch.put("macAfter", Util.mac(cov, newScore));
        State.upsertFile(file, patch);
        // cleaned files are edits on top of committed rounds — commit them so the PR carries them
        try {
            pr.commit("test: tidy generated tests for " + file);
        } catch (RuntimeException e) {
            State.event("preparing_pr", "cleanup commit note: " + State.clip(message(e), 160));
        }
        State.event("preparing_pr", "cleanup kept: " + String.join(", ", touched.stream()
                .map(t -> t.get("path") + " " + t.get("bytesBefore") + "→" + t.get("bytesAfter") + "B").toList()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("cleaned", touched.size());
        out.put("mutationAfter", newScore);
        out.put("results", results);
        return out;
    }

    static Object verify(Map<String, Object> body) throws IOException {
        needRun();
        String file = str(body.get("file"));
        if (!hasFile(file)) throw new IllegalStateException("unknown file: " + file);
        Map<String, Object> f = fileRecord(file);
        State.setStage("improving_mac", "verifying MAC improvement for " + file);
        try {
            // The coverage run executes the suite, so ask it whether the suite passed instead of
            // running all 1163 tests a second time to learn the same thing. When its log does not
            // say (`passed: null`), run them properly — an unclear log must never read green.
            Coverage.Result cov = Coverage.runCoverage(coverageIo());
            Tests.Suite suite = cov.suite();
            if (suite == null || suite.passed() == null) {
                State.event("tests", "the coverage build did not report a test outcome — running the suite to be sure");
                Tests.Result ran = Tests.runTests(testsIo(), null);
                suite = new Tests.Suite(ran.passed(), null, null, null, ran.summary());
            } else {
                State.event("tests", "full suite: " + (suite.passed() ? "green" : "RED")
                        + (State.jsTruthy(suite.tests())
                            ? " (" + suite.tests() + " tests, "
                              + (State.asLong(suite.failures()) + State.asLong(suite.errors())) + " failing)" : "")
                        + " — from the coverage run, not a second execution");
            }
            if (!Boolean.TRUE.equals(suite.passed())) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", true);
                out.put("improved", false);
                out.put("testsGreen", false);
                out.put("reason", "full suite red");
                out.put("summary", suite.summary());
                out.put("file", file);
                return out;
            }
            // The unit IS a method, so PIT measures exactly it: the score needs no projection and
            // no class-wide reconciliation. MAC for the unit = method coverage x method mutation
            // score, both measured on the same method.
            State.setStage("improving_mac", "re-measuring mutation score of " + unitLabel(file));
            Pit.PitResult st = Pit.runPit(file);
            // Did the mutant this round aimed at actually die? An unchanged score says the round
            // achieved nothing but not WHY; this distinguishes "the test never ran", "the test ran
            // but does not distinguish the mutation" and "it worked".
            Rounds.TargetMutant tm = Rounds.targetForRound(targetMutantOf(asMap(f.get("targetMutant"))),
                    (int) State.asLong(f.get("rounds")) + 1, (int) State.asLong(f.get("attempts")));
            // Did the round's test survive to this measurement, or was it deleted for breaking the
            // suite? Asked of the file system, because everything below is a statement about what a
            // test did and none of it holds if there was no test.
            String brokenKey = (tm != null && tm.line() != 0)
                    ? Select.attemptKey(new Select.Mutant(tm.mutator(), tm.line())) : null;
            List<String> attempted = strings(f.get("attemptedMutants"));
            RoundOutcome.Evidence ev = RoundOutcome.evidence(strings(f.get("roundTestPaths")),
                    Repo::testFileExists,
                    st.survived() == null ? List.of() : st.survived().stream().map(Server::selectMutant).toList(),
                    attempted, brokenKey, state().run.config.mutantChoices());
            // OUTSIDE the outcome block below, because that block does not run in batch mode:
            // RoundOutcome.of answers null when there is no single target mutant, which is every
            // batch round, and batch is what the pipeline runs. Written from the evidence, which
            // needs no target, so the next round is told "it did not compile" instead of being
            // advised about an assertion in a file that never ran.
            State.upsertFile(file, mapOf("lastRoundBroken", RoundOutcome.broken(ev)));
            Map<String, Object> brokenMutants = asMap(f.get("brokenMutants"));
            RoundOutcome.Outcome outcome = RoundOutcome.of(
                    tm == null ? null : new RoundOutcome.Mutant(tm.mutator(), tm.line()),
                    st.survived() == null ? List.<RoundOutcome.Mutant>of()
                            : st.survived().stream().map(m -> new RoundOutcome.Mutant(m.mutator(),
                                    m.line() == null ? 0 : m.line())).toList(),
                    ev.testsPresent(), ev.wroteAny(), ev.otherEligible(),
                    (int) State.asLong(brokenMutants == null ? null : brokenMutants.get(brokenKey)));
            if (outcome != null) {
                long killedNow = st.killed() - (num(f.get("mutation")) != null && State.jsTruthy(f.get("totalMutants"))
                        ? Math.round((num(f.get("mutation")) / 100) * State.asLong(f.get("totalMutants"))) : 0);
                State.event("improving_mac", "targeted mutant " + outcome.message()
                        + (outcome.kind() == RoundOutcome.Kind.KILLED && killedNow > 1
                            ? " — and took " + (killedNow - 1) + " other mutant(s) with it" : "")
                        + (State.jsTruthy(st.testsRun()) ? " (" + st.testsRun() + " tests ran)" : ""));
                Map<String, Object> patch = new LinkedHashMap<>();
                // null (broken) must not read as a kill anywhere downstream
                patch.put("lastTargetKilled", outcome.stillAlive() == null ? null : !outcome.stillAlive());
                // so the next round's prompt says "it did not compile" instead of "the path is
                // right; the assertion is not" — opposite advice for opposite problems
                patch.put("lastRoundBroken", outcome.kind() == RoundOutcome.Kind.BROKEN);
                State.upsertFile(file, patch);
                if (outcome.countMutatorTry()) {
                    synchronized (State.STATE) {
                        if (state().mutatorStats == null) state().mutatorStats = new LinkedHashMap<>();
                        Map<String, Object> e = asMap(state().mutatorStats.get(tm.mutator()));
                        if (e == null) {
                            e = new LinkedHashMap<>();
                            e.put("tried", 0L);
                            e.put("killed", 0L);
                            state().mutatorStats.put(tm.mutator(), e);
                        }
                        long tried = State.asLong(e.get("tried")) + 1;
                        long killed = State.asLong(e.get("killed")) + (Boolean.TRUE.equals(outcome.stillAlive()) ? 0 : 1);
                        e.put("tried", tried);
                        e.put("killed", killed);
                        if (tried >= 2 && killed == 0) {
                            State.event("improving_mac", tm.mutator() + " has survived " + tried
                                    + " attempts in this run — demoting that mutator kind behind everything else");
                        }
                    }
                }
                if (outcome.kind() == RoundOutcome.Kind.BROKEN) {
                    // it was marked attempted when the test was WRITTEN; nothing since has tested it
                    Map<String, Object> broken = new LinkedHashMap<>(brokenMutants == null ? Map.of() : brokenMutants);
                    broken.put(brokenKey, State.asLong(broken.get(brokenKey)) + 1);
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("brokenMutants", broken);
                    if (outcome.unattempt()) {
                        p.put("attemptedMutants", attempted.stream().filter(k -> !k.equals(brokenKey)).toList());
                    }
                    State.upsertFile(file, p);
                }
                State.save();
            }
            Double coverageAfter = num(fileRecord(file).get("coverage"));
            // Did this round's test execute the method at all? Cheap to record here and impossible
            // to infer later — a unit can look perfectly reachable and never run.
            Map<String, Object> reachEvidence = new LinkedHashMap<>();
            if ((coverageAfter == null ? 0 : coverageAfter) > 0) {
                reachEvidence.put("everReached", true);
            } else {
                reachEvidence.put("everReached", Boolean.TRUE.equals(f.get("everReached")));
                reachEvidence.put("missesEver", State.asLong(f.get("missesEver")) + 1);
            }
            State.upsertFile(file, reachEvidence);
            // and WHICH mutants have been attacked: that register cost real rounds to build, and
            // losing it on a restart lets a unit spend another one on a mutant it has already
            // failed to kill
            reachEvidence.put("attemptedMutants", strings(fileRecord(file).get("attemptedMutants")));
            // persist it: this is the only evidence that a unit cannot be executed at all, and it
            // took whole rounds to obtain — losing it on every restart means paying again
            recordMeasurement(file, reachEvidence);
            Double macAfter = Util.mac(coverageAfter, st.score());
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("mutation", st.score());
            after.put("mac", macAfter);
            after.put("macAfter", macAfter);
            after.put("coverageAfter", coverageAfter);
            after.put("mutationAfter", st.score());
            after.put("lastSurvived", storedMutants(st.survived(),
                    Math.max(10, nullish(state().run.config.mutantChoices(), 20))));
            State.upsertFile(file, after);
            state().run.result.coveragePct = cov.totalPct();
            refreshTotals();
            State.save();

            String diff = pr.diffAgainstBase();
            // Test files only. `changedFiles()` includes the pom.xml this pipeline injects PIT and
            // JaCoCo into, so `changed.length > 0` was true in every round — and a round that wrote
            // no test at all could be credited with measurement noise.
            List<String> changed = pr.changedTestFiles();
            // round criterion: keep the round iff ≥1 metric improves AND none degrades
            Map<String, Object> rb = asMap(f.get("roundBase"));
            Double rbCov = rb != null ? num(rb.get("coverage")) : num(f.get("coverageBefore"));
            Double rbMut = rb != null ? num(rb.get("mutation")) : num(f.get("mutationBefore"));
            Double rbMac = rb != null ? num(rb.get("mac")) : num(f.get("macBefore"));
            boolean improvedAny = !changed.isEmpty() && (or0(coverageAfter) > or0(rbCov)
                    || or0(st.score()) > or0(rbMut) || or0(macAfter) > or0(rbMac));
            boolean degradedAny = or0(coverageAfter) < or0(rbCov)
                    || or0(st.score()) < or0(rbMut) || or0(macAfter) < or0(rbMac);
            // no changed files → any measured delta is PIT run-to-run noise, not improvement
            boolean improved = or0(macAfter) > or0(num(f.get("macBefore"))) && !changed.isEmpty();
            // remember the best result any attempt reached, even if it is not kept — "we tried and
            // got this far" is information worth showing
            Map<String, Object> prev = measured().get(Measure.normalizeUnitKey(file));
            Double prevBest = prev == null ? null : num(prev.get("attemptMac"));
            if (or0(macAfter) >= (prevBest == null ? -1 : prevBest)) {
                recordMeasurement(file, mapOf("attemptCoverage", coverageAfter,
                        "attemptMutation", st.score(), "attemptMac", macAfter));
            }
            int rounds = (int) State.asLong(f.get("rounds"));
            // 0 = uncapped (Rounds honours that); `|| 5` silently made the documented "uncapped"
            // setting a cap of five, and reported "round budget 5 spent"
            int maxRounds = nullish(state().run.config.maxRoundsPerFile(), 5);
            long elapsedSec = State.asLong(f.get("spentSec"))
                    + (State.jsTruthy(f.get("attemptStartedAt")) ? Util.nowSec() - State.asLong(f.get("attemptStartedAt")) : 0);
            Rounds.Decision d = Rounds.decide(Rounds.Options.of()
                    .withMacBase(rbMac).withMacAfter(macAfter)
                    .withImprovedAny(improvedAny).withDegradedAny(degradedAny)
                    .withRounds(rounds).withMaxRounds(maxRounds)
                    .withMinGapFrac(state().run.config.minRoundGapFrac())
                    .withMinGain(state().run.config.minRoundGain())
                    // granularity: one mutant of N is the smallest move a round can make, and the
                    // floors must never sit above it
                    .withTotalMutants(nullish(st.totalMutants(), intOrNull(f.get("totalMutants"))))
                    .withCoverage(coverageAfter)
                    .withElapsedSec(elapsedSec)
                    .withBudgetSec(orDefault(state().run.config.unitBudgetSec(), 0))
                    // a miss drops the round but not the unit: what matters is whether any
                    // un-attempted survivor remains and how many misses came in a row
                    .withConsecutiveMisses((int) State.asLong(f.get("consecutiveMisses")))
                    .withMaxMisses(nullish(state().run.config.maxConsecutiveMisses(), 3))
                    // Select.eligible, not a second hand-rolled copy of its key: this one spelled
                    // the key without the mutant index, so several distinct mutants sharing a kind
                    // and a line counted as one and the round was told there was nothing left to
                    // attack
                    .withSurvivorsLeft(Select.eligible(
                            st.survived() == null ? List.of() : st.survived().stream().map(Server::selectMutant).toList(),
                            attempted).size()));
            // A ROUND WHOSE TEST NEVER RAN IS NOT EVIDENCE ABOUT THE UNIT. It was written, it
            // broke the suite, it was deleted before the measurement, and the score was then
            // re-measured against an unchanged tree — which is why such rounds report coverage
            // bit-identical to the round before. Charging that against the three-miss budget is
            // how four JSONObject units were written off `no_improvement` in one evening without
            // a single generated test ever executing. See Rounds.afterRound for why the free
            // retries are bounded rather than unlimited.
            Rounds.MissBudget budget = Rounds.afterRound(d.keepRound(), RoundOutcome.broken(ev),
                    State.asLong(f.get("consecutiveMisses")), State.asLong(f.get("brokenRounds")),
                    Rounds.DEFAULT_MAX_BROKEN_ROUNDS);
            State.upsertFile(file, mapOf(
                    "consecutiveMisses", budget.consecutiveMisses(),
                    "brokenRounds", budget.brokenRounds()));
            // Here, and not after /api/round/accept: `rounds` increments there, so an outcome
            // written later would carry the NEXT round's id and close no attempt at all. At this
            // point roundIdFor still spells exactly what the attempts were written under.
            captureOutcome(file,
                    d.keepRound() ? "kept" : RoundOutcome.broken(ev) ? "broken" : "missed",
                    mapOf("coverage", rbCov, "mutation", rbMut, "mac", rbMac),
                    mapOf("coverage", coverageAfter, "mutation", st.score(), "mac", macAfter),
                    RoundOutcome.broken(ev));
            // showMetric, not raw interpolation: this line read "cov undefined→0, mut undefined→0,
            // mac null→0" for a unit whose every stored field was correctly null. The ledger
            // invented nothing; the sentence a human reads to judge the run did.
            State.event("improving_mac", "round " + (rounds + 1) + " of " + file + ": "
                    + "cov " + Util.showMetric(rbCov) + "→" + Util.showMetric(coverageAfter) + ", "
                    + "mut " + Util.showMetric(rbMut) + "→" + Util.showMetric(st.score()) + ", "
                    + "mac " + Util.showMetric(rbMac) + "→" + Util.showMetric(macAfter) + " — " + d.verdict());
            // remembered so /api/round/accept can echo it: the loop's IF then reads the verdict off
            // its direct input instead of reaching back to a node that has run several times in
            // this execution
            State.upsertFile(file, mapOf("continueRounds", d.continueRounds()));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("file", file);
            out.put("testsGreen", true);
            out.put("coverageBefore", f.get("coverageBefore"));
            out.put("coverageAfter", coverageAfter);
            out.put("mutationBefore", f.get("mutationBefore"));
            out.put("mutationAfter", st.score());
            out.put("macBefore", f.get("macBefore"));
            out.put("macAfter", macAfter);
            out.put("improved", improved);
            out.put("improvedAny", improvedAny);
            out.put("degradedAny", degradedAny);
            out.put("keepRound", d.keepRound());
            out.put("continueRounds", d.continueRounds());
            out.put("roundGain", d.gain());
            out.put("roundGapClosed", d.gapClosed());
            out.put("rounds", rounds);
            out.put("maxRounds", maxRounds);
            out.put("totalCoverage", cov.totalPct());
            out.put("changedFiles", changed);
            out.put("diff", State.clip(diff, 30000));
            out.put("branch", f.get("branch"));
            return out;
        } catch (RuntimeException | IOException e) {
            State.event("improving_mac", "verification failed: " + State.clip(message(e), 300));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("improved", false);
            out.put("testsGreen", false);
            out.put("error", message(e));
            out.put("file", file);
            return out;
        }
    }

    private static final Pattern DIFF_NEW_FILE = Pattern.compile("^\\+\\+\\+ b/(.+)$", Pattern.MULTILINE);

    static Object roundDrop(Map<String, Object> body) {
        needRun();
        String file = str(body.get("file"));
        if (!hasFile(file)) throw new IllegalStateException("unknown file: " + file);
        Map<String, Object> f = fileRecord(file);
        State.setStage("improving_mac", "finalizing " + file + " after "
                + State.asLong(f.get("rounds")) + " accepted round(s)");
        // drop the last (stale/degraded) round: uncommitted changes only
        Repo.discardUncommitted();
        Map<String, Object> rb = asMap(f.get("roundBase"));
        Double rbCov = rb != null ? num(rb.get("coverage")) : num(f.get("coverageBefore"));
        Double rbMut = rb != null ? num(rb.get("mutation")) : num(f.get("mutationBefore"));
        Double rbMac = rb != null ? num(rb.get("mac")) : num(f.get("macBefore"));
        boolean keptRounds = State.asLong(f.get("rounds")) > 0;
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("coverage", rbCov);
        patch.put("mutation", rbMut);
        patch.put("mac", rbMac);
        // nothing was kept: leave the "after" columns empty rather than echoing "before" values,
        // which read as a measured (null) improvement
        patch.put("coverageAfter", keptRounds ? rbCov : null);
        patch.put("mutationAfter", keptRounds ? rbMut : null);
        patch.put("macAfter", keptRounds ? rbMac : null);
        State.upsertFile(file, patch);
        String diff = pr.diffAgainstBase();
        List<String> changed = new ArrayList<>();
        Matcher m = DIFF_NEW_FILE.matcher(diff == null ? "" : diff);
        // The JS `.match(/…/gm)` returns whole matches, not groups, and slices the `+++ b/` off
        // each — six characters, which is why the group is not used here either.
        while (m.find()) changed.add(m.group().substring(6));
        boolean improved = State.asLong(f.get("rounds")) > 0 && or0(rbMac) > or0(num(f.get("macBefore")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("file", file);
        out.put("testsGreen", true);
        out.put("coverageBefore", f.get("coverageBefore"));
        out.put("coverageAfter", rbCov);
        out.put("mutationBefore", f.get("mutationBefore"));
        out.put("mutationAfter", rbMut);
        out.put("macBefore", f.get("macBefore"));
        out.put("macAfter", rbMac);
        out.put("improved", improved);
        out.put("rounds", State.asLong(f.get("rounds")));
        out.put("changedFiles", changed);
        out.put("diff", State.clip(diff, 30000));
        out.put("branch", f.get("branch"));
        return out;
    }

    private static final Pattern NO_CHANGED_TESTS = Pattern.compile("no changed test files");

    static Object prCreate(Map<String, Object> body) {
        needRun();
        String file = str(body.get("file"));
        if (!hasFile(file)) throw new IllegalStateException("unknown file: " + file);
        Map<String, Object> f = fileRecord(file);
        State.setStage("preparing_pr", "preparing PR for " + file);
        // human-equivalent timesheet from the cumulative diff (before commit resets nothing: diff
        // is vs base and includes committed rounds)
        Timesheet.Estimate ts = null;
        try {
            // only what THIS unit added: the branch may already carry a sibling method's tests
            String diffText = pr.diffSince(str(f.get("startSha")));
            Timesheet.DiffStats stats = Timesheet.diffStats(diffText);
            String src = Repo.readFileSafe(unitPath(file), 500000);
            Double mutAfter = num(f.get("mutationAfter"));
            Double mutBefore = num(f.get("mutationBefore"));
            int mutantsKilled = (State.jsTruthy(f.get("totalMutants")) && mutAfter != null && mutBefore != null)
                    ? (int) Math.max(0, Math.round((mutAfter - mutBefore) * State.asLong(f.get("totalMutants")) / 100))
                    : (int) Math.ceil(stats.testCases() / 2.0);
            ts = Timesheet.estimate(new Timesheet.Inputs(
                    (src == null || src.isEmpty()) ? 0 : src.split("\n", -1).length,
                    stats.testCases(), stats.addedTestLines(), mutantsKilled,
                    (int) Math.max(1, State.asLong(f.get("rounds")))));
            // showMetric: `hours` is a double, so a whole number concatenates as "1.0 h" where
            // the JS printed "1 h" — and the rate card's 30/12/10/15+5 minutes hit a multiple
            // of 60 readily. The minutes beside it are ints and print the same either way.
            State.event("preparing_pr", "human-equivalent timesheet for " + file + ": "
                    + Util.showMetric(ts.hours())
                    + " h (analysis " + ts.analysisMin() + "m + writing " + ts.testsMin()
                    + "m + mutation " + ts.mutationMin() + "m + verify " + ts.verifyMin() + "m)");
        } catch (RuntimeException e) {
            State.event("preparing_pr", "timesheet estimate skipped: " + State.clip(message(e), 120));
        }
        String title = State.jsTruthy(body.get("title")) ? str(body.get("title"))
                : "test: improve MAC of " + file;
        try {
            pr.commit(title);
        } catch (RuntimeException e) {
            if (NO_CHANGED_TESTS.matcher(nullish(message(e), "")).find()) {
                Repo.resetToBase();
                State.upsertFile(file, mapOf("status", "no_improvement"));
                State.event("preparing_pr", "skipping PR for " + file + ": " + message(e));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", true);
                out.put("pr", null);
                out.put("skipped", message(e));
                return out;
            }
            throw e;
        }
        PrRecord rec = pr.createPr(file, str(f.get("branch")), str(body.get("title")),
                or(body.get("body"), ""), strings(body.get("labels")));
        long spentSec = accrueSpent(file);
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("status", "improved");
        patch.put("prUrl", rec.url());
        patch.put("prPatch", rec.patchPath());
        patch.put("timesheet", ts == null ? null : toMap(ts));
        State.upsertFile(file, patch);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("coverageBefore", f.get("coverageBefore"));
        metrics.put("coverageAfter", f.get("coverageAfter"));
        metrics.put("mutationBefore", f.get("mutationBefore"));
        metrics.put("mutationAfter", f.get("mutationAfter"));
        metrics.put("macBefore", f.get("macBefore"));
        metrics.put("macAfter", f.get("macAfter"));
        metrics.put("timesheet", ts == null ? null : toMap(ts));
        metrics.put("spentSec", spentSec);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("state", "improved");
        // written even when null — local mode has no URL and github mode no patch, and the purge
        // pass reads both keys off every improvement record
        entry.put("prUrl", rec.url());
        entry.put("patchPath", rec.patchPath());
        entry.put("branch", f.get("branch"));
        entry.put("ts", System.currentTimeMillis());
        // stamped, so the replay gate can tell a current measurement from a stale one instead of
        // rejecting every improvement record ever written
        entry.put("metrics", Measure.stamp(metrics));
        putLedger(file, entry);
        State.save();
        Repo.resetToBase();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("pr", toMap(rec));
        return out;
    }

    static Object discard(Map<String, Object> body) {
        needRun();
        String file = str(body.get("file"));
        State.setStage("improving_mac", "no improvement for " + file + " — discarding changes");
        Repo.resetToBase();
        if (file != null && hasFile(file)) {
            Map<String, Object> f = fileRecord(file);
            long spentSec = accrueSpent(file);
            if (!"failed".equals(str(f.get("status")))) {
                int maxAttempts = orDefault(state().run.config.maxAttemptsPerFile(), 3);
                // A unit with no un-attempted survivor left cannot produce anything on a further
                // attempt: the prompt skips, the round targets nothing, it is discarded — and each
                // of those costs a PIT run and a coverage run. Settle it now.
                boolean spent = Select.exhausted(unitState(f));
                long attempts = State.asLong(f.get("attempts"));
                if (spent && attempts < maxAttempts) {
                    State.event("improving_mac", unitLabel(file) + ": every surviving mutant has been "
                            + "attempted — settling it rather than spending another attempt that can "
                            + "only repeat itself");
                }
                Map<String, Object> patch = new LinkedHashMap<>();
                patch.put("status", (spent || attempts >= maxAttempts) ? "no_improvement" : "candidate");
                patch.put("attempts", spent ? maxAttempts : attempts);
                State.upsertFile(file, patch);
                if (spent || attempts >= maxAttempts) {
                    // keep the numbers: a file we could not improve is still a file we measured
                    Map<String, Object> m = measured().get(Measure.normalizeUnitKey(file));
                    if (m == null) m = Map.of();
                    Map<String, Object> metrics = new LinkedHashMap<>();
                    metrics.put("spentSec", spentSec);
                    metrics.put("attempts", attempts);
                    metrics.put("coverageBefore", nullish(f.get("coverageBefore"), m.get("coverageBefore")));
                    metrics.put("mutationBefore", nullish(f.get("mutationBefore"), m.get("mutationBefore")));
                    metrics.put("macBefore", nullish(f.get("macBefore"), m.get("macBefore")));
                    metrics.put("attemptCoverage", m.get("attemptCoverage"));
                    metrics.put("attemptMutation", m.get("attemptMutation"));
                    metrics.put("attemptMac", m.get("attemptMac"));
                    // deliberately NOT stamped, exactly as in the JS — see Measure.planReplay,
                    // which restores effort from an unstamped record and gates only the metrics
                    putLedger(file, "exhausted", metrics);
                    State.save();
                }
            }
        }
        State.event("improving_mac", "discarded changes for " + file + ": "
                + (State.jsTruthy(body.get("reason")) ? str(body.get("reason")) : "no MAC improvement"));
        return Map.of("ok", true);
    }

    /// Start a repo again from nothing: forget what previous runs settled and measured, and
    /// remove what they produced — prepared PR patches and the per-file branches carrying their
    /// generated tests. The clone itself is reset to the base branch and cleaned.
    static Object purgeRepo(Map<String, Object> body) {
        String repoUrl = str(body.get("repoUrl"));
        if (repoUrl == null || repoUrl.isEmpty()) {
            repoUrl = state().run != null ? state().run.config.repoUrl() : State.envConfig().repoUrl();
        }
        if (repoUrl == null || repoUrl.isEmpty()) throw new IllegalArgumentException("repoUrl required");
        String slug = Util.slugify(repoUrl);
        Map<String, Purge.Entry> entries = new LinkedHashMap<>();
        Map<String, Map<String, Object>> repoLedger = state().improvedLedger.get(slug);
        if (repoLedger != null) {
            for (Map.Entry<String, Map<String, Object>> e : repoLedger.entrySet()) {
                Map<String, Object> v = e.getValue();
                entries.put(e.getKey(), new Purge.Entry(str(v.get("branch")), str(v.get("patchPath"))));
            }
        }
        Path prsDir = State.DATA_DIR.resolve("prs");
        Purge.Plan plan = Purge.purgePlan(entries, prsDir.toString());

        List<String> removedFiles = new ArrayList<>();
        for (String p : plan.files()) {
            try {
                if (Files.deleteIfExists(Path.of(p))) removedFiles.add(Path.of(p).getFileName().toString());
            } catch (Exception e) {
                // already gone
            }
        }
        List<String> removedBranches = new ArrayList<>();
        Path dir = State.DATA_DIR.resolve("repos").resolve(slug);
        if (Files.exists(dir.resolve(".git"))) {
            String base = state().run != null && State.jsTruthy(state().run.config.prBase())
                    ? state().run.config.prBase()
                    : (state().run != null && State.jsTruthy(state().run.config.repoBranch())
                        ? state().run.config.repoBranch() : "HEAD");
            Exec.run(List.of("git", "checkout", "-f", base), gitOpts(dir, 60000));
            Exec.run(List.of("git", "clean", "-fdx", "--", "src"), gitOpts(dir, 120000));
            // every branch this pipeline made, not only the ones the ledger knows about
            Exec.Result listed = Exec.run(
                    List.of("git", "branch", "--list", "tests/improve-*", "--format=%(refname:short)"),
                    gitOpts(dir, 30000));
            Set<String> all = new LinkedHashSet<>(plan.branches());
            for (String b : listed.stdout().split("\n", -1)) {
                String t = b.trim();
                if (!t.isEmpty()) all.add(t);
            }
            for (String b : all) {
                if (!Purge.isOurs(b)) continue;
                Exec.Result rm = Exec.run(List.of("git", "branch", "-D", b), gitOpts(dir, 30000));
                if (rm.code() == 0) removedBranches.add(b);
            }
        }

        synchronized (State.STATE) {
            state().improvedLedger.remove(slug);
            state().measureLedger.remove(slug);
            state().overheadLedger.remove(slug);
            state().mutatorStats = new LinkedHashMap<>();
            state().llmBudget = null;
            if (state().run != null && Util.slugify(state().run.config.repoUrl()).equals(slug)) {
                state().files = new LinkedHashMap<>();
                state().prs = new ArrayList<>();
            }
        }
        State.event("starting", "purged " + repoUrl + ": removed " + removedFiles.size()
                + " prepared PR artifact(s) and " + removedBranches.size() + " branch(es), and cleared "
                + "its ledgers — the next run starts from the repo as it is upstream");
        State.save();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("repoUrl", repoUrl);
        out.put("removedFiles", removedFiles);
        out.put("removedBranches", removedBranches);
        return out;
    }

    private static Exec.Options gitOpts(Path dir, long timeoutMs) {
        return Exec.Options.of().withCwd(dir).withTimeoutMs(timeoutMs);
    }

    // ── static dashboard ───────────────────────────────────────────────────────

    static final Map<String, String> MIME = Map.of(
            ".html", "text/html",
            ".js", "text/javascript",
            ".css", "text/css",
            ".svg", "image/svg+xml",
            ".json", "application/json");

    /// The dashboard's files, and nothing above them.
    ///
    /// @return the absolute file to serve, or null when the request escapes the directory —
    ///         which the caller answers 403 to. Java's `Path#startsWith` compares whole path
    ///         COMPONENTS where the JS compared string prefixes, so `…/dashboard-evil` is
    ///         rejected here and would have been accepted there. Stricter, in the direction a
    ///         containment check is allowed to be wrong in.
    static Path resolveStatic(Path root, String rel) {
        String r = (rel == null || rel.isEmpty() || rel.equals("/")) ? "/index.html" : rel;
        // strip the leading slash, or resolve() would take it as an absolute path and answer
        // with something outside the dashboard entirely
        while (r.startsWith("/")) r = r.substring(1);
        Path abs = root.resolve(r).normalize();
        return abs.startsWith(root.normalize()) ? abs : null;
    }

    static String mimeOf(Path abs) {
        String name = abs.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "application/octet-stream"
                : MIME.getOrDefault(name.substring(dot), "application/octet-stream");
    }

    /// The dashboard is served at `/` and at `/dashboard` (Caddy strips the `/dashboard` prefix).
    static String dashboardRelative(String pathname) {
        if (!pathname.startsWith("/dashboard")) return pathname;
        String rest = pathname.substring("/dashboard".length());
        return rest.isEmpty() ? "/" : rest;
    }

    // ── dispatch ───────────────────────────────────────────────────────────────

    /// The query string, as `URLSearchParams` reads it.
    public record Query(Map<String, List<String>> params) {

        public static final Query EMPTY = new Query(Map.of());

        /// The FIRST value for a name, or null — `URLSearchParams.get`.
        public String get(String name) {
            List<String> v = params.get(name);
            return (v == null || v.isEmpty()) ? null : v.get(0);
        }

        public static Query of(String raw) {
            Map<String, List<String>> out = new LinkedHashMap<>();
            if (raw == null || raw.isEmpty()) return new Query(out);
            for (String pair : raw.split("&", -1)) {
                if (pair.isEmpty()) continue;
                int eq = pair.indexOf('=');
                String k = eq < 0 ? pair : pair.substring(0, eq);
                String v = eq < 0 ? "" : pair.substring(eq + 1);
                out.computeIfAbsent(decode(k), x -> new ArrayList<>()).add(decode(v));
            }
            return new Query(out);
        }

        /// `URLSearchParams` decodes `+` as a space as well as the percent escapes, which
        /// `URLDecoder` also does — and it never throws on a malformed escape, which
        /// `URLDecoder` does, so a bad query must not be able to take down the route.
        private static String decode(String s) {
            try {
                return URLDecoder.decode(s, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return s;
            }
        }
    }

    /// The request body cap. 20 MB — a generated test file is kilobytes, and a body past this is
    /// a mistake or an attack, not a round.
    static final int MAX_BODY_BYTES = 20_000_000;

    static Map<String, Object> readBody(InputStream in) throws IOException {
        byte[] data = in.readNBytes(MAX_BODY_BYTES + 1);
        if (data.length > MAX_BODY_BYTES) throw new IllegalStateException("request body too large");
        String text = new String(data, StandardCharsets.UTF_8);
        if (text.isEmpty()) return new LinkedHashMap<>();
        try {
            Map<String, Object> parsed = MAPPER.readValue(text, mapType());
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (Exception e) {
            throw new IllegalStateException("bad JSON body");
        }
    }

    static void handle(HttpExchange ex) throws IOException {
        URI uri = ex.getRequestURI();
        String pathname = uri.getPath();
        String key = ex.getRequestMethod() + " " + pathname;
        try {
            Route route = ROUTES.get(key);
            if (route != null) {
                Map<String, Object> body = "POST".equals(ex.getRequestMethod())
                        ? readBody(ex.getRequestBody()) : new LinkedHashMap<>();
                Object out = route.handle(Query.of(uri.getRawQuery()), body);
                json(ex, 200, out);
                return;
            }
            if (pathname.startsWith("/api/")) {
                json(ex, 404, Map.of("error", "no such endpoint: " + key));
                return;
            }
            serveStatic(ex, dashboardRelative(pathname));
        } catch (Exception e) {
            State.event("error", key + ": " + message(e));
            // a rejected concurrent start is a guard, not a run failure
            boolean guard = e instanceof HttpFailure hf && hf.statusCode == 409;
            // a hard error aborts the n8n execution → the run is dead; reflect that
            if (!guard && state().run != null && "running".equals(state().run.status)
                    && "POST".equals(ex.getRequestMethod())) {
                state().run.status = "failed";
                state().run.finishedAt = Util.nowSec();
                State.setStage("failed", State.clip(message(e), 200));
            }
            try {
                json(ex, guard ? 409 : 500,
                        failure(guard ? message(e) : Util.redact(message(e))));
            } catch (IOException | RuntimeException ignored) {
                // The response was already begun — a static file that failed mid-write, say. There
                // is no second set of headers to send, and throwing on from here would only lose
                // the event line above.
            }
        } finally {
            ex.close();
        }
    }

    private static Map<String, Object> failure(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", message);
        return out;
    }

    static void json(HttpExchange ex, int code, Object payload) throws IOException {
        String body = payload instanceof RawJson raw ? raw.json() : MAPPER.writeValueAsString(payload);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        // Content-Length comes from the byte count, not the character count — the two differ the
        // moment an event message carries a `→` or a `«redacted»`, and a wrong one truncates the
        // response mid-JSON.
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    static void serveStatic(HttpExchange ex, String rel) throws IOException {
        Path abs = resolveStatic(dashboardDir(), rel);
        if (abs == null) {
            ex.sendResponseHeaders(403, -1);
            return;
        }
        byte[] buf;
        try {
            buf = Files.readAllBytes(abs);
        } catch (IOException e) {
            byte[] msg = "not found".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(404, msg.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(msg); }
            return;
        }
        ex.getResponseHeaders().set("Content-Type", mimeOf(abs));
        ex.sendResponseHeaders(200, buf.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(buf);
        }
    }

    /// Start the sidecar.
    ///
    /// One virtual thread per request. Node served all of this on a single event loop and the
    /// routes here BLOCK — an hour inside Maven, minutes inside a model call — so a pool would
    /// have to be sized for the longest of them; virtual threads make the sizing question go
    /// away. It is also why every mutation of the store takes its monitor: this is the one place
    /// the JS's single-threadedness stops being free.
    public static HttpServer start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", Server::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server;
    }

    public static void main(String[] args) throws IOException {
        // The JS imported `setProgress` into exec.js directly; here the seam is installed by the
        // composition root, and without this line an hour-long build reports nothing at all.
        Exec.setProgressSink(State::setProgress);
        State.load();
        installCollaborators();
        int port = port();
        start(port);
        System.out.println("sidecar listening on :" + port);
    }

    /// Install the three modules the routes name but do not construct.
    ///
    /// server.js `require`s ./llm, ./pr and ./rules at the top of the file and the routes call
    /// them directly; here they are seams, and a seam nobody installs is a route that throws.
    /// The defaults above are the SAFE failure — "no PR gateway installed" is a legible 500 —
    /// but they are not a configuration: without this line `POST /api/rules/apply` fails on the
    /// first `Rules: post-clone` call of every run, and `handle` turns that 500 into
    /// `run.status = 'failed'`, so the run dies before it has picked a file.
    ///
    /// Called from {@link #main} BEFORE {@link #start}, so no request can arrive between the
    /// port opening and the collaborators existing. Kept separate from main so a test or an
    /// embedding process can install its own and this one can install the real ones.
    public static void installCollaborators() {
        llm(llmClient());
        pr(prGateway());
        // built after State.load(): the engine reads the run's rule text, and Llm.shared() seeds
        // its learned per-stage ceilings from the state file
        rules(Rules.forServer());
    }

    // ── the small conversions ──────────────────────────────────────────────────
    //
    // The store round-trips through JSON, so a file record is a map of loosely typed values and
    // nothing may cast one straight to its Java type. These are the readers.

    static List<Map<String, Object>> allFiles() {
        synchronized (State.STATE) { return new ArrayList<>(state().files.values()); }
    }

    static boolean hasFile(String key) {
        if (key == null) return false;
        synchronized (State.STATE) { return state().files.containsKey(key); }
    }

    /// `state.files[key] || {}` — a record that is not there answers every field with null
    /// rather than throwing, which is what the JS optional chaining bought.
    static Map<String, Object> fileRecord(String key) {
        synchronized (State.STATE) {
            Map<String, Object> f = key == null ? null : state().files.get(key);
            return f == null ? new LinkedHashMap<>() : f;
        }
    }

    static Object field(String key, String name) {
        return fileRecord(key).get(name);
    }

    static Select.UnitState unitState(Map<String, Object> f) {
        return new Select.UnitState(num(f.get("macBefore")), str(f.get("status")),
                // null and an empty list are different answers: nothing measured versus measured
                // and nothing survived. `containsKey` is what keeps them apart.
                f.get("lastSurvived") == null ? null
                        : mutantMaps(f.get("lastSurvived")).stream().map(Server::selectMutant).toList(),
                strings(f.get("attemptedMutants")));
    }

    static Object timesheetField(Map<String, Object> f, String name) {
        Map<String, Object> ts = asMap(f.get("timesheet"));
        return ts == null ? null : ts.get(name);
    }

    static String branchTemplate() {
        Map<String, Object> prePick = asMap(state().decisions.get("pre_pick"));
        Map<String, Object> result = prePick == null ? null : asMap(prePick.get("result"));
        String t = result == null ? null : str(result.get("branchTemplate"));
        return (t == null || t.isEmpty()) ? "tests/improve-{file}" : t;
    }

    static Object runnerField(String name) {
        synchronized (State.STATE) {
            return state().runner == null ? null : state().runner.get(name);
        }
    }

    static Object runnerJdk(String name) {
        Map<String, Object> jdk = asMap(runnerField("jdk"));
        return jdk == null ? null : jdk.get(name);
    }

    static Map<String, Select.MutatorStat> mutatorStats() {
        Map<String, Select.MutatorStat> out = new LinkedHashMap<>();
        synchronized (State.STATE) {
            if (state().mutatorStats == null) return out;
            for (Map.Entry<String, Object> e : state().mutatorStats.entrySet()) {
                Map<String, Object> v = asMap(e.getValue());
                if (v == null) continue;
                out.put(e.getKey(), new Select.MutatorStat((int) State.asLong(v.get("tried")),
                        (int) State.asLong(v.get("killed"))));
            }
        }
        return out;
    }

    /// The improvement ledger entry for a unit — what {@link Measure#planReplay} reads.
    static Measure.Improvement improvementOf(Map<String, Object> e) {
        if (e == null) return null;
        return new Measure.Improvement(str(e.get("state")), str(e.get("prUrl")),
                str(e.get("patchPath")), asMap(e.get("metrics")));
    }

    /// `ledger()[file] = { state, ts, metrics }` — the shape three of the four writers use.
    ///
    /// The keys are the on-disk contract, and they differ per writer: only the `improved` record
    /// carries `prUrl`, `patchPath` and `branch`, and it carries them even when they are null
    /// (local mode has no URL). {@link #putLedger(String, Map)} is the one that says so.
    static void putLedger(String file, String stateName, Map<String, Object> metrics) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("state", stateName);
        rec.put("ts", System.currentTimeMillis());
        rec.put("metrics", metrics);
        putLedger(file, rec);
    }

    static void putLedger(String file, Map<String, Object> record) {
        synchronized (State.STATE) { ledger().put(file, record); }
    }

    /// One mutant, as {@link Select} reads it. Every field is optional in the store, which is
    /// why every one of them is fetched rather than cast.
    static Select.Mutant selectMutant(Map<String, Object> m) {
        return new Select.Mutant(str(m.get("mutator")), intOrNull(m.get("line")),
                intOrNull(m.get("index")), intOrNull(m.get("difficulty")));
    }

    static Select.Mutant selectMutant(Pit.Mutant m) {
        return new Select.Mutant(m.mutator(), m.line(), m.index(), m.difficulty());
    }

    static Prompts.Mutant promptMutant(Map<String, Object> m) {
        return new Prompts.Mutant(str(m.get("status")), str(m.get("mutator")),
                (int) State.asLong(m.get("line")), str(m.get("method")), str(m.get("description")));
    }

    static Targets.Mutant targetsMutant(Map<String, Object> m) {
        return new Targets.Mutant(str(m.get("method")), (int) State.asLong(m.get("line")),
                str(m.get("mutator")), str(m.get("status")));
    }

    /// The stamped target as {@link Rounds} reads it. A stored target with no line at all cannot
    /// be matched against PIT output, and the record's `line` is a primitive — so an absent one
    /// becomes 0, which is precisely the value {@link RoundOutcome#of} refuses to give a verdict
    /// for.
    static Rounds.TargetMutant targetMutantOf(Map<String, Object> m) {
        if (m == null) return null;
        return new Rounds.TargetMutant(str(m.get("mutator")), (int) State.asLong(m.get("line")),
                intOrNull(m.get("round")), intOrNull(m.get("attempt")));
    }

    /// Survivors on their way INTO the store: plain maps, so a state file written now reads back
    /// the same after a restart. Records would serialise identically and come back as maps, and
    /// then only half the reads would work.
    static List<Map<String, Object>> storedMutants(List<Pit.Mutant> survived, int keep) {
        List<Pit.Mutant> all = survived == null ? List.of() : survived;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Pit.Mutant m : all.subList(0, Math.min(keep, all.size()))) out.add(toMap(m));
        return out;
    }

    static List<Map<String, Object>> mutantMaps(Object o) {
        return maps(o);
    }

    static Prompts.Uncovered uncoveredOf(JsonNode node) {
        JsonNode lines = node == null ? null : node.get("lines");
        if (lines == null || lines.isTextual()) return Prompts.Uncovered.entireMethod();
        List<Integer> out = new ArrayList<>();
        for (JsonNode n : lines) out.add(n.asInt());
        return Prompts.Uncovered.of(out);
    }

    // ── JS value semantics, as this file needs them ───────────────────────────

    /// `a ?? b` for objects: null falls through, everything else — including 0, false and "" —
    /// stands.
    static <T> T nullish(T a, T b) { return a == null ? b : a; }

    /// `String(v || fallback)` — the JS the routes actually use for stage names and statuses,
    /// where an EMPTY STRING must fall through to the default. `POST /api/stage` with
    /// `{"stage": ""}` moves to `idle`, not to a stage with no name, and `??` would keep the
    /// empty one. The two are not interchangeable; each site below says which it means.
    static String or(Object v, String fallback) {
        return State.jsTruthy(v) ? String.valueOf(v) : fallback;
    }

    static double nullish(Double a, double b) { return a == null ? b : a; }

    static int nullish(Integer a, int b) { return a == null ? b : a; }

    /// The JS `x || fallback` over a configured number, where 0 is falsy and therefore means
    /// "not configured". Distinct from `??` above, and the two are not interchangeable: see
    /// State.applyOverrides for what a 0 means in each field.
    static int orDefault(Integer v, int fallback) { return (v == null || v == 0) ? fallback : v; }

    /// `x ?? 0` in an arithmetic comparison.
    static double or0(Double v) { return v == null ? 0 : v; }

    static Double num(Object v) { return State.asDouble(v); }

    static String str(Object v) { return v == null ? null : String.valueOf(v); }

    static Integer intOrNull(Object v) { return v instanceof Number n ? n.intValue() : null; }

    static Boolean boolOrNull(Object v) { return v instanceof Boolean b ? b : null; }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    /// A stored list of strings — `(f.attemptedMutants || [])`.
    static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) for (Object o : l) if (o != null) out.add(String.valueOf(o));
        return out;
    }

    /// A stored list of objects. Anything that is not an object is dropped rather than crashing
    /// the route: these lists come off disk and out of an HTTP body.
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> maps(Object v) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) if (o instanceof Map) out.add((Map<String, Object>) o);
        }
        return out;
    }

    static <T> List<T> lastN(List<T> list, int n) {
        return list.size() <= n ? List.copyOf(list) : List.copyOf(list.subList(list.size() - n, list.size()));
    }

    /// `(s.match(/x/g) || []).length` for a plain substring.
    static int countOf(String haystack, String needle) {
        int n = 0, at = 0;
        while ((at = haystack.indexOf(needle, at)) != -1) { n++; at += needle.length(); }
        return n;
    }

    /// The JS `{ ok: true, ...r }` over a PIT result.
    ///
    /// The record's components ARE the JS object's keys, so this stays one conversion rather
    /// than a hand-written copy that drifts. Absent keys arrive as explicit nulls where the JS
    /// omitted them — every consumer tests them for truthiness, which reads the same.
    static Map<String, Object> spread(Pit.PitResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.putAll(toMap(r));
        return out;
    }

    static Map<String, Object> toMap(Object o) {
        return MAPPER.convertValue(o, mapType());
    }

    static <T> T convert(Object value, Class<T> type, T fallback) {
        if (value == null) return fallback;
        try {
            return MAPPER.convertValue(value, type);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    static <T> T convert(Object value, com.fasterxml.jackson.databind.JavaType type, T fallback) {
        if (value == null) return fallback;
        try {
            return MAPPER.convertValue(value, type);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    static com.fasterxml.jackson.databind.JavaType mapType() {
        return TypeFactory.defaultInstance().constructMapType(LinkedHashMap.class, String.class, Object.class);
    }

    static com.fasterxml.jackson.databind.JavaType listOf(Class<?> element) {
        return TypeFactory.defaultInstance().constructCollectionType(List.class, element);
    }

    static com.fasterxml.jackson.databind.JavaType listOf(com.fasterxml.jackson.databind.JavaType element) {
        return TypeFactory.defaultInstance().constructCollectionType(List.class, element);
    }

    static Map<String, Object> mapOf(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = mapOf(k1, v1);
        m.put(k2, v2);
        return m;
    }

    static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2, String k3, Object v3) {
        Map<String, Object> m = mapOf(k1, v1, k2, v2);
        m.put(k3, v3);
        return m;
    }

    /// `e.message` — always a string in JS. A Java exception may carry none, and `null` in the
    /// middle of an event line says less than the class that threw.
    static String message(Throwable e) {
        String m = e.getMessage();
        return (m == null || m.isEmpty()) ? e.toString() : m;
    }

    /// The unit a prompt route is about: the body's `path`, or the unit being worked on.
    static String unitArg(Map<String, Object> body) {
        String p = str(body.get("path"));
        return (p == null || p.isEmpty()) ? state().currentUnit : p;
    }

    // ── the seams the IO modules declare, wired to this backend ────────────────

    /// {@link Coverage.Io}, over the live store and the real repository.
    public static Coverage.Io coverageIo() {
        return new Coverage.Io() {
            public Path repoDir() { return Repo.repoDir(); }

            public Coverage.Runner runner() {
                Object tool = runnerField("tool");
                if (tool == null) return null;
                return new Coverage.Runner(str(tool), str(runnerField("wrapper")),
                        boolOrNull(runnerField("hasJacoco")), strings(runnerField("scanArgs")));
            }

            public String fqcnOf(String rel) { return Repo.fqcnOf(rel); }

            public Map<String, Coverage.UnitRef> files() {
                Map<String, Coverage.UnitRef> out = new LinkedHashMap<>();
                synchronized (State.STATE) {
                    for (Map.Entry<String, Map<String, Object>> e : state().files.entrySet()) {
                        Map<String, Object> f = e.getValue();
                        out.put(e.getKey(), new Coverage.UnitRef(str(f.get("fqcn")), str(f.get("path")),
                                str(f.get("method")), str(f.get("module")), num(f.get("coverage"))));
                    }
                }
                return out;
            }

            public void upsertFile(String unit, Map<String, Object> patch) { State.upsertFile(unit, patch); }

            public void event(String stage, String message) { State.event(stage, message); }

            public Coverage.Proc run(List<String> argv, long timeoutMs, String label) {
                Exec.Result r = Exec.run(argv, Exec.Options.of().withCwd(Repo.repoDir())
                        .withEnv(Repo.buildEnv()).withTimeoutMs(timeoutMs).withLabel(label));
                return new Coverage.Proc(r.code(), r.stdout(), r.stderr(), r.timedOut());
            }

            public Tests.Suite suiteOutcome(String log) { return Tests.suiteOutcome(log); }
        };
    }

    /// {@link Tests.Io}, over the live store and the real repository.
    public static Tests.Io testsIo() {
        return new Tests.Io() {
            public String repoDir() { return Repo.repoDir().toString(); }

            public Tests.Build runner() {
                Object tool = runnerField("tool");
                if (tool == null) return null;
                return new Tests.Build(str(tool), str(runnerField("wrapper")),
                        runnerField("scanArgs") == null ? null : strings(runnerField("scanArgs")));
            }

            public Map<String, String> buildEnv() { return Repo.buildEnv(); }

            public Tests.Ran run(List<String> argv, String cwd, long timeoutMs, String label,
                                 Map<String, String> env) {
                return Tests.exec(argv, cwd, timeoutMs, label, env);
            }

            public void event(String stage, String msg) { State.event(stage, msg); }
        };
    }

    /// {@link Pr.Io}, over the live store and the real repository.
    public static Pr.Io prIo() {
        return new Pr.Io() {
            public Path repoDir() { return Repo.repoDir(); }

            public Path dataDir() { return State.DATA_DIR; }

            /// `state.run.config.prBase`, which State.applyOverrides has already defaulted to
            /// the run's branch — there is no second fallback in the JS either.
            public String prBase() {
                return state().run == null ? null : state().run.config.prBase();
            }

            public String prMode() {
                return state().run == null ? null : state().run.config.prMode();
            }

            public boolean dryRun() {
                return state().run != null && Boolean.TRUE.equals(state().run.config.dryRun());
            }

            public Pr.Proc run(List<String> argv, long timeoutMs, String label) {
                return Pr.exec(argv, Repo.repoDir(), timeoutMs, label);
            }

            public void event(String stage, String msg) { State.event(stage, msg); }

            /// `state.prs.push(record)`. Stored as a MAP, not as the record: the store round-trips
            /// through state.json and every reader below (the dashboard, `GET /api/state`, the
            /// purge pass) reads it back as loosely typed keys.
            public void recordPr(Pr.Record record) {
                synchronized (State.STATE) { state().prs.add(toMap(record)); }
                State.save();
            }
        };
    }

    /// {@link PrGateway} — the five calls the routes make, over {@link #prIo}.
    ///
    /// `commit` drops the sha the module returns because no route reads it: `startSha` is
    /// stamped by /api/iteration/start from `git rev-parse HEAD`, before any round has run.
    public static PrGateway prGateway() {
        Pr.Io io = prIo();
        return new PrGateway() {
            public List<String> changedTestFiles() { return Pr.changedTestFiles(io); }

            public void commit(String message) { Pr.commit(io, message); }

            public String diffAgainstBase() { return Pr.diffAgainstBase(io); }

            public String diffSince(String sha) { return Pr.diffSince(io, sha); }

            public PrRecord createPr(String file, String branch, String title, String body,
                                     List<String> labels) {
                Pr.Record r = Pr.createPr(io, new Pr.Request(file, branch, title, body, labels));
                return new PrRecord(r.file(), r.branch(), r.title(), r.body(), r.labels(),
                        r.mode(), r.createdAt(), r.url(), r.patchPath());
            }
        };
    }

    /// {@link LlmClient} over {@link Llm#shared()}.
    ///
    /// The two conversions are the whole adapter. `messages` arrives as loose maps — it comes
    /// off an HTTP body — and becomes typed turns; the parsed answer goes back the other way,
    /// to plain maps and lists, because it is serialised into a response and, for the rule
    /// stages, into the state file.
    public static LlmClient llmClient() {
        return request -> {
            List<Llm.Message> messages = new ArrayList<>();
            for (Map<String, Object> m : request.messages() == null ? List.<Map<String, Object>>of()
                    : request.messages()) {
                messages.add(new Llm.Message(str(m.get("role")), str(m.get("content"))));
            }
            Llm.Options opts = new Llm.Options(request.system(), request.prompt(),
                    messages.isEmpty() ? null : messages, request.maxTokens(),
                    request.temperature(), request.json(), request.stage(), request.stageDetail());
            try {
                Llm.Answer a = Llm.shared().chat(opts);
                return new ChatReply(a.text(), Rules.plain(a.json()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                // restore the flag: a virtual thread that swallows it will not stop when the
                // server is asked to
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for the model", e);
            }
        };
    }
}
