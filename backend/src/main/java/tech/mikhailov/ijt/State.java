package tech.mikhailov.ijt;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// JSON-file-backed state store. Single-process, in-memory with debounced flush.
///
/// The on-disk shape is a contract, not an implementation detail: the n8n workflow and the
/// dashboard both read `state.json` — and `GET /api/state` serves this object verbatim — so
/// every key here is spelled exactly as the Node sidecar spelled it.
///
/// Three JS shapes have no faithful Java type and are deliberately left as maps:
/// the per-unit file records, the ledger entries and `runner`. Every caller patches them with
/// whatever it has learned (`startSha`, `consecutiveMisses`, `roundTestPaths`, `everReached`,
/// …), and pinning that to a record would either drop keys the dashboard renders or force a
/// schema change on every new field. See {@link #upsertFile}.
public final class State {

    private State() {}

    /// `process.env.DATA_DIR || '/data'`.
    ///
    /// Not final only because Java has no equivalent of setting `process.env.DATA_DIR` before
    /// `require('./state')`, which is how the JS tests redirect it. Read it, never cache it.
    public static Path DATA_DIR = Path.of(envOr(System::getenv, "DATA_DIR", "/data"));

    public static Path stateFile() { return DATA_DIR.resolve("state.json"); }

    public static Path eventsFile() { return DATA_DIR.resolve("events.jsonl"); }

    static final int MAX_EVENTS_IN_STATE = 400;

    /// The last few model exchanges kept for the dashboard's live dialog.
    static final int LLM_LOG_MAX = 12;

    /// The flush is debounced: a round writes state dozens of times per second and the file is
    /// only ever read on boot.
    static final long FLUSH_DEBOUNCE_MS = 150;

    static final ObjectMapper MAPPER = new ObjectMapper()
            // A state file written by another version of this pipeline (or by the Node sidecar)
            // carries keys this build does not know. JS `Object.assign` copies them across
            // untouched; the any-setters below do the same, and this keeps the ones that land on
            // a nested record from failing the whole load.
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // ── configuration ─────────────────────────────────────────────────────────

    /// The six rule slots, keyed on disk exactly as the JS spelled them — snake_case, because
    /// that is what the dashboard form posts and what `GET /api/rules` returns.
    public record Rules(
            @JsonProperty("post_clone") String postClone,
            @JsonProperty("pre_pick") String prePick,
            @JsonProperty("pick_file") String pickFile,
            @JsonProperty("write_test") String writeTest,
            @JsonProperty("check_changes") String checkChanges,
            @JsonProperty("make_pr") String makePr) {

        /// In the JS these are the keys of the rules object, and the override loop iterates
        /// them — so an unknown rule name in a request body is ignored rather than added.
        public static final List<String> STAGES =
                List.of("post_clone", "pre_pick", "pick_file", "write_test", "check_changes", "make_pr");

        public static final Rules EMPTY = new Rules("", "", "", "", "", "");

        /// The JS `rules()[stage]`. An unknown stage has no rule, exactly as a missing key has
        /// no value — callers guard with `|| ''`, so null here reads the same way.
        public String get(String stage) {
            return switch (stage == null ? "" : stage) {
                case "post_clone" -> postClone;
                case "pre_pick" -> prePick;
                case "pick_file" -> pickFile;
                case "write_test" -> writeTest;
                case "check_changes" -> checkChanges;
                case "make_pr" -> makePr;
                default -> null;
            };
        }

        /// `cfg.rules[stage] = value`, as a copy.
        public Rules with(String stage, String value) {
            return switch (stage == null ? "" : stage) {
                case "post_clone" -> new Rules(value, prePick, pickFile, writeTest, checkChanges, makePr);
                case "pre_pick" -> new Rules(postClone, value, pickFile, writeTest, checkChanges, makePr);
                case "pick_file" -> new Rules(postClone, prePick, value, writeTest, checkChanges, makePr);
                case "write_test" -> new Rules(postClone, prePick, pickFile, value, checkChanges, makePr);
                case "check_changes" -> new Rules(postClone, prePick, pickFile, writeTest, value, makePr);
                case "make_pr" -> new Rules(postClone, prePick, pickFile, writeTest, checkChanges, value);
                default -> this;
            };
        }
    }

