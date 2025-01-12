package io.muserver;

import org.jspecify.annotations.Nullable;

import javax.net.ssl.TrustManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.cert.Certificate;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * A connection between a server and a client.
 */
public interface HttpConnection {

    /**
     * The HTTP protocol for the connection.
     * @return A string such as <code>HTTP/1.1</code> or <code>HTTP/2</code>
     * @deprecated Use {@link #httpVersion()} instead
     */
    @Deprecated
    default String protocol() {
        return httpVersion().version();
    }

    /**
     * The HTTP protocol for the connection.
     * @return the version of HTTP supported.
     */
    HttpVersion httpVersion();

    /**
     * Gets the number of milliseconds that this connection has not had any read or write operations
     */
    long idleTimeMillis();

    /**
     * @return <code>true</code> if the connnection is secured over HTTPS, otherwise <code>false</code>
     */
    boolean isHttps();

    /**
     * Gets the HTTPS protocol, for example "TLSv1.2" or "TLSv1.3"
     * @return The HTTPS protocol being used, or <code>null</code> if this connection is not over HTTPS.
     */
    @Nullable
    String httpsProtocol();

    /**
     * @return The HTTPS cipher used on this connection, or <code>null</code> if this connection is not over HTTPS.
     */
    @Nullable
    String cipher();

    /**
     * @return The time that this connection was established.
     */
    Instant startTime();

    /**
     * This is the time taken between a socket being accepted from the client and it being ready to use.
     * <p>This may include things such as TLS handshake time, or HTTP2 handshaking.</p>
     * @return The time taken to perform any necessary handshakes
     */
    long handshakeDurationMillis();

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

    /**
     * @return The server that this connection belongs to
     */
    MuServer server();

    /**
     * Gets the TLS certificate the client sent.
     * <p>The returned certificate will be {@link Optional#empty()} when:</p>
     * <ul>
     *     <li>The client did not send a certificate, or</li>
     *     <li>The client sent a certificate that failed verification with the client trust manager, or</li>
     *     <li>No client trust manager was set with {@link HttpsConfigBuilder#withClientCertificateTrustManager(TrustManager)}, or</li>
     *     <li>The request was not sent over HTTPS</li>
     * </ul>
     * @return The client certificate, or <code>empty</code> if no certificate is available
     */
    Optional<Certificate> clientCertificate();

    /**
     * Closes this connection immediately, causing a connection reset on the client side.
     * <p>Generally, it is not recommended to use this as it may result in EOF errors for clients due to
     * skipping shutdown protocols.</p>
     */
    void abort() throws IOException;

    /**
     * Checks if this connection is being used or not
     * @return <code>true</code> if there are any active requests or websockets on this connection; otherwise <code>false</code>
     */
    boolean isIdle();
}

