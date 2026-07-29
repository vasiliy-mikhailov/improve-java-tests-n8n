package tech.mikhailov.ijt;

import java.util.Map;

/// Where a run's state lives — injected, not reached for.
///
/// ## Why this exists
///
/// Two problems with one shape.
///
/// The first is storage. `/data/state.json` is a 470 KB document rewritten in full on every
/// event, under one monitor, with a 400-entry ring buffer on the event log because that is what
/// you do when every append rewrites the whole file. H2 removes all three, and the Spring
/// orchestrator has the entities and repositories for it — but nothing could USE them while the
/// only way to reach state was the static `State.STATE`. The store was built, tested, and
/// connected to nothing.
///
/// The second is testing, and it is the reason to do this properly rather than bolt an H2
/// writer onto the static store. A unit test today resets `State.STATE` by hand between cases
/// (see the `@BeforeEach` in ApiControllerTest), which is a shared-mutable-singleton smell that
/// makes parallel execution unsafe and model tests — the ones that call a real LLM — awkward to
/// isolate. An injected store is swapped per test instead of reset.
///
/// ## What is deliberately NOT here
///
/// The pure helpers that happen to live on `State` — `jsParseInt`, `clip`, `asLong`,
/// `jsTruthy`, `envOr`, `asDouble` — are 14 of the ~62 static references and are just
/// functions. They take no state and need no injection; making them instance methods would add
/// a constructor argument to call a parser.
///
/// The 27 pure-logic classes need nothing either. They have private constructors and no state
/// dependency, which is exactly why 902 tests already pass without any of this.
///
/// ## Implementations
///
/// `FileStateStore` wraps today's behaviour and keeps `BACKEND=java` working standalone with no
/// Spring on the classpath — the rollback path must not require the orchestrator.
/// The orchestrator supplies an H2-backed one as a bean.
public interface StateStore {

    /// The whole store. Returned rather than proxied per-field because callers legitimately
    /// read several fields under one lock, and a getter per field would invite exactly the
    /// half-updated reads the monitor exists to prevent.
    State.Store state();

    /// Append one line to the run's event log.
    ///
    /// The single most-used method here — 25 of the call sites — and the one the dashboard
    /// reads. `seq` must keep rising across runs: a reconnecting client asks for everything
    /// after the last seq it holds, so rewinding it silently drops whatever fell in the gap.
    void event(String stage, String msg);

    /// Current stage and a human detail, e.g. `improving_mutation` / `mutation testing Foo#bar`.
    void setStage(String name, String detail);

    /// Live progress within a stage — the last output line of a subprocess and its elapsed
    /// seconds. Written every few seconds while a build runs, so an implementation must treat
    /// this as cheap and must NOT force a full persist per call.
    void setProgress(String line, long elapsedSec);

    /// Merge a patch into one unit's record, keyed `path::method`, and return the merged record.
    ///
    /// The return value is not decoration: callers read the merged result straight back — the
    /// pre-merge patch is not the same thing, since a patch carries only the fields it changes.
    /// Declaring this `void` compiled perfectly well and would have quietly forced every caller
    /// to re-read the store to see what it had just written.
    Map<String, Object> upsertFile(String unit, Map<String, Object> patch);

    /// Book LLM token usage against the run.
    void addTokens(long prompt, long completion);

    /// Record one model exchange for the dashboard's live dialog.
    void addLlmExchange(Map<String, Object> exchange);

    /// Persist. A no-op for stores that write through.
    ///
    /// Kept in the interface rather than hidden because the file implementation genuinely needs
    /// it and callers already know where the safe points are — removing it would mean either
    /// persisting on every mutation (470 KB per event) or guessing.
    void save();
}
