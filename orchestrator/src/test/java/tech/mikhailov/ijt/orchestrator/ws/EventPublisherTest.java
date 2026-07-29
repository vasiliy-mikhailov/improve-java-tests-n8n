package tech.mikhailov.ijt.orchestrator.ws;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tech.mikhailov.ijt.State;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// What the dashboard is promised: every event line exactly once, in `seq` order, and a stage
/// frame whenever the stage moves.
///
/// The publisher is driven by hand here — `poll()` rather than the scheduler — because the
/// interesting behaviour is all about the store's position between ticks, and a real clock
/// would only make it slower and flakier.
class EventPublisherTest {

    /// A `SimpMessagingTemplate` over a channel that keeps what it is given. Real template, real
    /// header accessors: what is asserted below is the message the broker would actually get.
    static final class Recorder implements MessageChannel {

        final List<Message<?>> sent = new ArrayList<>();
        boolean failing;

        @Override
        public boolean send(Message<?> message, long timeout) {
            if (failing) throw new MessageDeliveryException(message, "broker is not accepting messages");
            sent.add(message);
            return true;
        }

        List<Message<?>> to(String destination) {
            return sent.stream()
                    .filter(m -> destination.equals(SimpMessageHeaderAccessor.getDestination(m.getHeaders())))
                    .toList();
        }

        List<State.Event> events() {
            return to(EventPublisher.EVENTS).stream().map(m -> (State.Event) m.getPayload()).toList();
        }

        List<State.Stage> stages() {
            return to(EventPublisher.STAGE).stream().map(m -> (State.Stage) m.getPayload()).toList();
        }
    }

    private Recorder recorder;
    private EventPublisher publisher;

    @BeforeEach
    void fresh(@TempDir Path dir) throws IOException {
        StoreFixture.reset(dir);
        recorder = new Recorder();
        publisher = new EventPublisher(new SimpMessagingTemplate(recorder), 250);
    }

    @AfterAll
    static void drain() throws IOException {
        StoreFixture.drain();
    }

    /// The first tick adopts the store's position and sends nothing.
    private void prime() {
        publisher.poll();
        assertTrue(recorder.sent.isEmpty(), "the first tick must publish nothing");
    }

    @Test
    void the_stream_starts_where_the_store_is_not_at_the_backlog() {
        // The state.json a restart just loaded: a previous run's log, already in the past.
        State.event("cloning", "old line 1");
        State.event("cloning", "old line 2");

        prime();

        // and only what happens AFTER that reaches a subscriber. The backlog is what
        // GET /api/state is for; replaying it here would give every client that reconnects a
        // second copy of everything it already has.
        State.event("measuring_baseline", "new line");
        publisher.poll();

        assertEquals(List.of("new line"), recorder.events().stream().map(State.Event::msg).toList());
    }

    @Test
    void every_event_is_published_once_in_seq_order() {
        prime();
        State.event("picking_file", "one");
        State.event("picking_file", "two");
        State.event("picking_file", "three");

        publisher.poll();
        publisher.poll();   // a tick with nothing new must not resend anything

        List<State.Event> published = recorder.events();
        assertEquals(List.of("one", "two", "three"), published.stream().map(State.Event::msg).toList());
        assertEquals(List.of(1L, 2L, 3L), published.stream().map(State.Event::seq).toList());
        // the record itself, not a projection of it: {seq, ts, stage, msg} is the same shape as
        // an element of events[] in the snapshot, which is what lets the dashboard merge the two
        assertEquals("picking_file", published.get(0).stage());
        assertTrue(published.get(0).ts() > 0);
    }

