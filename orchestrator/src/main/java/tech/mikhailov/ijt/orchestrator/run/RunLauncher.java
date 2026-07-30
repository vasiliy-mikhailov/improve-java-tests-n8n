package tech.mikhailov.ijt.orchestrator.run;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tech.mikhailov.ijt.Server;
import tech.mikhailov.ijt.State;
import tech.mikhailov.ijt.orchestrator.config.IjtProperties;

/// What the n8n trigger did: start a run, and get out of the way.
///
/// The webhook node ran in `responseMode: onReceived` — it answered the moment the execution was
/// created and the work went on without the caller. Everything the batch driver and the eval
/// harness do is built on that: they POST, get a status back in milliseconds, and then watch
/// `/api/metrics` for hours. So the launch here is asynchronous, and the answer carries the ids
/// needed to watch it.
///
/// Three decisions live in this class, and each one is the difference between a working trigger
/// and one that looks fine until the second run:
///
///  1. **One run at a time.** n8n happily ran two executions at once and it was the sidecar's
///     409 that stopped them; here two executions would be two threads in ONE process sharing
///     one git worktree and one state store. So a trigger while a run is executing is refused,
///     and `force:true` — which the batch driver always sends, because it owns the lifecycle —
///     stops the incumbent and takes over when it has actually ended.
///
///  2. **`POST /api/run/start` belongs to the trigger, not to the job.** In the workflow it hung
///     off the trigger rather than the spine, and it must stay there: it resets `state.files`,
///     the event log and the decisions, so a job that called it would erase the very units a
///     restart exists to resume. See `Backend`, which deliberately does not expose it.
///
///  3. **A crashed run resumes; a stopped one does not.** That is the whole reason Spring Batch
///     is here — see {@link #resumableParameters}.
@Service
public class RunLauncher {

    private static final Logger log = LoggerFactory.getLogger(RunLauncher.class);

    /// The job this trigger launches. The design doc names it; the bean is qualified rather than
    /// taken by type so a second `Job` bean added later cannot silently become the one that runs.
    public static final String IMPROVE_JOB = "improveJob";

    /// The route the workflow called before its first node, keyed as `Server.routes()` keys it.
    private static final String RUN_START = "POST /api/run/start";

    private static final String CRASHED =
            "left running by a process that is no longer here — marked failed at boot so it can be restarted";

    private final Job improveJob;
    private final JobExplorer explorer;
    private final JobRepository repository;
    private final RunParameters parameters;
    private final JobLauncher launcher;
    private final TaskExecutor takeoverExecutor;
    private final Duration takeoverWait;
    private final Duration takeoverPoll;

    /// At most one takeover may be waiting. Without this a caller that retries a forced trigger
    /// every few seconds queues a launch per attempt, and they all fire the moment the incumbent
    /// ends — the concurrent runs rule 1 exists to prevent, arriving late.
    private final AtomicBoolean takeoverPending = new AtomicBoolean();

    /// What a trigger did. `queued` is the forced case: the incumbent has been asked to stop and
    /// the launch happens when it ends, so there is no execution id to give back yet.
    public record Launched(Long jobExecutionId, Long jobInstanceId, String runId,
                           boolean resumed, boolean queued, List<Long> stopping) {}

    @Autowired
    public RunLauncher(@Qualifier(IMPROVE_JOB) Job improveJob, JobExplorer explorer,
                       JobRepository repository, RunParameters parameters, IjtProperties properties) {
        this(improveJob, explorer, repository, parameters,
                asyncLauncher(repository), takeoverExecutor(),
                properties.run().takeoverWait(), properties.run().takeoverPoll());
    }

    /// The seams a test replaces: a launcher that does not fork, and an executor that does not
    /// either, so the takeover path can be asserted on instead of waited for.
    RunLauncher(Job improveJob, JobExplorer explorer, JobRepository repository, RunParameters parameters,
                JobLauncher launcher, TaskExecutor takeoverExecutor,
                Duration takeoverWait, Duration takeoverPoll) {
        this.improveJob = improveJob;
        this.explorer = explorer;
        this.repository = repository;
        this.parameters = parameters;
        this.launcher = launcher;
        this.takeoverExecutor = takeoverExecutor;
        this.takeoverWait = takeoverWait;
        this.takeoverPoll = takeoverPoll;
    }

    // ── the trigger ────────────────────────────────────────────────────────────

    /// `POST /webhook/improve-run`, with the body the caller sent.
    public Launched launch(Map<String, Object> body) {
        Set<JobExecution> running = runningExecutions();
        if (!running.isEmpty()) return takeOver(body, running);
        return launchNow(body);
    }

