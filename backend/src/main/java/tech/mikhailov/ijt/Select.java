package tech.mikhailov.ijt;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Which surviving mutant to attack next.
///
/// The order comes from PIT's own data — mutator kind, SURVIVED before NO_COVERAGE, both
/// folded into `difficulty` by pit.js — and from what has actually died in THIS run. It
/// never comes from asking the model which mutant it fancies: that judgement was
/// confidently wrong on four consecutive rounds, every time naming a RemoveConditional
/// mutant it then could not distinguish.
public final class Select {

    private Select() {}

    /// A surviving mutant, as much of it as selection reads. Its `status` (SURVIVED /
    /// NO_COVERAGE) is not read here — pit.js has already folded it into `difficulty`.
    ///
    /// @param line       boxed because the JS reads it as `line || 0`: a mutant with no line
    ///                   ranks as line 0 rather than crashing the sort
    /// @param index      PIT's per-mutant index inside `<indexes>`, null when the report
    ///                   carried none. See {@link #attemptKey}.
    /// @param difficulty pit.js's killDifficulty, null when the mutant never went through it —
    ///                   which ranks as 1, the middle, not as 0
    public record Mutant(String mutator, Integer line, Integer index, Integer difficulty) {

        /// A mutant known only by kind and line — no index, no difficulty yet.
        public Mutant(String mutator, Integer line) { this(mutator, line, null, null); }
    }

    /// What this run has seen a mutator kind do: attempts, and how many of them killed.
    public record MutatorStat(int tried, int killed) {}

    /// One key per MUTANT — not per mutator-and-line.
    ///
    /// A line can carry several mutations of the same kind, and PIT numbers them with
    /// `<index>`. Without it, `JSONObject#parseJSONObject` — 14 mutants, 11 killed, 3 alive,
    /// all three `RemoveConditionalMutator_EQUAL_ELSE` at line 248 — struck off all three when
    /// a round attacked one, then settled at 78.57% announcing "no un-attempted survivors left"
    /// with two survivors it had never tried. 37 survivors across 18 units were masked that way
    /// in a single run.
    public static String attemptKey(Mutant m) {
        return legacyKey(m) + (m.index() == null ? "" : "#" + m.index());
    }

    /// The pre-index spelling of a key, for registers written before indexes were recorded.
    private static String legacyKey(Mutant m) {
        return m.mutator() + "@" + m.line();
    }

    /// Kinds whose death can be bought with an assertion that pins nothing else.
    ///
    /// A NullReturnVals mutant returns null, so assertNotNull kills it — and proves only that
    /// something came back. Same for an empty collection or a zeroed primitive. The kill counts
    /// toward the score and is real, but it is not evidence that the method's behaviour is
    /// pinned, so a run of them should not make the kind look like a good bet.
    ///
    /// ConditionalsBoundary, Math and RemoveConditional are the opposite: there is no cheap
    /// assertion that kills them. You have to exercise the boundary, or the arithmetic, or the
    /// guarded branch — which is the behaviour worth testing.
    private static final Set<String> SHALLOW_KILL = Set.of(
            "NullReturnValsMutator",
            "EmptyObjectReturnValsMutator",
            "PrimitiveReturnsMutator");

    /// How badly this run's evidence argues against a mutator kind. 0 = no objection.
    ///
    /// The first version was `tried >= 2 && killed === 0 ? 1 : 0`, which let a single early
    /// kill immunise a kind permanently: ConditionalsBoundary killed once at line 73, then
    /// missed at 164, 191 and 234 while staying at the top of the queue every round. Kill
    /// RATE decides now, and a kind that has never killed anything is still the worst bet.
    ///
    /// @param st      null for a kind this run has never attacked — no evidence, no objection
    /// @param mutator the kind itself, read only to ask whether its kills mean anything
    public static int penalty(MutatorStat st, String mutator) {
        if (st == null || st.tried() < 2) return 0;          // one miss is noise, not evidence
        if (st.killed() == 0) return 2;                      // repeatedly attacked, never once died
        double rate = (double) st.killed() / st.tried();     // integer division here would read every rate as 0
        if (rate < 0.4) return 1;                            // mostly missing → behind the untried kinds
        // Evidence cuts both ways: a kind this run keeps killing is a better bet than one we
        // have never tried, even where PIT rates them equally hard.
        //
        // But only when the kill means something. Ranked on kill rate alone, the run harvested
        // the cheap kinds — 63% of all kills came from the two easiest, while conditionals sat
        // at a 4% kill rate and 173 survivors, because they were always ranked last. A bonus
        // for being easy to kill is a bonus for testing shallowly.
        return rate >= 0.6 && !SHALLOW_KILL.contains(mutator) ? -1 : 0;
    }

