package tech.mikhailov.ijt.orchestrator.batch;

import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

/// Everything that happens once, before the first unit.
///
///     clone -> rules(post_clone) -> build+detect -> baseline coverage
///           -> rules(pre_pick) -> rules(write_test)
///
/// Straight-line: the workflow's spine has no decision between `Start Run` and `Next Iteration`.
///
/// It is one tasklet rather than six steps because there is nothing to resume between them. A
/// crash here restarts the whole tasklet, and each call is idempotent in the way that matters —
/// the clone fetches and resets an existing checkout instead of re-cloning, `prepare` re-detects,
/// and the baseline coverage run replays the measure ledger, so a repeat costs minutes rather
/// than the forty it costs from cold. Six steps would buy a finer resume point for the one part
/// of the run that is already cheap to redo, and would put five more rows in the job repository
/// per run.
///
/// The step this belongs to is marked non-restartable-if-complete (the Batch default), which is
/// the point: on a restart it is SKIPPED, and the run resumes at the next unit rather than
/// re-cloning and re-measuring from zero.
public final class SetupTasklet implements Tasklet {

    private final Backend backend;

    public SetupTasklet(Backend backend) {
        this.backend = backend;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        backend.cloneRepo();

        // post_clone runs BEFORE the build: a team rule may exclude paths the build would
        // otherwise spend forty minutes measuring.
        backend.applyRules("post_clone", null);

        backend.prepare();

        // Not only a number. This is where scanned FILES become `path::method` units and where
        // the ledgers are replayed against them — in that order, because replaying earlier can
        // only ever match FILE keys and every settled unit comes back as a fresh candidate.
        backend.baselineCoverage();

        // pre_pick can set the branch template, which /api/iteration/start reads on the very
        // first pick; write_test assembles the constraints every later prompt carries. Both must
        // land before the unit loop, not inside it.
        backend.applyRules("pre_pick", null);
        backend.applyRules("write_test", null);

        return RepeatStatus.FINISHED;
    }
}
