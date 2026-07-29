package tech.mikhailov.ijt.orchestrator.run;

import org.springframework.http.HttpStatus;

/// A refusal that is not a run failure: it carries the status the answer must have.
///
/// The same shape as `Server.HttpFailure`, which the route bodies throw, and for the same
/// reason — "a run is already active" is a 409 and a legible sentence, not a stack trace and a
/// 500. That class is package-private to the domain layer, so this is its counterpart on this
/// side of the seam rather than a reuse of it.
///
/// Deliberately NOT `ResponseStatusException`: {@link RunLauncher} decides these, and a
/// launcher that throws a web exception is a launcher that cannot be called from anywhere but a
/// controller. The mapping to a response lives in {@link RunController}, once.
public class RunFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;

    public RunFailure(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public RunFailure(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus status() { return status; }
}
