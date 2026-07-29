package tech.mikhailov.ijt.orchestrator.store;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/// Package-private: {@link StateRepository} is the only door. See `package-info.java`.
interface EventRepository extends JpaRepository<EventRow, Long> {

    /// The reconnect query: everything after the last seq the client holds, for one run.
    ///
    /// Run-scoped because the event log belongs to a run, and a client must never be handed the
    /// previous run's lines. The seq filter alone would not stop that after a restart, where the
    /// table holds rows from several runs.
    List<EventRow> findByRunIdAndSeqGreaterThanOrderBySeqAsc(String runId, long seq);

    /// The unscoped form, for the window before a run exists.
    List<EventRow> findBySeqGreaterThanOrderBySeqAsc(long seq);

    /// Boxed `Long`, and it is not a style choice — a primitive here is a live defect.
    ///
    /// Spring Data JPA decides what a derived delete RETURNS with
    /// `Number.class.isAssignableFrom(queryMethod.getReturnType())`, and `Class.isAssignableFrom`
    /// does not box: for `long` that is false, so the method stops being a count and becomes a
    /// single-entity delete. Deleting no rows then returns null (`AopInvocationException: Null
    /// return value from advice does not match primitive return type`), deleting one returns the
    /// entity, and deleting several throws `IncorrectResultSizeDataAccessException` — the
    /// clear-the-log-at-run-start path failing precisely when it had work to do. `Long` is what
    /// the Spring Data reference itself uses for this.
    Long deleteByRunId(String runId);

    /// Only ever used to SEED the high-water mark on a database that has rows but no
    /// {@link StoreRow} — one written by an earlier build, or restored from a backup. NEVER to
    /// advance it: rows get deleted at run start, and the mark must not follow them down.
    @Query("select max(e.seq) from EventRow e")
    Long maxSeq();
}
