package io.muserver;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class WebsocketLatencyAverageTest {

    @Test
    void simpleWebsocketAveragesTheMeasuredLatencies() throws Exception {
        var socket = new SimpleWebSocket() {
            @Override
            public void onText(String message) {
            }

            @Override
            public void onBinary(ByteBuffer buffer) {
            }
        };
        socket.onConnect(sessionReturningLatencies(5L, 9L));

        socket.onPong(ByteBuffer.allocate(0));
        socket.onPong(ByteBuffer.allocate(0));

        assertThat(socket.averagePingPongLatencyMillis(), is(7L));
    }

    @SuppressWarnings("deprecation")
    @Test
    void deprecatedBaseWebsocketAveragesTheMeasuredLatencies() throws Exception {
        var socket = new BaseWebSocket() {
        };
        socket.onConnect(sessionReturningLatencies(10L, 20L));

        socket.onPong(ByteBuffer.allocate(0));
        socket.onPong(ByteBuffer.allocate(0));

        assertThat(socket.averagePingPongLatencyMillis(), is(15L));
    }

    private static MuWebSocketSession sessionReturningLatencies(long... latencies) {
        var nextLatency = new AtomicInteger();
        return (MuWebSocketSession) Proxy.newProxyInstance(
            MuWebSocketSession.class.getClassLoader(),
            new Class<?>[]{MuWebSocketSession.class},
            (proxy, method, args) -> {
                if (method.getName().equals("pongLatencyMillis")) {
                    return latencies[nextLatency.getAndIncrement()];
                }
                throw new UnsupportedOperationException(method.getName());
            }
        );
    }
}
