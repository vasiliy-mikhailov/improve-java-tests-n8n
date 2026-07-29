package tech.mikhailov.ijt.orchestrator.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/// Package-private: {@link StateRepository} is the only door. See `package-info.java`.
///
/// Every query here is scoped by repo slug and none by run — which is what makes the ledgers
/// survive `run/start`. It is a structural property, not a rule anyone has to remember.
interface LedgerRepository extends JpaRepository<LedgerRow, Long> {

    List<LedgerRow> findByKindAndRepoSlug(LedgerRow.Kind kind, String repoSlug);

    Optional<LedgerRow> findByKindAndRepoSlugAndUnitKey(LedgerRow.Kind kind, String repoSlug, String unitKey);

    /// `clearLedger:true` — one kind, one repo. Never all of them: the measurements are still
    /// true when the dispositions are thrown away.
    ///
    /// Boxed `Long` for the reason spelled out on {@link EventRepository#deleteByRunId}: Spring
    /// Data reads a primitive `long` as "not a Number", turns the derived delete into a
    /// single-entity delete, and this method then throws
    /// `IncorrectResultSizeDataAccessException` as soon as a repo has more than one entry.
    Long deleteByKindAndRepoSlug(LedgerRow.Kind kind, String repoSlug);

    /// `POST /api/purge` — every kind, but still one repo. A batch holds several repos' ledgers
    /// side by side.
    ///
    /// Boxed for the same reason as above.
    Long deleteByRepoSlug(String repoSlug);
}
