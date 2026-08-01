package tech.mikhailov.ijt.orchestrator.store;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Every method on the store is called by something that is not a test.
///
/// THE REPO'S SIGNATURE DEFECT, made visible. Ten times now a change has shipped as code that is
/// correct, tested, and reached by nothing: the improved-ledger clear that only cleared memory,
/// the unit patch that threw for every real unit, the feedback routes that answered 404 from
/// outside the process, and — the one that produced this test — an entire boot-recovery
/// subsystem on `StateRepository` whose four methods had no caller in `main` at all. Its
/// comments described how a dead run is marked `interrupted` so the start route can tell a
/// corpse from a live run. None of it ran. `IJT_RUN.STAGE_NAME` was NULL on every row the
/// deployment had ever written, and the run went on reporting `running` with nothing executing
/// it, for as long as anyone cared to look.
///
/// A unit test cannot catch that, because a unit test is exactly what such code has. The only
/// distinguishing fact is that the call sites are all under `src/test`.
///
/// ## Why text, and why it has to know the RECEIVER
///
/// A call graph would be better and needs a bytecode analyser this build does not have. Plain
/// name matching is not good enough here, and the first version of this test proved it: it
/// reported `finishRun` as used, because `ServerBackend.finishRun()` and `RunLauncher.startRun()`
/// are unrelated methods that happen to share a name with the ones being checked. A guard that
/// answers "used" for a call to a different class is worse than no guard.
///
/// So the search is anchored to the variables actually declared as `StateRepository` in main —
/// one field, `store`, on H2StatePersistence — and looks for `store.name(` and `store::name`.
/// The method-reference form matters: `store::putImproved` and `store::putMeasured` are real
/// production uses that a `store.` search misses entirely.
class StoreSurfaceTest {

    /// The debt, written down. Thirteen public methods whose only callers are tests.
    ///
    /// This list is a CHARACTERIZATION, not an allowance. The assertion is equality, so a new
    /// method with no caller fails exactly as loudly as a name left here after being wired up or
    /// deleted. It exists because the honest count was thirteen and deleting thirteen methods
    /// while fixing a liveness bug would be two changes wearing one commit — and at least one of
    /// them is a MISSING CALL rather than surplus code, which is a different fix:
    ///
    ///   startRun / clearEventsForRun — startRun clears the previous run's event rows and marks
    ///     it interrupted. Production reaches neither, so event rows from old runs are never
    ///     cleared out of H2. That is a question about the event log, and answering it by
    ///     deleting the method would answer it by accident.
    ///   finishRun — the run is stamped in memory and flushed by the snapshot writer instead.
    ///   prs / units / unit / improved / allRuns — accessors superseded by the ...AsMaps and
    ///     ...Ledger forms the snapshot actually uses.
    ///   addTokens / overheadSec / addOverheadSec / mergeMeasured / purgeRepo — no caller.
    ///
    /// Burn this list down; do not add to it.
    private static final Set<String> KNOWN_TEST_ONLY = new LinkedHashSet<>(List.of(
            "startRun", "finishRun", "prs", "clearEventsForRun", "overheadSec", "addTokens",
            "improved", "mergeMeasured", "purgeRepo", "addOverheadSec", "allRuns", "unit",
            "units"));

    @Test
    void everyPublicStateRepositoryMethodIsCalledFromMain() throws IOException {
        String main = sourceText();
        Set<String> receivers = receiversOf(main);
        assertEquals(Set.of("store"), receivers,
                "the set of things holding a StateRepository in main changed; this guard only "
                        + "looks at calls on those, so it must know about all of them");

        Set<String> unused = new LinkedHashSet<>();
        for (Method m : StateRepository.class.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) continue;
            if (m.isSynthetic()) continue;
            String name = m.getName();
            boolean called = receivers.stream().anyMatch(r ->
                    main.contains(r + "." + name + "(") || main.contains(r + "::" + name));
            if (!called) unused.add(name);
        }

        assertEquals(KNOWN_TEST_ONLY, unused,
                "the store's surface and its callers have diverged. A name here that is NOT in "
                        + "KNOWN_TEST_ONLY is code with a test and no caller — write the call or "
                        + "delete the method. A name in KNOWN_TEST_ONLY that is no longer here "
                        + "has been wired up or removed; take it out of the list.");
    }

    /// Every name declared as a StateRepository in main — field or constructor parameter.
    ///
    /// Anchored to a modifier or an opening paren, because the bare pattern matches PROSE: the
    /// comments in this package discuss "StateRepository is injected into exactly one class" and
    /// "StateRepository methods", and the first version of this dutifully reported receivers
    /// named `is` and `methods`.
    private static Set<String> receiversOf(String main) {
        Set<String> out = new LinkedHashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:(?:private|public|protected|final|static)\\s+|[(,]\\s*)"
                        + "StateRepository\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*[;,)=]").matcher(main);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /// Both source trees, because the store is reached from the orchestrator and the domain layer
    /// it drives lives in `backend`.
    private static String sourceText() throws IOException {
        StringBuilder all = new StringBuilder();
        for (Path root : List.of(Path.of("src/main/java"), Path.of("../backend/src/main/java"))) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    all.append(Files.readString(f)).append('\n');
                }
            }
        }
        return all.toString();
    }
}