    /// A run's configuration: the environment, with the run-start body laid over it.
    ///
    /// Every numeric component is BOXED and every one of them can be null. `parseInt('nonsense')`
    /// is NaN in JS and `JSON.stringify(NaN)` is `null`, so a garbled `SCOPE_LIMIT` reaches the
    /// dashboard as null and not as a confident 0 — an int field would have invented the 0.
    /// {@link #applyOverrides} is where the NaNs are resolved into the documented defaults, and
    /// it resolves only the fields the JS resolves.
    public record Config(
            String repoUrl,
            String repoBranch,
            String scopeGlob,
            Integer scopeLimit,
            Integer maxIterations,          // 0 = unlimited
            // 0 = no cap. Rounds end when a round kills nothing, when every surviving mutant
            // has already been attempted, or when the unit's time budget runs out — a fixed
            // count would stop a unit that is still paying off.
            Integer maxRoundsPerFile,
            // Mutants shown to the model per round. 1 = write ONE test for the single most
            // promising survivor, re-measure, repeat: tiny prompts, fast answers, and every
            // round is independently verified instead of betting a big test on many mutants.
            Integer mutantsPerRound,
            // survivors offered to the model when it chooses which mutant to kill this round
            Integer mutantChoices,
            // rounds are cheap now, but a mutant-dense method still needs a ceiling
            Integer unitBudgetSec,
            // consecutive rounds that fail to kill their mutant before a unit is given up
            Integer maxConsecutiveMisses,
            Integer maxAttemptsPerFile,
            // diminishing-returns stop: a further round is only worth its ~10 min of
            // machine time if the last one closed a real share of the remaining MAC gap
            Double minRoundGapFrac,
            Double minRoundGain,
            // a class with fewer mutants than this has no mutation surface worth a run
            // (annotations, marker interfaces, constants holders)
            Integer minMutantsPerClass,
            // 'method' (default): rounds mutate one method at a time and project the class
            // score, with one class-wide measurement at the end. 'class': mutate everything
            // every round — honest but far slower.
            String pitScope,
            // a method with fewer executable lines than this has no behaviour worth verifying
            // (one-line accessors, private utility-class constructors)
            Integer minUnitLines,
            // units we could not measure do not consume the scope quota, but too many of them
            // means the repo/toolchain is broken rather than the units
            Integer maxFailures,
            // The coverage phase runs only for a method covered at or below this (default 0:
            // completely unexecuted). Above it, mutation work extends coverage on its own —
            // killing a NO_COVERAGE mutant means executing the line it sits on.
            Double covPhaseMaxPct,
            String prMode,                  // github | local
            String prBase,                  // defaults to repoBranch
            String setupScript,             // extra build goal/task to run after compile (e.g. 'shade')
            Boolean dryRun,
            Rules rules) {}

    /// The configuration as the environment alone describes it.
    public static Config envConfig() {
        return envConfig(System::getenv);
    }

    /// The configuration as `env` describes it — the pure half, so the defaults can be tested
    /// without a process-wide environment.
    ///
    /// Note that an env var set to the empty string is INDISTINGUISHABLE from an unset one here:
    /// the JS is `e.REPO_BRANCH || 'main'`, and a container that passes `REPO_BRANCH=` must get
    /// `main` rather than a branch with no name.
    public static Config envConfig(UnaryOperator<String> env) {
        return new Config(
                envOr(env, "REPO_URL", ""),
                envOr(env, "REPO_BRANCH", "main"),
                envOr(env, "SCOPE_GLOB", "**/src/main/java/**/*.java"),
                jsParseInt(envOr(env, "SCOPE_LIMIT", "0")),
                jsParseInt(envOr(env, "MAX_ITERATIONS", "0")),
                jsParseInt(envOr(env, "MAX_ROUNDS_PER_FILE", "0")),
                jsParseInt(envOr(env, "MUTANTS_PER_ROUND", "8")),
                jsParseInt(envOr(env, "MUTANT_CHOICES", "20")),
                jsParseInt(envOr(env, "UNIT_BUDGET_SEC", "3600")),
                jsParseInt(envOr(env, "MAX_CONSECUTIVE_MISSES", "3")),
                jsParseInt(envOr(env, "MAX_ATTEMPTS_PER_FILE", "3")),
                jsParseFloat(envOr(env, "MIN_ROUND_GAP_FRAC", "0")),
                jsParseFloat(envOr(env, "MIN_ROUND_GAIN", "0")),
                jsParseInt(envOr(env, "MIN_MUTANTS_PER_CLASS", "1")),
                envOr(env, "PIT_SCOPE", "method"),
                jsParseInt(envOr(env, "MIN_UNIT_LINES", "1")),
                jsParseInt(envOr(env, "MAX_FAILURES", "10")),
                jsParseFloat(envOr(env, "COV_PHASE_MAX_PCT", "0")),
                envOr(env, "PR_MODE", "github"),
                envOr(env, "PR_BASE", ""),
                envOr(env, "SETUP_SCRIPT", ""),
                // exactly the string 'true'; anything else — including 'yes' and '1' — is off
                "true".equals(envOr(env, "DRY_RUN", "false")),
                new Rules(
                        envOr(env, "RULES_POST_CLONE", ""),
                        envOr(env, "RULES_PRE_PICK", ""),
                        envOr(env, "RULES_PICK_FILE", ""),
                        envOr(env, "RULES_WRITE_TEST", ""),
                        envOr(env, "RULES_CHECK_CHANGES", ""),
                        envOr(env, "RULES_MAKE_PR", "")));
    }

