package io.muserver;

import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Various statistics about the current instance of a Mu Server. Accessible via the {@link MuServer#stats()} method.
 */
public interface MuStats {

    /**
     * Gets the number of completed connections.
     *
     * @return The total number of connections that have been closed since the server started (excludes {@link #activeConnections()}
     */
    long completedConnections();

    /**
     * Gets the number of currently active connections.
     *
     * @return The number of open TCP connections.
     */
    long activeConnections();

    /**
     * Gets the number of completed requests.
     *
     * @return The number of completed requests (excludes {@link #activeRequests()}
     */
    long completedRequests();

    /**
     * Gets the number of invalid HTTP requests received.
     *
     * @return The number of requests received that were not valid HTTP messages.
     */
    long invalidHttpRequests();

    /**
     * Gets the total number of bytes sent.
     *
     * @return The number of bytes sent by this server.
     */
    long bytesSent();

    /**
     * Gets the total number of bytes read.
     *
     * @return The number of bytes received by this server.
     */
    long bytesRead();

    /**
     * Gets the number of requests rejected because of overload.
     *
     * @return The number of requests rejected because the executor passed to {@link MuServerBuilder#withHandlerExecutor(ExecutorService)}
     * rejected a new response.
     */
    long rejectedDueToOverload();

    /**
     * Gets the number of failed connection attempts.
     *
     * @return The number of requests that failed to connect, e.g. due to SSL protocols not matching, or handshakes failing.
     */
    long failedToConnect();

    /**
     * Gets the currently active requests.
     *
     * @return The requests that are currently in-flight
     */
    Set<MuRequest> activeRequests();
}
