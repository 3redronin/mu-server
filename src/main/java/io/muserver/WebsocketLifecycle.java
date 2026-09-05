package io.muserver;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Linearizes lifecycle transitions originating from the connection reader,
 * application writers, and timer or error callbacks. Terminal states are
 * monotonic so a delayed callback cannot overwrite an already completed
 * close handshake.
 */
final class WebsocketLifecycle {
    private final AtomicReference<WebsocketSessionState> state =
        new AtomicReference<>(WebsocketSessionState.NOT_STARTED);

    WebsocketSessionState state() {
        return state.get();
    }

    void onConnected() {
        if (!state.compareAndSet(
            WebsocketSessionState.NOT_STARTED,
            WebsocketSessionState.OPEN
        )) {
            throw new IllegalStateException(
                "Cannot connect a WebSocket in state " + state.get()
            );
        }
    }

    void onClientCloseStarted() {
        state.compareAndSet(
            WebsocketSessionState.OPEN,
            WebsocketSessionState.CLIENT_CLOSING
        );
    }

    void onServerCloseStarted() {
        state.compareAndSet(
            WebsocketSessionState.OPEN,
            WebsocketSessionState.SERVER_CLOSING
        );
    }

    void onCloseHandshakeCompleted() {
        while (true) {
            WebsocketSessionState current = state.get();
            WebsocketSessionState completed;
            if (current == WebsocketSessionState.CLIENT_CLOSING) {
                completed = WebsocketSessionState.CLIENT_CLOSED;
            } else if (current == WebsocketSessionState.SERVER_CLOSING) {
                completed = WebsocketSessionState.SERVER_CLOSED;
            } else {
                return;
            }
            if (state.compareAndSet(current, completed)) {
                return;
            }
        }
    }

    void terminateWith(WebsocketSessionState terminalState) {
        if (!terminalState.endState()) {
            throw new IllegalArgumentException(
                terminalState + " is not a terminal WebSocket state"
            );
        }
        while (true) {
            WebsocketSessionState current = state.get();
            if (current.endState()) {
                return;
            }
            if (state.compareAndSet(current, terminalState)) {
                return;
            }
        }
    }
}
