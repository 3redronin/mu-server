package io.muserver;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * A connection between a server and a client.
 */
public interface HttpConnection {

    /**
     * The HTTP protocol for the request.
     * @return A string such as <code>HTTP/1.1</code> or <code>HTTP/2</code>
     */
    String protocol();

    /**
     * @return <code>true</code> if the connnection is secured over HTTPS, otherwise <code>false</code>
     */
    boolean isHttps();

    /**
     * Gets the HTTPS protocol, for example "TLSv1.2" or "TLSv1.3"
     * @return The HTTPS protocol being used, or <code>null</code> if this connection is not over HTTPS.
     */
    String httpsProtocol();

    /**
     * @return The HTTPS cipher used on this connection, or <code>null</code> if this connection is not over HTTPS.
     */
    String cipher();

    /**
     * @return The time that this connection was established.
     */
    Instant startTime();

    /**
     * @return The socket address of the client.
     */
    InetSocketAddress remoteAddress();

    /**
     * @return The number of completed requests on this connection.
     */
    long completedRequests();

    /**
     * @return The number of requests received that were not valid HTTP messages.
     */
    long invalidHttpRequests();

    /**
     * @return The number of requests rejected because the executor passed to {@link MuServerBuilder#withHandlerExecutor(ExecutorService)}
     * rejected a new response.
     */
    long rejectedDueToOverload();


//    /**
//     * @return The number of bytes sent over this connection
//     */
//    long bytesSent();
//
//    /**
//     * @return The number of bytes received over this connection
//     */
//    long bytesReceived();

    /**
     * @return A readonly connection of requests that are in progress on this connection
     */
    Set<MuRequest> activeRequests();

    /**
     * The websockets on this connection.
     * <p>Note that in Mu Server websockets are only on HTTP/1.1 connections and there is a 1:1 mapping between
     * a websocket and an HTTP Connection. This means the returned set is either empty or has a size of 1.</p>
     * @return A readonly set of active websockets being used on this connection
     */
    Set<MuWebSocket> activeWebsockets();
}
