package io.muserver;

import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Properties;

/**
 * A web server handler. Create and start a web server by using {@link MuServerBuilder#httpsServer()} or
 * {@link MuServerBuilder#httpServer()}
 */
public interface MuServer {
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
     * Changes the HTTPS certificate. This can be changed without restarting the server.
     * @param newSSLContext The new SSL Context to use.
     * @deprecated Use {@link #changeSSLContext(SSLContextBuilder)} instead.
     */
    @Deprecated
    void changeSSLContext(SSLContext newSSLContext);

    /**
     * Changes the HTTPS certificate. This can be changed without restarting the server.
     * @param newSSLContext The new SSL Context to use.
     */
    void changeSSLContext(SSLContextBuilder newSSLContext);

    /**
     * Gets the SSL info of the server, or null if SSL is not enabled.
     * @return A description of the actual SSL settings used, or null.
     */
    SSLInfo sslInfo();
}
