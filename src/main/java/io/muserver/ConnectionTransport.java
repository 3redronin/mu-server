package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.cert.Certificate;

/**
 * Connection control and metadata shared by the HTTP protocols. The byte path is supplied
 * separately: the current blocking drivers receive streams, while a readiness-driven driver
 * will supply bytes to the protocol decoders. Implementations must not run application callbacks.
 *
 * <p>Control operations can race reads, writes and each other. In particular, abort must remain
 * available while orderly close is waiting for TLS output; it must not share that wait or lock.
 * Address and negotiated TLS metadata remain available after close for completion listeners.</p>
 */
interface ConnectionTransport extends Closeable {
    InetSocketAddress remoteAddress();
    InetSocketAddress localAddress();
    boolean isSecure();
    @Nullable String tlsProtocol();
    @Nullable String cipherSuite();
    @Nullable String sniHostName();
    @Nullable Certificate clientCertificate();

    /** Configure the timeout for the current read phase; zero disables it. Does not perform a read. */
    void readTimeoutMillis(int timeoutMillis) throws IOException;

    /** Stop receiving input while preserving the ability to finish output. */
    void shutdownInput() throws IOException;

    /**
     * Close the underlying transport immediately, bypassing TLS close-notify and pending writes.
     * May run concurrently with close(). Subsequent close() may still release wrapper resources.
     */
    void abort() throws IOException;

    /** Close normally, including TLS close-notify where applicable. May block on the current adapter. */
    @Override
    void close() throws IOException;
}
