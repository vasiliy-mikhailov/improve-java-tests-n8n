package tech.mikhailov.ijt;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/// How many completion tokens to allow a model call.
///
/// max_tokens is a CEILING, not a spend: a model that answers in 800 tokens costs 800
/// whatever the ceiling was. A ceiling set too low, on the other hand, costs everything —
/// the answer is cut off mid-JSON and the whole call is re-run, prompt reprocessed and
/// reasoning redone. In the JSON-java run, 4 of 12 completions finished with
/// finish_reason=length at ~100 s each, and nothing remembered it, so the next call for
/// the same stage walked into the same wall.
///
/// So: start from the answer budget plus a thinking reserve, and let each stage learn from
/// what actually happened to it.
public final class Budget {

    /// an answer this close to the ceiling nearly did not fit
    public static final double NEAR_CEILING = 0.9;

    /// What a stage is assumed to need when the caller states no answer budget.
    private static final int DEFAULT_ANSWER_TOKENS = 4096;
    private static final int DEFAULT_HARD_MAX = 16000;

    /// How the budget is configured, both fields boxed because both are read from the
    /// environment and either can be absent.
    ///
    /// @param thinkingReserve completion tokens thinking spends before any visible output;
    ///                        absent means none. Zero is a real setting (thinking off) and is
    ///                        NOT the same as absent for `hardMax` below, so the fallbacks
    ///                        below test for null rather than for falsiness.
    /// @param hardMax         the ceiling no stage may exceed; absent means 16000
    public record Options(Integer thinkingReserve, Integer hardMax) {}

    /// The learned state, as it is persisted with the run and handed back on the next start.
    ///
    /// @param learned stage → ceiling this stage has earned
    /// @param near    stage → consecutive near-ceiling answers
    public record Saved(Map<String, Integer> learned, Map<String, Integer> near) {}

    /// What a completion actually did.
    ///
    /// Every field is boxed: they are filled in from the model's response, which can report
    /// none of them. A primitive defaulting to 0 would silently read as "answered in zero
    /// tokens against a zero ceiling" — a ratio the learning rules would then act on.
    ///
    /// @param finish           the API's finish_reason; `length` means the answer was cut off
    /// @param ceiling          the max_tokens the call was actually issued with
    /// @param completionTokens how many tokens the answer used
    public record Completion(String finish, Integer ceiling, Integer completionTokens) {}

    private final int thinkingReserve;
    private final int hardMax;
    private final Map<String, Integer> learned = new LinkedHashMap<>();   // stage → ceiling this stage has earned
    private final Map<String, Integer> near = new LinkedHashMap<>();      // stage → consecutive near-ceiling answers

    public Budget(Options opts, Saved saved) {
        this.thinkingReserve = (opts == null || opts.thinkingReserve() == null) ? 0 : opts.thinkingReserve();
        this.hardMax = (opts == null || opts.hardMax() == null) ? DEFAULT_HARD_MAX : opts.hardMax();
        // copied, not aliased: what a restart hands in is a snapshot of an earlier run, and
        // learning on top of it must not write back through into the caller's state object
        if (saved != null && saved.learned() != null) learned.putAll(saved.learned());
        if (saved != null && saved.near() != null) near.putAll(saved.near());
    }

    public Budget(Options opts) { this(opts, null); }

    public Budget() { this(null, null); }

    public int hardMax() { return hardMax; }

    public int thinkingReserve() { return thinkingReserve; }

    /// Ceiling for the next call of this stage: the base need, or whatever it has learned.
    ///
    /// @param answerTokens what the caller thinks the answer needs; absent — or zero, which is
    ///                     what an unset option arrives as — means "no stated need", and the
    ///                     default stands in
    public int ceiling(String stage, Integer answerTokens) {
        int stated = (answerTokens == null || answerTokens == 0) ? DEFAULT_ANSWER_TOKENS : answerTokens;
        int base = stated + thinkingReserve;
        return Math.min(hardMax, Math.max(base, at(learned, stage)));
    }

    /// After a truncation: double, capped — and remember, so the next call starts there.
    public int escalate(String stage, int current) {
        int next = Math.min(hardMax, current * 2);
        learned.put(stage, Math.max(at(learned, stage), next));
        return next;
    }

    /// Would escalating from this ceiling actually buy room?
    ///
    /// `stage` is not consulted — the answer depends only on the ceiling and the cap — but it
    /// stays in the signature so callers ask this the same way they ask everything else, and
    /// so a future per-stage cap has somewhere to go.
    public boolean grew(String stage, int current) { return Math.min(hardMax, current * 2) > current; }

    /// Feed back what a completion actually did.
    public void record(String stage, Completion completion) {
        Completion done = completion == null ? new Completion(null, null, null) : completion;
        Integer ceiling = done.ceiling();
        if ("length".equals(done.finish())) {
            near.put(stage, 0);
            escalate(stage, ceiling == null ? 0 : ceiling);
            return;
        }
        double ratio = (ceiling == null || ceiling == 0)
                ? 0
                : (double) (done.completionTokens() == null ? 0 : done.completionTokens()) / ceiling;
        if (ratio < NEAR_CEILING) { near.put(stage, 0); return; }
        // stopping cleanly but only just — widen before the next answer runs off the end.
        // Two in a row, so one unusually long test does not inflate the stage for good.
        int seen = at(near, stage) + 1;
        near.put(stage, seen);
        if (seen >= 2) {
            learned.put(stage, Math.min(hardMax, Math.max(at(learned, stage), (int) Math.round(ceiling * 1.5))));
            near.put(stage, 0);
        }
    }

    /// The learned state, for persisting with the run — the JS `toJSON()`.
    ///
    /// Unmodifiable copies in insertion order: the state file is written from this, and a live
    /// reference would let a later call quietly rewrite what was already reported as saved.
    public Saved snapshot() {
        return new Saved(Collections.unmodifiableMap(new LinkedHashMap<>(learned)),
                         Collections.unmodifiableMap(new LinkedHashMap<>(near)));
    }

    /// A missing entry counts as zero — and so does a null one, which is what a hand-edited or
    /// half-written state file deserialises to (the JS `this.learned[stage] || 0`).
    private static int at(Map<String, Integer> m, String stage) {
        Integer v = m.get(stage);
        return v == null ? 0 : v;
    }
}
