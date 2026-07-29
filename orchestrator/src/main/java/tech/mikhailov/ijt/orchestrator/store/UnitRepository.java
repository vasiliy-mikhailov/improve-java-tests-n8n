package tech.mikhailov.ijt.orchestrator.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/// Package-private: {@link StateRepository} is the only door. See `package-info.java`.
interface UnitRepository extends JpaRepository<Unit, Long> {

    Optional<Unit> findByRunIdAndUnitKey(String runId, String unitKey);

    /// Ordered by key.
    ///
    /// The JS store was a LinkedHashMap and the dashboard's list inherited its insertion order.
    /// SQL owes nobody an insertion order, and the rows now outlive the process that inserted
    /// them, so the key is the stable substitute: at least it is the same order on every restart.
    List<Unit> findByRunIdOrderByUnitKeyAsc(String runId);

    long countByRunId(String runId);

    /// A derived delete, which loads the rows before removing them rather than issuing a bulk
    /// `delete … where`. Deliberate: a bulk delete has to be paired with clearing the persistence
    /// context, and clearing it mid-transaction detaches whatever the caller was holding. The
    /// volume is one run's units, once.
    ///
    /// Boxed `Long`, not `long` — see {@link EventRepository#deleteByRunId}. A primitive makes
    /// Spring Data treat this as a single-entity delete, and a run with two or more units then
    /// fails with `IncorrectResultSizeDataAccessException`.
    Long deleteByRunId(String runId);
}
