package tech.mikhailov.ijt.orchestrator.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import tech.mikhailov.ijt.State;
import tech.mikhailov.ijt.Util;
import tech.mikhailov.ijt.StatePersistence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// The store, in H2 — finally reachable.
///
/// The JPA layer under this has been complete and tested since the migration landed, and
/// connected to nothing: every one of the ~62 call sites goes through the static `State`, so
/// `StateRepository` was an island and `state.json` remained the actual store. Deleting
/// state.json and keeping ijt.mv.db lost the whole run, which is how that was found.
///
/// `StatePersistence` is the seam that fixes it. State's two write paths — the debounced
/// snapshot and the events.jsonl append — now go through an interface, and this is the H2
/// implementation of it. Nothing in `Repo`, `Pit`, `Rules`, `Llm` or `Measure` had to change.
///
/// ## Why the snapshot is written whole
///
/// `writeSnapshot` rewrites the run, its units, its PRs and both ledgers on every flush, which
/// looks wasteful next to a row-level update. It is what the caller can honestly promise: State
/// hands over a mutable Store that any thread may have touched since the last call, and there
/// is no change log to diff against. The debounce is what keeps the cost sane — the same
/// debounce that made a 470 KB file rewrite survivable, now moving far less data.
///
/// Events are the exception and are appended one at a time, because that is the one mutation
/// the interface reports individually.
@Component
public class H2StatePersistence implements StatePersistence {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final StateRepository store;

    /// The file writer, still running underneath.
    ///
    /// DUAL-WRITE, and temporary. Installing this persistence REPLACES the file writer, so
    /// state.json stops being written at all — and the H2 event log is currently lossy: a probe
    /// wrote four events and one row landed, with nothing logged either side. Until that is
    /// understood, dropping the file would trade a store that works for one that silently
    /// loses log entries.
    ///
    /// The cost is one extra write per flush of data that is already being serialised. Remove
    /// this the moment the event gap is closed and the decisive test passes on events too.
    private final tech.mikhailov.ijt.State.FilePersistence file = new tech.mikhailov.ijt.State.FilePersistence();

    public H2StatePersistence(StateRepository store) {
        this.store = store;
    }

    @Override
    public void writeSnapshot(State.Store s) {
        file.writeSnapshot(s);
        try {
            String runId = s.run == null ? null : s.run.id;
            if (runId == null) return;   // nothing to key rows against yet

            store.saveRun(toRun(s));
            // Without this the row exists and currentRun() still returns empty, because it
            // resolves through IJT_STORE.current_run_id rather than guessing from status.
            store.setCurrentRun(runId);
            for (Map.Entry<String, Map<String, Object>> e : s.files.entrySet()) {
                store.upsertUnit(runId, e.getKey(), e.getValue());
            }
            for (Object pr : s.prs) {
                store.addPr(prRow(runId, asMap(pr)));
            }
            // The ledgers are per-REPO and outlive the run: that is what stops a restart
            // re-improving files that already have open PRs.
            writeLedger(s.improvedLedger, store::putImproved);
            writeLedger(s.measureLedger, store::putMeasured);
            if (s.llmBudget != null) store.saveLlmBudget(asMap(s.llmBudget));
            store.bumpSeqTo(s.seq);
        } catch (Exception e) {
            // Never throw into the caller. A persistence failure has to degrade to a lost
            // write, not a failed run — the file implementation has always worked that way,
            // and a six-hour PIT measurement must not die because a row would not insert.
            System.err.println("H2 snapshot failed: " + e);
        }
    }

    @Override
    public void appendEvent(State.Event event) {
        file.appendEvent(event);
        try {
            String runId = State.STATE.run == null ? null : State.STATE.run.id;
            if (runId == null) return;
            store.appendEvent(runId, event.stage(), event.msg());
            // State assigned the seq before we got here, and /api/events?after= pages on it.
            // Keeping the table's counter at least that high stops a restart handing out a
            // seq a connected client has already seen.
            store.bumpSeqTo(event.seq());
        } catch (Exception e) {
            System.err.println("H2 event append failed: " + e);
        }
    }

