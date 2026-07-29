package tech.mikhailov.ijt.orchestrator.batch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The job's shape, and the one property it exists for.
///
/// Three steps, in order; `improveStep` repeated once per unit and committing between them; and a
/// killed run resuming at the NEXT unit rather than re-cloning and re-measuring from zero. That
/// last one is the entire justification for Spring Batch being here at all — if it does not hold,
/// the job repository, the schema and the restart semantics are ceremony.
@SpringJUnitConfig(ImproveJobTest.TestConfig.class)
@SpringBatchTest
class ImproveJobTest {

    @Configuration
    @EnableBatchProcessing
    @Import(ImproveJobConfig.class)
    static class TestConfig {

        /// `ImproveJobConfig` reads its two settings with `@Value`, and Boot registers one of
        /// these for the application. A plain test context does not, and an unresolved
        /// `${…:-1}` reaches the `int` parameter as a literal and fails the context with a
        /// NumberFormatException that says nothing about placeholders.
        @Bean
        static PropertySourcesPlaceholderConfigurer placeholders() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        /// Named `dataSource` and `transactionManager` deliberately: `@EnableBatchProcessing`
        /// resolves both BY NAME (`dataSourceRef` / `transactionManagerRef` default to exactly
        /// these), and a rename here fails the context, not the test.
        @Bean
        DataSource dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .addScript("classpath:org/springframework/batch/core/schema-h2.sql")
                    .generateUniqueName(true)
                    .build();
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        FakeBackend backend() {
            return new FakeBackend();
        }
    }

    @Autowired
    private JobLauncherTestUtils jobs;

    @Autowired
    private FakeBackend backend;

    @BeforeEach
    void reset() {
        backend.reset();
    }

    /// Required by `@SpringBatchTest`, and not optional however unused step scope is here.
    ///
    /// `StepScopeTestExecutionListener` runs before EVERY test method and hunts this class for a
    /// method whose RETURN TYPE is `StepExecution` — the name only breaks ties, in favour of
    /// exactly this one. Without it the listener finds the two-argument `stepExecution(execution,
    /// name)` helper below, invokes it with no arguments, and every test in the class errors with
    /// "Could not create step execution from method: stepExecution" before its body runs.
    ///
    /// The value returned is what the listener would have defaulted to on its own, so this states
    /// the default rather than changing it.
    StepExecution getStepExecution() {
        return MetaDataInstanceFactory.createStepExecution();
    }

    @Test
    void the_job_is_setup_then_improve_then_finish() throws Exception {
        backend.queue.addAll(List.of("src/main/java/A.java::one", "src/main/java/B.java::two"));

        JobExecution execution = jobs.launchJob(parameters("shape"));

        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
        assertEquals(List.of("setupStep", "improveStep", "finishStep"), stepNames(execution));
    }

    @Test
    void setup_runs_the_spine_once_and_in_order() throws Exception {
        backend.queue.add("src/main/java/A.java::one");

        jobs.launchJob(parameters("spine"));

        // clone -> rules(post_clone) -> build+detect -> baseline coverage -> rules(pre_pick)
        // -> rules(write_test), and nothing between them.
        assertEquals(List.of("cloneRepo", "applyRules(post_clone)", "prepare", "baselineCoverage",
                        "applyRules(pre_pick)", "applyRules(write_test)"),
                backend.calls.subList(0, 6));
        assertEquals(1L, backend.callCount("baselineCoverage"));
        assertEquals(1L, backend.callCount("finishRun"));
    }

    @Test
    void improveStep_handles_exactly_one_unit_per_invocation() throws Exception {
        backend.queue.addAll(List.of("A.java::one", "B.java::two", "C.java::three"));

        JobExecution execution = jobs.launchJob(parameters("one-per-invocation"));
        StepExecution improve = stepExecution(execution, "improveStep");

        // Three units settled, in one step execution: CONTINUABLE is what makes Batch call the
        // tasklet again instead of ending the step.
        assertEquals(3L, improve.getWriteCount());
        assertEquals(3L, backend.callCount("startIteration"));

        // Four invocations: three units and the one that read `done` and stopped. Each invocation
        // asks the candidate queue exactly once — the unit loop's first node.
        assertEquals(4L, backend.callCount("candidates"));

        // And each of them committed. That is what makes a unit a checkpoint rather than a
        // bookmark: a kill between units cannot lose one that is already settled.
        assertTrue(improve.getCommitCount() >= 3,
                "expected at least one commit per unit, got " + improve.getCommitCount());
    }

