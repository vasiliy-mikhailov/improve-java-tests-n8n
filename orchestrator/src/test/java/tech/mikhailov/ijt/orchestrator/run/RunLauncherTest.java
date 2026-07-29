package tech.mikhailov.ijt.orchestrator.run;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.databind.ObjectMapper;

import tech.mikhailov.ijt.State;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// What the trigger does with the execution table it finds.
///
/// The domain layer is real here, not stubbed: `POST /api/run/start` is the one thing the
/// trigger must call and the one thing a restart must NOT call, and both facts are only
/// checkable against the store it writes to. `State.DATA_DIR` is redirected at a temp directory
/// for the same reason the JS tests set `process.env.DATA_DIR` before requiring state.js.
class RunLauncherTest {

    private static final String JOB = "improveJob";

    private Path previousDataDir;
    private Job job;
    private JobExplorer explorer;
    private JobRepository repository;
    private JobLauncher jobLauncher;
    private RunParameters parameters;
    private RunLauncher launcher;

    /// Every set of parameters a launch was actually attempted with.
    private final List<JobParameters> launched = new ArrayList<>();

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        previousDataDir = State.DATA_DIR;
        State.DATA_DIR = tmp;
        // a fresh JVM's store, so one test's run is not the next test's "already active"
        synchronized (State.STATE) {
            State.STATE.run = null;
        }
        launched.clear();

