package tech.mikhailov.ijt.orchestrator.batch;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/// The job that replaces the 66-node workflow.
///
///     improveJob
///       setupStep    Tasklet, once
///       improveStep  Tasklet returning CONTINUABLE -- ONE UNIT per invocation
///       finishStep   Tasklet, once
///
/// Three steps and no deciders. Batch earns its place on exactly one thing — a killed run resumes
/// at the next UNIT instead of re-cloning and re-measuring from zero — and that needs a step
/// boundary either side of the unit loop, nothing more. The fifteen decision points stay
/// imperative Java in {@link ImproveTasklet} and {@link Phase}, where they can be read beside the
/// generator they came from.
///
/// **No `JobParametersIncrementer`, deliberately.** One would make every launch a new job
/// instance, and a new instance re-runs `setupStep` — a fresh clone and a forty-minute baseline
/// measurement — which is the precise opposite of why this job is a Batch job. Restart means
/// launching the same instance again: same parameters, completed steps skipped, `improveStep`
/// re-entered at the next unit. The caller that launches this owns that contract, and it must NOT
/// call `POST /api/run/start` on a restart: that resets `state.files`, and the candidate units
/// that survived the crash are what the resume depends on.
@Configuration
public class ImproveJobConfig {

    /// The shared timeout contract.
    ///
    /// @param configuredPath  `ijt.timeouts.file`, or blank to search the usual locations
    /// @param improveStepSeconds `ijt.batch.improve-step-timeout-seconds`, or -1 for no outer
    ///                           clock — the default, and see
    ///                           {@link StepTimeouts#improveStepSeconds()} for why
    @Bean
    public StepTimeouts stepTimeouts(
            @Value("${ijt.timeouts.file:}") String configuredPath,
            @Value("${ijt.batch.improve-step-timeout-seconds:-1}") int improveStepSeconds) {
        return StepTimeouts.load(configuredPath, improveStepSeconds);
    }

    @Bean
    public SetupTasklet setupTasklet(Backend backend) {
        return new SetupTasklet(backend);
    }

    @Bean
    public ImproveTasklet improveTasklet(Backend backend) {
        return new ImproveTasklet(backend);
    }

    @Bean
    public FinishTasklet finishTasklet(Backend backend) {
        return new FinishTasklet(backend);
    }

    /// Clone, rules, build, baseline. `allowStartIfComplete` is left at its default of false and
    /// said out loud, because that default is the restart behaviour this whole design is for: on
    /// a restart a COMPLETED setup is skipped, and the run resumes at the next unit.
    @Bean
    public Step setupStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                          SetupTasklet setupTasklet, StepTimeouts timeouts) {
        return new StepBuilder("setupStep", jobRepository)
                .tasklet(setupTasklet, transactionManager)
                .transactionAttribute(timeouts.attribute(timeouts.setupStepSeconds()))
                .allowStartIfComplete(false)
                .build();
    }

    /// One unit per invocation, repeated by Batch until the tasklet answers FINISHED.
    ///
    /// The transaction attribute is where the 6h47m defect would come back. A tasklet runs inside
    /// this transaction; give it a timeout at or below what a unit's subprocesses may legitimately
    /// take and the step fails while Maven, Surefire and the PIT minions carry on. The default is
    /// no outer clock at all — {@link StepTimeouts#improveStepSeconds()} explains why that is the
    /// honest answer for a unit whose bound is the run's own per-unit budget — and any configured
    /// value is refused at startup unless it exceeds one round's worth of ceilings.
    @Bean
    public Step improveStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                            ImproveTasklet improveTasklet, StepTimeouts timeouts) {
        return new StepBuilder("improveStep", jobRepository)
                .tasklet(improveTasklet, transactionManager)
                .transactionAttribute(timeouts.attribute(timeouts.improveStepSeconds()))
                .allowStartIfComplete(false)
                .build();
    }

    /// Reached however the unit loop ended — queue empty, terminal pick failure, or a cap — and
    /// exactly once.
    @Bean
    public Step finishStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                           FinishTasklet finishTasklet, StepTimeouts timeouts) {
        return new StepBuilder("finishStep", jobRepository)
                .tasklet(finishTasklet, transactionManager)
                .transactionAttribute(timeouts.attribute(timeouts.finishStepSeconds()))
                .allowStartIfComplete(false)
                .build();
    }

    @Bean
    public RunOutcomeListener runOutcomeListener(Backend backend) {
        return new RunOutcomeListener(backend);
    }

    @Bean
    public Job improveJob(JobRepository jobRepository, Step setupStep, Step improveStep, Step finishStep,
                          RunOutcomeListener runOutcomeListener) {
        return new JobBuilder("improveJob", jobRepository)
                .start(setupStep)
                .next(improveStep)
                .next(finishStep)
                // Registered on the JOB and not on a step: a step that never starts because an
                // earlier one failed has no listener to fire, and the run still needs marking.
                .listener(runOutcomeListener)
                .build();
    }
}