    @Override
    public boolean load(State.Store into) {
        try {
            if (store.isEmpty()) return false;   // caller falls back to state.json, once
            // While dual-writing, the file is still authoritative for events — H2 loses some.
            // Returning false here would also discard the run, so the run IS restored from H2
            // below and only the event log is topped up from the file by the caller.
            var run = store.currentRun().orElse(null);
            if (run == null) return false;

            into.run = fromRow(run);
            into.files = new LinkedHashMap<>(store.unitsAsMap(run.getId()));
            into.prs = new ArrayList<>(store.prsAsMaps(run.getId()));
            into.seq = store.lastSeq();
            into.events = new ArrayList<>();
            for (EventRow r : store.eventsAfter(run.getId(), 0)) {
                into.events.add(new State.Event(r.getSeq(), r.getTs(), r.getStage(), r.getMsg()));
            }
            into.llmBudget = store.llmBudget();

            // The ledgers, which are the whole reason persistence matters. They are keyed by
            // repo SLUG and outlive the run: improvedLedger is what stops a restart
            // re-improving files that already have open PRs, and measureLedger is what saves
            // re-measuring every unit from zero. Restoring the run without them is worse than
            // restoring nothing — the pipeline comes back believing it has never seen the repo.
            String slug = Util.slugify(into.run.config == null ? "" : into.run.config.repoUrl());
            if (slug != null && !slug.isEmpty()) {
                Map<String, Map<String, Object>> improved = store.improvedLedger(slug);
                if (improved != null && !improved.isEmpty()) {
                    into.improvedLedger.put(slug, new LinkedHashMap<>(improved));
                }
                Map<String, Map<String, Object>> measured = store.measureLedger(slug);
                if (measured != null && !measured.isEmpty()) {
                    into.measureLedger.put(slug, new LinkedHashMap<>(measured));
                }
            }
            return true;
        } catch (Exception e) {
            // A failed restore must read as "nothing stored" so the caller falls back to the
            // file, rather than starting a run against a half-populated store.
            System.err.println("H2 load failed, falling back: " + e);
            return false;
        }
    }

    /// The row back into State's Run.
    ///
    /// This was a placeholder assigning State.STATE.run — i.e. whatever was already in memory,
    /// which on a fresh boot is null. H2 held the row the whole time and load() handed back an
    /// empty store, which looked exactly like "H2 stores nothing" and cost a round of
    /// diagnosis. Written out now.
    private static State.Run fromRow(Run row) {
        // Through Jackson rather than field by field. `config` is a record with over a hundred
        // components and the row already stores it whole in config_json — hand-mapping it would
        // be a hundred lines that silently drop whichever one someone adds next.
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.getId());
        m.put("status", row.getStatus());
        m.put("startedAt", row.getStartedAt());
        m.put("finishedAt", row.getFinishedAt());
        m.put("config", row.getConfig());
        return MAPPER.convertValue(m, State.Run.class);
    }

    /// Map State's Run onto the entity by hand.
    ///
    /// Field by field rather than through Jackson, because the two shapes are NOT the same and
    /// pretending otherwise is how a rename silently drops a column: State.Run carries a nested
    /// `config`, the row flattens the parts it indexes on. Adding a field means adding it here,
    /// which is a nuisance the compiler will remind you about.
    private Run toRun(State.Store s) {
        Run r = store.run(s.run.id).orElseGet(Run::new);
        Map<String, Object> m = asMap(MAPPER.convertValue(s.run, MAP));
        Map<String, Object> cfg = asMap(m.getOrDefault("config", Map.of()));
        r.setId(String.valueOf(m.get("id")));
        r.setRepoUrl(str(cfg.get("repoUrl")));
        r.setRepoBranch(str(cfg.get("repoBranch")));
        r.setStatus(str(m.get("status")));
        // the whole config, not the two fields the row indexes on — see fromRow
        r.setConfig(cfg);
        if (m.get("startedAt") instanceof Number n) r.setStartedAt(n.longValue());
        if (m.get("finishedAt") instanceof Number n) r.setFinishedAt(n.longValue());
        return r;
    }

    @SuppressWarnings("unchecked")
    private static PrRow prRow(String runId, Map<String, Object> m) {
        Object labels = m.get("labels");
        long created = m.get("createdAt") instanceof Number n ? n.longValue() : System.currentTimeMillis();
        return new PrRow(runId, str(m.get("file")), str(m.get("branch")), str(m.get("title")),
                str(m.get("body")), labels instanceof List ? (List<String>) labels : List.of(),
                str(m.get("mode")), str(m.get("url")), str(m.get("patchPath")), created);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private void writeLedger(Map<String, Map<String, Map<String, Object>>> ledger,
                             TriConsumer put) {
        if (ledger == null) return;
        for (var repo : ledger.entrySet()) {
            for (var unit : repo.getValue().entrySet()) {
                put.accept(repo.getKey(), unit.getKey(), unit.getValue());
            }
        }
    }

    @FunctionalInterface
    private interface TriConsumer {
        void accept(String repoSlug, String unitKey, Map<String, Object> record);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : MAPPER.convertValue(o, MAP);
    }
}
