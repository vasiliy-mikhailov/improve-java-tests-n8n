package tech.mikhailov.ijt.orchestrator.ws;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.config.SimpleBrokerRegistration;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The two registrations and the prefixes, because both are the kind of thing that fails
/// silently: a destination outside the broker prefix is dropped without a word, and a missing
/// plain endpoint leaves the dashboard staring at a socket that will never open.
///
/// The registries are recorded rather than mocked. `StompEndpointRegistry` is an interface, so a
/// `java.lang.reflect.Proxy` records it with no knowledge of its method list and no mocking
/// framework on a very new JDK; `MessageBrokerRegistry` is a class, so the recording is a
/// subclass that still calls through to the real one.
class WebSocketConfigTest {

    /// Records every call, and keeps the chain going: a method returning the interface it was
    /// called on gets the same proxy back, and one returning another interface gets a new proxy
    /// sharing this log.
    static final class Calls implements InvocationHandler {

        final List<String> log = new ArrayList<>();

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "recording-proxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
            }
            log.add(method.getName() + Arrays.deepToString(args == null ? new Object[0] : args));
            Class<?> returnType = method.getReturnType();
            if (returnType.isInstance(proxy)) return proxy;
            // withSockJS() returns a CLASS, which a Proxy cannot stand in for — null is safe
            // because nothing in the configuration chains off it.
            if (returnType.isInterface()) return proxyOf(returnType, this);
            return null;
        }

        int count(String call) {
            return Collections.frequency(log, call);
        }
    }

    private static Object proxyOf(Class<?> type, Calls calls) {
        return Proxy.newProxyInstance(WebSocketConfigTest.class.getClassLoader(), new Class<?>[] {type}, calls);
    }

    static final class RecordingBroker extends MessageBrokerRegistry {

        final List<String> brokerPrefixes = new ArrayList<>();
        final List<String> appPrefixes = new ArrayList<>();

        RecordingBroker() {
            super(new ExecutorSubscribableChannel(), new ExecutorSubscribableChannel());
        }

        @Override
        public SimpleBrokerRegistration enableSimpleBroker(String... prefixes) {
            brokerPrefixes.addAll(List.of(prefixes));
            return super.enableSimpleBroker(prefixes);
        }

        @Override
        public MessageBrokerRegistry setApplicationDestinationPrefixes(String... prefixes) {
            appPrefixes.addAll(List.of(prefixes));
            return super.setApplicationDestinationPrefixes(prefixes);
        }
    }

    /// The configuration owns a heartbeat thread from construction, so every test that builds
    /// one gives it back.
    private WebSocketConfig config;

    @BeforeEach
    void build() {
        config = new WebSocketConfig();
    }

    @AfterEach
    void release() {
        config.stopHeartbeats();
    }

    @Test
    void the_endpoint_is_registered_twice_plain_and_sockjs() {
        Calls calls = new Calls();
        StompEndpointRegistry registry = (StompEndpointRegistry) proxyOf(StompEndpointRegistry.class, calls);

        config.registerStompEndpoints(registry);

        // Two registrations of ONE path. The SockJS one is the transport the design names; the
        // plain one is `/ws` itself, and the dashboard needs it because it cannot load the
        // SockJS client library (app.js is the only file in that directory that may change) and
        // a native WebSocket cannot speak to a SockJS endpoint.
        assertEquals(2, calls.count("addEndpoint[[" + WebSocketConfig.ENDPOINT + "]]"), calls.log.toString());
        assertEquals(1, calls.count("withSockJS[]"), calls.log.toString());
        // Behind Caddy the browser's Origin is the public https host while the container sees
        // its own http host, so the same-origin default would reject every handshake in the one
        // deployment that matters.
        assertEquals(2, calls.count("setAllowedOriginPatterns[[*]]"), calls.log.toString());
    }

    @Test
    void the_broker_serves_the_prefix_the_publisher_sends_to() {
        RecordingBroker registry = new RecordingBroker();

        config.configureMessageBroker(registry);

        assertEquals(List.of(WebSocketConfig.BROKER_PREFIX), registry.brokerPrefixes);
        assertEquals(List.of(WebSocketConfig.APP_PREFIX), registry.appPrefixes);
        // and both destinations are inside it — a send to anything else is dropped in silence
        assertTrue(EventPublisher.EVENTS.startsWith(WebSocketConfig.BROKER_PREFIX + "/"));
        assertTrue(EventPublisher.STAGE.startsWith(WebSocketConfig.BROKER_PREFIX + "/"));
    }

    /// `getInterceptors()` is protected on `ChannelRegistration`; a subclass can read its own.
    static final class RecordingChannel extends ChannelRegistration {

        List<ChannelInterceptor> captured() {
            return getInterceptors();
        }
    }

    private static Message<?> stompFrame(StompCommand command) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setDestination(EventPublisher.EVENTS);
        return MessageBuilder.createMessage(
                "{\"seq\":999999,\"ts\":0,\"stage\":\"done\",\"msg\":\"forged\"}".getBytes(StandardCharsets.UTF_8),
                accessor.getMessageHeaders());
    }

    @Test
    void the_stream_is_read_only() {
        RecordingChannel inbound = new RecordingChannel();

        config.configureClientInboundChannel(inbound);

        List<ChannelInterceptor> interceptors = inbound.captured();
        assertEquals(1, interceptors.size());
        ChannelInterceptor guard = interceptors.get(0);
        MessageChannel anywhere = (message, timeout) -> true;

        // The broker fans out whatever reaches its prefix, whoever sent it. A browser SEND to
        // /topic/events would arrive at every other dashboard looking exactly like a line the
        // pipeline had logged.
        assertNull(guard.preSend(stompFrame(StompCommand.SEND), anywhere));

        // …and what a spectator legitimately does still passes.
        Message<?> subscribe = stompFrame(StompCommand.SUBSCRIBE);
        assertSame(subscribe, guard.preSend(subscribe, anywhere));
    }

    @Test
    void heartbeats_come_with_the_scheduler_they_require() {
        // Configured together or not at all: SimpleBrokerMessageHandler.start() refuses to start
        // with heartbeat values and no scheduler, and the context fails with it. The quieter
        // half of the same pairing is a scheduler with no values — no heartbeat is ever sent,
        // and an idle connection through a proxy dies without either end noticing.
        assertTrue(WebSocketConfig.HEARTBEAT_MS > 0);
        assertNotNull(config.heartbeats());
    }
}
