package tech.mikhailov.ijt.orchestrator.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/// Package-private, like every repository here: {@link StateRepository} is the only door. See
/// `package-info.java`.
interface RunRepository extends JpaRepository<Run, String> {

    /// Newest first — for diagnostics. The CURRENT run is read from
    /// {@link StoreRow#getCurrentRunId()}, which does not depend on two runs having distinct
    /// timestamps: `force:true` creates the replacement run in the same millisecond as the one
    /// it takes over from.
    List<Run> findAllByOrderByStartedAtDesc();

    List<Run> findByStatus(String status);
}
