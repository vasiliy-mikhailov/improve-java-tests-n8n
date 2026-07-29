package tech.mikhailov.ijt;

import java.util.Map;

/// Today's behaviour, behind the port: `/data/state.json` plus `events.jsonl`.
///
/// This is not a stepping stone to be deleted. It is what keeps `BACKEND=java` a real rollback
/// path — `Server` must start and run with no Spring, no JPA and no H2 on the classpath, which
/// it cannot do if the only store is a Spring bean. The rollback stops being a rollback the
/// moment it needs the thing you are rolling back from.
///
/// Every method delegates to the existing statics, so behaviour is unchanged by construction
/// rather than by inspection. The point of this class is the SEAM, not new logic.
public final class FileStateStore implements StateStore {

    /// The process-wide instance.
    ///
    /// A singleton is honest here: `State.STATE` is already process-wide, and pretending
    /// otherwise by handing out fresh instances that all mutate the same static would be worse
    /// than saying so. Tests that want isolation inject a different StateStore rather than
    /// constructing a second one of these.
    public static final FileStateStore INSTANCE = new FileStateStore();

    public FileStateStore() {}

    @Override
    public State.Store state() {
        return State.STATE;
    }

    @Override
    public void event(String stage, String msg) {
        State.event(stage, msg);
    }

    @Override
    public void setStage(String name, String detail) {
        State.setStage(name, detail);
    }

    @Override
    public void setProgress(String line, long elapsedSec) {
        State.setProgress(line, elapsedSec);
    }

    @Override
    public Map<String, Object> upsertFile(String unit, Map<String, Object> patch) {
        return State.upsertFile(unit, patch);
    }

    @Override
    public void addTokens(long prompt, long completion) {
        State.addTokens(prompt, completion);
    }

    @Override
    public void addLlmExchange(Map<String, Object> exchange) {
        State.addLlmExchange(exchange);
    }

    @Override
    public void save() {
        State.save();
    }
}
