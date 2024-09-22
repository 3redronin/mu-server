package scaffolding;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;

/**
 * A wrapper of SSLSocketFactory which provide extra SSLParameters on creating SSL sockets
 *
 * referring <a href="http://javabreaks.blogspot.com/2015/12/java-ssl-handshake-with-server-name.html">java-ssl-handshake-with-server-name</a>
 */
public class SSLSocketFactoryWrapper extends SSLSocketFactory {

    private final SSLSocketFactory wrappedFactory;
    private final SSLParameters sslParameters;

    public SSLSocketFactoryWrapper(SSLSocketFactory factory, SSLParameters sslParameters) {
        this.wrappedFactory = factory;
        this.sslParameters = sslParameters;
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        SSLSocket socket = (SSLSocket) wrappedFactory.createSocket(host, port);
        setParameters(socket);
        return socket;
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
        throws IOException, UnknownHostException {
        SSLSocket socket = (SSLSocket) wrappedFactory.createSocket(host, port, localHost, localPort);
        setParameters(socket);
        return socket;
    }


    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        SSLSocket socket = (SSLSocket) wrappedFactory.createSocket(host, port);
        setParameters(socket);
        return socket;
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        SSLSocket socket = (SSLSocket) wrappedFactory.createSocket(address, port, localAddress, localPort);
        setParameters(socket);
        return socket;

    }

    @Override
    public Socket createSocket() throws IOException {
        SSLSocket socket = (SSLSocket) wrappedFactory.createSocket();
        setParameters(socket);
        return socket;
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return wrappedFactory.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return wrappedFactory.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        SSLSocket socket = (SSLSocket) wrappedFactory.createSocket(s, host, port, autoClose);
        setParameters(socket);
        return socket;
    }

    private void setParameters(SSLSocket socket) {
        socket.setSSLParameters(sslParameters);
    }

}
