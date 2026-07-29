package tech.mikhailov.ijt.orchestrator.ws;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import tech.mikhailov.ijt.State;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/// The bridge from `State` to the browser: `State.event()` → `/topic/events`, stage changes →
/// `/topic/stage`.
///
/// WHY THIS WATCHES THE STORE INSTEAD OF BEING CALLED BY IT. Events do not come from the
/// orchestrator. They come from thirty backend classes — `Repo`, `Measure`, `Pit`, `Llm`, `Pr`
/// — every one of which calls the static `State.event()` directly, as the Node modules called
/// the JS `state.event()`. That layer is done, tested and live, and it has no listener seam;
/// adding one to publish from would mean editing the domain to serve the dashboard. So this
/// tails the store instead, four times a second, and the domain keeps not knowing that anything
/// is watching.
///
/// The tail is cheap in a way the poll it replaces was not: it reads two fields under the
/// store's own monitor, in the same process, once per tick regardless of how many browsers are
/// open. The 2-second poll it replaces re-derived and re-serialised the whole payload per
/// client per tick.
///
/// `seq` IS THE CONTRACT. It only ever rises — it survives `run/start`, which clears the event
/// list but not the counter, because a client that reconnects asks for everything after the
/// last seq it holds. This class never publishes an event at or below one it has already sent,
/// and never publishes history: the backlog belongs to `GET /api/state`, which is what a
/// freshly loaded page reads before it subscribes.
@Component
public class EventPublisher {

    /// One message per event line, in `seq` order. The payload is `State.Event` verbatim —
    /// `{seq, ts, stage, msg}` — the same shape as an element of `events[]` in the snapshot, so
    /// the dashboard merges the two without a second code path.
    public static final String EVENTS = WebSocketConfig.BROKER_PREFIX + "/events";

    /// The whole `State.Stage` — `{name, detail, since, progress}` — on every change, progress
    /// included. The progress line is the only evidence a forty-minute PIT run is still alive,
    /// and it is what the banner under the stage name shows.
    public static final String STAGE = WebSocketConfig.BROKER_PREFIX + "/stage";

    private final SimpMessagingTemplate broker;
    private final long pollMillis;

    /// Highest `seq` already on the wire. Guarded by the tick: only the scheduler thread (or a
    /// test) ever runs {@link #poll()}, and one publisher runs one tick at a time, which is also
    /// what keeps `/topic/events` in seq order.
    private long lastSeq;

    /// The last stage published, compared by value — `State.Stage` is a record and `setStage` /
    /// `setProgress` replace it whole, so equality is the change detector.
    private State.Stage lastStage;

    /// False until the first tick has adopted the store's current position.
    ///
    /// It is a flag rather than a constructor read because `State.load()` — which restores
    /// `seq` and up to 400 events from the previous process — may run before or after this bean
    /// is built, and "the stream starts now" must be true either way. Without it, a load that
    /// lands after construction would replay the previous run's log onto the topic.
    private boolean primed;

    private ScheduledExecutorService ticker;

    public EventPublisher(SimpMessagingTemplate broker,
                          @Value("${ijt.ws.poll-millis:250}") long pollMillis) {
        this.broker = broker;
        // 250 ms: four frames a second is under a person's perception of "live" and eight times
        // finer than the 2-second poll it replaces, while still coalescing the burst of progress
        // lines a chatty Maven build produces into at most four updates a second.
        this.pollMillis = pollMillis;
    }

    @PostConstruct
    void start() {
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-publisher");
            // Watching a run is not a reason to keep the JVM alive.
            t.setDaemon(true);
            return t;
        });
        // WITH FIXED DELAY, not at a fixed rate: if a tick ever runs long (a broker channel
        // under back-pressure), fixed rate would queue the missed runs and fire them
        // back-to-back the moment it recovered.
        ticker.scheduleWithFixedDelay(this::tick, pollMillis, pollMillis, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        if (ticker != null) ticker.shutdownNow();
    }

    /// One scheduled tick.
    ///
    /// The try/catch is the whole point of this method existing. A `ScheduledExecutorService`
    /// CANCELS a repeating task for ever the first time its runnable throws, and says nothing:
    /// one failed send — a serialisation problem in one event's message, a broker mid-shutdown —
    /// and the dashboard would go silent for the rest of the run while every other part of the
    /// system behaved perfectly. It would read as a browser problem.
    void tick() {
        try {
            poll();
        } catch (RuntimeException e) {
            System.err.println("ws publish failed: " + e);
        }
    }

    /// Publish whatever has happened since the last call. Package-visible so tests can drive it
    /// without a clock.
    void poll() {
        long seqNow;
        State.Stage stage;
        List<State.Event> fresh;
        // Everything read from the store is read under the store's monitor and materialised
        // there — State's own class comment says why: this process mutates that map from other
        // threads, and a lazy stream over it would serialise a half-written list.
        synchronized (State.STATE) {
            seqNow = State.STATE.seq;
            stage = State.STATE.stage;
            fresh = primed
                    ? State.STATE.events.stream().filter(e -> e.seq() > lastSeq).toList()
                    : List.of();
        }

        if (!primed) {
            primed = true;
            lastSeq = seqNow;
            lastStage = stage;
            return;
        }

        // The counter went backwards, so it is not the counter this publisher was following: a
        // store reloaded from disk, or a test resetting it. Track the store rather than sit
        // above it waiting for a seq that will not arrive for another thousand events.
        if (seqNow < lastSeq) lastSeq = seqNow;

        // Already ascending — events are appended under the same monitor that increments seq.
        //
        // A gap is possible and is not repaired here: the store keeps only the last 400 events,
        // so a burst larger than that inside one tick drops the oldest before this sees them.
        // The client orders and de-duplicates by seq and re-reads the snapshot on reconnect, so
        // a gap costs a missing line, never a wrong order or a duplicate.
        //
        // The position advances AFTER each send, one event at a time, so a send that throws
        // leaves the position on the last event that actually reached the broker and the next
        // tick picks up from there. Advancing first would turn one refused message into a hole
        // in the feed.
        for (State.Event e : fresh) {
            broker.convertAndSend(EVENTS, e);
            if (e.seq() > lastSeq) lastSeq = e.seq();
        }

        // After the events, so a client that renders both sees the line explaining the stage
        // before the banner changes under it.
        if (!Objects.equals(stage, lastStage)) {
            lastStage = stage;
            broker.convertAndSend(STAGE, stage);
        }
    }
}