    /// The run-start body laid over the environment, and the coercions that follow it.
    ///
    /// Pure, and the interesting half of `freshRun` — the rest is a timestamp and zeros.
    ///
    /// @param overrides the raw request body. Only the keys named below can override anything,
    ///                  which is what keeps `force` and `clearLedger` — posted in the same body
    ///                  by /api/run/start — out of the run's configuration.
    public static Config applyOverrides(Config base, Map<String, Object> overrides) {
        Map<String, Object> o = overrides == null ? Map.of() : overrides;

        String repoUrl = str(o, "repoUrl", base.repoUrl());
        String repoBranch = str(o, "repoBranch", base.repoBranch());
        String scopeGlob = str(o, "scopeGlob", base.scopeGlob());
        Integer scopeLimit = num(o, "scopeLimit", base.scopeLimit());
        Integer maxIterations = num(o, "maxIterations", base.maxIterations());
        Integer maxRoundsPerFile = num(o, "maxRoundsPerFile", base.maxRoundsPerFile());
        Integer maxAttemptsPerFile = num(o, "maxAttemptsPerFile", base.maxAttemptsPerFile());
        Double minRoundGapFrac = dbl(o, "minRoundGapFrac", base.minRoundGapFrac());
        Double minRoundGain = dbl(o, "minRoundGain", base.minRoundGain());
        Integer mutantsPerRound = num(o, "mutantsPerRound", base.mutantsPerRound());
        // Not re-coerced by the JS: it keeps whatever the body sent, string included, and relies
        // on the arithmetic that reads it to coerce. Java has to parse it here, because the field
        // is typed — equivalent everywhere the value is used as a number.
        Integer mutantChoices = num(o, "mutantChoices", base.mutantChoices());
        Integer unitBudgetSec = num(o, "unitBudgetSec", base.unitBudgetSec());
        Integer maxConsecutiveMisses = num(o, "maxConsecutiveMisses", base.maxConsecutiveMisses());
        Integer minMutantsPerClass = num(o, "minMutantsPerClass", base.minMutantsPerClass());
        String pitScope = str(o, "pitScope", base.pitScope());
        Integer minUnitLines = num(o, "minUnitLines", base.minUnitLines());
        Integer maxFailures = num(o, "maxFailures", base.maxFailures());
        Double covPhaseMaxPct = dbl(o, "covPhaseMaxPct", base.covPhaseMaxPct());
        String prMode = str(o, "prMode", base.prMode());
        String prBase = str(o, "prBase", base.prBase());
        String setupScript = str(o, "setupScript", base.setupScript());
        // JS assigns the raw value and every consumer tests it for truthiness, so the STRING
        // 'false' would be dryRun ON. Kept: an override arriving as text must not read as the
        // opposite of an override arriving as a boolean just because Java can tell them apart.
        Boolean dryRun = bool(o, "dryRun", base.dryRun());

        Rules rules = base.rules() == null ? Rules.EMPTY : base.rules();
        Object rawRules = o.get("rules");
        if (rawRules instanceof Map<?, ?> ro) {
            for (String k : Rules.STAGES) {
                // Deliberately only an "is it present" check, unlike the fields above: null and ''
                // DO override here. Clearing a rule from the dashboard posts an empty string, and
                // the falsy guard used for the rest would make that a no-op.
                if (!ro.containsKey(k)) continue;
                Object v = ro.get(k);
                rules = rules.with(k, v == null ? null : String.valueOf(v));
            }
        }

        // ── the coercions, in the JS's own order and with its own asymmetries ──
        // No Math.max: a negative scope limit stays negative, and every read of it is
        // `scopeLimit > 0`, so it means "unlimited" just as 0 does.
        scopeLimit = orIfFalsy(jsParseInt(scopeLimit), 0);
        maxIterations = Math.max(0, orIfFalsy(jsParseInt(maxIterations), 0)); // 0 = unlimited
        maxRoundsPerFile = Math.max(0, orIfFalsy(jsParseInt(maxRoundsPerFile), 0));
        mutantsPerRound = Math.max(1, orIfFalsy(jsParseInt(mutantsPerRound), 1));
        unitBudgetSec = Math.max(0, orIfFalsy(jsParseInt(unitBudgetSec), 0));
        // `|| 3` is falsy, not null-ish: MAX_ATTEMPTS_PER_FILE=0 configures THREE attempts, not
        // zero. Unintuitive, and load-bearing — a 0 here would settle every unit before its first
        // attempt and the run would improve nothing while reporting it had finished.
        maxAttemptsPerFile = orIfFalsy(jsParseInt(maxAttemptsPerFile), 3);
        minRoundGapFrac = Math.max(0, orIfFalsy(jsParseFloat(minRoundGapFrac), 0.0));
        minRoundGain = Math.max(0, orIfFalsy(jsParseFloat(minRoundGain), 0.0));
        minMutantsPerClass = Math.max(0, orIfFalsy(jsParseInt(minMutantsPerClass), 0));
        // the PR opens against the branch we cloned unless told otherwise
        if (prBase == null || prBase.isEmpty()) prBase = repoBranch;

        return new Config(repoUrl, repoBranch, scopeGlob, scopeLimit, maxIterations, maxRoundsPerFile,
                mutantsPerRound, mutantChoices, unitBudgetSec, maxConsecutiveMisses, maxAttemptsPerFile,
                minRoundGapFrac, minRoundGain, minMutantsPerClass, pitScope, minUnitLines, maxFailures,
                covPhaseMaxPct, prMode, prBase, setupScript, dryRun, rules);
    }