    private Launched launchNow(Map<String, Object> body) {
        JobParameters resume = resumableParameters(body);
        JobParameters params = resume != null ? resume : parameters.forNewRun(body, System.currentTimeMillis());
        // A RESTART MUST NOT START A RUN. `POST /api/run/start` clears state.files, the event log
        // and the decisions; calling it here would throw away the candidate units and the
        // measurements the crashed run left behind, which is exactly what resuming is for. The
        // run object it created is still in the store, and `State.load()` has already re-labelled
        // it `interrupted`.
        String runId = resume != null ? currentRunId() : startRun(body);
        try {
            JobExecution execution = launcher.run(improveJob, params);
            log.info("{} {} as job execution {} (instance {})", resume != null ? "resumed" : "started",
                    runId, execution.getId(), execution.getJobId());
            return new Launched(execution.getId(), execution.getJobId(), runId,
                    resume != null, false, List.of());
        } catch (JobExecutionException e) {
            // The four things JobLauncher.run declares — already running, already complete, not
            // restartable, invalid parameters — share a supertype and a meaning: a conflict with
            // what the tables already hold, rather than a fault in the request. A fault is left
            // to propagate as a 500.
            //
            // The run created above is left as it is: `running` with no execution behind it,
            // which the next trigger's `force` (or the 15-minute staleness window inside the
            // route) takes over, exactly as it did when n8n owned this.
            throw new RunFailure(HttpStatus.CONFLICT,
                    "improveJob could not be launched: " + e.getMessage(), e);
        }
    }

    /// Stop the incumbent, then launch when it has actually ended.
    ///
    /// The wait is not politeness. A stop lands at a UNIT boundary — the improve step returns
    /// `CONTINUABLE` once per unit and Batch checks between invocations — so the incumbent may
    /// still be inside a PIT run or a full suite for as long as the ceilings in
    /// config/timeouts.json allow. Launching before it ends would put two runs in one worktree,
    /// which is the failure this whole class is arranged to prevent.
    private Launched takeOver(Map<String, Object> body, Set<JobExecution> running) {
        List<Long> ids = running.stream().map(JobExecution::getId).sorted().toList();
        if (!truthy(body.get("force"))) {
            throw new RunFailure(HttpStatus.CONFLICT,
                    "a run is already executing (job execution " + ids + ") — POST /api/run/stop first, "
                            + "or pass force:true to stop it and take over");
        }
        if (!takeoverPending.compareAndSet(false, true)) {
            throw new RunFailure(HttpStatus.CONFLICT,
                    "a takeover is already pending: the previous run has been asked to stop and the "
                            + "next launch is waiting for it to end");
        }
        try {
            List<Long> stopped = stop(null);
            takeoverExecutor.execute(() -> {
                try {
                    if (awaitIdle()) {
                        launchNow(body);
                    } else {
                        log.error("takeover abandoned: job execution(s) {} did not end within {}",
                                stopped, takeoverWait);
                    }
                } catch (RuntimeException e) {
                    log.error("takeover failed after stopping job execution(s) {}", stopped, e);
                } finally {
                    takeoverPending.set(false);
                }
            });
            return new Launched(null, null, null, false, true, stopped);
        } catch (RuntimeException e) {
            // the stop threw, or the executor refused the task: nothing is waiting, so nothing
            // may keep claiming to be
            takeoverPending.set(false);
            throw e;
        }
    }

    // ── stopping ───────────────────────────────────────────────────────────────

    /// `POST /api/run/stop` — ask the running execution(s) to stop, and answer immediately.
    ///
    /// This replaces the batch driver's `POST /rest/executions/{id}/stop` against n8n, with one
    /// difference worth knowing: n8n killed the execution where it stood, and this is
    /// cooperative. Writing `STOPPING` to the execution row is what
    /// `SimpleJobRepository.update(StepExecution)` reads back on the next step update — it
    /// synchronises the live job execution's status and sets `terminateOnly` on the step — so
    /// the improve step ends after the unit it is on rather than in the middle of a Maven build.
    /// That is the granularity the whole design is built around: a unit is where the ledgers
    /// already record a disposition.
    ///
    /// `JobOperator.stop` does exactly this and then also calls `stop()` on a running
    /// `StoppableTasklet`. Nothing here implements that interface; if a tasklet ever does — to
    /// kill a Maven process group early, say — route this through `JobOperator` rather than
    /// growing a second stop path beside it.
    ///
    /// @param executionId one execution, or null for every running one
    /// @return the ids actually asked to stop
    public List<Long> stop(Long executionId) {
        List<Long> stopped = new ArrayList<>();
        for (JobExecution execution : runningExecutions()) {
            if (executionId != null && !executionId.equals(execution.getId())) continue;
            try {
                execution.setStatus(BatchStatus.STOPPING);
                repository.update(execution);
                stopped.add(execution.getId());
                log.info("stop requested for job execution {}", execution.getId());
            } catch (RuntimeException e) {
                // An optimistic-locking clash means the run wrote its own update first; the
                // others must still be asked, and the caller must still get an answer.
                log.warn("could not request a stop for job execution {}", execution.getId(), e);
            }
        }
        return stopped;
    }

