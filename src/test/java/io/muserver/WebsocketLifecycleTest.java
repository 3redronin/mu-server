package io.muserver;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebsocketLifecycleTest {

    @Test
    void serverInitiatedHandshakeCompletesCleanly() {
        WebsocketLifecycle lifecycle = connectedLifecycle();

        lifecycle.onServerCloseStarted();
        lifecycle.onClientCloseStarted();
        lifecycle.onCloseHandshakeCompleted();

        assertThat(
            lifecycle.state(),
            is(WebsocketSessionState.SERVER_CLOSED)
        );
    }

    @Test
    void clientInitiatedHandshakeCompletesCleanly() {
        WebsocketLifecycle lifecycle = connectedLifecycle();

        lifecycle.onClientCloseStarted();
        lifecycle.onServerCloseStarted();
        lifecycle.onCloseHandshakeCompleted();

        assertThat(
            lifecycle.state(),
            is(WebsocketSessionState.CLIENT_CLOSED)
        );
    }

    @Test
    void delayedFailureCannotOverwriteCleanClose() {
        WebsocketLifecycle lifecycle = connectedLifecycle();
        lifecycle.onServerCloseStarted();
        lifecycle.onCloseHandshakeCompleted();

        lifecycle.terminateWith(WebsocketSessionState.ERRORED);

        assertThat(
            lifecycle.state(),
            is(WebsocketSessionState.SERVER_CLOSED)
        );
    }

    @Test
    void firstTerminalFailureWins() {
        WebsocketLifecycle lifecycle = connectedLifecycle();

        lifecycle.terminateWith(WebsocketSessionState.TIMED_OUT);
        lifecycle.terminateWith(WebsocketSessionState.ERRORED);

        assertThat(
            lifecycle.state(),
            is(WebsocketSessionState.TIMED_OUT)
        );
    }

    @Test
    void nonTerminalFailureStateIsRejected() {
        WebsocketLifecycle lifecycle = connectedLifecycle();

        assertThrows(
            IllegalArgumentException.class,
            () -> lifecycle.terminateWith(WebsocketSessionState.OPEN)
        );
    }

    @Test
    void baseImplementationsDoNotReplyAgainWhileServerCloseIsInFlight()
        throws Exception {
        assertCloseReplyCount(emptySimpleWebSocket(), 0);
        assertCloseReplyCount(emptyBaseWebSocket(), 0);
    }

    @Test
    void baseImplementationsReplyToClientInitiatedClose() throws Exception {
        assertCloseReplyCount(
            emptySimpleWebSocket(),
            WebsocketSessionState.CLIENT_CLOSING,
            1
        );
        assertCloseReplyCount(
            emptyBaseWebSocket(),
            WebsocketSessionState.CLIENT_CLOSING,
            1
        );
    }

    private static WebsocketLifecycle connectedLifecycle() {
        WebsocketLifecycle lifecycle = new WebsocketLifecycle();
        lifecycle.onConnected();
        return lifecycle;
    }

    private static MuWebSocket emptySimpleWebSocket() {
        return new SimpleWebSocket() {
            @Override
            public void onText(String message) {
            }

            @Override
            public void onBinary(ByteBuffer payload) {
            }
        };
    }

    private static MuWebSocket emptyBaseWebSocket() {
        return new BaseWebSocket() {
            @Override
            public void onText(String message) {
            }

            @Override
            public void onBinary(ByteBuffer payload) {
            }
        };
    }

    private static void assertCloseReplyCount(
        MuWebSocket webSocket,
        int expectedReplies
    ) throws Exception {
        assertCloseReplyCount(
            webSocket,
            WebsocketSessionState.SERVER_CLOSING,
            expectedReplies
        );
    }

    private static void assertCloseReplyCount(
        MuWebSocket webSocket,
        WebsocketSessionState state,
        int expectedReplies
    ) throws Exception {
        AtomicInteger replies = new AtomicInteger();
        MuWebSocketSession session = (MuWebSocketSession) Proxy.newProxyInstance(
            WebsocketLifecycleTest.class.getClassLoader(),
            new Class<?>[]{MuWebSocketSession.class},
            (proxy, method, args) -> {
                if (method.getName().equals("state")) {
                    return state;
                }
                if (method.getName().equals("closeSent")) {
                    return false;
                }
                if (method.getName().equals("close")) {
                    replies.incrementAndGet();
                }
                return null;
            }
        );
        webSocket.onConnect(session);

        webSocket.onClientClosed(1001, "closing");

        assertThat(replies.get(), is(expectedReplies));
    }
}