    /// The evidence alone, with no kind to weigh it against — the shallow-kill exemption
    /// cannot apply, exactly as in the JS when the second argument is omitted.
    public static int penalty(MutatorStat st) {
        return penalty(st, null);
    }

    /// Survivors we have not already attacked and failed to kill.
    ///
    /// Attempt registers persist across runs, and the ones written before `<index>` was
    /// recorded hold the bare `Mutator@line`. Such an entry cannot say WHICH mutant on that
    /// line it meant, so it keeps its old, broad meaning: better to skip a mutant that might be
    /// fresh than to re-offer one already known to have resisted.
    public static List<Mutant> eligible(List<Mutant> list, List<String> attempted) {
        Set<String> tried = new HashSet<>(attempted == null ? List.<String>of() : attempted);
        Set<String> legacy = new HashSet<>();
        for (String k : tried) if (!k.contains("#")) legacy.add(k);
        List<Mutant> out = new ArrayList<>();
        for (Mutant m : list == null ? List.<Mutant>of() : list) {
            if (!tried.contains(attemptKey(m)) && !legacy.contains(legacyKey(m))) out.add(m);
        }
        return out;
    }

    /// Best bet first. Stable and deterministic: same input, same round, same choice.
    ///
    /// A copy is sorted, never the caller's list — the JS `.slice().sort()`. Java's sort is
    /// stable like the JS one, so equal mutants keep the order PIT reported them in.
    public static List<Mutant> rankSurvivors(List<Mutant> list, Map<String, MutatorStat> stats) {
        Map<String, MutatorStat> st = stats == null ? Map.of() : stats;
        List<Mutant> out = new ArrayList<>(list == null ? List.<Mutant>of() : list);
        // compare(), never subtraction: a difference of two ints can overflow, and the JS
        // comparator's arithmetic has no such trap
        out.sort((a, b) -> {
            int c = Integer.compare(penalty(statFor(st, a.mutator()), a.mutator()),
                                    penalty(statFor(st, b.mutator()), b.mutator()));
            if (c != 0) return c;
            c = Integer.compare(difficulty(a), difficulty(b));
            if (c != 0) return c;
            return Integer.compare(line(a), line(b));
        });
        return out;
    }

    /// `stats[mutator]` in the JS, which simply misses on a mutant carrying no kind. A
    /// `Map.of()` throws on a null key, so the miss is made explicit rather than fatal.
    private static MutatorStat statFor(Map<String, MutatorStat> stats, String mutator) {
        return mutator == null ? null : stats.get(mutator);
    }

    /// No difficulty is the middle of the range, not the easiest — `difficulty ?? 1`.
    private static int difficulty(Mutant m) {
        return m.difficulty() == null ? 1 : m.difficulty();
    }

    private static int line(Mutant m) {
        return m.line() == null ? 0 : m.line();
    }

    private static boolean isCtor(String m) {
        return "<init>".equals(m) || "<clinit>".equals(m);
    }

    /// How hard it is for a test to get at this unit — a RANKING signal, never a filter.
    ///
    /// It was a filter, and it deleted real work: reachability is decided by regex over Java
    /// source, which missed multi-line signatures, callers inside inner and anonymous classes,
    /// `this::foo` method references, and overloads whose first declaration was private. Each
    /// miss removed a genuinely testable method from the queue for the whole run, silently and
    /// permanently. Ranked last instead, an analysis mistake costs some rounds at the end of a
    /// run rather than the method itself.
    private static final Map<String, Integer> REACH_COST = Map.of("public", 0, "route", 1, "none", 2);

