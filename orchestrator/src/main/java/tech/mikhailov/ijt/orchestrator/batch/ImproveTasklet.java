package tech.mikhailov.ijt.orchestrator.batch;

import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

import java.util.Map;

import static tech.mikhailov.ijt.orchestrator.batch.Wire.map;
import static tech.mikhailov.ijt.orchestrator.batch.Wire.orEmpty;
import static tech.mikhailov.ijt.orchestrator.batch.Wire.text;
import static tech.mikhailov.ijt.orchestrator.batch.Wire.truthy;

/// ONE UNIT per invocation, and Batch repeats it.
///
///     candidates -> more work? --no--> finish
///       -> rules(pick_file) -> picked? --no--> retryable? -> loop | finish
///       -> start iteration -> baseline mutation -> has mutants? --no--> loop
///       -> ROUND LOOP
///       -> drop last round -> rules(check_changes) -> approved?
///            yes -> cleanup -> rules(make_pr) -> create PR
///            no  -> discard
///       -> loop
///
/// **Why `CONTINUABLE` and not a chunk-oriented step, or a flow of deciders.**
///
/// `RepeatStatus.CONTINUABLE` makes Batch call this method again inside the same step, and — the
/// part that matters — commit between calls. A unit is therefore a transaction boundary and a
/// checkpoint, which buys the one thing Batch is here for: a killed or crashed run resumes at the
/// NEXT unit instead of re-cloning and re-measuring from zero. Restart granularity is a unit
/// because that is where the natural checkpoint already is — the ledgers record final
/// dispositions per unit, and a half-finished unit's work is discarded by design anyway.
///
/// The ROUND loop inside stays imperative Java. Expressed as `JobExecutionDecider`s it would be
/// eleven beans and a flow definition to say `while (continueRounds)`, and less readable than the
/// n8n graph it replaces — which is a low bar to miss. Batch is good at persistence, restart and
/// per-step metrics; it is bad at tight nested loops with rich state, and this is one.
///
/// **Why the round loop may be `while (true)`.** Its exit is `continueRounds`, decided by
/// `Rounds.decide` on the round budget, the unit's time budget, whether any survivor is left
/// un-attempted, and the consecutive-miss count. The one time this loop did not terminate,
/// `/api/round/miss` was echoing the PREVIOUS round's answer — a verify that failed to measure
/// sent back a stale `true` and the pipeline spent a suite, a JaCoCo run and a PIT run per cycle
/// with nothing advancing. That was fixed in the route, which now decides afresh. A belt-and-
/// braces cap here would hide a recurrence rather than prevent one, and would silently truncate
/// the documented `MAX_ROUNDS_PER_FILE=0` (uncapped) setting the deployment actually runs.
public final class ImproveTasklet implements Tasklet {

    /// What the dashboard's stage names call the discard reason when the rule gives none.
    private static final String NOT_APPROVED = "not approved";

    private final Backend backend;