    // ── the run ───────────────────────────────────────────────────────────────

    /// Coverage, mutation and MAC as a triple. Mutable, because the phases fill it in one field
    /// at a time (`state.run.baseline.coveragePct = …` from the coverage route, `mutationPct`
    /// from the PIT route).
    ///
    /// All three are boxed and start null: not yet measured is not zero. A run that reports 0%
    /// coverage because it has not run JaCoCo yet is a lie the dashboard would print in bold.
    public static final class Metrics {
        public Double coveragePct;
        public Double mutationPct;
        public Double mac;
    }

    /// Token usage for a run. Mutable and accumulated in place by {@link #addTokens}.
    public static final class Tokens {
        public long in;
        public long out;
        public long calls;
    }

    /// One run of the pipeline. Mutable: the routes advance it field by field.
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static final class Run {
        public String id;
        public long startedAt;
        public Long finishedAt;
        public String status;
        public Config config;
        public int iteration;
        public Metrics baseline = new Metrics();
        public Metrics result = new Metrics();
        public Tokens tokens = new Tokens();

        /// Fields the routes add as the run goes on. They are absent from a fresh run's JSON —
        /// as they are in the JS, where the key does not exist until something assigns it — so
        /// they are written only once set.
        @JsonInclude(JsonInclude.Include.NON_NULL) public Integer measuredFiles;
        @JsonInclude(JsonInclude.Include.NON_NULL) public Boolean overheadCharged;
        /// branch name → the unit that owns it (repo.js)
        @JsonInclude(JsonInclude.Include.NON_NULL) public Map<String, Object> branchOwners;

        private final Map<String, Object> extra = new LinkedHashMap<>();

        /// Anything a newer (or older) writer put in the run object survives a load/save cycle,
        /// which is what `Object.assign` gave the JS for free.
        @JsonAnyGetter public Map<String, Object> extra() { return extra; }

        @JsonAnySetter public void set(String key, Object value) { extra.put(key, value); }
    }

    /// The clock behind a run id, never handing out the same millisecond twice.
    ///
    /// The id is not decoration: it is what marks a branch as THIS run's work, so
    /// `branchAction` reuses a branch when the owner matches and resets it when it does not
    /// (see Branch). Two runs sharing an id means the second silently inherits the first's
    /// branch and force-pushes over commits that are already in an open PR.
    ///
    /// `Date.now()` was unique enough in the JS only by accident — two starts always arrived
    /// as two HTTP requests, milliseconds apart. `POST /api/run/start` with `force:true`
    /// creates the replacement run in the same millisecond as the one it takes over from, so
    /// the wall clock alone is not an identity. The counter only ever moves forward and stays
    /// a plain millisecond integer, so ids remain comparable and readable as times.
    private static final AtomicLong LAST_RUN_MILLIS = new AtomicLong(0);

    private static long nextRunMillis() {
        return LAST_RUN_MILLIS.updateAndGet(prev -> Math.max(prev + 1, System.currentTimeMillis()));
    }

    /// A fresh run from the environment plus the request body.
    public static Run freshRun(Map<String, Object> overrides) {
        return freshRun(envConfig(), overrides);
    }

    public static Run freshRun() {
        return freshRun(envConfig(), Map.of());
    }

    public static Run freshRun(Config base, Map<String, Object> overrides) {
        Run r = new Run();
        // milliseconds, and printed as a plain integer — `String.valueOf(1.7e12)` would have put
        // an exponent in the run id and in every branch name derived from it
        r.id = "run-" + nextRunMillis();
        r.startedAt = Util.nowSec();
        r.finishedAt = null;
        r.status = "running";
        r.config = applyOverrides(base, overrides);
        r.iteration = 0;
        // baseline and result start entirely null — see Metrics
        return r;
    }

    // ── the store ─────────────────────────────────────────────────────────────

    /// The current stage, and the last progress line under it.
    ///
    /// A record, not a mutable object: the JS replaces the whole stage on a change and only ever
    /// assigns `stage.progress` in place, so {@link #setProgress} rebuilds the stage around a new
    /// progress and nothing else can drift.
    public record Stage(String name, String detail, long since, Progress progress) {}

    /// The last line a child process printed, how long it has been running, and when we saw it.
    public record Progress(String line, long elapsed, long ts) {}

    /// One line of the event log. `seq` is what `GET /api/events?after=` pages on, so it must
    /// only ever go up.
    public record Event(long seq, long ts, String stage, String msg) {}

