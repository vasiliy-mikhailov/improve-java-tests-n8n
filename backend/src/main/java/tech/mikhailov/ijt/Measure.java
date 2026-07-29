package tech.mikhailov.ijt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Measurements persist across runs so a repo is not re-measured from scratch every time.
/// That is only safe while the numbers still mean what they meant when they were written.
///
/// They have twice stopped meaning it. The unit of work moved from a FILE to a
/// `path::method`, so old entries describe a whole class. And PIT reports were only scoped
/// to the target method later, so before that a method's "mutation score" could include
/// another method's mutants — JSONObject#isRecordStyleAccessor was recorded at 15.79% with
/// 3 kills that all belonged to toString(); scoped properly, the same method measures 0%.
/// Replaying either kind hands the run a baseline that no longer describes anything.
///
/// So every measurement carries the version of the semantics that produced it, and this
/// number goes up whenever those semantics change.
///
///     1 — per-file coverage/mutation
///     2 — per-method units (`path::method`)
///     3 — PIT reports scoped to the unit's own method before scoring
public final class Measure {

    private Measure() {}

    /// The semantics a measurement was produced under. See the class comment: this goes up
    /// whenever coverage/mutation/MAC stop meaning what they used to.
    public static final int MEASURE_VERSION = 3;

    /// Entities leak in from JaCoCo/PIT XML, where a constructor is `&lt;init&gt;`.
    private static final Map<String, String> ENTITIES = Map.of(
            "&lt;", "<",
            "&gt;", ">",
            "&amp;", "&",
            "&quot;", "\"",
            "&apos;", "'");

    private static final Pattern ENTITY = Pattern.compile("&(lt|gt|amp|quot|apos);");

    /// One canonical spelling per unit. The live ledger holds both
    /// `Property.java::&lt;init&gt;` and `Property.java::<init>` for a single constructor, so a
    /// measurement written under one is invisible under the other.
    ///
    /// One pass, exactly as the JS does: an `&amp;lt;` unescapes to `&lt;` and stops there
    /// rather than being decoded twice.
    public static String normalizeUnitKey(String key) {
        String k = key == null ? "" : key;
        return ENTITY.matcher(k).replaceAll(m -> Matcher.quoteReplacement(ENTITIES.get(m.group())));
    }

