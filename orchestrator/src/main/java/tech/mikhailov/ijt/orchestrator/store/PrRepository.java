package tech.mikhailov.ijt.orchestrator.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/// Package-private: {@link StateRepository} is the only door. See `package-info.java`.
interface PrRepository extends JpaRepository<PrRow, Long> {

    /// Oldest first, and tie-broken by id: `createdAt` is a millisecond stamp and two PRs
    /// prepared in the same millisecond would otherwise swap places between reads.
    List<PrRow> findByRunIdOrderByCreatedAtAscIdAsc(String runId);

    /// The row a re-flushed PR should UPDATE rather than duplicate.
    ///
    /// A snapshot restates every PR of the run on every flush, and the id is an IDENTITY
    /// surrogate, so a blind save() inserts each time: one real PR had become 958 rows inside an
    /// hour. `(run, unit)` is the natural key — a PR is prepared for one unit and later gains its
    /// url, which must edit that row.
    List<PrRow> findByRunIdAndUnitKey(String runId, String unitKey);

    /// The same, for a PR that carries no unit key: the branch is what remains to identify it.
    List<PrRow> findByRunIdAndBranch(String runId, String branch);

    /// Boxed `Long`, not `long` — see {@link EventRepository#deleteByRunId}.
    Long deleteByRunId(String runId);
}
