package tech.mikhailov.ijt.orchestrator.batch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static tech.mikhailov.ijt.orchestrator.batch.Wire.count;
import static tech.mikhailov.ijt.orchestrator.batch.Wire.map;
import static tech.mikhailov.ijt.orchestrator.batch.Wire.orEmpty;
import static tech.mikhailov.ijt.orchestrator.batch.Wire.strings;
import static tech.mikhailov.ijt.orchestrator.batch.Wire.text;
import static tech.mikhailov.ijt.orchestrator.batch.Wire.truthy;

/// One improvement phase, and both instances of it.
///
/// The workflow generator builds these twice from one `phase(prefix, promptEndpoint, entryNode)`
/// function — thirteen nodes each, identical but for the prompt route and the stage name. An
/// enum with one method is the same generic-over-prefix shape, and it keeps the two from
/// drifting the way two hand-copied subgraphs would.
///
///     build prompt -> has work? --no--> done
///       -> llm -> parse -> write -> run tests -> green? --yes--> done
///       -> wrote any? --no--> done
///       -> build repair -> llm -> parse -> write -> re-run -> green after repair? --yes--> done
///       -> delete broken tests -> done
///
/// Four decision points, and each `done` is a return: in n8n they all converge on one NoOp whose
/// only successor is the next phase.
public enum Phase {

    /// Only for a method NOTHING executes: once anything runs it, killing NO_COVERAGE mutants
    /// drags coverage along anyway, and `Prompts.coveragePrompt` decides that and says so — which
    /// is why this phase usually answers `skip` and costs nothing.
    COVERAGE("POST /api/prompt/coverage", "improving_coverage"),

    /// `/api/prompt/batch`, NOT `/api/prompt/mutation`. One call for every surviving LINE of the
    /// class and then ONE measurement, instead of one mutant per round with a PIT run either
    /// side: that older loop spent 609 PIT runs across 22 classes on a single JSON-java run, 43%
    /// of its wall clock, mostly re-measuring code that had not changed. The route is still
    /// registered and is not what this pipeline calls.
    MUTATION("POST /api/prompt/batch", "improving_mutation");

    private final String promptRoute;
    private final String stage;

    Phase(String promptRoute, String stage) {
        this.promptRoute = promptRoute;
        this.stage = stage;
    }

    /// The route `Build Prompt` posts to for this phase.
    public String promptRoute() {
        return promptRoute;
    }

    /// The stage this phase's writes and test runs are attributed to.
    public String stage() {
        return stage;
    }

    /// Run the phase for one unit. Returns when the phase's `Done` node would be reached; every
    /// exit is a `return`, and there are five of them, exactly as there are five edges into
    /// `Done`.
    void run(Backend backend, String unit) {
        Map<String, Object> ask = backend.buildPrompt(this, unit);

        // Has Work? -- no. The prompt builders answer `skip` when there is nothing to ask for:
        // the method is fully covered, it is unreachable from any public entry point, every
        // surviving line already has a test named for it. Asking anyway costs a model call and
        // produces a test for a mutant that is already dead.
        if (truthy(ask.get("skip"))) {
            return;
        }

        Map<String, Object> answer = backend.chat(ask);
        Map<String, Object> parsed = backend.parseTests(answer, ask);

        // The model's own account of which mutant it chose, shown on the dashboard because the
        // choice drives the whole round. `chosen` absent -> no note and no target, which is what
        // `$json.chosen ? … : null` says.
        Map<String, Object> chosen = map(parsed.get("chosen"));
        String note = chosen == null ? null
                : "targeting " + text(chosen.get("mutator")) + " at line " + text(chosen.get("line"));
        backend.writeTests(parsed.get("tests"), stage, note, chosen);

        Map<String, Object> ran = backend.runTests(stage);

        // Green? -- yes. Done: the round's test compiles and the suite still passes, and whether
        // it killed anything is verification's question, not this phase's.
        if (truthy(ran.get("passed"))) {
            return;
        }

        // Wrote Any? -- no. The suite is red and this phase wrote nothing, so it did not break
        // it. Repairing a file we did not write would hand the model somebody else's failure.
        if (!(count(parsed.get("count")) > 0)) {
            return;
        }

        Map<String, Object> repairAsk = backend.buildRepair(unit, stage, ran.get("summary"), parsed.get("tests"));
        Map<String, Object> repairAnswer = backend.chat(repairAsk);
        Map<String, Object> repaired = backend.parseRepair(repairAnswer, parsed);

        // No stage, no note, no target: the repair rewrites files it was handed. See
        // Backend#writeTests.
        backend.writeTests(repaired.get("tests"), null, null, null);

        // and no stage on the re-run either -- the workflow sends `{}`.
        Map<String, Object> reran = backend.runTests(null);

        // Green After Repair? -- yes.
        if (truthy(reran.get("passed"))) {
            return;
        }

        // Both lists, concatenated. The repair writes to the paths generation used AND may add
        // its own; dropping either half leaves a red suite behind for the next unit to inherit.
        // The route salvages what it can — a batch round writes N tests into one file, and losing
        // all N to one bad assertion cost the first unit of the v2 run three tests and recorded
        // the round as cov 0->0.
        List<String> paths = new ArrayList<>(strings(orEmpty(parsed).get("paths")));
        paths.addAll(strings(orEmpty(repaired).get("paths")));
        backend.deleteTests(paths);
    }
}
