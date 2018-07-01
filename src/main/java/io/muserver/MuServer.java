package io.muserver;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;

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
}
