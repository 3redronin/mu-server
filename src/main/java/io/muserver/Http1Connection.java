package io.muserver;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.security.cert.Certificate;
import java.time.Instant;

abstract class Http1Connection extends BaseHttpConnection {

    private static final Logger log = LoggerFactory.getLogger(Http1Connection.class);

    Http1Connection(Mu3ServerImpl server, ConnectionAcceptor creator, Socket clientSocket, @Nullable Certificate clientCertificate, Instant handshakeStartTime) {
        super(server, creator, clientSocket, clientCertificate, handshakeStartTime);
    }

    protected HttpConnectionState state = HttpConnectionState.OPEN;

    @Override
    void initiateGracefulShutdown() {
        // TODO thread safety
        if (state == HttpConnectionState.OPEN) {
            state = HttpConnectionState.CLOSED_LOCAL;
        }
        if (isIdle()) {
            log.info("Connection is idle; shutting down");
            forceShutdown();
        } else if (!activeWebsockets().isEmpty()) {
            for (MuWebSocket activeWebsocket : activeWebsockets()) {
                try {
                    activeWebsocket.onServerShuttingDown();
                } catch (Exception e) {
                    log.info("Error while aborting websocket: {}", e.getMessage());
                    forceShutdown();
                }
            }
        }
    }

    @Override
    boolean isShutdown() {
        return state == HttpConnectionState.CLOSED;
    }

    @Override
    void forceShutdown() {
        // todo thread safety
        if (state == HttpConnectionState.CLOSED) {
            return;
        }
        try {
            clientSocket.close();
        } catch (IOException ignored) {
        } finally {
            state = HttpConnectionState.CLOSED;
        }

    }
}

