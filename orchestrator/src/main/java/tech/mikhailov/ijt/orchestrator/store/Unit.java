package tech.mikhailov.ijt.orchestrator.store;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import tech.mikhailov.ijt.State;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// One unit of work — `path::method` — and everything the run has learned about it. The row
/// that replaces one entry of `state.files`.
///
/// The identity is (run, unitKey) and the surrogate id exists only because Spring Data is
/// markedly simpler with one. Scoping by run is what implements "everything else resets" at
/// run start: a new run is a new id, so the previous run's units are not visible to it without
/// anything having to delete them — and the ledger tables, which are scoped by REPO and not by
/// run, survive for exactly the same structural reason.
///
/// The typed columns below are the ones the pipeline ranks, filters and renders on. Everything
/// else a caller has learned goes into `extra`. See {@link Json} for why that is deliberate.
@Entity
@Table(name = "ijt_unit",
        uniqueConstraints = @UniqueConstraint(name = "uq_unit_run_key", columnNames = {"run_id", "unit_key"}),
        indexes = @Index(name = "ix_unit_run", columnList = "run_id"))
public class Unit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "run_id", length = 64, nullable = false)
    private String runId;

    /// `src/main/java/org/json/XML.java::parse` — the key `state.files` is keyed by, and the
    /// value the record carries as `key`. This is the identity and nothing may rename it.
    @Column(name = "unit_key", length = 512, nullable = false)
    private String unitKey;

    /// `src/main/java/org/json/XML.java` — the FILE, and a different thing from the key.
    ///
    /// This column existed only as a comment claiming the two were the same, and the store paid
    /// for it: `patch` read the record's `path` as the key, so every real unit — whose path is
    /// its file and whose key is `path::method` — was rejected as an attempted rename, and the
    /// exception took the rest of the snapshot with it. See SnapshotShapeTest.
    ///
    /// They are not interchangeable. `path` is what a PR is opened against, what the prompt
    /// shows the model and what groups 319 units into 26 files; `key` is what the ledgers,
    /// `state.files` and this table are keyed by.
    @Column(name = "path", length = 512)
    private String path;

    @Column(name = "method", length = 256)
    private String method;

    @Column(name = "fqcn", length = 512)
    private String fqcn;

    // ── metrics ───────────────────────────────────────────────────────────────
    // Every one boxed, every one null until measured, and NEVER 0 for "we could not measure
    // it". Zero is a measurement; absent is not. A unit whose PIT run failed and recorded 0
    // reads as a unit with no tests at all — it sorts to the top of the candidate list, gets
    // picked over units that would actually pay, and every later number is measured against a
    // floor that was never true.

    @Column(name = "coverage")
    private Double coverage;

    @Column(name = "mutation")
    private Double mutation;

    @Column(name = "mac")
    private Double mac;

    @Column(name = "mac_before")
    private Double macBefore;

    @Column(name = "mac_after")
    private Double macAfter;

    /// `candidate` | `improved` | `no_improvement` | `no_mutants` | `failed` | …
    @Column(name = "status", length = 32)
    private String status;

    /// A count, so primitive and genuinely 0 to begin with — the deliberate contrast with the
    /// metrics above.
    @Column(name = "attempts")
    private int attempts;

    /// PIT's survivors from the last measurement, as the mutant maps `Select` reads.
    ///
    /// NULL when nothing has ever been measured; an EMPTY LIST means measured and nothing
    /// survived. `Select.exhausted` gives opposite verdicts for the two — never measured is not
    /// exhausted — so the column must be able to hold both and the converter must not fold one
    /// into the other.
    @Column(name = "last_survived_json", length = 1_000_000)
    @Convert(converter = Json.ListConverter.class)
    private List<Object> lastSurvived;

    @Column(name = "branch", length = 256)
    private String branch;

    @Column(name = "pr_url", length = 512)
    private String prUrl;

    /// The prepared patch's path, in local PR mode. Exactly one of this and `prUrl` is ever set,
    /// which is how a caller tells an opened PR from a prepared one.
    @Column(name = "pr_patch", length = 1024)
    private String prPatch;

    /// Cumulative machine seconds this unit has cost. Boxed: `Measure.Effort.toMap` omits it
    /// when it is absent rather than writing a 0, because an explicit zero here is read as
    /// "this unit was free" and the headline prints "Human-equivalent work 0" beside "2
    /// improved".
    @Column(name = "spent_sec")
    private Long spentSec;

    // Counters again, and again primitive: `State.addTokens` reads them as `(f.tokensIn || 0)`.

    @Column(name = "tokens_in")
    private long tokensIn;

    @Column(name = "tokens_out")
    private long tokensOut;

    @Column(name = "llm_calls")
    private long llmCalls;

    @Column(name = "updated_at")
    private long updatedAt;

    /// `startSha`, `consecutiveMisses`, `roundTestPaths`, `everReached`, `attemptedMutants`,
    /// `timesheet`, `attemptStartedAt`, `failure`, … — the open half of the record.
    ///
    /// Null values are KEPT here, not dropped: `{attemptStartedAt: null}` is how a caller stops
    /// the stopwatch, and a store that skipped nulls would leave it running for ever and charge
    /// the unit for every second until the process died.
    @Column(name = "extra_json", length = 1_000_000)
    @Convert(converter = Json.MapConverter.class)
    private Map<String, Object> extra;

    protected Unit() {} // JPA

    public Unit(String runId, String unitKey) {
        this.runId = runId;
        this.unitKey = unitKey;
        this.status = "candidate";
    }

    // ── the JS record, in and out ─────────────────────────────────────────────

    /// The keys the seed record carries — `State.newFileRecord` — written in its order and
    /// written even when null, because the dashboard reads the presence of a key and the JS
    /// object has them from the moment the unit exists.
    private static final List<String> SEEDED = List.of(
            "path", "key", "coverage", "mutation", "mac", "macBefore", "macAfter",
            "status", "attempts", "branch", "prUrl", "prPatch", "updatedAt");

    /// Apply one patch, key by key, exactly as `state.files[p] = {...f, ...patch}` did.
    ///
    /// Nulls in the patch ARE applied: see `extra`. `updatedAt` is not settable from a patch —
    /// the facade stamps it after the patch lands, so it cannot be forged by one.
    ///
    /// Every value goes through the JS-shaped coercions in `State`: the store round-trips
    /// through JSON, so a number that went out as 12.0 comes back as an Integer and one that
    /// went out as 12.5 comes back as a Double. `State.asDouble` reads either — and answers
    /// null, not 0, for anything that is not a number at all.
    public void patch(Map<String, Object> patch) {
        if (patch == null || patch.isEmpty()) return;
        // A copy, reassigned at the end. Mutating a converted attribute in place is the kind of
        // thing Hibernate's dirty checking is entitled to miss, and a silently unsaved unit is
        // indistinguishable from a unit nothing happened to.
        Map<String, Object> nextExtra = extra == null ? new LinkedHashMap<>() : new LinkedHashMap<>(extra);
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            switch (k) {
                // An ordinary column. It is the FILE, it is not the identity, and a caller
                // correcting it is not renaming anything.
                case "path" -> path = str(v);
                case "key" -> {
                    // THIS is the identity. Renaming a unit through a patch would leave its
                    // ledger entries, its branch and its PR pointing at a unit that no longer
                    // exists under that name. The guard used to sit on `path`, where it fired
                    // for every unit the pipeline ever wrote and for no actual rename.
                    if (v != null && !unitKey.equals(String.valueOf(v))) {
                        throw new IllegalArgumentException(
                                "patch would rename unit " + unitKey + " to " + v + "; a unit's key is its identity");
                    }
                }
                case "method" -> method = str(v);
                case "fqcn" -> fqcn = str(v);
                case "coverage" -> coverage = State.asDouble(v);
                case "mutation" -> mutation = State.asDouble(v);
                case "mac" -> mac = State.asDouble(v);
                case "macBefore" -> macBefore = State.asDouble(v);
                case "macAfter" -> macAfter = State.asDouble(v);
                case "status" -> status = str(v);
                case "attempts" -> attempts = (int) State.asLong(v);
                case "lastSurvived" -> lastSurvived = list(v);
                case "branch" -> branch = str(v);
                case "prUrl" -> prUrl = str(v);
                case "prPatch" -> prPatch = str(v);
                case "spentSec" -> spentSec = v instanceof Number n ? n.longValue() : null;
                case "tokensIn" -> tokensIn = State.asLong(v);
                case "tokensOut" -> tokensOut = State.asLong(v);
                case "llmCalls" -> llmCalls = State.asLong(v);
                case "updatedAt" -> { /* stamped by the store, never by a caller */ }
                default -> nextExtra.put(k, v);
            }
        }
        extra = nextExtra;
    }

    /// The unit as the record the pipeline and the dashboard read — the shape
    /// `state.files[key]` had.
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        // The file, falling back to the key for rows written before `path` was a column of its
        // own — those were seeded during discovery, when the two really were the same string.
        out.put("path", path == null ? unitKey : path);
        out.put("key", unitKey);
        out.put("coverage", coverage);
        out.put("mutation", mutation);
        out.put("mac", mac);
        out.put("macBefore", macBefore);
        out.put("macAfter", macAfter);
        out.put("status", status);
        out.put("attempts", attempts);
        out.put("branch", branch);
        out.put("prUrl", prUrl);
        out.put("prPatch", prPatch);
        out.put("updatedAt", updatedAt);
        // Written only once set, as in the JS, where the key does not exist until something
        // assigns it. `lastSurvived` in particular: an emitted null would be read as "measured,
        // nothing survived" by nothing at all, but an emitted `[]` would.
        if (method != null) out.put("method", method);
        if (fqcn != null) out.put("fqcn", fqcn);
        if (lastSurvived != null) out.put("lastSurvived", lastSurvived);
        if (spentSec != null) out.put("spentSec", spentSec);
        if (tokensIn != 0 || tokensOut != 0 || llmCalls != 0) {
            out.put("tokensIn", tokensIn);
            out.put("tokensOut", tokensOut);
            out.put("llmCalls", llmCalls);
        }
        if (extra != null) {
            for (Map.Entry<String, Object> e : extra.entrySet()) {
                // A seeded key can never live in `extra` — `patch` routes all twelve to columns
                // — so this cannot shadow one. Stated rather than assumed: the day someone adds
                // a column, the guard is what stops the old rows' copy overwriting the new one.
                if (SEEDED.contains(e.getKey())) continue;
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private static String str(Object v) { return v == null ? null : String.valueOf(v); }

    /// A copy, and an ArrayList rather than `List.copyOf` — the immutable factories reject null
    /// ELEMENTS, and a survivor list assembled from partially parsed PIT output can carry one.
    /// Anything that is not a list at all — including null — clears the column back to
    /// "never measured", which is the only other thing it can honestly mean.
    private static List<Object> list(Object v) {
        return v instanceof List<?> l ? new ArrayList<Object>(l) : null;
    }

    public Long getId() { return id; }

    public String getRunId() { return runId; }

    public String getUnitKey() { return unitKey; }

    public String getPath() { return path == null ? unitKey : path; }

    public String getMethod() { return method; }

    public String getFqcn() { return fqcn; }

    public Double getCoverage() { return coverage; }

    public Double getMutation() { return mutation; }

    public Double getMac() { return mac; }

    public Double getMacBefore() { return macBefore; }

    public Double getMacAfter() { return macAfter; }

    public String getStatus() { return status; }

    public int getAttempts() { return attempts; }

    public List<Object> getLastSurvived() { return lastSurvived; }

    public String getBranch() { return branch; }

    public String getPrUrl() { return prUrl; }

    public String getPrPatch() { return prPatch; }

    public Long getSpentSec() { return spentSec; }

    public long getTokensIn() { return tokensIn; }

    public long getTokensOut() { return tokensOut; }

    public long getLlmCalls() { return llmCalls; }

    public long getUpdatedAt() { return updatedAt; }

    void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public Map<String, Object> getExtra() { return extra; }

    @Override
    public String toString() {
        return "Unit[" + runId + " " + unitKey + " " + status + "]";
    }
}
