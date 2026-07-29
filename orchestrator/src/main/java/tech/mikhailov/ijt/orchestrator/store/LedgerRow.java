package tech.mikhailov.ijt.orchestrator.store;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.Map;

/// A ledger entry: what a PREVIOUS run established about a unit of a repo.
///
/// One table for the three per-repo ledgers, discriminated by {@link Kind}. They are the only
/// rows in this store that are scoped by repo rather than by run, and that is the whole point —
/// THE LEDGERS SURVIVE `run/start`. Everything else about a run is reset; these are what make
/// batched full-repo runs and crash-restart possible:
///
///   - `improvedLedger` (143 entries on the live deployment) is what stopped run 2 redoing
///     run 1's work. Without it the pipeline re-improves files that already have open PRs.
///   - `measureLedger` is why a full run starts in seconds instead of re-measuring 319 units
///     for 40 minutes. That cost is paid once and reused — unless it evaporates.
///
/// They must also survive the PROCESS, which is why the datasource is file-backed H2 under
/// DATA_DIR and not `jdbc:h2:mem`. Surviving `run/start` but not a restart would fix half the
/// problem and hide the other half.
@Entity
@Table(name = "ijt_ledger",
        uniqueConstraints = @UniqueConstraint(name = "uq_ledger_kind_repo_unit",
                columnNames = {"kind", "repo_slug", "unit_key"}),
        indexes = @Index(name = "ix_ledger_kind_repo", columnList = "kind, repo_slug"))
public class LedgerRow {

    public enum Kind {
        /// Final dispositions: `improved` | `exhausted` | `failed` | `no_mutants`.
        IMPROVED,
        /// Every measurement ever taken, improved or not. Kept apart from IMPROVED because these
        /// entries say nothing about whether a unit is SETTLED — they exist so the numbers
        /// survive batches.
        MEASURE,
        /// Per-repo cumulative clone/install/baseline seconds, so the FTE ratio counts run
        /// overhead and not just per-unit work. Repo-scoped, so it has no unit key.
        OVERHEAD
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /// STRING, never ORDINAL. An ordinal is a promise that nobody ever inserts a constant into
    /// the middle of that enum, and the cost of breaking it is that every existing row silently
    /// changes meaning — improved units reading as overhead totals.
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 16, nullable = false)
    private Kind kind;

    /// `Util.slugify(repoUrl)` — the same key the three JS ledgers were keyed by.
    @Column(name = "repo_slug", length = 256, nullable = false)
    private String repoSlug;

    /// The unit key, and the EMPTY STRING for repo-scoped kinds (OVERHEAD).
    ///
    /// Not null: SQL uniqueness treats NULLs as distinct, so a nullable column here would let
    /// the unique constraint above admit a second overhead row for the same repo — and the
    /// second one would be found first as often as not.
    @Column(name = "unit_key", length = 512, nullable = false)
    private String unitKey;

    /// The entry exactly as its writer wrote it: `{state, ts, metrics, prUrl?, patchPath?,
    /// branch?}` for IMPROVED, the flat measurement map for MEASURE.
    ///
    /// Verbatim, and reconstituted verbatim. The keys differ per writer — only the `improved`
    /// record carries `prUrl`, `patchPath` and `branch`, and it carries them even when they are
    /// null, because local mode has no URL and the dashboard tells the two apart by which key is
    /// present. Reassembling that shape from columns would be a second implementation of a
    /// contract that already has one.
    @Column(name = "payload_json", length = 1_000_000, nullable = false)
    @Convert(converter = Json.MapConverter.class)
    private Map<String, Object> payload;

    // ── projections of the payload, for querying ──────────────────────────────
    // Written from the payload, never read back into it. They exist so a caller can ask "what
    // is settled for this repo" without deserialising every entry.

    /// IMPROVED only: `improved` | `exhausted` | `failed` | `no_mutants`.
    @Column(name = "disposition", length = 32)
    private String disposition;

    /// MEASURE only: `Measure.MEASURE_VERSION` as it stood when the numbers were taken. A later
    /// change to what a number MEANS invalidates the entry instead of silently replaying it into
    /// a new run. Boxed, because an entry written before the stamp existed has no version — and
    /// "version 0" would be a claim about semantics nobody made.
    @Column(name = "measure_version")
    private Integer measureVersion;

    /// Milliseconds — `Date.now()`, matching what the JS ledgers stamped.
    @Column(name = "ts")
    private long ts;

    protected LedgerRow() {} // JPA

    public LedgerRow(Kind kind, String repoSlug, String unitKey, Map<String, Object> payload) {
        this.kind = kind;
        this.repoSlug = repoSlug;
        this.unitKey = unitKey == null ? "" : unitKey;
        applyPayload(payload);
    }

    /// Replace the entry and re-derive the projections.
    ///
    /// NOT final, and the constructor does not call it. Hibernate rejects a final setter on an
    /// entity outright — `Setter methods of lazy classes cannot be final` — and the app then
    /// starts but fails at the first request touching this entity. The `final` was there for a
    /// real reason (a constructor must not call an overridable method), so the body moved to
    /// applyPayload and both callers use that instead of one constraint defeating the other.
    public void setPayload(Map<String, Object> payload) {
        applyPayload(payload);
    }

    /// The actual work: private, so the constructor can call it safely, and invisible to JPA.
    private void applyPayload(Map<String, Object> payload) {
        this.payload = payload;
        Object state = payload == null ? null : payload.get("state");
        this.disposition = state == null ? null : String.valueOf(state);
        Object v = payload == null ? null : payload.get("v");
        this.measureVersion = v instanceof Number n ? n.intValue() : null;
        Object stamp = payload == null ? null : payload.get("ts");
        this.ts = stamp instanceof Number n ? n.longValue() : System.currentTimeMillis();
    }

    public Long getId() { return id; }

    public Kind getKind() { return kind; }

    public String getRepoSlug() { return repoSlug; }

    public String getUnitKey() { return unitKey; }

    public Map<String, Object> getPayload() { return payload; }

    public String getDisposition() { return disposition; }

    public Integer getMeasureVersion() { return measureVersion; }

    public long getTs() { return ts; }

    @Override
    public String toString() {
        return "LedgerRow[" + kind + " " + repoSlug + " " + unitKey + " " + disposition + "]";
    }
}
