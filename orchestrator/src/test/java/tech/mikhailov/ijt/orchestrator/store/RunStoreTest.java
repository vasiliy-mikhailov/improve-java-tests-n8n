package tech.mikhailov.ijt.orchestrator.store;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The run: starting one, finishing one, and finding one still running after a restart.
class RunStoreTest extends StoreTestSupport {

    private static final String REPO = "https://github.com/stleary/JSON-java";

    @Test
    void aFreshStoreIsEmptyAndHasNoRun() {
        assertAll(
                () -> assertTrue(store.isEmpty(), "the migration would decline to import into this"),
                () -> assertTrue(store.currentRun().isEmpty()));
    }

    @Test
    void startingARunMakesItTheCurrentOne() {
        store.startRun(newRun("run-1", REPO));
        reload();

        Run run = store.currentRun().orElseThrow();
        assertAll(
                () -> assertEquals("run-1", run.getId()),
                () -> assertEquals("running", run.getStatus()),
                () -> assertEquals(REPO, run.getRepoUrl()),
                () -> assertFalse(store.isEmpty()),
                () -> assertNull(run.getFinishedAt(), "a run that has not finished has no finish time"),
                // not yet measured is not zero — a run reporting 0% coverage before JaCoCo has
                // run is a lie the dashboard prints in bold
                () -> assertNull(run.getBaselineCoveragePct()),
                () -> assertNull(run.getBaselineMac()),
                () -> assertNull(run.getResultMac()));
    }

    /// The run's whole configuration, back the way it went in. Every numeric component of
    /// `State.Config` is boxed and every one can be null: a garbled `SCOPE_LIMIT` must reach the
    /// dashboard as null and not as a confident 0.
    @Test
    void theConfigurationRoundTrips() {
        Run run = newRun("run-1", REPO);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("repoUrl", REPO);
        config.put("maxAttemptsPerFile", 3);
        config.put("minRoundGapFrac", 0.25);
        config.put("dryRun", false);
        config.put("scopeLimit", null);       // NaN in the JS, and null on the wire
        run.setConfig(config);
        store.startRun(run);
        reload();

        Map<String, Object> back = store.currentRun().orElseThrow().getConfig();
        assertAll(
                () -> assertEquals(3, back.get("maxAttemptsPerFile")),
                () -> assertEquals(0.25, back.get("minRoundGapFrac")),
                () -> assertEquals(Boolean.FALSE, back.get("dryRun")),
                () -> assertTrue(back.containsKey("scopeLimit")),
                () -> assertNull(back.get("scopeLimit"), "an unparseable limit became a confident 0"));
    }

    /// The JS overwrote `state.run` and the outgoing run's status vanished with it. The row
    /// survives here, so it has to be told: a row still reading `running` would look like a
    /// second live run to anything that asks.
    @Test
    void takingOverARunEndsIt() {
        store.startRun(newRun("run-1", REPO));
        store.startRun(newRun("run-2", REPO));
        reload();

        assertAll(
                () -> assertEquals("run-2", store.currentRun().orElseThrow().getId()),
                () -> assertEquals("interrupted", store.run("run-1").orElseThrow().getStatus()),
                () -> assertEquals(2, store.allRuns().size(), "the previous run's row was deleted"));
    }

    @Test
    void finishingARunStampsItsStatusAndTime() {
        store.startRun(newRun("run-1", REPO));
        store.finishRun("run-1", "done");
        reload();

        Run run = store.run("run-1").orElseThrow();
        assertAll(
                () -> assertEquals("done", run.getStatus()),
                () -> assertTrue(run.getFinishedAt() != null && run.getFinishedAt() > 0));
    }

    /// A hung run was recoverable ONLY because the state survived a container restart: the
    /// backend read it back and marked it `interrupted` rather than pretending it was idle.
    ///
    /// The STAGE moves; the STATUS does not. `POST /api/run/start` reads the stage to decide
    /// whether a live run may be taken over, and a status of `done` here would make a hung run
    /// look finished and lose the only route that ever recovered one.
    @Test
    void aRunLeftRunningIsMarkedInterruptedOnBoot() {
        store.startRun(newRun("run-1", REPO));
        reload();

        store.markInterruptedIfRunning();
        reload();

        Run run = store.currentRun().orElseThrow();
        assertAll(
                () -> assertEquals("interrupted", run.getStageName()),
                () -> assertEquals("running", run.getStatus(), "the status must stay takeover-able"),
                () -> assertTrue(run.getStageSince() > 0));
    }

