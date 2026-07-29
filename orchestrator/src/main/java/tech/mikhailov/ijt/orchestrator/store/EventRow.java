package tech.mikhailov.ijt.orchestrator.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/// One line of the event log: the dashboard's feed, and the only record of what a run did once
/// its stage has moved on.
///
/// Append-only. The single mutation the log ever sees is the scoped delete at run start — see
/// {@link StateRepository#clearEventsForRun}.
@Entity
@Table(name = "ijt_event", indexes = @Index(name = "ix_event_run", columnList = "run_id"))
public class EventRow {

    /// The primary key, and not decoration.
    ///
    /// `GET /api/events?after=` pages on it and a reconnecting STOMP client asks for everything
    /// after the last seq it holds, so it must ONLY EVER RISE — across runs, across the delete
    /// at run start, and across a process restart. It is assigned by
    /// {@link StateRepository#appendEvent} from a durable high-water mark
    /// ({@link StoreRow#getLastSeq()}) rather than by an identity column or a generator, because
    /// both of those are properties of the ROWS: delete the rows and `max(seq) + 1` rewinds,
    /// and every event a connected client already holds becomes undeliverable — it looks older
    /// than what the client has, so the client filters it out and simply stops updating.
    @Id
    @Column(name = "seq", nullable = false)
    private long seq;

    /// The run this line belongs to. Null for the handful written outside one — `purged X`,
    /// and anything logged before the first `run/start`.
    ///
    /// The event log belongs to a run. It was the one thing left behind at run start, and
    /// because it was a ring buffer persisted in state.json it survived restarts too: a live run
    /// against stleary/JSON-java opened with entries naming units of java-dataloader, from a
    /// different run hours earlier against a different repository. The work was real; the record
    /// describing it was not.
    @Column(name = "run_id", length = 64)
    private String runId;

    /// Epoch seconds — `Util.nowSec`.
    @Column(name = "ts")
    private long ts;

    @Column(name = "stage", length = 64)
    private String stage;

    /// Redacted and clipped to 500 characters by {@link StateRepository#appendEvent}, as
    /// `State.event` did. The column is wider than the clip on purpose: a row written by an
    /// older build, or imported from events.jsonl, must not fail to insert.
    @Column(name = "msg", length = 2000)
    private String msg;

    protected EventRow() {} // JPA

    public EventRow(long seq, String runId, long ts, String stage, String msg) {
        this.seq = seq;
        this.runId = runId;
        this.ts = ts;
        this.stage = stage;
        this.msg = msg;
    }

    public long getSeq() { return seq; }

    public String getRunId() { return runId; }

    public long getTs() { return ts; }

    public String getStage() { return stage; }

    public String getMsg() { return msg; }

    @Override
    public String toString() {
        return "EventRow[" + seq + " " + stage + " " + msg + "]";
    }
}