    @Test
    void a_killed_run_resumes_at_the_next_unit_without_re_cloning() throws Exception {
        backend.queue.addAll(List.of("A.java::one", "B.java::two", "C.java::three"));
        backend.failOnUnit = "B.java::two";

        JobParameters parameters = parameters("restart");
        JobExecution killed = jobs.launchJob(parameters);

        assertEquals(BatchStatus.FAILED, killed.getStatus());
        assertEquals(List.of("setupStep", "improveStep"), stepNames(killed));
        assertEquals(1L, stepExecution(killed, "improveStep").getWriteCount(), "the first unit settled");

        // The crash is over; the same job instance is launched again.
        backend.failOnUnit = null;
        JobExecution resumed = jobs.launchJob(parameters);

        assertEquals(BatchStatus.COMPLETED, resumed.getStatus());

        // setupStep is COMPLETED and therefore skipped — no second clone, no second forty-minute
        // baseline measurement. This is the whole reason the job is a Batch job.
        assertEquals(List.of("improveStep", "finishStep"), stepNames(resumed));
        assertEquals(1L, backend.callCount("cloneRepo"));
        assertEquals(1L, backend.callCount("baselineCoverage"));

        // And it resumed at the NEXT unit: the one that was in flight when the run died is not
        // re-attempted, which matches what the ledgers record for it.
        assertEquals(List.of("A.java::one", "B.java::two", "C.java::three"),
                backend.calls.stream()
                        .filter(c -> c.startsWith("startIteration("))
                        .map(c -> c.substring("startIteration(".length(), c.length() - 1))
                        .toList());
        // one unit settled in the second execution — the third — and the second is not retried
        assertEquals(1L, stepExecution(resumed, "improveStep").getWriteCount());
    }

    @Test
    void the_step_records_the_unit_it_was_last_working_on() throws Exception {
        backend.queue.addAll(List.of("A.java::one", "B.java::two"));

        JobExecution execution = jobs.launchJob(parameters("last-unit"));

        assertEquals("B.java::two",
                stepExecution(execution, "improveStep").getExecutionContext().getString("lastUnit"));
    }

    @Test
    void improveStep_alone_drains_the_queue_in_a_single_step_execution() throws Exception {
        backend.queue.addAll(List.of("A.java::one", "B.java::two", "C.java::three", "D.java::four"));

        JobExecution execution = jobs.launchStep("improveStep");

        assertEquals(1, execution.getStepExecutions().size());
        assertEquals(4L, stepExecution(execution, "improveStep").getWriteCount());
        // no setup, no finish: the step is the unit loop and nothing else
        assertEquals(0L, backend.callCount("cloneRepo"));
        assertEquals(0L, backend.callCount("finishRun"));
    }

    @Test
    void a_run_with_nothing_to_do_still_finishes() throws Exception {
        JobExecution execution = jobs.launchJob(parameters("empty"));

        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
        assertEquals(0L, stepExecution(execution, "improveStep").getWriteCount());
        // More Work? -- no, on the first ask. The pick is never made.
        assertEquals(0L, backend.callCount("applyRules(pick_file)"));
        assertEquals(1L, backend.callCount("finishRun"));
    }

    /// Identical parameters are what a RESTART is; a `JobParametersIncrementer` would make every
    /// launch a new instance and re-run setup, which is why the job does not have one.
    private static JobParameters parameters(String name) {
        return new JobParametersBuilder()
                .addString("repoUrl", "https://example.invalid/" + name + ".git")
                .toJobParameters();
    }

    private static List<String> stepNames(JobExecution execution) {
        return execution.getStepExecutions().stream().map(StepExecution::getStepName).toList();
    }

    private static StepExecution stepExecution(JobExecution execution, String name) {
        return execution.getStepExecutions().stream()
                .filter(s -> s.getStepName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no step execution named " + name
                        + " in " + stepNames(execution)));
    }
}
