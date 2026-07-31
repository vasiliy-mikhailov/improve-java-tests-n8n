package tech.mikhailov.ijt;

/// Where the store's bytes actually go.
///
/// ## Why this is separate from StateStore
///
/// `StateStore` is the seam for DEPENDENCY INJECTION — who a caller asks for state. This is the
/// seam for STORAGE — where that state is durable. They look similar and solve different
/// problems, and conflating them is what made the H2 work stall.
///
/// The orchestrator shipped a full JPA layer — entities, repositories, `StateRepository`, all
/// tested — that nothing could reach, because every one of the ~62 call sites goes through the
/// static `State`. Wiring it the DI way means converting five stateful classes first, and until
/// that lands H2 stores nothing. Meanwhile `state.json` is still a 470 KB document rewritten in
/// full on every flush.
///
/// So storage moves first, through the one choke point that already exists: `writeStateFile()`
/// for the snapshot and the `events.jsonl` append in `event()`. Swap what those two call and
/// H2 becomes the real store without touching Repo, Pit, Rules, Llm or Measure. The DI
/// conversion then proceeds on its own schedule, for the reason it was actually wanted —
/// isolating unit and model tests from a shared mutable singleton.
///
/// ## Contract
///
/// Implementations must tolerate being called from several threads and from the shutdown hook,
/// and must never throw into the caller: a persistence failure has to degrade to a lost write,
/// never to a failed run. The file implementation already works that way — a state file that
/// cannot be written must not take a six-hour PIT measurement down with it.
public interface StatePersistence {

    /// Persist the whole store. Called on a debounce, not per mutation.
    ///
    /// The debounce is not an optimisation to preserve blindly — it exists because a full
    /// rewrite costs 470 KB. An implementation with cheap incremental writes may ignore the
    /// hint and write eagerly.
    void writeSnapshot(State.Store store);

    /// Append one event.
    ///
    /// Separate from the snapshot because it IS separate today: events.jsonl is append-only and
    /// grew to 4.8 MB on the live box, while the in-memory log is capped at 400 entries so the
    /// snapshot stays a manageable size. A store with real rows has no reason to keep that cap,
    /// but it must keep the append cheap — this is called every few seconds during a build.
    void appendEvent(State.Event event);

    /// Restore the store at boot, or return false when there is nothing to restore.
    ///
    /// Returning false matters: it is what lets a fresh H2 database fall back to reading an
    /// existing state.json ONCE. A deployment carries 319 units, 400 events and two ledger
    /// repos, and losing the improvedLedger re-improves files that already have open PRs.
    boolean load(State.Store into);

    /// Forget one repo's improved-unit ledger, durably.
    ///
    /// `clearLedger:true` used to be `state().improvedLedger.remove(slug)` and nothing else — an
    /// in-memory removal. The run that asked for it did start clean, and the rows stayed in the
    /// store: `putImproved` upserts and nothing ever deleted. So the next restart restored every
    /// cleared entry and the run silently went back to skipping units it had been told to redo.
    ///
    /// Measured: a from-scratch run cleared 320 entries, converted 23 units, was restarted for an
    /// unrelated deploy, and came back with 320 entries and 108 improved — the old ledger, plus
    /// the new run's work merged into it. Nothing reported anything.
    ///
    /// `StateRepository.clearImproved` had existed the whole time, correct and unreachable. This
    /// is the seam that reaches it.
    ///
    /// Default is a no-op because the file implementation needs none: state.json is rewritten
    /// whole from memory on the next flush, so an in-memory removal is already durable there.
    default void clearImprovedLedger(String repoSlug) { }
}
