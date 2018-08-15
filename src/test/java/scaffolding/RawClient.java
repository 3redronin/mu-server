package scaffolding;

import java.io.*;
import java.net.Socket;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;

public class RawClient implements Closeable {
    private static final ExecutorService executorService = Executors.newCachedThreadPool();

    private final ByteArrayOutputStream baos = new ByteArrayOutputStream();
    private BufferedOutputStream request;
    private Socket socket;
    private InputStream response;
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicReference<Exception> exception = new AtomicReference<>();

    public static RawClient create(URI uri) throws IOException {
        RawClient rawClient = new RawClient();
        rawClient.start(uri);
        return rawClient;
    }

    private void start(URI uri) throws IOException {
        this.socket = new Socket(uri.getHost(), uri.getPort());
        this.request = new BufferedOutputStream(socket.getOutputStream(), 2048);
        this.response = socket.getInputStream();
        isConnected.set(true);

        executorService.submit(() -> {
            byte[] buffer = new byte[8192];
            int read;
            try {
                while ((read = response.read(buffer)) > -1) {
//                    System.out.println("Got " + read + " bytes: " + new String(buffer, 0, read, UTF_8));
                    if (read > 0) {
                        baos.write(buffer, 0, read);
                    }
                }
            } catch (IOException e) {
                exception.set(e);
                isConnected.set(false);
            }

        });
    }

    public void send(byte[] bytes) throws IOException {
        if (bytes.length > 0) {
            request.write(bytes);
        }
    }
    public void sendUTF8(String message) throws IOException {
//        System.out.println(" >> " + message);
        send(message.getBytes(UTF_8));
    }
    public void sendLine(String line) throws IOException {
        sendUTF8(line + "\r\n");
    }

    public void sendStartLine(String method, String target) throws IOException {
        sendLine(method + " " + target + " HTTP/1.1");
    }

    public void sendHeader(String name, String value) throws IOException {
        sendLine(name + ": " + value);
    }
    public void endHeaders() throws IOException {
        sendLine("");
    }
    public void flushRequest() throws IOException {
        request.flush();
    }

    public void closeRequest() throws IOException {
        request.close();
        request = null;
    }
    public void closeResponse() throws IOException {
        response.close();
        response = null;
    }

    public long bytesReceived() {
        return baos.size();
    }

    public String responseString() {
        try {
            return baos.toString("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    public byte[] asBytes() {
        return baos.toByteArray();
    }

    @Override
    public void close() {
        close(request);
        close(response);
        close(socket);
        isConnected.set(false);
    }

    private static void close(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
            }
        }
    }

    public void clear() {
        baos.reset();
    }
}