    /// Has the run itself proved it cannot execute this unit? Two or more attempts in which
    /// coverage never moved off zero is evidence no static analysis can offer: JSONObject's
    /// isRecordStyleAccessor sits behind `if (isRecordType && ...)` and needs a real Java
    /// record, which a test compiled at the project's source level cannot declare. The call
    /// graph says reachable; the runs say otherwise, and the runs are measurements.
    ///
    /// Strictly `everReached == false`, as in the JS: a unit nobody has looked at yet carries
    /// null, and null is not evidence of anything.
    private static boolean provenUnexecutable(Unit x) {
        return (x.misses() == null ? 0 : x.misses()) >= 2 && Boolean.FALSE.equals(x.everReached());
    }

    /// Compiler-generated methods. `lambda$foo$2`, `access$100` — no test can name them, and
    /// the prompt cannot show the model a method that is absent from the source. The `$` is
    /// the marker: somebody's real `lambdaFactory` is not synthetic.
    private static final java.util.regex.Pattern SYNTHETIC =
            java.util.regex.Pattern.compile("^(lambda\\$|access\\$)");

    /// Can this unit yield a kill at all?
    ///
    /// One predicate rather than three special cases, because the run produced three shapes of
    /// the same waste and each cost a baseline coverage run, a PIT run, a model call and a
    /// verification to discover it was never work:
    ///
    ///   no mutation surface  `public Cookie() { }`, `public void flush() { }`. PIT emits
    ///                        nothing for an empty body and reports "no tests to run"; the
    ///                        round then records UNMEASURED and the unit stays a candidate.
    ///   already perfect      0 survivors and mutation at 100 — Statistics#combine,
    ///                        DataLoaderRegistry#combine, PromisedValues#allOf.
    ///   synthetic            lambda$dispatchQueueBatch$3, lambda$invokeLoader$7.
    ///
    /// @param mutants   how many PIT found for it; 0 means there is nothing to kill
    /// @param exhausted every surviving mutant has already been attempted
    public static boolean eligibleUnit(Unit u, int mutants, boolean exhausted) {
        if (u == null) return false;
        if (exhausted) return false;
        // Zero mutants covers BOTH the empty body and the already-perfect unit: nothing to
        // mutate and nothing left alive are the same fact from the selector's point of view.
        if (mutants <= 0) return false;
        String m = u.method() == null ? "" : u.method();
        return !SYNTHETIC.matcher(m).find();
    }

    /// A candidate unit, with the measurements the ranking reads.
    ///
    /// Every metric is boxed on purpose. `mac ?? (coverage ?? 0) / 2` is load-bearing — a
    /// primitive defaulting to 0 would rank an unmeasured unit as the weakest thing in the run
    /// instead of estimating it from the coverage that IS known.
    ///
    /// @param path            the unit key `<source file>::<method>`, and the final tie-break
    /// @param reach           `public` | `route` | `none`, null meaning `public`
    /// @param misses          rounds that wrote a passing test and still executed none of it
    /// @param everReached     null until a round has measured it — see {@link #provenUnexecutable}
    public record Unit(String path, String method, Double mac, Double coverage, String reach,
                       Integer executableLines, Integer lines, Integer misses, Boolean everReached) {}

    /// @param unreachable always 0. Units no public path reaches are RANKED LAST, never
    ///                    dropped; the count stays in the answer so the callers and the
    ///                    dashboard that report it keep reading a number rather than nothing.
    public record Ranking(List<Unit> units, int unreachable) {}

    /// Which unit to work on next.
    ///
    /// Weakest first, because that is where the MAC gap is — but a unit no public path reaches
    /// is dropped rather than queued: the prompt correctly refuses to write a test for it, the
    /// round changes nothing, and the unit is abandoned after three misses, having spent four
    /// PIT runs to learn what the call graph already said. Among equally weak units, one a test
    /// can call outright is worth more per round than one behind three private hops.
    ///
    /// (That dropping is what the comment above described and what the code no longer does:
    /// see {@link #REACH_COST}. Last place keeps the saving where the analysis is right and
    /// costs only ordering where it is wrong.)
    public static Ranking rankUnits(List<Unit> list) {
        List<Unit> units = new ArrayList<>(list == null ? List.<Unit>of() : list);
        units.sort((a, b) -> {
            // evidence of unexecutability outranks everything: it is measured, not guessed
            int c = Integer.compare(provenUnexecutable(a) ? 1 : 0, provenUnexecutable(b) ? 1 : 0);
            if (c != 0) return c;
            // a unit with no MAC yet is not a perfect one — rank it on the coverage we do have
            c = Double.compare(weakness(a), weakness(b));
            if (c != 0) return c;
            c = Integer.compare(isCtor(a.method()) ? 1 : 0, isCtor(b.method()) ? 1 : 0);
            if (c != 0) return c;
            c = reachOrder(a, b);
            if (c != 0) return c;
            // descending: the most code to mutate first. Note the operands are the other way
            // round from every other criterion here.
            c = Integer.compare(codeSize(b), codeSize(a));
            if (c != 0) return c;
            return comparePaths(String.valueOf(a.path()), String.valueOf(b.path()));
        });
        return new Ranking(units, 0);
    }