    // ── crash recovery ─────────────────────────────────────────────────────────

    /// Mark executions that no process is behind as failed.
    ///
    /// One container owns /data, so a `STARTED` execution found at boot belongs to a JVM that is
    /// gone — killed mid-run, or stopped while a unit still had an hour of PIT to go. Leaving it
    /// costs both halves of the design: every later trigger is refused because a run "is already
    /// executing", and the instance can never be restarted, because Batch will not create a new
    /// execution while the last one claims to be running. So the record is corrected to what
    /// actually happened, which is the same statement `State.load()` makes when it re-labels a
    /// `running` run as `interrupted`.
    ///
    /// FAILED and not ABANDONED: failed is restartable, and restarting at the next unit rather
    /// than re-cloning and re-measuring from zero is the reason this job is a Batch job at all.
    ///
    /// On `ApplicationReadyEvent` rather than during refresh: the batch schema is created by a
    /// database initializer, and reading the execution tables before that has run is a boot
    /// failure wearing a data-access mask. The window between the port opening and this running
    /// is milliseconds long, and a trigger that lands inside it is refused with a 409 that names
    /// the stale execution — noisy, not wrong.
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileCrashedExecutions() {
        for (JobExecution execution : runningExecutions()) {
            LocalDateTime now = LocalDateTime.now();
            for (StepExecution step : execution.getStepExecutions()) {
                if (!step.getStatus().isRunning()) continue;
                step.setStatus(BatchStatus.FAILED);
                step.setExitStatus(ExitStatus.FAILED.addExitDescription(CRASHED));
                step.setEndTime(now);
                repository.update(step);
            }
            execution.setStatus(BatchStatus.FAILED);
            execution.setExitStatus(ExitStatus.FAILED.addExitDescription(CRASHED));
            execution.setEndTime(now);
            repository.update(execution);
            log.warn("job execution {} was {} — {}", execution.getId(), CRASHED,
                    "the next trigger with the same settings will resume it");
        }
    }

    // ── restart policy ─────────────────────────────────────────────────────────

    /// The parameters that would RESUME the last run, or null if this trigger is a new run.
    ///
    /// Resuming means reusing the previous execution's parameters: same identifying values, same
    /// job instance, so Batch skips the steps that completed (the setup: clone, build, baseline
    /// coverage — 40 minutes on a real repo) and re-runs the improve step from the next unit.
    ///
    /// Two conditions, and both matter.
    ///
    /// FAILED ONLY, never STOPPED. Failed is a run that died — a crash, a killed container
    /// reconciled at boot, an exception — and resuming it is the payoff. Stopped is a run
    /// somebody ENDED, and resuming that would be worse than useless: the stopped run has
    /// already counted its settled units against `scopeLimit`, so the resumed one would find the
    /// quota spent and finish having done nothing. A driver that stops a batch and triggers the
    /// next would then loop over no-op batches forever.
    ///
    /// SAME BODY. A trigger carrying different settings is a different request, and silently
    /// resuming an old instance would run it with the OLD configuration while the caller reads
    /// its own body back in the response. Different body, new instance.
    /// Truthy the way the rest of the pipeline reads a flag off a JSON body: the webhook may
    /// deliver a real boolean or the string "true", and only one of those is a Boolean.
    private static boolean asksForAFreshStart(Object v) {
        return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
    }

    private JobParameters resumableParameters(Map<String, Object> body) {
        // `force` and `clearLedger` both mean "do not continue what is there".
        //
        // Spring Batch keys a job INSTANCE by its parameters, so a second call with the same
        // body restarts the same instance rather than creating a new one. That is the right
        // default after a crash and exactly wrong here: a caller asking to start from scratch
        // got `resumed: true`, the previous run's units back, and a clearLedger that never
        // ran because no run had started to apply it to. Reproduced live — three attempts to
        // begin a clean run all resumed the same instance with 262 ledger entries intact.
        if (asksForAFreshStart(body.get("force")) || asksForAFreshStart(body.get("clearLedger"))) {
            return null;
        }
        List<JobInstance> instances = explorer.getJobInstances(improveJob.getName(), 0, 1);
        if (instances.isEmpty()) return null;
        JobExecution last = explorer.getJobExecutions(instances.get(0)).stream()
                .max(Comparator.comparing(JobExecution::getId)).orElse(null);
        if (last == null || last.getStatus() != BatchStatus.FAILED) return null;
        String incoming = parameters.canonicalJson(body);
        if (!incoming.equals(last.getJobParameters().getString(RunParameters.OVERRIDES))) return null;
        return last.getJobParameters();
    }

