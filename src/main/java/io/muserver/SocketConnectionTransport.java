package io.muserver;

import org.jspecify.annotations.Nullable;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.StandardConstants;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.cert.Certificate;
import java.util.List;

/** Established blocking socket transport. TLS handshaking is complete before construction. */
final class SocketConnectionTransport implements ConnectionTransport {
    // Package visibility permits socket-level fault injection in transport tests, not protocol code.
    final Socket socket;
    private final Socket acceptedSocket;
    private final InetSocketAddress remoteAddress;
    private final InetSocketAddress localAddress;
    private final @Nullable Certificate clientCertificate;

    SocketConnectionTransport(Socket socket, Socket acceptedSocket, @Nullable Certificate clientCertificate) {
        this.socket = socket;
        this.acceptedSocket = acceptedSocket;
        this.clientCertificate = clientCertificate;
        remoteAddress = (InetSocketAddress) socket.getRemoteSocketAddress();
        localAddress = (InetSocketAddress) socket.getLocalSocketAddress();
    }

    /** Preserve bytes read during cleartext protocol detection. */
    InputStream input(@Nullable InputStream providedInput) throws IOException {
        return providedInput == null ? socket.getInputStream() : providedInput;
    }

    OutputStream output() throws IOException {
        return socket.getOutputStream();
    }

    @Override public InetSocketAddress remoteAddress() { return remoteAddress; }
    @Override public InetSocketAddress localAddress() { return localAddress; }
    @Override public boolean isSecure() { return socket instanceof SSLSocket; }
    @Override public @Nullable Certificate clientCertificate() { return clientCertificate; }

    // Query the established session as before, including any later renegotiated session.
    @Override public @Nullable String tlsProtocol() {
        return socket instanceof SSLSocket ? ((SSLSocket) socket).getSession().getProtocol() : null;
    }
    @Override public @Nullable String cipherSuite() {
        return socket instanceof SSLSocket ? ((SSLSocket) socket).getSession().getCipherSuite() : null;
    }
    @Override public @Nullable String sniHostName() {
        return socket instanceof SSLSocket ? requestedServerName(((SSLSocket) socket).getSession()) : null;
    }

    static @Nullable String requestedServerName(SSLSession session) {
        if (!(session instanceof ExtendedSSLSession)) return null;
        List<SNIServerName> serverNames;
        try {
            serverNames = ((ExtendedSSLSession) session).getRequestedServerNames();
        } catch (UnsupportedOperationException unsupported) {
            // SNI introspection is optional for SSL providers.
            return null;
        }
        for (SNIServerName serverName : serverNames) {
            if (serverName.getType() == StandardConstants.SNI_HOST_NAME) {
                return serverName instanceof SNIHostName ? ((SNIHostName) serverName).getAsciiName()
                    : new SNIHostName(serverName.getEncoded()).getAsciiName();
            }
        }
        return null;
    }

    @Override public void readTimeoutMillis(int timeoutMillis) throws IOException { socket.setSoTimeout(timeoutMillis); }
    @Override public void shutdownInput() throws IOException { socket.shutdownInput(); }
    @Override public void close() throws IOException { socket.close(); }

    @Override
    public void abort() throws IOException {
        // Deliberately no shared close lock: TLS close() may be waiting for a stalled writer.
        acceptedSocket.close();
    }
}
