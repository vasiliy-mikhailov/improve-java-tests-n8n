package tech.mikhailov.ijt.orchestrator.store;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tech.mikhailov.ijt.Measure;
import tech.mikhailov.ijt.State;
import tech.mikhailov.ijt.Util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// The one door into the store.
///
/// Everything the pipeline knows about a run — the run, its units, its event log, its PRs and
/// the per-repo ledgers — is read and written here and nowhere else. That is a design
/// requirement, not a preference: `H2 in-memory` and `H2 file-backed under DATA_DIR` must stay
/// one JDBC URL apart, and they only do while nothing outside this package holds an
/// `EntityManager` or a Spring Data repository. Every repository interface in this package is
/// package-private, so the rule is enforced by the compiler rather than by review.
///
/// It replaces `State`, the JSON-file-backed store, and it keeps four of its rules because each
/// one cost a real run to learn:
///
///   1. `seq` only ever rises — {@link #appendEvent}.
///   2. Clearing the event log at run start deletes THIS RUN's rows, never the table —
///      {@link #startRun}, {@link #clearEventsForRun}.
///   3. The ledgers survive run start — {@link LedgerRow}.
///   4. Unmeasured is NULL, never 0 — every metric column is boxed; see {@link Unit}.
///
/// Class-level `@Transactional`, so a caller that forgets one still gets a transaction: several
/// of the operations below are read-modify-write on a row (the seq high-water mark, the overhead
/// total, a measurement merge) and are only atomic inside one.
@Repository
@Transactional
public class StateRepository {

    /// What `State.event` clipped a message to. The container log gets the raw line; this is the
    /// dashboard's copy.
    static final int MAX_EVENT_MSG = 500;

    private final RunRepository runs;
    private final UnitRepository units;
    private final EventRepository events;
    private final PrRepository prs;
    private final LedgerRepository ledgers;
    private final StoreRowRepository store;

    public StateRepository(RunRepository runs, UnitRepository units, EventRepository events,
                           PrRepository prs, LedgerRepository ledgers, StoreRowRepository store) {
        this.runs = runs;
        this.units = units;
        this.events = events;
        this.prs = prs;
        this.ledgers = ledgers;
        this.store = store;
    }

    // ── the singleton row ─────────────────────────────────────────────────────

    /// The store's own row, created on first use.
    ///
    /// `synchronized` because two threads finding it absent would both insert id 1 and the
    /// second would fail on the primary key. Cheap: after the first call it is a keyed read.
    private synchronized StoreRow storeRow() {
        return store.findById(StoreRow.SINGLETON_ID).orElseGet(() -> {
            StoreRow fresh = new StoreRow();
            // A database that already holds events but no store row — one written by an earlier
            // build, or restored from a backup — must not restart numbering inside a range
            // clients have already seen. This is the ONLY place max(seq) may seed the mark.
            Long max = events.maxSeq();
            fresh.setLastSeq(max == null ? 0L : max);
            return store.save(fresh);
        });
    }

    // ── runs ──────────────────────────────────────────────────────────────────

    /// The run this process is working on, if any.
    public Optional<Run> currentRun() {
        String id = storeRow().getCurrentRunId();
        return id == null ? Optional.empty() : runs.findById(id);
    }

    public Optional<Run> run(String runId) {
        return runs.findById(runId);
    }

    public List<Run> allRuns() {
        return runs.findAllByOrderByStartedAtDesc();
    }

