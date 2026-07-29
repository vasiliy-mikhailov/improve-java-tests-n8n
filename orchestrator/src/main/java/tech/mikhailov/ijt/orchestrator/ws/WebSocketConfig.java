package tech.mikhailov.ijt.orchestrator.ws;

import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/// STOMP over SockJS, replacing the dashboard's 2-second poll.
///
/// The dashboard is a SPECTATOR. Nothing here may be able to slow a run down: the pipeline
/// publishes through {@link EventPublisher} and never waits for a client, and the transport
/// limits below cap what one wedged browser tab can hold on to. A dashboard that can stall the
/// run it is watching is worse than no dashboard.
///
/// Destinations (see docs/SPRING-MIGRATION.md, "WebSocket"):
///
///     /topic/events   one message per `State.event()` line — the feed
///     /topic/stage    the whole `State.Stage`, on every change including progress
///
/// The stream is one-way. Nothing maps to an application destination and client SENDs are
/// dropped — see {@link #configureClientInboundChannel}.
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /// Everything the broker fans out sits under this. A `convertAndSend` to a destination
    /// OUTSIDE the prefix is not an error and not a warning — the message is simply dropped on
    /// the broker channel and the dashboard goes quiet with nothing in any log to say why. The
    /// destinations in {@link EventPublisher} are built from this constant so the two cannot
    /// drift apart, and a test pins it.
    public static final String BROKER_PREFIX = "/topic";

    /// Client → server. Nothing maps here today, and a destination that resolves to no
    /// `@MessageMapping` is dropped — which is the point of having the prefix at all: without
    /// one, every client destination is a BROKER destination.
    public static final String APP_PREFIX = "/app";

    /// The handshake path, relative to wherever the app is mounted. The dashboard resolves it
    /// against `document.baseURI`, exactly as it resolves `api/metrics`, so it works both at `/`
    /// and behind Caddy's `/dashboard` prefix.
    public static final String ENDPOINT = "/ws";

    /// Both directions of the STOMP heart-beat.
    ///
    /// This is not politeness. A unit's PIT measurement can run for forty minutes without
    /// producing a single event, and an idle connection through a reverse proxy gets reaped long
    /// before that — silently, with the browser's socket left believing it is connected. The
    /// heart-beat is what turns "nothing is happening" into "the connection is gone" so the
    /// client reconnects and re-reads the snapshot instead of showing a frozen page that looks
    /// exactly like a healthy one.
    public static final long HEARTBEAT_MS = 10_000;

    /// One daemon thread, for heartbeats and nothing else.
    ///
    /// NOT a `@Bean`, and that is deliberate. A second `TaskScheduler` in the context is not
    /// free: Boot's task-scheduling auto-configuration and `@Scheduled` both resolve one by
    /// type, and this application is being assembled by more than one hand. A scheduler whose
    /// entire job is to emit a newline every ten seconds must not become the one some other
    /// component's scheduled work quietly lands on. Kept private, wired only into the broker
    /// registration below, and shut down with this bean.
    ///
    /// Daemon, because watching a run is not a reason to keep the JVM alive.
    private final ThreadPoolTaskScheduler heartbeats = heartbeatScheduler();

    private static ThreadPoolTaskScheduler heartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.setDaemon(true);
        // Its own lifecycle, because the container does not own it: nothing else calls
        // afterPropertiesSet() on a plain field.
        scheduler.initialize();
        return scheduler;
    }

    /// The scheduler behind the heart-beat. Package-visible for the test that pins it to the
    /// heartbeat values — the two are configured together or the context does not start.
    TaskScheduler heartbeats() {
        return heartbeats;
    }

    @PreDestroy
    void stopHeartbeats() {
        heartbeats.shutdown();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker(BROKER_PREFIX)
                // Heartbeats need a scheduler ON THIS REGISTRATION. The simple broker does not
                // pick one up from the context: SimpleBrokerMessageHandler.start() asserts
                // "Heartbeat values configured but no TaskScheduler provided" and the whole
                // context fails to start. Configuring one without the other is the quieter
                // failure — the values are accepted and no heartbeat is ever sent.
                .setHeartbeatValue(new long[] {HEARTBEAT_MS, HEARTBEAT_MS})
                .setTaskScheduler(heartbeats);
        registry.setApplicationDestinationPrefixes(APP_PREFIX);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // TWO registrations of the same path, and both are load-bearing.
        //
        // The SockJS one is the transport the design doc names, and it is what any client with
        // the SockJS library gets — `/ws/**` (info, xhr-streaming, the lot).
        //
        // The plain one is `/ws` itself, and it exists because of a constraint on the other
        // side: the dashboard is unported static HTML and app.js is the only file in it that may
        // change, so it cannot gain a <script> tag for the SockJS or STOMP client libraries.
        // It therefore speaks STOMP down a native WebSocket, which is a few dozen lines of frame
        // handling and no vendored dependency — and a native WebSocket cannot talk to a SockJS
        // endpoint, whose websocket transport wraps every frame in SockJS's own JSON framing.
        //
        // They do not collide: the SockJS registration is mapped at `/ws/**` and this one at
        // `/ws`.
        registry.addEndpoint(ENDPOINT).setAllowedOriginPatterns("*");
        registry.addEndpoint(ENDPOINT).setAllowedOriginPatterns("*").withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // The stream is READ-ONLY, and this is what makes it so.
        //
        // The simple broker fans out anything addressed to its prefix, and it does not care
        // whether that came from this process or from a browser: a client SEND to
        // `/topic/events` would be delivered to every other dashboard as though the pipeline had
        // logged it. That is one connection away for anyone who can reach the endpoint, and the
        // origin patterns above make "anyone" include another site's page.
        //
        // The line the pipeline never wrote would look exactly like the ones it did — and this
        // project has already paid once for a dashboard showing units from a run that was not
        // the one in front of the reader. A feed that can be written by its audience is not a
        // record of anything.
        //
        // Dropped rather than rejected: returning null stops the message, where throwing would
        // send an ERROR frame and tear down the session. A client with nothing to say is not
        // worth disconnecting a viewer over.
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompCommand command = StompHeaderAccessor.wrap(message).getCommand();
                return StompCommand.SEND.equals(command) ? null : message;
            }
        });
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        // Stated rather than inherited, because these are the numbers that decide whether a
        // spectator can hurt a run. A browser tab that stops reading — laptop asleep, tab
        // throttled, network gone — must be disconnected, not buffered indefinitely.
        registration
                // Give up on a send that has not completed in this long and close the session.
                .setSendTimeLimit(10_000)
                // …or that has queued more than this while the client was not reading. An event
                // line is capped at 500 characters by State.event, so 512 KB is roughly a
                // thousand messages behind: a real backlog, not a hiccup.
                .setSendBufferSizeLimit(512 * 1024)
                // Inbound. The dashboard only ever sends CONNECT, SUBSCRIBE and heartbeats;
                // anything approaching this size is not this client.
                .setMessageSizeLimit(64 * 1024);
    }
}