    @Test
    void aFinishedRunIsNotMarkedInterrupted() {
        store.startRun(newRun("run-1", REPO));
        store.finishRun("run-1", "done");
        reload();

        store.markInterruptedIfRunning();
        reload();

        assertEquals("done", store.currentRun().orElseThrow().getStatus());
    }

    /// The stage's `since` is the clock `run/start` measures staleness against, so the store
    /// stamps it rather than trusting a caller's.
    @Test
    void settingAStageStampsItsOwnClock() {
        store.startRun(newRun("run-1", REPO));
        store.setStage("run-1", "improving_mutation", "XML#toJSONObject()");
        reload();

        Run run = store.currentRun().orElseThrow();
        assertAll(
                () -> assertEquals("improving_mutation", run.getStageName()),
                () -> assertEquals("XML#toJSONObject()", run.getStageDetail()),
                () -> assertTrue(run.getStageSince() > 0));
    }

    /// Token usage lands on the run AND on the unit being improved. Every completion counts,
    /// including retries and repairs: the cost of a unit is everything spent getting there.
    @Test
    void tokensAccumulateOnTheRunAndOnTheUnit() {
        String key = "src/main/java/org/json/XML.java::toJSONObject";
        store.startRun(newRun("run-1", REPO));
        store.upsertUnit("run-1", key, null);

        store.addTokens("run-1", key, 1000, 200);
        store.addTokens("run-1", key, 500, 100);
        reload();

        Run run = store.currentRun().orElseThrow();
        Unit unit = store.unit("run-1", key).orElseThrow();
        assertAll(
                () -> assertEquals(1500, run.getTokensIn()),
                () -> assertEquals(300, run.getTokensOut()),
                () -> assertEquals(2, run.getLlmCalls()),
                () -> assertEquals(1500, unit.getTokensIn()),
                () -> assertEquals(2, unit.getLlmCalls()));
    }

    /// No unit is being improved yet — setup, or a call between units. The run's total still has
    /// to move, because the tokens were still spent.
    @Test
    void tokensWithNoCurrentUnitStillCountOnTheRun() {
        store.startRun(newRun("run-1", REPO));
        store.addTokens("run-1", null, 700, 70);
        reload();

        assertEquals(700, store.currentRun().orElseThrow().getTokensIn());
    }

    /// `state.prs` — what `GET /api/state` serves and the dashboard renders. Every field of
    /// `Pr.Record` survives, and exactly one of `url` and `patchPath` is set.
    @Test
    void prsKeepTheirShapeAndScope() {
        store.startRun(newRun("run-1", REPO));
        store.addPr(new PrRow("run-1", "a.java::x", "tests/improve-a", "Add tests for A", "body text",
                List.of("tests", "automated"), "local", null, "/data/prs/a.patch", 1_700_000_000_000L));
        store.addPr(new PrRow("run-2", "b.java::y", "tests/improve-b", "Add tests for B", "body",
                List.of(), "github", "https://github.com/o/r/pull/9", null, 1_700_000_001_000L));
        reload();

        List<Map<String, Object>> mine = store.prsAsMaps("run-1");
        assertAll(
                () -> assertEquals(1, mine.size(), "another run's PR is in this run's list"),
                () -> assertEquals("a.java::x", mine.get(0).get("file")),
                () -> assertEquals("Add tests for A", mine.get(0).get("title")),
                () -> assertEquals(List.of("tests", "automated"), mine.get(0).get("labels")),
                () -> assertEquals("local", mine.get(0).get("mode")),
                () -> assertNull(mine.get(0).get("url"), "a prepared PR must not look like an opened one"),
                () -> assertEquals("/data/prs/a.patch", mine.get(0).get("patchPath")),
                () -> assertEquals(1_700_000_000_000L, mine.get(0).get("createdAt")));
    }

    /// The LLM budget's learned ceilings are NOT a run's: a stage that once ran out of room
    /// should start wider next time instead of paying for the same truncated call again.
    @Test
    void theLearnedBudgetSurvivesARunBoundary() {
        store.saveLlmBudget(new LinkedHashMap<>(Map.of("learned", Map.of("write_test", 8000))));
        store.startRun(newRun("run-1", REPO));
        store.startRun(newRun("run-2", REPO));
        reload();

        assertEquals(Map.of("write_test", 8000), store.llmBudget().get("learned"));
    }
}
