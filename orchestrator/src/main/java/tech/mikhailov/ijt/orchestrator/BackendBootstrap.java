package tech.mikhailov.ijt.orchestrator;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import tech.mikhailov.ijt.Exec;
import tech.mikhailov.ijt.Server;
import tech.mikhailov.ijt.State;

/// The three lines `Server.main` runs before it opens a port.
///
/// The domain layer is a library here rather than a process, so nothing calls its `main`, and
/// each of these is invisible by its absence rather than loud:
///
///  - `Exec.setProgressSink` is what turns an hour-long Maven build into a moving line on the
///    dashboard. Without it the sink stays the no-op default and a run reports nothing at all
///    while it works, which is indistinguishable from a hang — and this project has already
///    paid for one hang that looked like progress.
///
///  - `State.load()` reads /data/state.json back. It carries the improvedLedger that stops this
///    run redoing the last one's work, the measureLedger that saves 40 minutes of re-measuring,
///    and `seq`, which must keep rising across processes or a reconnecting dashboard silently
///    drops events. It is also what marks a run left `running` by a killed container as
///    `interrupted` instead of pretending the pipeline is idle.
///
///  - `Server.installCollaborators()` installs the LLM client, the PR gateway and the rules
///    engine into the seams the route bodies call. A seam nobody installs is a route that
///    throws: without it the first `Rules: post-clone` of every run fails, and the run dies
///    before it has picked a file.
///
/// **Why `@PostConstruct` and not `ApplicationReadyEvent`.** Singletons are created during
/// context refresh and the web server is started afterwards, by a `SmartLifecycle` — so this
/// has run before the port is open and no request can arrive between the two. That is the same
/// ordering `Server.main` states in its own comment, for the same reason.
///
/// Nothing here is Spring-specific and all of it is idempotent: `State.load()` re-reads the
/// file, and installing a collaborator twice installs the same one.
@Component
public class BackendBootstrap {

    @PostConstruct
    public void install() {
        Exec.setProgressSink(State::setProgress);
        State.load();
        // after the load: the rules engine seeds its per-stage token ceilings from the
        // budget the state file carries, and reads the run's rule text through it
        Server.installCollaborators();
    }
}