    /// Persist a fresh run and reset everything a run owns — the port of `POST /api/run/start`.
    ///
    /// WHAT RESETS, AND HOW. Units, PRs, decisions, the mutator stats and the token counters all
    /// belong to a run, and they reset because they are scoped BY run: a new id is a new set of
    /// rows and the previous run's are simply not visible to this one. Nothing has to remember to
    /// clear them, which is the failure this design removes — the event log was the one thing
    /// left behind in the JS, and a live run against stleary/JSON-java opened with units of
    /// java-dataloader from a different run hours earlier.
    ///
    /// The ledger tables are keyed by repo and not by run, so they survive, which is the whole
    /// reason they exist. `clearLedger:true` on the request body is a separate, explicit call to
    /// {@link #clearImproved}.
    ///
    /// THE EVENT LOG is the one thing that needs a delete, and the delete is scoped to a run id.
    /// Not `truncate`: that would take every other run's rows with it and, on most databases,
    /// reset the identity that `seq` used to come from. `seq` does not reset here at all — the
    /// high-water mark on {@link StoreRow} is untouched by this method.
    public Run startRun(Run fresh) {
        StoreRow s = storeRow();
        String previous = s.getCurrentRunId();
        if (previous != null && !previous.equals(fresh.getId())) {
            clearEventsForRun(previous);
            // The JS overwrote `state.run` and the outgoing run's status vanished with it. The row
            // stays here, so a status still reading `running` would look like a second live run to
            // anything that asks. It is not running: this call is what took it over.
            runs.findById(previous).ifPresent(old -> {
                if ("running".equals(old.getStatus())) {
                    old.setStatus("interrupted");
                    runs.save(old);
                }
            });
        }
        // `force:true` restarts a run under an id that may already have written events (it mints
        // ids a millisecond apart, and a takeover happens inside one). Its log belongs to the
        // attempt that just ended.
        clearEventsForRun(fresh.getId());
        Run saved = runs.save(fresh);
        s.setCurrentRunId(saved.getId());
        store.save(s);
        return saved;
    }

    public Run saveRun(Run run) {
        return runs.save(run);
    }

    /// `POST /api/run/finish`.
    public Optional<Run> finishRun(String runId, String status) {
        return runs.findById(runId).map(run -> {
            run.setStatus(status == null || status.isEmpty() ? "done" : status);
            run.setFinishedAt(Util.nowSec());
            return runs.save(run);
        });
    }

    /// Boot recovery: a run left `running` by a process that died mid-run.
    ///
    /// The STAGE moves to `interrupted`; the STATUS is deliberately left alone. `POST
    /// /api/run/start` reads the stage to decide whether a run is genuinely active and refuses a
    /// takeover otherwise — marking the status finished here would make a hung run look
    /// completed and lose the only route that ever recovered one. This is the behaviour that
    /// made a hung run recoverable at all, and it only ever worked because state.json survived
    /// the container restart; the H2 file is what survives it now.
    ///
    /// @return the run it marked, if there was one
    public Optional<Run> markInterruptedIfRunning() {
        return currentRun().map(run -> {
            if (!"running".equals(run.getStatus())) return run;
            run.setStageName("interrupted");
            run.setStageDetail("orchestrator restarted");
            run.setStageSince(Util.nowSec());
            return runs.save(run);
        });
    }

    /// Move a run's stage. `since` is stamped here: `run/start` measures staleness from it, and a
    /// caller passing its own clock is how two components disagree about how long a run has been
    /// stuck.
    public Optional<Run> setStage(String runId, String name, String detail) {
        return runs.findById(runId).map(run -> {
            run.setStageName(name);
            run.setStageDetail(detail == null ? "" : Util.redact(detail));
            run.setStageSince(Util.nowSec());
            return runs.save(run);
        });
    }

    /// Accumulate LLM usage on the run and on the unit being improved — `State.addTokens`.
    ///
    /// Called for EVERY completion, including retries and repairs: the cost of a unit is
    /// everything spent getting there, not just the successful call.
    public void addTokens(String runId, String unitKey, long inTok, long outTok) {
        Run run = runs.findById(runId).orElse(null);
        if (run == null) return;    // before the save, as in the JS
        run.setTokensIn(run.getTokensIn() + inTok);
        run.setTokensOut(run.getTokensOut() + outTok);
        run.setLlmCalls(run.getLlmCalls() + 1);
        runs.save(run);
        if (unitKey == null) return;
        units.findByRunIdAndUnitKey(runId, unitKey).ifPresent(u -> {
            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("tokensIn", u.getTokensIn() + inTok);
            patch.put("tokensOut", u.getTokensOut() + outTok);
            patch.put("llmCalls", u.getLlmCalls() + 1);
            u.patch(patch);
            u.setUpdatedAt(Util.nowSec());
            units.save(u);
        });
    }

