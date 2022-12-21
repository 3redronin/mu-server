package io.muserver;

import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * A web server handler. Create and start a web server by using {@link MuServerBuilder#httpsServer()} or
 * {@link MuServerBuilder#httpServer()}
 */
public interface MuServer {

    /**
     * Shuts down the server
     */
    void stop();

    /**
     * @return The HTTPS (or if unavailable the HTTP) URI of the web server.
     */
    URI uri();

    /**
     * @return The HTTP URI of the web server, if HTTP is supported; otherwise null
     */
    URI httpUri();

    /**
     * @return The HTTPS URI of the web server, if HTTPS is supported; otherwise null
     */
    URI httpsUri();

    /**
     * @return Provides stats about the server
     */
    MuStats stats();

    /**
     * @return The current HTTP connections between this server and its clients.
     */
    Set<HttpConnection> activeConnections();

    /**
     * @return The address of the server. To get the ip address, use {@link InetSocketAddress#getAddress()} and on that
     * call {@link InetAddress#getHostAddress()}. To get the hostname, use {@link InetSocketAddress#getHostName()} or
     * {@link InetSocketAddress#getHostString()}.
     */
    InetSocketAddress address();

    /**
     * @return Returns the current version of MuServer, or 0.x if unknown
     */
    static String artifactVersion() {
        try {
            Properties props = new Properties();
            InputStream in = MuServer.class.getResourceAsStream("/META-INF/maven/io.muserver/mu-server/pom.properties");
            if (in == null) {
                return "0.x";
            }
            try {
                props.load(in);
            } finally {
                in.close();
            }
            return props.getProperty("version");
        } catch (Exception ex) {
            return "0.x";
        }
    }

    /**
     * The size a response body must be before GZIP is enabled, if {@link #gzipEnabled()} is true and the mime type is in {@link #mimeTypesToGzip()}
     * <p>This can only be set at point of server creation with {@link MuServerBuilder#withGzip(long, Set)}</p>
     * @return Size in bytes.
     */
    long minimumGzipSize();

    /**
     * The maximum allowed size of request headers.
     * <p>This can only be set at point of server creation with {@link MuServerBuilder#withMaxHeadersSize(int)}</p>
     * @return Size in bytes.
     */
    int maxRequestHeadersSize();

    /**
     * The maximum idle timeout for reading request bodies.
     * <p>This can only be set at point of server creation with {@link MuServerBuilder#withIdleTimeout(long, TimeUnit)}</p>
     * @return Timeout in milliseconds.
     */
    long requestIdleTimeoutMillis();

    /**
     * The maximum allowed size of a request body.
     * <p>This can only be set at point of server creation with {@link MuServerBuilder#withMaxRequestSize(long)}</p>
     * @return Size in bytes.
     */
    long maxRequestSize();

    /**
     * The maximum allowed size of the URI sent in a request line.
     * <p>This can only be set at point of server creation with {@link MuServerBuilder#withMaxUrlSize(int)}</p>
     * @return Length of allowed URI string.
     */
    int maxUrlSize();

    /**
     * Specifies whether GZIP is on or not.
     * <p>This can only be set at point of server creation with {@link MuServerBuilder#withGzipEnabled(boolean)} or
     * {@link MuServerBuilder#withGzip(long, Set)}</p>
     * @return True if gzip is enabled for responses that match gzip criteria; otherwise false.
     */
    boolean gzipEnabled();

    /**
     * Specifies the mime-types that GZIP should be applied to.
     * <p>This can only be set at point of server creation with {@link MuServerBuilder#withGzip(long, Set)}</p>
     * @return A set of mime-types.
     */
    Set<String> mimeTypesToGzip();

    /**
     * Changes the HTTPS certificate. This can be changed without restarting the server.
     * @param newSSLContext The new SSL Context to use.
     * @deprecated Use {@link #changeSSLContext(SSLContextBuilder)} instead.
     */
    @Deprecated
    void changeSSLContext(SSLContext newSSLContext);

    /**
     * Changes the HTTPS certificate. This can be changed without restarting the server.
     * @param newSSLContext The new SSL Context to use.
     * @deprecated Use {@link #changeHttpsConfig(HttpsConfigBuilder)} instead
     */
    @Deprecated
    void changeSSLContext(SSLContextBuilder newSSLContext);


    /**
     * Changes the HTTPS certificate. This can be changed without restarting the server.
     * @param newHttpsConfig The new SSL Context to use.
     */
    void changeHttpsConfig(HttpsConfigBuilder newHttpsConfig);

    /**
     * Gets the SSL info of the server, or null if SSL is not enabled.
     * @return A description of the actual SSL settings used, or null.
     */
    SSLInfo sslInfo();

    /**
     * @return The rate limiters added to the server with {@link MuServerBuilder#withRateLimiter(RateLimitSelector)}, in the order they are applied.
     */
    List<RateLimiter> rateLimiters();
}
