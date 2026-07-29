package tech.mikhailov.ijt.orchestrator.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/// Package-private: {@link StateRepository} is the only door. See `package-info.java`.
interface PrRepository extends JpaRepository<PrRow, Long> {

    /// Oldest first, and tie-broken by id: `createdAt` is a millisecond stamp and two PRs
    /// prepared in the same millisecond would otherwise swap places between reads.
    List<PrRow> findByRunIdOrderByCreatedAtAscIdAsc(String runId);

    /// Boxed `Long`, not `long` — see {@link EventRepository#deleteByRunId}.
    Long deleteByRunId(String runId);
}