    /// How much MAC there is to win here. Lower is more urgent.
    private static double weakness(Unit u) {
        if (u.mac() != null) return u.mac();
        // Coverage IS a measurement, so estimate from it as before.
        if (u.coverage() != null) return u.coverage() / 2;
        // Neither measured. This used to fall through to 0 — which ranked a unit nothing is
        // known about as the weakest thing in the repo and picked it at rank 1, repeatedly.
        // Unmeasurable is not weak, it is UNKNOWN, and unknown must not outrank a measured
        // gap. Sorting it last costs nothing: if it turns out to have a real gap, the next
        // run measures it and it takes its proper place.
        return Double.MAX_VALUE;
    }

    // NOT IMPLEMENTED, deliberately, and recorded because the next person will try it.
    //
    // JSONObject#isRecordType burned all three misses with mutation 0 -> 0 and was immediately
    // re-picked at rank 1, because provenUnexecutable only demotes units at exactly zero
    // coverage (everReached == false) and this one has 33%. Up to nine rounds on a method three
    // measurements say will not move.
    //
    // Demoting on the miss count instead fails aUnitThatHasBeenReachedIsNeverDemotedForMissing,
    // and that test is RIGHT: a unit whose code a test does reach can miss because the tests
    // were bad, and demoting it throws away work a better prompt would win. Nor does a measured
    // MAC of 0 separate the two — the fixture there is also 0.0 with five misses.
    //
    // The signal that would actually distinguish them is whether the mutation score MOVED
    // across an attempt, and Unit does not carry a before/after. Fixing this properly means
    // adding that, not sharpening a predicate over data that cannot answer the question.

    private static int codeSize(Unit u) {
        if (u.executableLines() != null) return u.executableLines();
        return u.lines() == null ? 0 : u.lines();
    }

    private static int reachOrder(Unit a, Unit b) {
        Integer ca = REACH_COST.get(a.reach() == null ? "public" : a.reach());
        Integer cb = REACH_COST.get(b.reach() == null ? "public" : b.reach());
        // A reach the table does not know is `undefined` in the JS, so the subtraction is NaN —
        // which is falsy, and the `||` chain falls through to the next criterion instead of
        // deciding the order. A missing entry does the same here rather than throwing.
        if (ca == null || cb == null) return 0;
        return Integer.compare(ca, cb);
    }

    /// The final tie-break: `String(a.path).localeCompare(String(b.path))`.
    ///
    /// Not `String.compareTo`, which orders by code point and would put `Zoo.java` before
    /// `apple.java` — paths mix case on every real repo. And not `java.text.Collator` either,
    /// which was the first reading and is a DIFFERENT order: Java's root rules make `-`, `/`
    /// and `.` ignorable at primary strength, while Node's localeCompare is ICU's CLDR root
    /// collation, where punctuation carries a real primary weight (`alternate=non-ignorable`).
    /// The two disagree, and they disagree on exactly the shape a multi-module repo has —
    /// `core/…` against `core-api/…`, `dataloader/…` against `dataloader-core/…` — where the
    /// collator answers "before" and Node answers "after". This tie-break is reached whenever
    /// provenUnexecutable, weakness, isCtor, reach and codeSize all tie, which is the normal
    /// case on a fresh scan where nothing has been measured yet; and since the run is capped by
    /// scopeLimit/maxIterations, a different order means a different SET of units gets improved.
    ///
    /// So the CLDR order is spelled out. Weights compare in two passes, as the UCA does:
    /// PRIMARY over the whole string first (case-blind), then case as the tertiary difference
    /// (lowercase before uppercase). Verified against node v22 on the whole printable-ASCII
    /// alphabet and on 2000 random paths.
    static int comparePaths(String a, String b) {
        String x = a == null ? "null" : a;
        String y = b == null ? "null" : b;
        int n = Math.min(x.length(), y.length());
        for (int i = 0; i < n; i++) {
            int c = Integer.compare(primaryWeight(x.charAt(i)), primaryWeight(y.charAt(i)));
            if (c != 0) return c;
        }
        // a primary-prefix sorts first, and nothing here is primary-ignorable, so length decides
        int c = Integer.compare(x.length(), y.length());
        if (c != 0) return c;
        for (int i = 0; i < n; i++) {
            int t = Integer.compare(caseWeight(x.charAt(i)), caseWeight(y.charAt(i)));
            if (t != 0) return t;
        }
        return 0;
    }

