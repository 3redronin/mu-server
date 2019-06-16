package io.muserver;

import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Various statistics about the current instance of a Mu Server. Accessible via the {@link MuServer#stats()} method.
 */
public interface MuStats {

    /**
     * @return The number of open TCP connections.
     */
    long activeConnections();

    /**
     * @return The number of completed requests.
     */
    long completedRequests();

    /**
     * @return The number of requests received that were not valid HTTP messages.
     */
    long invalidHttpRequests();

    /**
     * @return The number of bytes sent by this server.
     */
    long bytesSent();

    /**
     * @return The number of bytes received by this server.
     */
    long bytesRead();

    /**
     * @return The number of requests rejected because the executor passed to {@link MuServerBuilder#withHandlerExecutor(ExecutorService)}
     * rejected a new response.
     */
    long rejectedDueToOverload();

    /**
     * @return The number of requests that failed to connect, e.g. due to SSL protocols not matching, or handshakes failing.
     */
    long failedToConnect();

    /**
     * @return The requests that are currently in-flight
     */
    Set<MuRequest> activeRequests();
}
