package tech.mikhailov.ijt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// A constructor PIT will not mutate is not a broken toolchain.
///
/// `candidates()` carries a circuit breaker, and its own message says what it is for:
///
///     failed + " units failed to measure (limit " + maxFailures
///         + ") — the repo or its toolchain is the problem, not the units"
///
/// It fires at ten. On run-1785455296408 it fired on ten units that were nothing of the kind:
///
///     JSONException#<init>        JSONWriter#<init>         ParserConfiguration#<init>
///     XMLTokener#<init>           StringBuilderWriter#<init>  JSONPointerException#<init>
///     JSONMLParserConfiguration#<init>  XMLParserConfiguration#<init>
///     JSONArray#writeArrayAttempt       JSONObject#attemptWriteValue
///
/// Eight are empty constructors at 100% line coverage. PIT emits nothing for them and reports
/// "no tests to run", which the pipeline correctly reads as UNMEASURED rather than as a mutation
/// score of zero — and then files under `status: failed`. Ten of those and the run declares
/// itself done.
///
/// It stopped with **34 workable units still on the list** — real mutants, MAC between 55 and 85,
/// one completed attempt each. CDL#appendRowValue at 57.14 with 3 mutants; JSONArray#checkForSyntaxError
/// at 55.41 with 12. The run reported "done", which reads as "nothing left to improve".
///
/// The branch immediately below the offending one already knows this hazard. NO_MUTANTS refunds
/// the iteration, and says why: "a couple of duds in a row end the run before a single improvable
/// class is ever attempted (which is exactly what happened to jackson-core, json-path and
/// concurrency-limits: two skips, done)". UNMEASURED is the same kind of dud and was never given
/// the same treatment.
///
/// The breaker itself is right and must keep working — a repo whose build is broken should stop
/// early rather than burn a quota discovering it one unit at a time. What it must not do is count
/// a method that has nothing to mutate as evidence against the toolchain.
class UnmeasurableIsNotFailureTest {

    @BeforeEach
    void freshRun() {
        State.STATE.run = State.freshRun(Map.of("repoUrl", "https://example.invalid/r", "force", true));
        State.STATE.files = new LinkedHashMap<>();
    }

    private static void unit(String key, String status, Integer totalMutants, Double mac) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("status", status);
        f.put("key", key);
        f.put("path", key.split("::")[0]);
        f.put("method", key.contains("::") ? key.split("::")[1] : "m");
        f.put("coverage", 100.0);
        f.put("mac", mac);
        if (totalMutants != null) f.put("totalMutants", totalMutants);
        f.put("executableLines", 3);
        State.STATE.files.put(key, f);
    }

    /// The ten duds, filed with the status the PRODUCER actually writes.
    ///
    /// `Server.unmeasuredPatch`, not a string spelled out here. Writing "unmeasured" by hand
    /// would test a shape the pipeline never emits and pass whatever the producer did — which is
    /// the mistake that let the store's twelve tests pass while it was write-dead, and the first
    /// version of this test made it too.
    private static void tenUnmeasurable() {
        String status = String.valueOf(Server.unmeasuredPatch("PIT ran no tests", 3).get("status"));
        for (int i = 0; i < 10; i++) {
            unit("src/main/java/org/json/C" + i + ".java::<init>", status, null, null);
        }
    }

    private static void tenGenuineFailures() {
        for (int i = 0; i < 10; i++) {
            unit("src/main/java/org/json/C" + i + ".java::<init>", "failed", null, null);
        }
    }

    /// THE producer assertion. Everything else here follows from this one value.
    @Test
    void anUnmeasurableUnitIsNotSettledAsAFailure() {
        Map<String, Object> patch = Server.unmeasuredPatch("PIT ran no tests against this unit", 3);
        assertEquals("unmeasured", patch.get("status"),
                "the circuit breaker counts `failed`, and this is not a failure of the toolchain");
        // and it is still settled, so it is never picked a second time
        assertEquals(3, patch.get("attempts"));
    }

    private static void oneWorkableUnit() {
        unit("src/main/java/org/json/CDL.java::appendRowValue", "candidate", 3, 57.14);
    }

    @Test
    void tenUnmeasurableUnitsDoNotEndARunThatStillHasWork() {
        // THE bug, in one assertion. 34 improvable units were abandoned by this.
        tenUnmeasurable();
        oneWorkableUnit();

        Map<String, Object> out = Server.candidates();
        assertFalse(Boolean.TRUE.equals(out.get("done")),
                "a method with nothing to mutate is not evidence against the toolchain — got: "
                        + out.get("reason"));
    }

    @Test
    void anUnmeasurableUnitIsNotCountedAsAFailure() {
        tenUnmeasurable();
        oneWorkableUnit();
        assertEquals(0L, State.asLong(Server.candidates().get("failed")));
    }

    @Test
    void theWorkableUnitIsStillOffered() {
        // and it must still be pickable, not merely un-blocking
        tenUnmeasurable();
        oneWorkableUnit();

        Object c = Server.candidates().get("candidates");
        assertEquals(1, ((java.util.List<?>) c).size());
    }

    @Test
    void anUnmeasurableUnitIsNeverOfferedAgain() {
        // The other half of why it was marked failed: it must not be picked twice. Property#<init>
        // spent three attempts learning the same thing. Settling it is right; calling it a failure
        // is not.
        tenUnmeasurable();
        Object c = Server.candidates().get("candidates");
        assertTrue(((java.util.List<?>) c).isEmpty(), "a settled dud is not a candidate");
    }

    /// The negative half, and the reason the breaker exists at all.
    @Test
    void tenRealFailuresStillStopTheRun() {
        // A repo whose build is broken fails on everything, and should stop early rather than
        // spend the whole quota discovering that one unit at a time. Removing the breaker to fix
        // the bug above would trade one failure mode for a worse one.
        tenGenuineFailures();
        oneWorkableUnit();

        Map<String, Object> out = Server.candidates();
        assertTrue(Boolean.TRUE.equals(out.get("done")),
                "ten genuine measurement failures still mean the toolchain is the problem");
        assertTrue(String.valueOf(out.get("reason")).contains("failed to measure"));
    }
}
