package tech.mikhailov.ijt.orchestrator.store;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.List;
import java.util.Map;

/// One run of the pipeline — the row that replaces `state.run` in state.json.
///
/// Tables are prefixed `ijt_` for two reasons. Spring Batch owns `BATCH_*` in the same
/// database, and half the natural names here (`run`, `unit`, `event`, `key`, `value`) are
/// either reserved words in some SQL dialect or too generic to read in a shared schema.
///
/// The id is the domain's own — `run-<millis>`, minted by `State.freshRun` — and not a
/// generated number. It is not decoration: it is what marks a branch as THIS run's work, so
/// `Branch.branchAction` reuses a branch when the owner matches and resets it when it does
/// not. A surrogate key here would leave two identities for one run and the branch owner
/// checking the wrong one.
@Entity
@Table(name = "ijt_run")
public class Run {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "repo_url", length = 512)
    private String repoUrl;

    @Column(name = "repo_branch", length = 256)
    private String repoBranch;

    /// `running` | `done` | `failed` | `stopped` | `interrupted`.
    ///
    /// TERMINAL IFF NO PROCESS IS EXECUTING THE RUN. That is the whole rule, and it is written
    /// at the three points that can observe it: `POST /api/run/finish` when the job completes,
    /// `RunOutcomeListener` when the job ends any other way, and the boot restore when the JVM
    /// that owned the run is provably gone.
    ///
    /// This comment used to say the opposite — that `interrupted` is deliberately NOT written
    /// here, because the STAGE carries it and `run/start` reads the stage to decide whether a
    /// run is genuinely active. The stage never survived the process: the columns that would
    /// have carried it had no writer, and the stage a restarted instance reports is synthesised
    /// in memory from this field. So the field every consumer reads went on saying `running`
    /// while nothing ran, and neither half of the supposed pair was true.
    ///
    /// The takeover path the old comment protected is intact: `run/start` still reads the stage,
    /// and a terminal status makes its `active` check false through the FIRST clause instead of
    /// the second. Nothing is lost by telling the truth twice.
    @Column(name = "status", length = 32)
    private String status;

    /// Epoch SECONDS (`Util.nowSec`), like everything else the dashboard renders as a time.
    /// PR rows carry milliseconds; the difference is the domain's, and it is kept rather than
    /// harmonised so a value can move between store and domain without a conversion nobody
    /// remembers to apply.
    @Column(name = "started_at")
    private long startedAt;

    /// Null until the run finishes. Boxed for the usual reason: 0 would be a timestamp.
    @Column(name = "finished_at")
    private Long finishedAt;

    @Column(name = "iteration")
    private int iteration;

    /// The whole `State.Config`, as JSON.
    ///
    /// Twenty-three knobs, most of them nullable, several of which mean "unlimited" at 0 and
    /// are read as `x > 0` everywhere. A column each would be twenty-three migrations waiting
    /// to happen and would tempt someone into `int` — and `State.Config` boxes every numeric
    /// component ON PURPOSE, because a garbled `SCOPE_LIMIT` must reach the dashboard as null
    /// and not as a confident 0.
    @Column(name = "config_json", length = 1_000_000)
    @Convert(converter = Json.MapConverter.class)
    private Map<String, Object> config;

    // ── metrics ───────────────────────────────────────────────────────────────
    // All six boxed and all six null until measured. NOT YET MEASURED IS NOT ZERO: a run that
    // reports 0% coverage because JaCoCo has not run yet is a lie the dashboard prints in bold,
    // and a 0 baseline flatters every later improvement into a triumph.

    @Column(name = "baseline_coverage_pct")
    private Double baselineCoveragePct;

    @Column(name = "baseline_mutation_pct")
    private Double baselineMutationPct;

    @Column(name = "baseline_mac")
    private Double baselineMac;

    @Column(name = "result_coverage_pct")
    private Double resultCoveragePct;

    @Column(name = "result_mutation_pct")
    private Double resultMutationPct;

    @Column(name = "result_mac")
    private Double resultMac;

    // ── token usage ───────────────────────────────────────────────────────────
    // Primitive, unlike the metrics above, and the contrast is the point: a count really does
    // start at zero, and `State.asLong` says so by treating an absent counter as 0. A
    // measurement does not.

    @Column(name = "tokens_in")
    private long tokensIn;

    @Column(name = "tokens_out")
    private long tokensOut;

    @Column(name = "llm_calls")
    private long llmCalls;

    /// The unit being improved right now — token usage is attributed to it.
    @Column(name = "current_unit", length = 512)
    private String currentUnit;

    /// Fields the routes add as a run goes on. Absent from a fresh run in the JS, where the key
    /// does not exist until something assigns it, so all three are boxed and start null.
    @Column(name = "measured_files")
    private Integer measuredFiles;

    /// Whether this batch has already charged its setup time to the overhead ledger. A flag and
    /// not `iteration == 0`: a unit skipped for having no mutation surface REFUNDS its
    /// iteration, so the counter returned to 0 and the next pick charged the whole elapsed time
    /// again. Three skips reported roughly three times the real setup cost as machine hours.
    @Column(name = "overhead_charged")
    private Boolean overheadCharged;

    /// Consecutive picks the model failed to make.
    @Column(name = "pick_failures")
    private Integer pickFailures;

    // ── the stage IS NOT HERE, and was not before either ──────────────────────
    // Three columns stood here — stage_name, stage_detail, stage_since — with a comment
    // explaining that the stage must survive the process. Nothing ever wrote them. The only
    // live writer of this row (H2StatePersistence.toRun) never set them and the only live
    // reader (fromRow) never read them, so they were NULL on every row in the deployment while
    // the design was believed to depend on them. Deleted rather than wired: the stage a
    // restarted instance needs is derivable from `status`, which is durable, and one durable
    // fact beats two that disagree. With ddl-auto:update the columns simply linger unused.

    // ── run-scoped blobs ──────────────────────────────────────────────────────
    // Each of these resets with the run, and gets that for free by living on the run's row:
    // a new run is a new row, so the old values are neither visible nor deletable by accident.

    /// `{ tool, wrapper, jdk, testFramework, testFrameworkVersion }`.
    @Column(name = "runner_json", length = 1_000_000)
    @Convert(converter = Json.MapConverter.class)
    private Map<String, Object> runner;

    /// ruleStage → the last application result.
    @Column(name = "decisions_json", length = 1_000_000)
    @Convert(converter = Json.MapConverter.class)
    private Map<String, Object> decisions;

    /// What this run has learned about mutator kinds. Reset on run start on purpose: carrying it
    /// over demotes a mutator in run B for misses that happened in run A, against another repo.
    @Column(name = "mutator_stats_json", length = 1_000_000)
    @Convert(converter = Json.MapConverter.class)
    private Map<String, Object> mutatorStats;

    /// branch name → the unit that owns it.
    @Column(name = "branch_owners_json", length = 1_000_000)
    @Convert(converter = Json.MapConverter.class)
    private Map<String, Object> branchOwners;

    /// The last few model exchanges, so the dashboard can show what is actually being asked and
    /// answered rather than only that a call is in flight.
    @Column(name = "llm_log_json", length = 1_000_000)
    @Convert(converter = Json.ListConverter.class)
    private List<Object> llmLog;

    /// Anything a newer (or older) writer put on the run object. The JS got this from
    /// `Object.assign` for free and `State.Run` keeps it with an any-setter; without it a
    /// rollback to the Node sidecar — or to `BACKEND=java` — reads back a run with keys missing.
    @Column(name = "extra_json", length = 1_000_000)
    @Convert(converter = Json.MapConverter.class)
    private Map<String, Object> extra;

    protected Run() {} // JPA

    public Run(String id) {
        this.id = id;
    }

    public String getId() { return id; }

    public void setId(String id) { this.id = id; }

    public String getRepoUrl() { return repoUrl; }

    public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }

    public String getRepoBranch() { return repoBranch; }

    public void setRepoBranch(String repoBranch) { this.repoBranch = repoBranch; }

    public String getStatus() { return status; }

    public void setStatus(String status) { this.status = status; }

    public long getStartedAt() { return startedAt; }

    public void setStartedAt(long startedAt) { this.startedAt = startedAt; }

    public Long getFinishedAt() { return finishedAt; }

    public void setFinishedAt(Long finishedAt) { this.finishedAt = finishedAt; }

    public int getIteration() { return iteration; }

    public void setIteration(int iteration) { this.iteration = iteration; }

    public Map<String, Object> getConfig() { return config; }

    public void setConfig(Map<String, Object> config) { this.config = config; }

    public Double getBaselineCoveragePct() { return baselineCoveragePct; }

    public void setBaselineCoveragePct(Double v) { this.baselineCoveragePct = v; }

    public Double getBaselineMutationPct() { return baselineMutationPct; }

    public void setBaselineMutationPct(Double v) { this.baselineMutationPct = v; }

    public Double getBaselineMac() { return baselineMac; }

    public void setBaselineMac(Double v) { this.baselineMac = v; }

    public Double getResultCoveragePct() { return resultCoveragePct; }

    public void setResultCoveragePct(Double v) { this.resultCoveragePct = v; }

    public Double getResultMutationPct() { return resultMutationPct; }

    public void setResultMutationPct(Double v) { this.resultMutationPct = v; }

    public Double getResultMac() { return resultMac; }

    public void setResultMac(Double v) { this.resultMac = v; }

    public long getTokensIn() { return tokensIn; }

    public void setTokensIn(long tokensIn) { this.tokensIn = tokensIn; }

    public long getTokensOut() { return tokensOut; }

    public void setTokensOut(long tokensOut) { this.tokensOut = tokensOut; }

    public long getLlmCalls() { return llmCalls; }

    public void setLlmCalls(long llmCalls) { this.llmCalls = llmCalls; }

    public String getCurrentUnit() { return currentUnit; }

    public void setCurrentUnit(String currentUnit) { this.currentUnit = currentUnit; }

    public Integer getMeasuredFiles() { return measuredFiles; }

    public void setMeasuredFiles(Integer measuredFiles) { this.measuredFiles = measuredFiles; }

    public Boolean getOverheadCharged() { return overheadCharged; }

    public void setOverheadCharged(Boolean overheadCharged) { this.overheadCharged = overheadCharged; }

    public Integer getPickFailures() { return pickFailures; }

    public void setPickFailures(Integer pickFailures) { this.pickFailures = pickFailures; }

    public Map<String, Object> getRunner() { return runner; }

    public void setRunner(Map<String, Object> runner) { this.runner = runner; }

    public Map<String, Object> getDecisions() { return decisions; }

    public void setDecisions(Map<String, Object> decisions) { this.decisions = decisions; }

    public Map<String, Object> getMutatorStats() { return mutatorStats; }

    public void setMutatorStats(Map<String, Object> mutatorStats) { this.mutatorStats = mutatorStats; }

    public Map<String, Object> getBranchOwners() { return branchOwners; }

    public void setBranchOwners(Map<String, Object> branchOwners) { this.branchOwners = branchOwners; }

    public List<Object> getLlmLog() { return llmLog; }

    public void setLlmLog(List<Object> llmLog) { this.llmLog = llmLog; }

    public Map<String, Object> getExtra() { return extra; }

    public void setExtra(Map<String, Object> extra) { this.extra = extra; }

    @Override
    public String toString() {
        return "Run[" + id + " " + status + " " + repoUrl + "#" + repoBranch + "]";
    }
}