    // ── the run object ─────────────────────────────────────────────────────────

    /// Create the run, through the route body that owns what "a run starts" means.
    ///
    /// Every line of `POST /api/run/start` was paid for: the 409 and its staleness window, the
    /// takeover event written AFTER the log is cleared (a line written before it would be
    /// wiped), the reachability memo cleared because it is keyed by repo-relative path, the
    /// mutator statistics that must not carry one run's misses into the next, `seq` deliberately
    /// NOT reset because `/api/events?after=` pages on it. Re-typing any of that here would be
    /// reimplementing the domain layer.
    private String startRun(Map<String, Object> body) {
        Server.Route start = Server.routes().get(RUN_START);
        try {
            Object answer = start.handle(Server.Query.EMPTY, body);
            Object run = answer instanceof Map<?, ?> m ? m.get("run") : null;
            return run instanceof State.Run r ? r.id : null;
        } catch (IOException e) {
            throw new RunFailure(HttpStatus.INTERNAL_SERVER_ERROR,
                    "the run could not be started: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // `Server.HttpFailure` carries the status the answer must have — 409 when a run is
            // already active — and is package-private to the domain layer, so only its NAME can
            // be read from here. Anything else out of this route is a fault rather than a
            // refusal and must read as 500: "try again with force" is bad advice for a bug.
            HttpStatus status = "HttpFailure".equals(e.getClass().getSimpleName())
                    ? HttpStatus.CONFLICT : HttpStatus.INTERNAL_SERVER_ERROR;
            throw new RunFailure(status, "the run could not be started: " + e.getMessage(), e);
        }
    }

    /// The id of the run in the store, for a resume — the response names a run either way.
    private String currentRunId() {
        synchronized (State.STATE) {
            return State.STATE.run == null ? null : State.STATE.run.id;
        }
    }

    // ── plumbing ───────────────────────────────────────────────────────────────

    private Set<JobExecution> runningExecutions() {
        return explorer.findRunningJobExecutions(improveJob.getName());
    }

    private boolean awaitIdle() {
        long deadline = System.nanoTime() + takeoverWait.toNanos();
        // never zero: a poll interval configured to nothing would spin a core for as long as the
        // incumbent takes to finish, which can be hours
        long sleepMs = Math.max(1, takeoverPoll.toMillis());
        while (!runningExecutions().isEmpty()) {
            if (System.nanoTime() - deadline >= 0) return false;
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /// A launcher that returns as soon as the execution EXISTS.
    ///
    /// The auto-configured `JobLauncher` runs the job on a `SyncTaskExecutor`, so a trigger would
    /// hold the HTTP connection open for the length of the run — hours — and the caller that
    /// posts and then polls would never see its status code. `TaskExecutorJobLauncher` writes the
    /// execution row on the calling thread and hands the job to the executor, so the id is
    /// already real when this returns: the same contract as n8n's `onReceived`.
    ///
    /// Built here rather than declared as a `@Bean` on purpose. A second `JobLauncher` bean makes
    /// every by-type injection of one ambiguous — including `JobLauncherTestUtils`, which every
    /// step test in this module uses — and marking it `@Primary` would quietly redirect those
    /// tests onto an asynchronous launcher, where a completed step is a race rather than an
    /// assertion.
    ///
    /// The threads are NOT daemons, deliberately: a run in flight when the context closes should
    /// finish what it is doing (and let `Exec` kill its process group) rather than vanish with
    /// the JVM and leave Maven's children behind.
    private static JobLauncher asyncLauncher(JobRepository repository) {
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(repository);
        launcher.setTaskExecutor(new SimpleAsyncTaskExecutor("improve-run-"));
        try {
            launcher.afterPropertiesSet();
        } catch (Exception e) {
            throw new IllegalStateException("the run launcher could not be built", e);
        }
        return launcher;
    }

    /// The takeover waiter, which only ever sleeps. A daemon, because waiting for a run that has
    /// been asked to stop is not a reason to hold the JVM open at shutdown.
    private static TaskExecutor takeoverExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("improve-takeover-");
        executor.setDaemon(true);
        return executor;
    }

    /// JS truthiness, for the one field this class reads out of a free-form JSON body.
    ///
    /// `force` is tested with `State.jsTruthy` inside `POST /api/run/start`, so a body that is
    /// forceful to the route and not to this method would be refused here and accepted there.
    /// That function is package-private to the domain layer; `Wire.truthy` in the batch package
    /// is a second copy of it for the same reason. THREE is too many — widen `State.jsTruthy` to
    /// public and delete both copies rather than adding a fourth.
    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0 && !Double.isNaN(n.doubleValue());
        if (v instanceof String s) return !s.isEmpty();
        return true;
    }
}
