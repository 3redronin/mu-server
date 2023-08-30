package scaffolding;

import io.muserver.*;

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
    private final MuServer server;

    public Http1Client(Socket socket, InputStream inputStream, OutputStream outputStream, MuServer server) {
        this.socket = socket;
        this.inputStream = inputStream;
        this.outputStream = outputStream;
        this.server = server;
    }

    public static Http1Client connect(MuServer server) {
        try {
            var uri = server.uri();
            Socket socket;
            if (uri.getScheme().equals("http")) {
                socket = new Socket(uri.getHost(), uri.getPort());
            } else {
                socket = sslSocketFactory.createSocket(uri.getHost(), uri.getPort());
            }
            OutputStream os = new BufferedOutputStream(socket.getOutputStream(), 8192);
            return new Http1Client(socket, socket.getInputStream(), os, server);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Http1Client writeRequestLine(Method method, String uri) {
        return writeRequestLine(method, server.uri().resolve(uri), HttpVersion.HTTP_1_1, true);
    }
    public Http1Client writeRequestLine(Method method, URI uri, HttpVersion httpVersion, boolean writeHost) {
        writeAscii(method.name() + " " + uri.getRawPath() + " " + httpVersion.version() + "\r\n");
        if (writeHost) {
            writeHeader("host", server.uri().getAuthority());
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


    public HttpVersion readVersion() throws IOException {
        return HttpVersion.valueOf(readUntil(' ', 10));
    }

    public String readLine() throws IOException {
        return readUntil('\r', 10000);
    }

    public String readBody(Headers headers) throws IOException {
        if (!headers.hasBody()) return "";
        if (headers.contains(HeaderNames.TRANSFER_ENCODING, HeaderValues.CHUNKED, true)) {
            throw new RuntimeException("Chunked not supported yet");
        } else {
            int toRead = headers.getInt(HeaderNames.CONTENT_LENGTH, 0);
            byte[] buf = new byte[toRead];
            int off = 0;
            while (toRead > 0) {
                int actuallyRead = inputStream.read(buf, off, toRead);
                if (actuallyRead == -1) throw new EOFException("EOF while reading response body");
                off += actuallyRead;
                toRead -= actuallyRead;
            }
            String charset = headers.contentType().getParameters().getOrDefault("charset", "UTF-8");
            return new String(buf, charset);
        }

    }

    public Headers readHeaders() throws IOException {
        String line;
        var headers = Headers.http1Headers();
        while (!(line = readUntil('\r', 1000)).isEmpty()) {
            String[] bits = line.split(":", 2);
            headers.add(bits[0], bits[1].trim());
        }
        return headers;
    }

    public String readUntil(char target, int maxLength) throws IOException {
        var sb = new StringBuilder();
        while (true) {
            int i = inputStream.read();
            if (i == -1) throw new EOFException(sb.toString());
            var c = (char) i;
            if (c == target) {
                if (target == '\r') {
                    if (((char)inputStream.read()) != '\n') throw new IllegalStateException("Expected a newline after carriage return. " + sb);
                }
                return sb.toString();
            } else {
                sb.append(c);
            }
            if (sb.length() > maxLength) throw new RuntimeException("Too long. Stopped at: " + sb);
        }
    }

}