    // ── units ─────────────────────────────────────────────────────────────────

    /// Create or patch a unit's record, and return it — `State.upsertFile`.
    ///
    /// The patch is applied key by key, INCLUDING its nulls: `{attemptStartedAt: null}` is how a
    /// caller stops the stopwatch, and skipping nulls would leave it running for ever.
    /// `updatedAt` is written after the patch and so cannot be forged by one.
    public Unit upsertUnit(String runId, String unitKey, Map<String, Object> patch) {
        Unit unit = units.findByRunIdAndUnitKey(runId, unitKey)
                .orElseGet(() -> new Unit(runId, unitKey));
        unit.patch(patch);
        unit.setUpdatedAt(Util.nowSec());
        return units.save(unit);
    }

    public Optional<Unit> unit(String runId, String unitKey) {
        return units.findByRunIdAndUnitKey(runId, unitKey);
    }

    public List<Unit> units(String runId) {
        return units.findByRunIdOrderByUnitKeyAsc(runId);
    }

    public long unitCount(String runId) {
        return units.countByRunId(runId);
    }

    /// The run's units in the shape `state.files` had: unit key → record.
    ///
    /// The pipeline reads unit records as open maps — see {@link Json} — so this is what the
    /// domain code and `GET /api/state` want, and {@link #units} is for anything that would
    /// rather have columns.
    public Map<String, Map<String, Object>> unitsAsMap(String runId) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Unit u : units.findByRunIdOrderByUnitKeyAsc(runId)) {
            out.put(u.getUnitKey(), u.toMap());
        }
        return out;
    }

    // ── events ────────────────────────────────────────────────────────────────

    /// Append one line to the event log and give it the next seq.
    ///
    /// `synchronized` on top of the transaction: the high-water mark is a read-modify-write, and
    /// the Node sidecar this replaces had one thread where the orchestrator has a Batch thread
    /// and a pool of web threads. The monitor makes the numbering contiguous instead of leaving
    /// two writers to fight over the row and one of them to lose on a lock timeout.
    ///
    /// The message is redacted and clipped exactly as `State.event` did — child-process output
    /// can echo credentials the pipeline passed in, and the dashboard is not the place to
    /// diagnose that. The container log still gets the raw line, from the caller.
    public synchronized EventRow appendEvent(String runId, String stage, String msg) {
        StoreRow s = storeRow();
        long seq = s.getLastSeq() + 1;
        s.setLastSeq(seq);
        store.save(s);
        EventRow row = new EventRow(seq, runId, Util.nowSec(), stage, clip(Util.redact(msg)));
        return events.save(row);
    }

    /// The highest seq ever issued. What a fresh dashboard reads before subscribing, so it can
    /// ask for everything after it.
    ///
    /// Not `readOnly`, despite reading: on the first call against a fresh database it CREATES the
    /// singleton row, and a read-only transaction sets the flush mode to manual — the insert
    /// would be prepared, never written, and the row would be re-created on every call.
    public long lastSeq() {
        return storeRow().getLastSeq();
    }

    /// Raise the high-water mark — never lower it.
    ///
    /// For the migration: an existing /data/state.json carries a `seq` and an events.jsonl
    /// carries lines numbered from it. Importing those without moving the mark would re-issue
    /// numbers a client connected across the migration already holds, and it would drop every
    /// one of them as old.
    public long bumpSeqTo(long seq) {
        StoreRow s = storeRow();
        if (seq > s.getLastSeq()) {
            s.setLastSeq(seq);
            store.save(s);
        }
        return s.getLastSeq();
    }

    /// The reconnect query, scoped to a run: everything after the last seq the client holds.
    @Transactional(readOnly = true)
    public List<EventRow> eventsAfter(String runId, long after) {
        return events.findByRunIdAndSeqGreaterThanOrderBySeqAsc(runId, after);
    }

    /// Every event after `after`, whatever run wrote it. For the window before the first run
    /// exists; the dashboard's feed should use the run-scoped form.
    @Transactional(readOnly = true)
    public List<EventRow> eventsAfter(long after) {
        return events.findBySeqGreaterThanOrderBySeqAsc(after);
    }

    /// Delete one run's log. THE SCOPE IS THE POINT: `truncate` (or a `delete` with no
    /// predicate) would take the rows of every other run in the table, and on most databases
    /// reset the identity `seq` would otherwise come from. `seq` is untouched here — the mark
    /// lives on {@link StoreRow}, which this method does not write.
    public long clearEventsForRun(String runId) {
        return events.deleteByRunId(runId);
    }

    // ── PRs ───────────────────────────────────────────────────────────────────

    public PrRow addPr(PrRow pr) {
        return prs.save(pr);
    }

    public List<PrRow> prs(String runId) {
        return prs.findByRunIdOrderByCreatedAtAscIdAsc(runId);
    }

    /// `state.prs` — the list `GET /api/state` serves and the dashboard renders.
    public List<Map<String, Object>> prsAsMaps(String runId) {
        return prs(runId).stream().map(PrRow::toMap).toList();
    }

    // ── ledgers ───────────────────────────────────────────────────────────────

    /// `improvedLedger[repoSlug]` — unit key → final disposition.
    @Transactional(readOnly = true)
    public Map<String, Map<String, Object>> improvedLedger(String repoSlug) {
        return ledgerAsMap(LedgerRow.Kind.IMPROVED, repoSlug);
    }

    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> improved(String repoSlug, String unitKey) {
        return ledgers.findByKindAndRepoSlugAndUnitKey(LedgerRow.Kind.IMPROVED, repoSlug, unitKey)
                .map(LedgerRow::getPayload);
    }

    /// Record a unit's final disposition. The record is stored verbatim; see
    /// {@link LedgerRow#getPayload()} for why it is not decomposed into columns.
    public void putImproved(String repoSlug, String unitKey, Map<String, Object> record) {
        put(LedgerRow.Kind.IMPROVED, repoSlug, unitKey, record);
    }

    /// `measureLedger[repoSlug]` — every measurement ever taken for this repo, improved or not.
    @Transactional(readOnly = true)
    public Map<String, Map<String, Object>> measureLedger(String repoSlug) {
        return ledgerAsMap(LedgerRow.Kind.MEASURE, repoSlug);
    }

    /// `Server.recordMeasurement`: merge over what is already there, stamp it, store it.
    ///
    /// A MERGE and not a replace, because the numbers arrive in pieces — the coverage route
    /// knows `coverageBefore`, the PIT route knows `mutationBefore`, and a later attempt knows
    /// what it reached. A replace would blank whichever half was not in this caller's hand.
    ///
    /// `Measure.stamp` is applied HERE, at the write, rather than left to the caller. The stamp
    /// is what lets a later change to the MEANING of a number invalidate the entry instead of
    /// silently replaying it into a new run, and an unstamped entry is indistinguishable from
    /// one measured under semantics that no longer hold.
    public Map<String, Object> mergeMeasured(String repoSlug, String unitKey, Map<String, Object> patch) {
        Optional<LedgerRow> existing =
                ledgers.findByKindAndRepoSlugAndUnitKey(LedgerRow.Kind.MEASURE, repoSlug, unitKey);
        Map<String, Object> merged = new LinkedHashMap<>();
        existing.map(LedgerRow::getPayload).ifPresent(merged::putAll);
        if (patch != null) merged.putAll(patch);
        merged.put("ts", System.currentTimeMillis());
        Map<String, Object> stamped = Measure.stamp(merged);
        existing.ifPresentOrElse(
                row -> { row.setPayload(stamped); ledgers.save(row); },
                () -> ledgers.save(new LedgerRow(LedgerRow.Kind.MEASURE, repoSlug, unitKey, stamped)));
        return stamped;
    }

    /// Store a measurement exactly as given — for the migration, which replays entries that were
    /// already stamped by the writer that produced them and must not be re-stamped with today's
    /// version.
    public void putMeasured(String repoSlug, String unitKey, Map<String, Object> entry) {
        put(LedgerRow.Kind.MEASURE, repoSlug, unitKey, entry);
    }

    /// `clearLedger:true` on `POST /api/run/start`: forget what previous runs SETTLED for this
    /// repo, and only that. The measurements stay — they are still true, and re-measuring 319
    /// units costs 40 minutes.
    public long clearImproved(String repoSlug) {
        return ledgers.deleteByKindAndRepoSlug(LedgerRow.Kind.IMPROVED, repoSlug);
    }

    /// `POST /api/purge`: start a repo again from nothing. Every kind, including the overhead
    /// total.
    ///
    /// The ledgers only. `Server.purgeRepo` also clears `mutatorStats`, the learned
    /// {@link #llmBudget()} and the current run's units and PRs — those belong to a RUN, not to
    /// the repo, and a purge that reached into them from here would be deleting rows the caller
    /// did not ask about. The caller does them by name.
    public long purgeRepo(String repoSlug) {
        return ledgers.deleteByRepoSlug(repoSlug);
    }

    /// Cumulative setup seconds charged to this repo across all runs.
    ///
    /// Absent reads as 0, and here that is right: it is a total of time spent, and no time has
    /// been spent. The rule it does not contradict — unmeasured is not zero — is about
    /// MEASUREMENTS, which is why every metric column is boxed and this is not.
    @Transactional(readOnly = true)
    public long overheadSec(String repoSlug) {
        return ledgers.findByKindAndRepoSlugAndUnitKey(LedgerRow.Kind.OVERHEAD, repoSlug, "")
                .map(row -> State.asLong(row.getPayload().get("seconds")))
                .orElse(0L);
    }

    /// Charge setup time to the repo. Additive: a batch is many runs against one repo and the
    /// FTE ratio counts all of it.
    public long addOverheadSec(String repoSlug, long seconds) {
        long total = overheadSec(repoSlug) + Math.max(0, seconds);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("seconds", total);
        payload.put("ts", System.currentTimeMillis());
        put(LedgerRow.Kind.OVERHEAD, repoSlug, "", payload);
        return total;
    }

    private void put(LedgerRow.Kind kind, String repoSlug, String unitKey, Map<String, Object> payload) {
        String key = unitKey == null ? "" : unitKey;
        ledgers.findByKindAndRepoSlugAndUnitKey(kind, repoSlug, key).ifPresentOrElse(
                row -> { row.setPayload(payload); ledgers.save(row); },
                () -> ledgers.save(new LedgerRow(kind, repoSlug, key, payload)));
    }

    private Map<String, Map<String, Object>> ledgerAsMap(LedgerRow.Kind kind, String repoSlug) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (LedgerRow row : ledgers.findByKindAndRepoSlug(kind, repoSlug)) {
            out.put(row.getUnitKey(), row.getPayload());
        }
        return out;
    }

    // ── store-wide ────────────────────────────────────────────────────────────

    /// The LLM budget's learned per-stage ceilings, or null. Survives `run/start`; only `purge`
    /// clears it. See {@link StoreRow#getLlmBudget()}. Not `readOnly`, for the reason
    /// {@link #lastSeq()} gives.
    public Map<String, Object> llmBudget() {
        return storeRow().getLlmBudget();
    }

    public void saveLlmBudget(Map<String, Object> budget) {
        StoreRow s = storeRow();
        s.setLlmBudget(budget);
        store.save(s);
    }

    /// Is this database empty — nothing ever written to it?
    ///
    /// The migration's question. An existing /data/state.json holds a populated improvedLedger
    /// and measureLedger, and it must be read in ONCE, on the first boot where the H2 file does
    /// not yet exist. Read it again on a later boot and it would resurrect entries a `purge`
    /// deliberately removed; skip it on the first and the first Spring run redoes work the Node
    /// and Java backends already finished.
    @Transactional(readOnly = true)
    public boolean isEmpty() {
        return runs.count() == 0 && ledgers.count() == 0 && events.count() == 0;
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() <= MAX_EVENT_MSG ? s : s.substring(0, MAX_EVENT_MSG);
    }
}
