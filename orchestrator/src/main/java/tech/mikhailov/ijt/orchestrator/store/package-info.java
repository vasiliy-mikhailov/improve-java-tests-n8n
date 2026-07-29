/// The persistence layer — H2, and the one facade in front of it.
///
/// It replaces `/data/state.json`. `State` is still the shape being spoken: the same units keyed
/// by `path::method`, the same append-only event log, the same per-repo ledgers, the same
/// records-as-open-maps where the JS had no faithful Java type.
///
/// ONE DOOR. Everything outside this package goes through {@link
/// tech.mikhailov.ijt.orchestrator.store.StateRepository}. The design requires that moving
/// between `jdbc:h2:mem:` and `jdbc:h2:file:/data/ijt` — or, one day, off H2 entirely — stay a
/// URL change rather than a refactor, and that only holds while nothing else holds an
/// `EntityManager` or a Spring Data repository. So the six repository interfaces here are
/// PACKAGE-PRIVATE: a tasklet in a sibling package cannot inject one even by accident, and the
/// rule is enforced by the compiler instead of by review.
///
/// WHY ON DISK, since it is the decision most likely to be quietly reversed. Three things would
/// be lost on every container restart, and all three are correctness rather than performance:
/// the `improvedLedger` that stops a run redoing the last one's work; the `measureLedger` that
/// is why a full run starts in seconds instead of re-measuring 319 units for 40 minutes; and the
/// state that let a hung run be marked `interrupted` and taken over rather than pretended idle.
///
/// The four rules that cost real runs, and where they live:
///
///   1. `seq` only ever rises, across runs and across the delete at run start —
///      {@link tech.mikhailov.ijt.orchestrator.store.StoreRow#getLastSeq()}.
///   2. Clearing the event log deletes ONE RUN's rows, never the table —
///      {@link tech.mikhailov.ijt.orchestrator.store.StateRepository#clearEventsForRun}.
///   3. The ledgers survive `run/start` —
///      {@link tech.mikhailov.ijt.orchestrator.store.LedgerRow}.
///   4. Unmeasured is NULL, never 0 —
///      {@link tech.mikhailov.ijt.orchestrator.store.Unit}.
package tech.mikhailov.ijt.orchestrator.store;