    @Test
    void the_stage_is_published_when_it_changes_and_not_otherwise() {
        prime();

        State.setStage("cloning", "https://example.test/repo.git");
        publisher.poll();
        State.setStage("cloning", "https://example.test/repo.git");   // same stage, same detail
        publisher.poll();

        assertEquals(1, recorder.stages().size(), "re-entering the same stage moves nothing");
        assertEquals("cloning", recorder.stages().get(0).name());
        assertEquals("https://example.test/repo.git", recorder.stages().get(0).detail());
        // setStage logs the move as well, and the feed gets it: the banner and the log agree
        assertEquals(List.of("https://example.test/repo.git"),
                recorder.events().stream().map(State.Event::msg).toList());

        // A progress line is a change of stage too. It is the ONLY evidence a forty-minute PIT
        // run is still alive, so it must reach the banner without waiting for an event.
        State.setProgress("[INFO] Running mutation tests", 42);
        publisher.poll();

        List<State.Stage> stages = recorder.stages();
        assertEquals(2, stages.size());
        assertNotNull(stages.get(1).progress());
        assertEquals("[INFO] Running mutation tests", stages.get(1).progress().line());
        assertEquals(42, stages.get(1).progress().elapsed());
        assertEquals("cloning", stages.get(1).name(), "progress does not change the stage name");
    }

    @Test
    void a_cleared_event_log_neither_replays_nor_stalls() {
        prime();
        State.event("starting", "run-1 line");
        publisher.poll();

        // POST /api/run/start clears the log and leaves `seq` alone — the counter is what a
        // reconnecting client pages on, and rewinding it would make a new run's events look
        // older than ones a client already holds.
        synchronized (State.STATE) {
            State.STATE.events.clear();
        }
        publisher.poll();
        assertEquals(1, recorder.events().size(), "clearing the log must not republish or resend");

        State.event("starting", "run-2 line");
        publisher.poll();

        assertEquals(List.of("run-1 line", "run-2 line"),
                recorder.events().stream().map(State.Event::msg).toList());
        assertEquals(List.of(1L, 2L), recorder.events().stream().map(State.Event::seq).toList());
    }

    @Test
    void a_rewound_seq_resyncs_instead_of_going_silent() {
        prime();
        State.event("stage", "a");
        State.event("stage", "b");
        publisher.poll();
        assertEquals(2, recorder.events().size());

        // Not a thing `State.event` can do — but `State.load()` restores whatever the file on
        // disk says, and a store restored from an older file carries a lower counter. Following
        // the store is the only option: waiting for seq to climb back past 2 would mean silence
        // for as many events as were lost.
        synchronized (State.STATE) {
            State.STATE.seq = 0;
            State.STATE.events.clear();
        }
        publisher.poll();   // notices the rewind, publishes nothing
        State.event("stage", "after reload");
        publisher.poll();

        assertEquals(List.of("a", "b", "after reload"),
                recorder.events().stream().map(State.Event::msg).toList());
    }

    @Test
    void a_failed_send_does_not_stop_the_ticker() {
        prime();
        recorder.failing = true;
        State.event("stage", "during the outage");
        publisher.tick();   // a ScheduledExecutorService cancels a repeating task that throws

        recorder.failing = false;
        State.event("stage", "after it");
        publisher.tick();

        // Both, in order: the position only advances past an event that was actually handed to
        // the broker, so a tick that failed is retried by the next one rather than skipped. One
        // bad send must not silence the dashboard, and must not punch a hole in the feed either.
        assertEquals(List.of("during the outage", "after it"),
                recorder.events().stream().map(State.Event::msg).toList());
    }

    @Test
    void destinations_sit_under_the_prefix_the_broker_actually_serves() {
        // A convertAndSend to a destination outside the broker prefix is silently dropped: no
        // exception, no log line, no subscriber. Both destinations are built from the constant
        // the configuration registers, and this is what says so out loud.
        assertTrue(EventPublisher.EVENTS.startsWith(WebSocketConfig.BROKER_PREFIX + "/"));
        assertTrue(EventPublisher.STAGE.startsWith(WebSocketConfig.BROKER_PREFIX + "/"));
        assertEquals("/topic/events", EventPublisher.EVENTS);
        assertEquals("/topic/stage", EventPublisher.STAGE);
        assertFalse(EventPublisher.EVENTS.equals(EventPublisher.STAGE));
    }

    @Test
    void the_published_event_is_the_store_s_own_record() {
        prime();
        State.event("improving_mutation", "targeting CONDITIONALS_BOUNDARY at line 42");
        publisher.poll();

        State.Event fromStore;
        synchronized (State.STATE) {
            fromStore = State.STATE.events.get(State.STATE.events.size() - 1);
        }
        assertSame(fromStore, recorder.events().get(0));
    }
}