    /// Tag a measurement with the semantics that produced it.
    ///
    /// A copy, never the caller's map, and insertion-ordered so a stamped measurement
    /// serialises in the order it was written. `v` is written last and so wins over any `v`
    /// already in the patch.
    public static Map<String, Object> stamp(Map<String, Object> patch) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (patch != null) out.putAll(patch);
        out.put("v", MEASURE_VERSION);
        return out;
    }

    /// May this stored measurement be replayed into a fresh run, ignoring what it is keyed
    /// under? The two-argument form is the one nearly every caller wants.
    public static boolean restorable(Map<String, Object> entry) {
        // strictly the number 3: an entry written before versioning existed has no `v` at
        // all, and a `v` that is not a number is not this stamp
        return entry != null
                && entry.get("v") instanceof Number n
                && n.doubleValue() == MEASURE_VERSION;
    }

    /// May this stored measurement be replayed into a fresh run?
    ///
    /// @param key the unit key it is stored under. A null key fails the check, matching the
    ///            JS, where only a genuinely absent argument skips it — use
    ///            {@link #restorable(Map)} for that.
    public static boolean restorable(Map<String, Object> entry, String key) {
        if (!restorable(entry)) return false;
        // the current unit model is `path::method`; a bare file key predates it
        return key != null && key.contains("::");
    }

    /// The effort accounting inside a metrics object: what the work COST, not what it measured.
    ///
    /// Only the fields that are actually present — a missing timesheet must read as absent, not
    /// as zero hours, or the headline understates the work instead of admitting it does not
    /// know. Hence nullable fields and {@link #toMap()}, which omits what is absent: the caller
    /// spreads this over a unit's state, and writing an explicit zero there is exactly the
    /// understatement this guards against.
    ///
    /// @param spentSec kept as the number the ledger stored rather than widened to a double,
    ///                 so a whole number goes back out as `1389` and not `1389.0`
    public record Effort(Map<String, Object> timesheet, Number spentSec) {

        public static final Effort NONE = new Effort(null, null);

        public boolean isEmpty() { return timesheet == null && spentSec == null; }

        /// Only the fields that are actually present.
        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            if (timesheet != null) out.put("timesheet", timesheet);
            if (spentSec != null) out.put("spentSec", spentSec);
            return out;
        }
    }

    /// The effort accounting inside a metrics object. See {@link Effort}.
    @SuppressWarnings("unchecked")
    public static Effort effortOf(Map<String, Object> metrics) {
        if (metrics == null) return Effort.NONE;
        Object timesheet = metrics.get("timesheet");
        Object spentSec = metrics.get("spentSec");
        return new Effort(
                timesheet instanceof Map ? (Map<String, Object>) timesheet : null,
                spentSec instanceof Number n ? n : null);
    }

    /// One entry of the improvement ledger: what happened to a unit in an earlier run.
    ///
    /// @param state   `improved`, `no_mutants`, `failed`, …
    /// @param metrics whatever was recorded alongside it, stamped or not — null when the
    ///                record carries none, which is not the same as carrying an empty one
    public record Improvement(String state, String prUrl, String patchPath, Map<String, Object> metrics) {
        public static Improvement of(String state) {
            return new Improvement(state, null, null, null);
        }

        public static Improvement of(String state, Map<String, Object> metrics) {
            return new Improvement(state, null, null, metrics);
        }
    }

    /// A unit earlier runs already settled — this run must not pick it up again.
    ///
    /// @param metrics null when the record's numbers were measured under older semantics.
    ///                Deliberately not an empty map: the caller lays these over the unit's
    ///                state, and "nothing to say" must not overwrite anything.
    /// @param effort  what the work cost, whatever version the numbers were measured under
    public record Settled(String key, Improvement record, Map<String, Object> metrics, Effort effort) {}

    /// A stored measurement still valid under the current semantics.
    public record Restored(String key, Map<String, Object> entry) {}

    /// @param stale     measurements discarded as measured under older semantics
    /// @param unknown   ledger entries naming units this run does not have
    /// @param retryable units left open because they failed to MEASURE, which settles nothing
    public record Plan(List<Settled> settle, List<Restored> restore, int stale, int unknown, int retryable) {}

    /// What of the persisted ledgers applies to the units this run actually has.
    ///
    /// This must be decided AFTER classes are expanded into `path::method` units. It used to
    /// run in /api/repo/prepare, before that expansion, when `state.files` still held FILE
    /// keys — so every unit-keyed entry missed, and a unit that had already been improved and
    /// PR'd in an earlier batch came back as a fresh candidate to be measured, improved and
    /// PR'd all over again.
    ///
    /// @param improved     the improvement ledger (unit key → record)
    /// @param measurements the measurement ledger (unit key → measurement)
    /// @param hasUnit      does this run have that unit?
    public static Plan planReplay(Map<String, Improvement> improved,
                                  Map<String, Map<String, Object>> measurements,
                                  Predicate<String> hasUnit) {
        List<Settled> settle = new ArrayList<>();
        List<Restored> restore = new ArrayList<>();
        int stale = 0, unknown = 0;
        int retryable = 0;
        Map<String, Improvement> ledger = improved == null ? Map.of() : improved;
        for (Map.Entry<String, Improvement> e : ledger.entrySet()) {
            String key = normalizeUnitKey(e.getKey());
            Improvement record = e.getValue();
            if (!hasUnit.test(key)) { unknown += 1; continue; }
            // A unit we could not MEASURE settles nothing: blacklisting it for the lifetime of
            // the ledger writes off improvable work on the strength of a measurement that, by the
            // pipeline's own definition, measured nothing.
            if (record != null && "failed".equals(record.state())) { retryable += 1; continue; }
            // The status is a record of what happened and stands. Its measurements are gated:
            // the same numbers the measurement ledger rejects as stale were being admitted
            // through this door and averaged into the headline.
            //
            // But the gate was applied to the WHOLE metrics object, and these are written
            // unstamped — so it rejected every one of them, always, and a replayed run showed
            // "Human-equivalent work 0" next to "2 file(s) improved" with FTE and ETA blank while
            // the ledger held spentSec 1389 and a full timesheet.
            //
            // Effort is not a measurement of the code. Human minutes and machine seconds mean the
            // same thing under per-file, per-method and method-scoped semantics alike, which is
            // the only thing the version stamp tracks. So they come back regardless; coverage,
            // mutation and MAC stay gated.
            Map<String, Object> m = (record == null || record.metrics() == null) ? Map.of() : record.metrics();
            settle.add(new Settled(key, record, restorable(m, key) ? record.metrics() : null, effortOf(m)));
        }
        Map<String, Map<String, Object>> measured = measurements == null ? Map.of() : measurements;
        for (Map.Entry<String, Map<String, Object>> e : measured.entrySet()) {
            String key = normalizeUnitKey(e.getKey());
            Map<String, Object> entry = e.getValue();
            if (!hasUnit.test(key)) { unknown += 1; continue; }
            if (!restorable(entry, key)) { stale += 1; continue; }
            restore.add(new Restored(key, entry));
        }
        return new Plan(List.copyOf(settle), List.copyOf(restore), stale, unknown, retryable);
    }
}
