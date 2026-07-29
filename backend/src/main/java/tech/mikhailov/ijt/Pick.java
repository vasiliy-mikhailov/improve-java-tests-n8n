package tech.mikhailov.ijt;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/// Choosing the next unit of work.
///
/// The model used to pick, with the mechanical ranking only as a fallback — and it picked
/// the fifth-ranked unit (a private method three hops from any public entry) over three
/// directly callable ones, on the strength of "11 lines of reflective logic … high
/// potential". That is the same mistake as letting it choose which mutant to attack, where
/// its judgement was wrong four rounds running.
///
/// So the split is: **the model applies the team rule, the measurements choose.** The rule
/// is an exclusion ("don't touch ui") — a question the model genuinely answers better than
/// a glob — and among whatever survives, the ranking decides.
public final class Pick {

    private Pick() {}

    /// A ranked candidate unit: `path` is the unit key `<source file>::<method>`, `file` the
    /// bare source path. Both are read here; the ranking metrics that produced the order live
    /// upstream (select.rankUnits) and this never re-derives them.
    public record Candidate(String path, String file, String method) {}

    /// @param retry  null on a successful pick — the JS answer carries no `retry` key there at
    ///               all, and only the two no-pick answers state one. Boxed so "not stated"
    ///               stays distinct from "stated false"; read it via {@link #shouldRetry()}
    ///               rather than unboxing.
    public record Decision(String file, Boolean retry, String reason) {
        /// Falsy-safe read, matching the JS `if (decision.retry)` at the call site.
        public boolean shouldRetry() { return Boolean.TRUE.equals(retry); }
    }

    private static String norm(String s) { return s == null ? "" : s.trim(); }

    /// @param ranked    weakest-first (select.rankUnits)
    /// @param excluded  unit keys or bare file paths the team rule rules out
    public static Decision choosePick(List<Candidate> ranked, List<String> excluded) {
        List<Candidate> list = ranked == null ? List.of() : ranked;
        if (list.isEmpty()) return new Decision(null, false, "no candidates left to work on");

        Set<String> out = new LinkedHashSet<>();
        for (String e : excluded == null ? List.<String>of() : excluded) {
            String n = norm(e);
            if (!n.isEmpty()) out.add(n);
        }

        Candidate pick = null;
        int skipped = 0;
        for (int i = 0; i < list.size(); i++) {
            Candidate c = list.get(i);
            // a rule speaks about files ("don't touch ui"), so excluding a file excludes its methods
            if (out.contains(norm(c.path())) || out.contains(norm(c.file()))) continue;
            pick = c;
            skipped = i;
            break;
        }
        if (pick == null) {
            return new Decision(
                    null,
                    false,                          // the rule itself excludes everything: terminal
                    "every remaining candidate is excluded by the team rule (" + out.size() + " exclusion(s))");
        }
        return new Decision(
                pick.path(),
                null,
                skipped != 0
                        ? "weakest unit the team rule allows (" + skipped + " higher-ranked unit(s) excluded)"
                        : "weakest unit by measured coverage x mutation (rank 1)");
    }
}
