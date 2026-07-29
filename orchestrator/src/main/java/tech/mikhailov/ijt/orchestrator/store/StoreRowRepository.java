package tech.mikhailov.ijt.orchestrator.store;

import org.springframework.data.jpa.repository.JpaRepository;

/// Package-private: {@link StateRepository} is the only door. See `package-info.java`.
///
/// One row, id 1. There are no finders because there is nothing to find.
interface StoreRowRepository extends JpaRepository<StoreRow, Integer> {
}