    /// The whole store — this object IS the body of `GET /api/state` and the content of
    /// state.json, so field names are the wire format.
    public static final class Store {
        public Run run = null;
        public Stage stage = new Stage("idle", "", Util.nowSec(), null);
        /// { tool, wrapper, jdk, testFramework, testFrameworkVersion }
        public Map<String, Object> runner = null;
        /// unit key (`path::method`) → file record
        public Map<String, Map<String, Object>> files = new LinkedHashMap<>();
        /// ruleStage → last application result
        public Map<String, Object> decisions = new LinkedHashMap<>();
        public List<Object> prs = new ArrayList<>();
        public List<Event> events = new ArrayList<>();
        public long seq = 0;

        /// per-repo ledger of final file dispositions, SURVIVES run/start — enables
        /// batched full-repo runs and crash-restart without redoing finished files.
        /// `{ [repoSlug]: { [path]: {state: 'improved'|'exhausted'|'failed', prUrl?, ts} } }`
        public Map<String, Map<String, Map<String, Object>>> improvedLedger = new LinkedHashMap<>();

        /// per-repo cumulative clone/install/baseline seconds, so the FTE ratio counts
        /// run overhead and not just per-file work
        public Map<String, Object> overheadLedger = new LinkedHashMap<>();

        /// per-repo measurements for EVERY file we ever measured, improved or not:
        /// baseline coverage/mutation/MAC plus what the best attempt reached. Kept
        /// separate from improvedLedger because these entries say nothing about
        /// whether a file is settled — they exist so the numbers survive batches.
        /// `{ [repoSlug]: { [path]: {coverageBefore, mutationBefore, macBefore,
        ///                           attemptCoverage, attemptMutation, attemptMac, ts} } }`
        public Map<String, Map<String, Map<String, Object>>> measureLedger = new LinkedHashMap<>();

        /// unit the pipeline is improving right now — token usage is attributed to it
        public String currentUnit = null;

        /// the last few model exchanges, so the dashboard can show what is actually being
        /// asked and answered rather than only that a call is in flight
        public List<Map<String, Object>> llmLog = new ArrayList<>();

        // ── keys other modules add at runtime ─────────────────────────────────
        // Absent from state.json until something sets them, exactly as in the JS, where the key
        // does not exist until it is assigned. Hence NON_NULL: writing `"pickFailures": null` on
        // a fresh boot would be a shape the Node sidecar never produced.

        /// consecutive picks the model failed to make (rules.js)
        @JsonInclude(JsonInclude.Include.NON_NULL) public Integer pickFailures;
        /// what this run has learned about mutator kinds; cleared on run start
        @JsonInclude(JsonInclude.Include.NON_NULL) public Map<String, Object> mutatorStats;
        /// the LLM budget's learned per-stage ceilings, persisted with the run and fed straight
        /// back into `new Budget(...)`. Left untyped for the same reason it is untyped in the JS:
        /// this module never reads it, and a shape mismatch here must not cost the whole load.
        @JsonInclude(JsonInclude.Include.NON_NULL) public Object llmBudget;

        private final Map<String, Object> extra = new LinkedHashMap<>();

        @JsonAnyGetter public Map<String, Object> extra() { return extra; }

        @JsonAnySetter public void set(String key, Object value) { extra.put(key, value); }
    }

    /// The single in-memory store — the JS module-level `state`, which every other module
    /// imports and mutates directly.
    ///
    /// Node ran all of this on one thread. The HTTP server here answers on virtual threads, so
    /// every mutation below holds this object's monitor and so does the snapshot taken for the
    /// flush. Code outside this class that mutates the store — and most of the pipeline does —
    /// must do the same or it will serialise a half-written map.
    public static final Store STATE = new Store();

    // ── load / save ───────────────────────────────────────────────────────────

