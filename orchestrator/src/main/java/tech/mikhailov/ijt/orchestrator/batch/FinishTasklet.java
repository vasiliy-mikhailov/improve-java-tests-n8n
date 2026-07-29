package tech.mikhailov.ijt.orchestrator.batch;

import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

/// `POST /api/run/finish`, once.
///
/// Its own step rather than a tail on {@link ImproveTasklet} for the reason the workflow gives it
/// its own node: it is reached from THREE places — the candidate queue running out, a terminal
/// pick failure, and the iteration cap — and it must run exactly once whichever way the unit loop
/// ended. As a step it also runs after a RESTART whose improveStep completed, which a tail inside
/// the repeat would not.
///
/// What it does is not bookkeeping: it stamps the run's status and finish time, sets the stage a
/// human reads to `done`, and resets the checkout to the base branch — swallowing a failure to do
/// so, because the run is over either way and a checkout that will not reset must not turn a
/// finished run into a failed one.
public final class FinishTasklet implements Tasklet {

    private final Backend backend;

    public FinishTasklet(Backend backend) {
        this.backend = backend;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        backend.finishRun();
        return RepeatStatus.FINISHED;
    }
}
