package io.muserver;

import java.util.Set;

public interface MuStats {

    /**
     * @return The number of Socket connections (including idle kept-alive connections)
     */
    long activeConnections();

    /**
     * @return The number of requests that have completed processing.
     */
    long completedRequests();

    /**
     * @return The number of invalid HTTP requests (e.g. malformed requests) sent to the server.
     */
    long invalidHttpRequests();

    /**
     * @return The number of bytes that have been sent.
     */
    long bytesSent();

    /**
     * @return The number of bytes that have been received.
     */
    long bytesRead();

    /**
     * @return The number of requests that are currently active.
     */
    long currentRequests();

    Set<MuRequest> activeRequests();
}