    /// Read state.json into the store, if there is one.
    ///
    /// A failure to read or parse means first boot and is silent, as in the JS. Individual keys
    /// are applied one at a time on purpose: `Object.assign` cannot fail on a value's SHAPE, but
    /// Jackson can, and one ledger written by a different version must not cost the run every
    /// other ledger in the file.
    public static void load() {
        JsonNode root;
        try {
            root = MAPPER.readTree(Files.readString(stateFile()));
        } catch (Exception e) {
            return; // first boot
        }
        if (root == null || !root.isObject()) return;
        synchronized (STATE) {
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> f = fields.next();
                try {
                    ObjectNode one = MAPPER.createObjectNode();
                    one.set(f.getKey(), f.getValue());
                    MAPPER.readerForUpdating(STATE).readValue(one);
                } catch (Exception e) {
                    System.err.println("state load skipped '" + f.getKey() + "': " + e.getMessage());
                }
            }
            if (STATE.run != null && "running".equals(STATE.run.status)) {
                // process restarted mid-run
                STATE.stage = new Stage("interrupted", "sidecar restarted", Util.nowSec(), null);
            }
        }
    }

    private static final Object FLUSH_LOCK = new Object();
    private static ScheduledFuture<?> flushTimer;

    private static final ScheduledExecutorService FLUSHER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "state-flush");
                // must never hold the JVM open on its own; a flush still owed at exit is written
                // by the shutdown hook below, which is what node's pending setTimeout amounted to
                t.setDaemon(true);
                return t;
            });

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(State::flushIfPending, "state-flush-atexit"));
    }

    /// Ask for a write. Coalesces: the first caller schedules the flush and the rest ride on it.
    public static void save() {
        synchronized (FLUSH_LOCK) {
            if (flushTimer != null) return;
            flushTimer = FLUSHER.schedule(() -> {
                synchronized (FLUSH_LOCK) { flushTimer = null; }
                writeStateFile();
            }, FLUSH_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    /// Write now, cancelling any pending flush. For shutdown paths and for tests, which cannot
    /// wait on a debounce and stay deterministic.
    public static void flushNow() {
        synchronized (FLUSH_LOCK) {
            if (flushTimer != null) { flushTimer.cancel(false); flushTimer = null; }
        }
        writeStateFile();
    }

    /// Write only if a flush is owed — the shutdown hook, which must not invent a state file for
    /// a process that never changed anything.
    static void flushIfPending() {
        synchronized (FLUSH_LOCK) {
            if (flushTimer == null) return;
            flushTimer.cancel(false);
            flushTimer = null;
        }
        writeStateFile();
    }

    /// Where the store's bytes go. Defaults to state.json + events.jsonl, which is exactly what
    /// this class always did; the orchestrator replaces it with an H2-backed one at startup.
    ///
    /// A static rather than a constructor argument, deliberately and temporarily: every one of
    /// the ~62 call sites reaches `State` statically, and converting them is the DI work in
    /// StateStore. Storage should not have to wait for that — H2 currently stores nothing while
    /// state.json is rewritten whole, 470 KB at a time, on every flush.
    ///
    /// `volatile` because the orchestrator installs this from the Spring startup thread while
    /// the debounce flusher may already be reading it from its own.
    private static volatile StatePersistence persistence = new FilePersistence();

    /// Install a different backing store. Call before a run starts.
    ///
    /// Passing null restores the file writer, which is what a test wants in an @AfterEach — this
    /// is process-wide, and a leaked H2 store would silently follow the next test.
    public static void persistence(StatePersistence p) {
        persistence = p == null ? new FilePersistence() : p;
    }

    public static StatePersistence persistence() {
        return persistence;
    }

    private static void writeStateFile() {
        persistence.writeSnapshot(STATE);
    }

    /// The default: a temp file and an atomic rename, unchanged from when this was inline.
    static final class FilePersistence implements StatePersistence {
        @Override
        public void writeSnapshot(State.Store store) {
            writeStateFileNow();
        }

        @Override
        public void appendEvent(State.Event event) {
            appendEventLine(event);
        }

        @Override
        public boolean load(State.Store into) {
            // State.load() already owns the file-reading path and its migrations; this exists so
            // an H2 store can answer false and let the caller fall back to it.
            return Files.exists(stateFile());
        }
    }

    private static void appendEventLine(State.Event entry) {
        try {
            Files.writeString(eventsFile(), MAPPER.writeValueAsString(entry) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // Best effort, exactly as in the JS: the directory is created by the flush, so the
            // first events of a cold boot can land before it exists. Losing a log line must never
            // be able to fail a round.
        }
    }

    private static void writeStateFileNow() {
        try {
            Files.createDirectories(DATA_DIR);
            Path tmp = DATA_DIR.resolve("state.json.tmp");
            Files.writeString(tmp, snapshotJson());
            // tmp + rename, so a crash mid-write leaves the previous state file intact rather
            // than a truncated one. ATOMIC_MOVE has to be asked for in Java; fs.renameSync is
            // already atomic within a filesystem.
            try {
                Files.move(tmp, stateFile(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, stateFile(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            System.err.println("state save failed: " + e.getMessage());
        }
    }

    /// The store as a read-only tree, for callers that navigate it by path the way the JS read
    /// `state.run?.config?.[field]` and `state.files[key]?.method`.
    ///
    /// A SNAPSHOT, not a view. Two consequences worth knowing before using it: it can be walked
    /// while other threads keep mutating the store, and it will not show what they do next. It
    /// costs one serialisation of the whole store, which is right once per phase and wrong
    /// inside a loop over units — read {@link #STATE} directly (under its monitor) there.
    public static JsonNode state() {
        synchronized (STATE) {
            return MAPPER.valueToTree(STATE);
        }
    }

    /// The store as JSON — the body of `GET /api/state`, and what the flush writes.
    public static String snapshotJson() {
        try {
            synchronized (STATE) {
                return MAPPER.writeValueAsString(STATE);
            }
        } catch (Exception e) {
            throw new IllegalStateException("state is not serialisable: " + e.getMessage(), e);
        }
    }

    // ── events ────────────────────────────────────────────────────────────────

    /// Append one line to the event log: the dashboard's feed, and the only record of what a run
    /// did once its stage has moved on.
    public static void event(String stage, String msg) {
        Event entry;
        synchronized (STATE) {
            STATE.seq += 1;
            entry = new Event(STATE.seq, Util.nowSec(), stage, clip(Util.redact(msg), 500));
            STATE.events.add(entry);
            // keep the LAST 400; the file below keeps everything
            if (STATE.events.size() > MAX_EVENTS_IN_STATE) {
                STATE.events.subList(0, STATE.events.size() - MAX_EVENTS_IN_STATE).clear();
            }
        }
        persistence.appendEvent(entry);
        save();
        // the RAW message, not the redacted one — as in the JS. The container log is not the
        // dashboard: it is where a secret that leaked into tool output is diagnosed.
        System.out.println("[" + stage + "] " + msg);
    }

    /// Move to a stage, logging the move — but only when it is actually a move.
    public static void setStage(String name) { setStage(name, ""); }

    public static void setStage(String name, String rawDetail) {
        String detail = Util.redact(rawDetail);
        boolean changed;
        synchronized (STATE) {
            Stage s = STATE.stage;
            changed = !Objects.equals(s.name(), name) || !Objects.equals(s.detail(), detail);
            if (changed) STATE.stage = new Stage(name, detail, Util.nowSec(), null);
        }
        // outside the monitor: event() takes it again, and it also writes a file
        if (changed) event(name, detail.isEmpty() ? name : detail);
        // unconditional, as in the JS — re-entering the same stage still moves nothing on disk,
        // but the caller has usually just changed something else
        save();
    }

    /// The live output line under the current stage.
    ///
    /// @param line    child-process output: may echo credentials we passed in
    /// @param elapsed seconds since the child started
    public static void setProgress(String line, long elapsed) {
        synchronized (STATE) {
            Stage s = STATE.stage;
            STATE.stage = new Stage(s.name(), s.detail(), s.since(),
                    new Progress(clip(Util.redact(line), 200), elapsed, Util.nowSec()));
        }
        save();
    }

    /// Record one model exchange for the dashboard's live dialog.
    ///
    /// `ts` and `unit` are written last and so win over anything of those names in the entry —
    /// the JS spread `{ ...entry, ts, unit }` does the same.
    public static void addLlmExchange(Map<String, Object> entry) {
        synchronized (STATE) {
            Map<String, Object> e = new LinkedHashMap<>();
            if (entry != null) e.putAll(entry);
            e.put("ts", Util.nowSec());
            e.put("unit", STATE.currentUnit);
            STATE.llmLog.add(e);
            if (STATE.llmLog.size() > LLM_LOG_MAX) {
                STATE.llmLog.subList(0, STATE.llmLog.size() - LLM_LOG_MAX).clear();
            }
        }
        save();
    }

    /// Accumulate LLM token usage: on the run total and on the unit currently being improved.
    ///
    /// Called from the LLM client for every completion, including retries and repairs — the cost
    /// of a unit is everything spent getting there, not just the successful call.
    public static void addTokens(long inTok, long outTok) {
        synchronized (STATE) {
            if (STATE.run == null) return;      // before the save, as in the JS
            Tokens t = STATE.run.tokens;
            if (t == null) t = STATE.run.tokens = new Tokens();
            t.in += inTok;
            t.out += outTok;
            t.calls += 1;
            String key = STATE.currentUnit;
            Map<String, Object> f = key == null ? null : STATE.files.get(key);
            if (f != null) {
                f.put("tokensIn", asLong(f.get("tokensIn")) + inTok);
                f.put("tokensOut", asLong(f.get("tokensOut")) + outTok);
                f.put("llmCalls", asLong(f.get("llmCalls")) + 1);
            }
        }
        save();
    }

    /// Create or patch a unit's record, and return the live record.
    ///
    /// The patch is applied key by key, INCLUDING its nulls: `{attemptStartedAt: null}` is how a
    /// caller stops the stopwatch, and skipping nulls would leave it running for ever. `updatedAt`
    /// is written after the patch and so cannot be forged by one.
    public static Map<String, Object> upsertFile(String p, Map<String, Object> patch) {
        Map<String, Object> f;
        synchronized (STATE) {
            f = STATE.files.get(p);
            if (f == null) f = newFileRecord(p);
            if (patch != null) f.putAll(patch);
            f.put("updatedAt", Util.nowSec());
            STATE.files.put(p, f);
        }
        save();
        return f;
    }

    /// The seed record for a unit nothing has measured yet.
    ///
    /// Every metric starts null rather than 0: a unit with no coverage measurement is not a unit
    /// with no coverage, and the difference decides whether it is worth picking.
    static Map<String, Object> newFileRecord(String p) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("path", p);
        f.put("coverage", null);
        f.put("mutation", null);
        f.put("mac", null);
        f.put("macBefore", null);
        f.put("macAfter", null);
        f.put("status", "candidate");
        f.put("attempts", 0);
        f.put("branch", null);
        f.put("prUrl", null);
        f.put("prPatch", null);
        f.put("updatedAt", Util.nowSec());
        return f;
    }

    // ── JS value semantics ────────────────────────────────────────────────────
    // The store round-trips through JSON, so a number that went out as 12.0 comes back as an
    // Integer and one that went out as 12.5 comes back as a Double. Nothing may cast a field of
    // a file record straight to Double; read it through these.

    /// A stored number as a double, or null when there is nothing there to read. Not zero: an
    /// absent measurement is not a measurement of zero.
    public static Double asDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        return null;
    }

    /// A stored counter as a long — the JS `(f.tokensIn || 0)`, where absent counts as zero
    /// because a count really does start at zero.
    public static long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    /// `env.X || fallback` — an env var set to the empty string is as good as unset.
    static String envOr(UnaryOperator<String> env, String name, String fallback) {
        String v = env.apply(name);
        return (v == null || v.isEmpty()) ? fallback : v;
    }

    /// JS `String.prototype.slice(0, n)`, which clamps where `substring` would throw.
    static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n);
    }

    /// The JS override guard: `undefined`, `null` and `''` all leave the configured value alone.
    /// An empty string is how a form posts "not set", and it must not blank out an env default.
    private static Object override(Map<String, Object> o, String key) {
        Object v = o.get(key);
        if (v == null) return null;
        if (v instanceof String s && s.isEmpty()) return null;
        return v;
    }

    private static String str(Map<String, Object> o, String key, String base) {
        Object v = override(o, key);
        return v == null ? base : String.valueOf(v);
    }

    private static Integer num(Map<String, Object> o, String key, Integer base) {
        Object v = override(o, key);
        return v == null ? base : jsParseInt(v);
    }

    private static Double dbl(Map<String, Object> o, String key, Double base) {
        Object v = override(o, key);
        return v == null ? base : jsParseFloat(v);
    }

    private static Boolean bool(Map<String, Object> o, String key, Boolean base) {
        Object v = override(o, key);
        // deliberately not a ternary: `v == null ? base : jsTruthy(v)` mixes Boolean with boolean,
        // which unboxes `base` — and a config loaded from a file that predates the field carries
        // a null there, so the whole run would fail to start on a NullPointerException
        if (v == null) return base;
        return jsTruthy(v);
    }

    /// JS truthiness, for a value that arrived as JSON: a non-empty string is TRUE, whatever it
    /// spells, and 0 is false.
    static boolean jsTruthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0 && !Double.isNaN(n.doubleValue());
        if (v instanceof String s) return !s.isEmpty();
        return true;
    }

    /// JS `x || fallback`, for a number that may be NaN (null here) — and, crucially, for one
    /// that may be 0, which is falsy too.
    static Integer orIfFalsy(Integer v, int fallback) {
        return (v == null || v == 0) ? fallback : v;
    }

    static Double orIfFalsy(Double v, double fallback) {
        return (v == null || v == 0.0 || v.isNaN()) ? fallback : v;
    }

    private static final Pattern JS_INT = Pattern.compile("^[\\s\\u00a0\\ufeff]*([+-]?\\d+)");
    private static final Pattern JS_FLOAT = Pattern.compile(
            "^[\\s\\u00a0\\ufeff]*([+-]?(?:Infinity|(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?))");

    /// `parseInt(v, 10)`, NaN as null.
    ///
    /// Not `Integer.parseInt`: that throws on ' 7', on '30s' and on '', where JS answers 7, 30
    /// and NaN. Configuration arrives from environment variables and from an HTTP body, both of
    /// which carry text a human typed.
    public static Integer jsParseInt(Object v) {
        if (v == null) return null;
        // JS coerces to a string first, so parseInt(3.7) is 3 and parseInt(true) is NaN
        String s = v instanceof String str ? str : (v instanceof Number ? String.valueOf(v) : jsString(v));
        Matcher m = JS_INT.matcher(s);
        if (!m.find()) return null;
        try {
            long l = Long.parseLong(m.group(1));
            // JS would carry 1e12 as a double; nothing this configures is meaningful past int
            // range, so it saturates rather than wrapping into a negative limit
            return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, l));
        } catch (NumberFormatException e) {
            return m.group(1).startsWith("-") ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        }
    }

    /// `parseFloat(v)`, NaN as null. Same reasoning as {@link #jsParseInt}.
    public static Double jsParseFloat(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        String s = v instanceof String str ? str : jsString(v);
        Matcher m = JS_FLOAT.matcher(s);
        if (!m.find()) return null;
        String g = m.group(1);
        if (g.endsWith("Infinity")) return g.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        try {
            return Double.valueOf(g);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /// Booleans, maps and lists all parse as NaN in JS once stringified; only their text form
    /// matters here, and none of it starts with a digit.
    private static String jsString(Object v) {
        return String.valueOf(v);
    }
}