    public ImproveTasklet(Backend backend) {
        this.backend = backend;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Map<String, Object> candidates = backend.candidates();

        // More Work? -- no. `done` covers all four terminations the route knows about: the
        // iteration cap, the scope limit, too many units that failed to MEASURE (a broken
        // toolchain is not a verdict on the units), and an empty queue.
        if (truthy(candidates.get("done"))) {
            return RepeatStatus.FINISHED;
        }

        Map<String, Object> pick = backend.applyRules("pick_file", null);
        Map<String, Object> picked = map(pick.get("result"));

        // File Picked? -- no.
        if (picked == null || !truthy(picked.get("file"))) {
            // Pick Retryable? Transient (the model returned something unusable) -> pick again;
            // terminal (the team rule excludes every remaining candidate) -> the batch is over.
            // The two are not interchangeable: retrying a terminal answer loops for ever, and
            // finishing on a transient one abandons a repo over one bad completion. The route
            // caps consecutive transient retries, which is what stops the first case here.
            if (picked != null && truthy(picked.get("retry"))) {
                return RepeatStatus.CONTINUABLE;
            }
            return RepeatStatus.FINISHED;
        }

        Map<String, Object> started = backend.startIteration(text(picked.get("file")));

        // The unit key every later call uses is the one `Start Iteration` echoes, not the one the
        // pick proposed. Every downstream node in the workflow reads
        // `$('Start Iteration').first().json.file` for the same reason: one spelling of the unit,
        // fixed at the point the branch was created for it.
        String unit = text(started.get("file"));

        // Remembered for the operator reading a crashed run: the step's execution context is
        // persisted at each commit, so this survives the kill that ended the unit.
        chunkContext.getStepContext().getStepExecution().getExecutionContext().putString("lastUnit", unit);

        Map<String, Object> baseline = backend.baselineMutation(unit);

        // Has Mutants? -- no. A class PIT finds (almost) nothing to mutate in — an annotation, a
        // marker interface, a constants holder — cannot be improved, so skip it without spending
        // the round budget. `skip` also covers UNMEASURED, which is settled by the route without
        // recording a zero: absent is not zero, and reporting it as zero flatters every later
        // improvement.
        //
        // A PIT run that THREW answers `failed` and no `skip`, and the workflow carries on into
        // the phases below. That is deliberate and is reproduced rather than corrected here: the
        // unit is already marked failed and its ledger written, and the phases will find nothing
        // to ask for and cost a prompt build each.
        if (truthy(baseline.get("skip"))) {
            return RepeatStatus.CONTINUABLE;
        }

        // ── ROUND LOOP ────────────────────────────────────────────────────────
        while (true) {
            backend.coverageGaps(unit);

            boolean wroteCoverage = Phase.COVERAGE.run(backend, unit);
            boolean wroteMutation = Phase.MUTATION.run(backend, unit);

            // Nothing went into the tree, so there is nothing new to measure.
            //
            // `verify` is the most expensive call in the loop — a full JaCoCo build and a PIT
            // run. Against an unchanged tree it re-measures the same numbers, reports no
            // improvement, and the round is booked as a miss. Observed on the very first unit
            // of a from-scratch run: round 2 of JSONArray#getNumber asked for 0 targets
            // (every surviving line already had a named test) and still paid the verify.
            //
            // Breaking here rather than calling missRound is deliberate: no round happened.
            // Booking a miss would count this against the unit's miss budget and inflate its
            // spentSec with work it did not do. The code below the loop still runs, so the
            // unit's accepted rounds are dropped-to-committed and its PR is prepared normally.
            if (!wroteCoverage && !wroteMutation) {
                break;
            }

            Map<String, Object> verified = backend.verify(unit);

            // Keep Round? Keep it iff at least one of coverage/mutation/MAC improved AND none
            // degraded. A miss drops the ROUND, never the unit — every accepted round before it
            // stays committed on the branch.
            Map<String, Object> round = truthy(verified.get("keepRound"))
                    ? backend.acceptRound(unit)
                    : backend.missRound(unit);

            // Another Round? -- read off accept/miss, NOT off verify. Keeping the two questions
            // apart is what makes a kept-but-final round committed rather than dropped: `verify`
            // says whether this round earned its place, and accept/miss says whether a FURTHER
            // round is worth its machine time.
            if (!truthy(round.get("continueRounds"))) {
                break;
            }
        }

        Map<String, Object> dropped = backend.dropLastRound(unit);

        Map<String, Object> checked = backend.applyRules("check_changes", dropped);
        Map<String, Object> verdict = map(checked.get("result"));

        // Approved? Both halves. The rule may approve a diff that improved nothing measurable —
        // `improved` is the unit's own MAC delta against a branch that actually changed test
        // files — and a PR for an unchanged score is noise a reviewer pays for.
        boolean approved = verdict != null && truthy(verdict.get("approved")) && truthy(dropped.get("improved"));

        if (approved) {
            // Cleanup BEFORE make_pr: the rule composes the PR from the diff, and the tidy-up
            // changes that diff. It is verified — suite green and mutation score held — or
            // reverted inside the route.
            backend.cleanupTests(unit);

            // Context is the DROP result, not the check result: the PR is described by the unit's
            // final numbers.
            Map<String, Object> composed = orEmpty(map(backend.applyRules("make_pr", dropped).get("result")));
            backend.createPr(unit, composed.get("title"), composed.get("body"), composed.get("labels"));
        } else {
            String reason = verdict != null && truthy(verdict.get("reason"))
                    ? text(verdict.get("reason"))
                    : NOT_APPROVED;
            backend.discardChanges(unit, reason);
        }

        // One unit, settled. The count is the step metric worth having: `writeCount` is units
        // carried to a disposition, and `commitCount` is invocations — they differ by the picks
        // that retried and the classes with nothing to mutate, which is exactly the difference an
        // operator wants to see.
        contribution.incrementWriteCount(1);

        // Iteration Done -> Next Iteration.
        return RepeatStatus.CONTINUABLE;
    }
}
