package tech.mikhailov.ijt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Turning a PIT report into the work a round should actually do.
///
/// The one-mutant-at-a-time loop asks the model about ONE mutant, measures, and repeats — two
/// PIT runs per round per method-unit, 609 runs across 22 classes on one JSON-java run, 43% of
/// its wall-clock. Most of that re-measures code that did not change.
///
/// This models the round differently: every surviving mutant maps to a deterministic test
/// NAME, mutants on the same line share it, and any target whose name already appears in the
/// test sources is dropped without a model call. What remains is asked for once, compiled once,
/// and measured once.
///
/// The line-level dedup is worth having and is not the prize: on org.json.XML, 96 surviving
/// mutants collapse to 76 targets, 21%. The prize is measuring once per class instead of twice
/// per round.
public final class Targets {

    private Targets() {}

    /// A surviving mutant, as much of it as targeting needs.
    ///
    /// `status` is not read by anything here and is carried anyway, because grouping must hand
    /// the mutant through VERBATIM: a NO_COVERAGE line is a survivor PIT never reached, it still
    /// needs a test, and the prompt downstream says something different about it than about a
    /// SURVIVED one. Nullable — the JS mutant object carries whatever the report had, and a
    /// caller that never read a status has none to give.
    public record Mutant(String method, int line, String mutator, String status) {

        /// The three fields targeting itself uses, for callers with no status to hand on.
        public Mutant(String method, int line, String mutator) { this(method, line, mutator, null); }
    }

    /// One unit of work: a line, and every mutation found on it.
    public record Target(String marker, String method, int line, List<Mutant> mutants) {}

    /// @param covered how many targets the name filter spared a model call — reported rather
    ///                than inferred, because if it is always zero the filter is a no-op and
    ///                the whole design bought nothing
    public record Plan(List<Target> all, List<Target> pending, int covered) {}

    private static final Pattern NON_ALNUM = Pattern.compile("[^A-Za-z0-9]+(.)?");

    /// `<init>` → Ctor, `lambda$dispatchQueueBatch$2` → lambdaDispatchQueueBatch2.
    public static String javaSafeMethod(String method) {
        String m = method == null ? "" : method;
        if (m.equals("<init>")) return "Ctor";
        if (m.equals("<clinit>")) return "StaticInit";
        // lambda$foo$2 → lambdaFoo2: keep it readable and legal, never just strip to nothing
        Matcher matcher = NON_ALNUM.matcher(m);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String c = matcher.group(1);
            // Locale.ROOT: JS toUpperCase is locale-independent, `String.toUpperCase()` is not,
            // and under tr-TR `foo_isValid` would become `fooİsValid` (U+0130) — a non-ASCII
            // marker written into a Java comment the model is asked to reproduce verbatim
            matcher.appendReplacement(sb,
                    c == null ? "" : Matcher.quoteReplacement(c.toUpperCase(java.util.Locale.ROOT)));
        }
        matcher.appendTail(sb);
        String cleaned = sb.toString();
        return cleaned.isEmpty() ? "Method" : cleaned;
    }

    /// The machine-readable link between a test and the mutant it kills.
    ///
    /// The first version made the model NAME each test `kill_<method>_line<N>`, which is not
    /// how these repos name tests — java-dataloader has clearCacheOnError and disableCache,
    /// JSON-java has emptyStringCookieList and malFormedCookieListException — and it reads as
    /// machine output in a PR a human is asked to review. It is also wrong the moment the
    /// source shifts a line.
    ///
    /// So the two jobs are separated. The model names the test the way the repo does, and
    /// carries this marker in the one short comment the rules already allow it. The filter
    /// greps the marker; the reviewer reads the name.
    ///
    /// Keyed on METHOD and LINE, deliberately not on mutator kind: `>` becoming `>=`, becoming
    /// `<`, and the branch being removed are three mutants of one condition, and the single
    /// test that exercises that boundary distinguishes all three.
    ///
    /// A marker that no longer matches a freshly measured line means the code moved, and
    /// re-asking is then the correct answer rather than a miss.
    public static String targetMarker(Mutant mutant) {
        String method = javaSafeMethod(mutant == null ? null : mutant.method());
        int line = mutant == null ? 0 : mutant.line();
        return "covers " + method + ":" + line;
    }

    /// One target per surviving line, carrying every mutation found on it.
    public static List<Target> groupTargets(List<Mutant> survivors) {
        Map<String, Target> byName = new LinkedHashMap<>();
        for (Mutant mu : survivors == null ? List.<Mutant>of() : survivors) {
            String marker = targetMarker(mu);
            byName.computeIfAbsent(marker, k -> new Target(marker, mu.method(), mu.line(), new ArrayList<>()))
                  .mutants().add(mu);
        }
        return new ArrayList<>(byName.values());
    }

    /// Targets with no test of that name yet — the only ones worth a model call.
    ///
    /// 1000 mutants at ~100 tokens each and 30 tokens/sec is hours of generation; a string
    /// check over the test sources costs nothing. The match is anchored so the marker for line
    /// 17 cannot satisfy line 170.
    public static List<Target> pendingTargets(List<Target> targets, List<String> testSources) {
        String haystack = testSources == null ? "" : String.join("\n", testSources);
        List<Target> out = new ArrayList<>();
        for (Target t : targets == null ? List.<Target>of() : targets) {
            Pattern p = Pattern.compile(Pattern.quote(t.marker()) + "(?![0-9])");
            if (!p.matcher(haystack).find()) out.add(t);
        }
        return out;
    }

    /// Everything a batch round needs to know, in one place.
    public static Plan targetsFor(List<Mutant> survivors, List<String> testSources) {
        List<Target> all = groupTargets(survivors);
        List<Target> pending = pendingTargets(all, testSources);
        return new Plan(all, pending, all.size() - pending.size());
    }
}