        job = mock(Job.class);
        when(job.getName()).thenReturn(JOB);
        explorer = mock(JobExplorer.class);
        repository = mock(JobRepository.class);
        jobLauncher = mock(JobLauncher.class);
        when(jobLauncher.run(eq(job), any())).thenAnswer(call -> {
            JobParameters params = call.getArgument(1);
            launched.add(params);
            return new JobExecution(new JobInstance(7L, JOB), 11L, params);
        });
        parameters = new RunParameters(new ObjectMapper());
        // The takeover waiter runs INLINE: the production one is a thread that sleeps until the
        // stopped run ends, and a test that waited for it would be asserting on a clock.
        TaskExecutor inline = Runnable::run;
        launcher = new RunLauncher(job, explorer, repository, parameters, jobLauncher, inline,
                Duration.ofSeconds(5), Duration.ofMillis(1));
    }

    @AfterEach
    void tearDown() {
        State.DATA_DIR = previousDataDir;
        synchronized (State.STATE) {
            State.STATE.run = null;
        }
    }

    @Test
    void aFirstTriggerStartsARunAndLaunchesTheJob() {
        RunLauncher.Launched result = launcher.launch(Map.of("scopeLimit", 1));

        assertEquals(1, launched.size());
        assertNotNull(launched.get(0).getString(RunParameters.OVERRIDES));
        assertNotNull(launched.get(0).getLong(RunParameters.STARTED_AT));
        assertEquals(11L, result.jobExecutionId().longValue());
        assertEquals(7L, result.jobInstanceId().longValue());
        assertFalse(result.resumed());
        assertFalse(result.queued());
        // the run exists BEFORE the job does — `Backend` deliberately has no run/start, because
        // the job must never be the thing that creates (or resets) a run
        assertNotNull(State.STATE.run);
        assertEquals(State.STATE.run.id, result.runId());
        assertEquals(1, State.STATE.run.config.scopeLimit().intValue());
    }

    @Test
    void aCrashedRunIsResumedWithTheParametersItStartedWith() {
        // The payoff Batch is here for: the setup step already completed, so a resume skips the
        // clone, the build and a baseline measurement that takes 40 minutes on a real repo.
        Map<String, Object> body = Map.of("scopeLimit", 25);
        JobParameters original = parameters.forNewRun(body, 1234L);
        JobInstance instance = new JobInstance(3L, JOB);
        JobExecution crashed = new JobExecution(instance, 9L, original);
        crashed.setStatus(BatchStatus.FAILED);
        when(explorer.getJobInstances(JOB, 0, 1)).thenReturn(List.of(instance));
        when(explorer.getJobExecutions(instance)).thenReturn(List.of(crashed));

        RunLauncher.Launched result = launcher.launch(body);

        assertEquals(List.of(original), launched);
        assertTrue(result.resumed());
        // AND NO NEW RUN. run/start clears state.files, the decisions and the event log — the
        // candidate units that survived the crash are exactly what the resume is for.
        assertNull(State.STATE.run);
    }

    @Test
    void aStoppedRunIsNotResumed() {
        // Stopping means "end this run". Resuming it would find `processed >= scopeLimit`
        // already true and finish having done nothing — a batch driver that stops one batch and
        // triggers the next would then loop over no-op batches for as long as it is left running.
        Map<String, Object> body = Map.of("scopeLimit", 25);
        JobParameters original = parameters.forNewRun(body, 1234L);
        JobInstance instance = new JobInstance(3L, JOB);
        JobExecution stopped = new JobExecution(instance, 9L, original);
        stopped.setStatus(BatchStatus.STOPPED);
        when(explorer.getJobInstances(JOB, 0, 1)).thenReturn(List.of(instance));
        when(explorer.getJobExecutions(instance)).thenReturn(List.of(stopped));

        RunLauncher.Launched result = launcher.launch(body);

        assertFalse(result.resumed());
        assertEquals(1, launched.size());
        assertNotEqualsParameters(original, launched.get(0));
        assertNotNull(State.STATE.run);
    }

    @Test
    void aTriggerCarryingDifferentSettingsIsANewRun() {
        // Resuming here would run the OLD configuration while answering the caller about the new
        // one. Different body, different intent, new instance.
        JobParameters original = parameters.forNewRun(Map.of("scopeLimit", 25), 1234L);
        JobInstance instance = new JobInstance(3L, JOB);
        JobExecution crashed = new JobExecution(instance, 9L, original);
        crashed.setStatus(BatchStatus.FAILED);
        when(explorer.getJobInstances(JOB, 0, 1)).thenReturn(List.of(instance));
        when(explorer.getJobExecutions(instance)).thenReturn(List.of(crashed));

        RunLauncher.Launched result = launcher.launch(Map.of("scopeLimit", 1));

        assertFalse(result.resumed());
        assertNotEqualsParameters(original, launched.get(0));
    }

    @Test
    void aSecondTriggerWhileARunIsExecutingIsRefused() {
        // Two executions in ONE process means two threads in one git worktree and one state
        // store. n8n allowed the concurrency and left the refusal to the sidecar's 409; here the
        // refusal has to be in front of the launch.
        when(explorer.findRunningJobExecutions(JOB)).thenReturn(Set.of(running(5L)));

        RunFailure refused = assertThrows(RunFailure.class, () -> launcher.launch(Map.of()));

        assertEquals(HttpStatus.CONFLICT, refused.status());
        assertTrue(refused.getMessage().contains("force"), refused.getMessage());
        assertTrue(launched.isEmpty());
        assertNull(State.STATE.run);
    }

    @Test
    void forceStopsTheRunningExecutionAndLaunchesOnceItHasEnded() {
        // What `eval/full-run.mjs` does between batches, and why it passes force: the driver owns
        // the lifecycle. The launch waits for the stop to land — a stop is noticed at a UNIT
        // boundary, so the incumbent can still be inside a PIT run for another hour.
        JobExecution incumbent = running(5L);
        when(explorer.findRunningJobExecutions(JOB))
                .thenReturn(Set.of(incumbent), Set.of(incumbent), Set.of());

        RunLauncher.Launched result = launcher.launch(Map.of("force", true));

        assertTrue(result.queued());
        assertEquals(List.of(5L), result.stopping());
        assertNull(result.jobExecutionId());          // nothing to watch yet — it has not started
        assertEquals(BatchStatus.STOPPING, incumbent.getStatus());
        verify(repository).update(incumbent);
        assertEquals(1, launched.size());             // and then it did start
    }

    @Test
    void aStopIsAskedForOncePerRunningExecution() {
        JobExecution one = running(5L);
        when(explorer.findRunningJobExecutions(JOB)).thenReturn(Set.of(one));

        assertEquals(List.of(5L), launcher.stop(null));
        assertEquals(BatchStatus.STOPPING, one.getStatus());
        verify(repository).update(one);
    }

    @Test
    void stoppingNothingIsNotAnError() {
        // The driver calls this before every batch, and most of the time there is nothing to
        // stop. n8n's loop over zero running executions did nothing, quietly.
        assertEquals(List.of(), launcher.stop(null));
        assertEquals(List.of(), launcher.stop(42L));
    }

    @Test
    void oneExecutionCanBeStoppedByName() {
        JobExecution one = running(5L);
        when(explorer.findRunningJobExecutions(JOB)).thenReturn(Set.of(one));

        assertEquals(List.of(), launcher.stop(6L));
        assertEquals(BatchStatus.STARTED, one.getStatus());
        verify(repository, never()).update(one);
    }

    @Test
    void anExecutionLeftRunningByADeadProcessIsMarkedFailedAtBoot() {
        // One container owns /data, so a STARTED execution found at boot has no JVM behind it.
        // Left alone it refuses every later trigger ("a run is already executing") AND can never
        // be restarted, because Batch will not create an execution while the last one claims to
        // be running — the crash recovery and the refusal deadlock each other.
        JobExecution crashed = new JobExecution(new JobInstance(1L, JOB), 4L, new JobParameters());
        crashed.setStatus(BatchStatus.STARTED);
        StepExecution step = crashed.createStepExecution("improveStep");
        step.setStatus(BatchStatus.STARTED);
        when(explorer.findRunningJobExecutions(JOB)).thenReturn(Set.of(crashed));

        launcher.reconcileCrashedExecutions();

        // FAILED, not ABANDONED: failed is restartable, and restarting at the next unit is the
        // whole reason this job is a Batch job.
        assertEquals(BatchStatus.FAILED, crashed.getStatus());
        assertNotNull(crashed.getEndTime());
        assertEquals(BatchStatus.FAILED, step.getStatus());
        assertNotNull(step.getEndTime());
        verify(repository).update(step);
        verify(repository).update(crashed);
    }

    private JobExecution running(long id) {
        JobExecution execution = new JobExecution(new JobInstance(1L, JOB), id, new JobParameters());
        execution.setStatus(BatchStatus.STARTED);
        return execution;
    }

    private static void assertNotEqualsParameters(JobParameters unwanted, JobParameters actual) {
        assertFalse(unwanted.equals(actual), "expected a NEW job instance, got the previous one's parameters");
    }
}
