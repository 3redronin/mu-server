package scaffolding;

import io.muserver.HttpVersion;
import io.muserver.Method;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import java.io.*;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;

import static scaffolding.ClientUtils.veryTrustingTrustManager;

public class Http1Client implements AutoCloseable {

    private static final SSLSocketFactory sslSocketFactory;

    static {
        var trustAllCertificates = new TrustManager[]{veryTrustingTrustManager()};
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCertificates, new java.security.SecureRandom());
            sslSocketFactory = sslContext.getSocketFactory();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new RuntimeException(e);
        }
    }

    private final Socket socket;
    private final InputStream inputStream;
    private final OutputStream outputStream;

    public Http1Client(Socket socket, InputStream inputStream, OutputStream outputStream) {
        this.socket = socket;
        this.inputStream = inputStream;
        this.outputStream = outputStream;
    }

    public static Http1Client connect(URI uri) {
        try {
            Socket socket;
            if (uri.getScheme().equals("http")) {
                socket = new Socket(uri.getHost(), uri.getPort());
            } else {
                socket = sslSocketFactory.createSocket(uri.getHost(), uri.getPort());
            }
            OutputStream os = new BufferedOutputStream(socket.getOutputStream(), 8192);
            return new Http1Client(socket, socket.getInputStream(), os);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Http1Client writeRequestLine(Method method, URI uri) {
        return writeRequestLine(method, uri, HttpVersion.HTTP_1_1, true);
    }
    public Http1Client writeRequestLine(Method method, URI uri, HttpVersion httpVersion, boolean writeHost) {
        writeAscii(method.name() + " " + uri.getRawPath() + " " + httpVersion.version() + "\r\n");
        if (writeHost) {
            writeHeader("host", uri.getAuthority());
        }
        return this;
    }

    private void writeAscii(String s) {
        try {
            outputStream.write(s.getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Http1Client writeHeader(String name, Object value) {
        writeAscii(name + ": " + value.toString() + "\r\n");
        return this;
    }

    public Http1Client endHeaders() {
        writeAscii("\r\n");
        return this;
    }

    public OutputStream out() {
        return outputStream;
    }

    public InputStream in() {
        return inputStream;
    }

    public void close() {
        try {
            socket.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Http1Client flush() {
        try {
            outputStream.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }
}
