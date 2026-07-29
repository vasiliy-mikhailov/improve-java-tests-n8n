package tech.mikhailov.ijt.orchestrator.store;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Map;

/// The store's own row: the three things that belong to neither a run nor a repo. There is
/// exactly one, id 1.
///
/// Not in the design's table list. It is here because `seq` has a requirement no row-derived
/// counter can meet — see {@link #getLastSeq()} — and once a singleton exists, the current run
/// pointer and the LLM budget have an obvious home rather than an invented one.
@Entity
@Table(name = "ijt_store")
public class StoreRow {

    /// The only id there is. A constant rather than a generated key: two "singleton" rows is a
    /// class of bug that ends with two counters handing out the same seq.
    public static final int SINGLETON_ID = 1;

    @Id
    @Column(name = "id", nullable = false)
    private int id = SINGLETON_ID;

    /// The highest event seq ever ISSUED — not the highest still stored.
    ///
    /// This is the distinction the whole column exists for. `seq` must keep rising across runs
    /// because a reconnecting client asks for everything after the last seq it holds; and run
    /// start DELETES that run's event rows. Seed a counter from `max(seq)` over the rows and the
    /// delete rewinds it: the next run re-issues numbers a connected client already holds, the
    /// client filters them as old, and its feed silently stops. An identity column or a JPA
    /// generator has the same shape of problem the day someone reaches for TRUNCATE.
    ///
    /// It is written in the same transaction as the event it numbers, so a crash between the two
    /// leaves a GAP. Gaps are harmless — `after=` is a floor, not an index — and a rewind is not.
    @Column(name = "last_seq", nullable = false)
    private long lastSeq;

    /// The run `startRun` created last, and the one boot recovery looks at. Explicit rather than
    /// `order by started_at desc limit 1`: `POST /api/run/start` with `force:true` creates the
    /// replacement run in the same millisecond as the one it takes over from, so the wall clock
    /// alone is not an ordering.
    @Column(name = "current_run_id", length = 64)
    private String currentRunId;

    /// The LLM budget's learned per-stage ceilings.
    ///
    /// Deliberately NOT reset by `run/start`, unlike `mutatorStats` beside it on the run: a stage
    /// that once ran out of room should start wider next time instead of paying for the same
    /// truncated call again. Only `purge` clears it. Left as a map for the reason `Llm` gives —
    /// nothing here reads it, and a shape mismatch must cost the learned ceilings and nothing
    /// else.
    @Column(name = "llm_budget_json", length = 1_000_000)
    @Convert(converter = Json.MapConverter.class)
    private Map<String, Object> llmBudget;

    public StoreRow() {}

    public int getId() { return id; }

    public long getLastSeq() { return lastSeq; }

    public void setLastSeq(long lastSeq) { this.lastSeq = lastSeq; }

    public String getCurrentRunId() { return currentRunId; }

    public void setCurrentRunId(String currentRunId) { this.currentRunId = currentRunId; }

    public Map<String, Object> getLlmBudget() { return llmBudget; }

    public void setLlmBudget(Map<String, Object> llmBudget) { this.llmBudget = llmBudget; }
}
