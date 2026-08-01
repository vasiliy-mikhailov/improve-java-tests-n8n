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
            // Per ROW, not per snapshot. One rejected unit used to abort the whole flush —
            // every unit after it, every PR, both ledgers and the budget, on every flush for the
            // life of the run — and the only trace was a single line on stderr. A record the
            // store cannot accept is one lost row; it is not grounds for losing 42 PRs.
            for (Map.Entry<String, Map<String, Object>> e : s.files.entrySet()) {
                guarded("unit " + e.getKey(), () -> store.upsertUnit(runId, e.getKey(), e.getValue()));
            }
            for (Object pr : s.prs) {
                guarded("pr", () -> store.addPr(prRow(runId, asMap(pr))));
            }
            // The ledgers are per-REPO and outlive the run: that is what stops a restart
            // re-improving files that already have open PRs.
            guarded("improvedLedger", () -> writeLedger(s.improvedLedger, store::putImproved));
            guarded("measureLedger", () -> writeLedger(s.measureLedger, store::putMeasured));
            if (s.llmBudget != null) guarded("llmBudget", () -> store.saveLlmBudget(asMap(s.llmBudget)));
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

            // A THINNER STORE MUST NOT WIN. While the dual-write is on, state.json is the
            // complete copy and this is the mirror, so a restore that would hand back fewer
            // units than the file already holds is not a restore — it is a deletion with extra
            // steps, and it happened: 26 rows replaced 319 units and 42 PRs in memory, and the
            // next flush would have written that over the file. Fall back and let State.load()
            // read the file, exactly as it does on a fresh deployment.
            long inDb = store.unitCount(run.getId());
            long inFile = unitsInStateFile();
            if (inFile > inDb) {
                System.err.println("H2 holds " + inDb + " units against state.json's " + inFile
                        + " — falling back to the file, which is authoritative while both are written");
                return false;
            }

            into.run = fromRow(run);
            // THE MIGRATION State.load() DOES, which this path reimplemented without. A run
            // restored as `running` has no process behind it — that is what a restore means —
            // and Server's start guard reads this exact stage name to tell a genuine
            // concurrency conflict from a corpse. Without it the run is both unstartable (409,
            // "a run is already active") and unstoppable (nothing to stop) until the 900s
            // staleness window expires.
            if (into.run != null && "running".equals(into.run.status)) {
                into.stage = new State.Stage("interrupted", "orchestrator restarted", Util.nowSec(), null);
                // AND THE STATUS, which is the field every consumer actually reads. This line is
                // where the observed lie came from: the stage above is SYNTHESISED here, in
                // memory, out of `status == "running"` — it is not loaded from the row and never
                // was. `IJT_RUN.STAGE_NAME` is NULL on every row this deployment has written,
                // because the four StateRepository methods that would have written it have no
                // production caller. So the stage was never a second, independent signal; it was
                // this field wearing a different name, while this field went on saying `running`.
                into.run.status = "interrupted";
            }
            // The store-level state that sits beside the run — see toRun. `runner` first,
            // because without it the resumed run has no build and every unit it touches fails.
            into.runner = run.getRunner();
            if (run.getDecisions() != null) into.decisions = new LinkedHashMap<>(run.getDecisions());
            into.mutatorStats = run.getMutatorStats();
            into.pickFailures = run.getPickFailures();
            if (run.getExtra() != null && run.getExtra().get("overheadLedger") instanceof Map<?, ?> o) {
                into.overheadLedger = new LinkedHashMap<>(asMap(o));
            }
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

    @Override
    public void clearImprovedLedger(String repoSlug) {
        file.clearImprovedLedger(repoSlug);
        try {
            long gone = store.clearImproved(repoSlug);
            System.err.println("cleared " + gone + " improved-ledger row(s) for " + repoSlug);
        } catch (Exception e) {
            // Loud, unlike the other guards here. A clear that silently fails is worse than a
            // lost row: the caller believes the repo will be redone from scratch, and the next
            // restart quietly proves otherwise.
            System.err.println("H2 improved-ledger clear FAILED for " + repoSlug + ": " + e);
        }
    }

    /// How many units the file on disk holds, or 0 if there is no readable file.
    ///
    /// Counted rather than trusted: the comparison above is only worth making against a number
    /// that came from the file itself. An unreadable or absent file answers 0, which lets the
    /// database win — the fresh-deployment case, where there is nothing to lose.
    private static long unitsInStateFile() {
        try {
            java.nio.file.Path f = State.stateFile();
            if (!java.nio.file.Files.exists(f)) return 0;
            Map<String, Object> raw = MAPPER.readValue(java.nio.file.Files.readString(f), MAP);
            return raw.get("files") instanceof Map<?, ?> m ? m.size() : 0;
        } catch (Exception e) {
            return 0;
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

        // THE STORE-LEVEL STATE, which lives beside the run rather than inside it. These columns
        // have existed since the migration landed, with getters and setters, and nothing ever
        // wrote them.
        //
        // `runner` is the one that cost a run. It holds {tool, wrapper, jdk, testFramework} and
        // both Pit and Coverage refuse to do anything without it — "build not detected — call
        // /api/repo/prepare first". A resumed run skips setup, so losing it left the pipeline
        // convinced it had no build: job execution 41 picked nine units, failed PIT on every one
        // inside two seconds, marked them all `failed` — XML#parse among them — and declared
        // itself done with 0 PRs and 70 candidates still on the list.
        r.setRunner(s.runner);
        r.setDecisions(s.decisions);
        r.setMutatorStats(s.mutatorStats);
        r.setPickFailures(s.pickFailures);
        // No column of its own. It is per-repo like the other two ledgers, but unlike them it is
        // one shallow map rather than a table's worth of rows, so it rides in extra_json instead
        // of earning a schema change.
        Map<String, Object> extra = r.getExtra() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(r.getExtra());
        extra.put("overheadLedger", s.overheadLedger);
        r.setExtra(extra);
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

    /// Run one piece of a snapshot, and let the rest of the snapshot happen regardless.
    ///
    /// Loud, and counted: a store quietly dropping rows is how this one stayed write-dead for
    /// the life of three runs while every request it served looked fine. The count is what makes
    /// the boot-time comparison in BackendBootstrap meaningful rather than a coincidence.
    private void guarded(String what, Runnable piece) {
        try {
            piece.run();
        } catch (Exception e) {
            dropped++;
            System.err.println("H2 snapshot dropped " + what + ": " + e);
        }
    }

    private volatile long dropped;

    /// How many rows this process has failed to write. Read by the boot-time reconciliation.
    public long droppedRows() {
        return dropped;
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
