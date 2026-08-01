package tech.mikhailov.ijt.orchestrator.batch;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.StepExecution;

/// The run is over; say so on the run itself.
///
/// Spring Batch knew. The job ended FAILED and the execution row recorded it, and `run.status`
/// went on saying `running` — because the only thing that ever wrote a terminal status was
/// `ServerBackend.failRun`, and that fires solely when a **POST** route throws. A step that dies
/// any other way reached nothing:
///
///   - a GET route throwing (`ServerBackend` returns early there: reading cannot have broken the
///     run — true of the read, and irrelevant to the job it just killed by rethrowing)
///   - an exception in tasklet Java outside the route call
///   - a step transaction timeout
///   - an OutOfMemoryError on the run thread
///   - `POST /api/run/stop`, which ends the job STOPPED and marked the run not at all
///
/// `afterJob` is called by `AbstractJob.execute` for EVERY terminal status, so one hook covers
/// all of them. That is the point: the previous design needed a new writer per death, and the
/// deaths it did not enumerate are exactly the ones that shipped.
///
/// It does NOT cover the JVM dying — no callback runs then. That case belongs to the boot
/// restore, which is the code that can observe it.
public final class RunOutcomeListener implements JobExecutionListener {

    private final Backend backend;

    public RunOutcomeListener(Backend backend) {
        this.backend = backend;
    }

    @Override
    public void afterJob(JobExecution execution) {
        // COMPLETED means finishStep ran and already stamped the run through the same route.
        // Writing again would overwrite a considered verdict — including the PR count in its
        // detail — with a generic one.
        if (execution.getStatus() == BatchStatus.COMPLETED) return;
        // NO State.STATE READ HERE. "Only stamp a run that is still running" is a rule about
        // state, and the route owns the state — every other component in this package talks
        // through the Backend seam for exactly that reason. Reaching into the global directly
        // also made this untestable: with a faked backend there is no run in State at all, so
        // the listener returned early and the test that should have caught a missing listener
        // passed against one that could never fire.
        String status = execution.getStatus() == BatchStatus.STOPPED ? "stopped" : "failed";
        backend.abortRun(status, detail(execution));
    }

    /// What actually went wrong, preferring the exception over the exit description.
    ///
    /// The job's own exit description is a stack trace by the time it reaches here, and the first
    /// step failure is the thing a person needs. Truncated, because this becomes a stage detail
    /// the dashboard renders on one line.
    private static String detail(JobExecution execution) {
        for (Throwable t : execution.getAllFailureExceptions()) {
            String m = message(t);
            if (m != null && !m.isBlank()) return clip(m);
        }
        for (StepExecution step : execution.getStepExecutions()) {
            for (Throwable t : step.getFailureExceptions()) {
                String m = message(t);
                if (m != null && !m.isBlank()) return clip("in " + step.getStepName() + ": " + m);
            }
        }
        return execution.getStatus() == BatchStatus.STOPPED
                ? "the run was stopped" : "the job ended " + execution.getStatus();
    }

    private static String message(Throwable t) {
        // The cause carries the sentence; the wrapper carries the class name of the wrapper.
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
    }

    private static String clip(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200);
    }
}