    /// CLDR root's primary order for printable ASCII, in order: whitespace, then punctuation,
    /// then symbols, then the currency sign, then digits — and letters after all of them, case
    /// folded. This string was not written from the specification, it was read off
    /// `[...chars].sort((a, b) => a.localeCompare(b))` in node.
    private static final String PRIMARY_ORDER =
            "\t\n\f\r _-,;:!?.'\"()[]{}@*/\\&#%`^+<=>|~$0123456789";

    private static final int LETTER_BASE = PRIMARY_ORDER.length() + 1;

    /// Anything outside printable ASCII keeps a stable place AFTER every letter rather than
    /// being folded onto a base letter the way ICU would. Java source paths are ASCII in every
    /// repo this pipeline targets, and a wrong order among two non-ASCII paths costs an
    /// arbitrary choice between two equally weak units — not a wrong one.
    private static final int UNKNOWN_BASE = LETTER_BASE + 26;

    private static int primaryWeight(char c) {
        if (c >= 'a' && c <= 'z') return LETTER_BASE + (c - 'a');
        if (c >= 'A' && c <= 'Z') return LETTER_BASE + (c - 'A');
        int i = PRIMARY_ORDER.indexOf(c);
        return i >= 0 ? i : UNKNOWN_BASE + c;
    }

    /// The tertiary difference CLDR gives a case pair: lowercase first, so `a` precedes `A`.
    private static int caseWeight(char c) { return (c >= 'A' && c <= 'Z') ? 1 : 0; }

    /// What the run has recorded about a unit, for the two questions below. Kept apart from
    /// {@link Unit}, which carries the ranking metrics; the JS reads both off one object.
    ///
    /// @param macBefore        null until the unit has been measured — which is not zero
    /// @param lastSurvived     null when nothing has ever been measured; an EMPTY list means
    ///                         measured and nothing survived, and the two must not be confused
    /// @param attemptedMutants attempt keys, see {@link #attemptKey}
    public record UnitState(Double macBefore, String status,
                            List<Mutant> lastSurvived, List<String> attemptedMutants) {}

    /// Did this run actually take this unit on? Only those belong in the headline averages.
    ///
    /// A unit settled as having no mutation surface records a macBefore of 0 and never gets an
    /// "after", so averaging it in reported avg MAC after = 13.33 for a batch whose single
    /// improved unit stood at 66.67 — understating the work fivefold with units that cannot be
    /// improved at all.
    public static boolean isTargeted(UnitState f) {
        return f != null && f.macBefore() != null && !"no_mutants".equals(f.status());
    }

    /// Is there anything left to attack? A unit that has been measured and whose every
    /// surviving mutant has already been attempted can produce nothing: the prompt skips, the
    /// round targets nothing, and it is discarded — then picked again, up to its attempt limit,
    /// costing a PIT run and a coverage run each time for an outcome that cannot change.
    ///
    /// Never measured is NOT exhausted: nothing is known about it yet.
    public static boolean exhausted(UnitState f) {
        if (f == null || f.lastSurvived() == null) return false;
        List<String> attempted = f.attemptedMutants() == null ? List.<String>of() : f.attemptedMutants();
        return eligible(f.lastSurvived(), attempted).isEmpty();
    }
}
