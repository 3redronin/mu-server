package io.muserver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import scaffolding.Http1Client;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ClientUtils.veryTrustingTrustManager;

class Http1ResponseBufferingTest {
    private MuServer server;

    @ParameterizedTest
    @CsvSource({"http,false", "https,false", "http,true", "https,true"})
    void flushDeliversBufferedBytesBeforeTheHandlerCompletes(String protocol, boolean chunked) throws Exception {
        exerciseOutputStream(protocol, chunked, 8192);
    }

    @ParameterizedTest
    @CsvSource({"http,false", "https,false", "http,true", "https,true"})
    void zeroBufferDeliversWritesWithoutAnExplicitFlush(String protocol, boolean chunked) throws Exception {
        exerciseOutputStream(protocol, chunked, 0);
    }

    private void exerciseOutputStream(String protocol, boolean chunked, int bufferSize) throws Exception {
        var written = new CountDownLatch(1);
        var flush = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        server = ServerUtils.httpsServerForTest(protocol)
            .addHandler((request, response) -> {
                if (!chunked) response.headers().set(HeaderNames.CONTENT_LENGTH, 2);
                var out = response.outputStream(bufferSize);
                out.write('a');
                written.countDown();
                assertTrue(flush.await(5, TimeUnit.SECONDS));
                out.flush();
                assertTrue(finish.await(5, TimeUnit.SECONDS));
                out.write('b');
                return true; // Cleanup must send the final bytes and preserve keep-alive.
            }).start();

        try (Socket socket = connect();
             var client = new Http1Client(socket, socket.getInputStream(), socket.getOutputStream(), server.uri())) {
            client.writeRequestLine(Method.GET, "/").endHeaders().flush();
            assertTrue(written.await(5, TimeUnit.SECONDS));
            if (bufferSize > 0) {
                socket.setSoTimeout(200);
                assertThrows(SocketTimeoutException.class, () -> client.in().read());
                socket.setSoTimeout(3000);
                flush.countDown();
            }
            readHeaders(client, chunked);
            readPiece(client, chunked, 'a');
            // The peer received 'a' while the handler is still waiting on this latch.
            flush.countDown();
            finish.countDown();
            readPiece(client, chunked, 'b');
            readEnd(client, chunked);

            client.writeRequestLine(Method.GET, "/").endHeaders().flush();
            readHeaders(client, chunked);
            readPiece(client, chunked, 'a');
            readPiece(client, chunked, 'b');
            readEnd(client, chunked);
        } finally {
            flush.countDown();
            finish.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "https"})
    void sendChunkDeliversEachChunkBeforeTheNextOneIsProduced(String protocol) throws Exception {
        var next = new CountDownLatch(1);
        server = ServerUtils.httpsServerForTest(protocol)
            .addHandler((request, response) -> {
                response.sendChunk("a");
                assertTrue(next.await(5, TimeUnit.SECONDS));
                response.sendChunk("b");
                return true;
            }).start();
        try (Socket socket = connect();
             var client = new Http1Client(socket, socket.getInputStream(), socket.getOutputStream(), server.uri())) {
            client.writeRequestLine(Method.GET, "/").endHeaders().flush();
            readHeaders(client, true);
            readPiece(client, true, 'a');
            next.countDown();
            readPiece(client, true, 'b');
            readEnd(client, true);
        } finally {
            next.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "https"})
    void aFullSmallBufferMakesProgressWithoutAnExplicitFlush(String protocol) throws Exception {
        var finish = new CountDownLatch(1);
        server = ServerUtils.httpsServerForTest(protocol)
            .addHandler((request, response) -> {
                response.headers().set(HeaderNames.CONTENT_LENGTH, 2);
                var out = response.outputStream(1);
                out.write(new byte[]{'a'});
                assertTrue(finish.await(5, TimeUnit.SECONDS));
                out.write('b');
                return true;
            }).start();
        try (Socket socket = connect();
             var client = new Http1Client(socket, socket.getInputStream(), socket.getOutputStream(), server.uri())) {
            client.writeRequestLine(Method.GET, "/").endHeaders().flush();
            readHeaders(client, false);
            readPiece(client, false, 'a');
            finish.countDown();
            readPiece(client, false, 'b');
        } finally {
            finish.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "https"})
    void informationalResponsesAreFlushedBeforeTheFinalResponse(String protocol) throws Exception {
        var finish = new CountDownLatch(1);
        server = ServerUtils.httpsServerForTest(protocol)
            .addHandler((request, response) -> {
                response.sendInformationalResponse(HttpStatus.of(103), Headers.create().set("Link", "</app.css>"));
                assertTrue(finish.await(5, TimeUnit.SECONDS));
                response.write("ab");
                return true;
            }).start();
        try (Socket socket = connect();
             var client = new Http1Client(socket, socket.getInputStream(), socket.getOutputStream(), server.uri())) {
            client.writeRequestLine(Method.GET, "/").endHeaders().flush();
            assertTrue(client.readLine().startsWith("HTTP/1.1 103 "));
            assertEquals("</app.css>", client.readHeaders().get("Link"));
            finish.countDown();
            readHeaders(client, false);
            readPiece(client, false, 'a');
            readPiece(client, false, 'b');
        } finally {
            finish.countDown();
        }
    }

    @ParameterizedTest
    @CsvSource({"http,message", "https,message", "http,comment", "https,comment", "http,retry", "https,retry"})
    void sseSendsImmediatelyBeforeThePublisherCloses(String protocol, String kind) throws Exception {
        var finish = new CountDownLatch(1);
        String expected = kind.equals("message") ? "data: a\n\n"
            : kind.equals("comment") ? ":a\n\n" : "retry: 10\n";
        server = ServerUtils.httpsServerForTest(protocol)
            .addHandler((request, response) -> {
                try (var publisher = SsePublisher.start(request, response)) {
                    if (kind.equals("message")) publisher.send("a");
                    else if (kind.equals("comment")) publisher.sendComment("a");
                    else publisher.setClientReconnectTime(10, TimeUnit.MILLISECONDS);
                    assertTrue(finish.await(5, TimeUnit.SECONDS));
                }
                return true;
            }).start();
        try (Socket socket = connect();
             var client = new Http1Client(socket, socket.getInputStream(), socket.getOutputStream(), server.uri())) {
            client.writeRequestLine(Method.GET, "/").endHeaders().flush();
            readHeaders(client, true);
            assertEquals(Integer.toHexString(expected.length()), client.readLine());
            assertEquals(expected, new String(client.in().readNBytes(expected.length()), StandardCharsets.UTF_8));
            assertEquals("", client.readLine());
            finish.countDown();
            readEnd(client, true);
        } finally {
            finish.countDown();
        }
    }

    private Socket connect() throws Exception {
        Socket socket;
        if (server.uri().getScheme().equals("https")) {
            var context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{veryTrustingTrustManager()}, null);
            socket = context.getSocketFactory().createSocket("127.0.0.1", server.uri().getPort());
        } else {
            socket = new Socket("127.0.0.1", server.uri().getPort());
        }
        socket.setSoTimeout(3000);
        return socket;
    }

    private static void readHeaders(Http1Client client, boolean chunked) throws Exception {
        assertEquals("HTTP/1.1 200 OK", client.readLine());
        Headers headers = client.readHeaders();
        assertEquals(chunked ? "chunked" : "2", headers.get(chunked ? "Transfer-Encoding" : "Content-Length"));
    }

    private static void readPiece(Http1Client client, boolean chunked, char value) throws Exception {
        if (chunked) assertEquals("1", client.readLine());
        assertEquals(value, client.in().read());
        if (chunked) assertEquals("", client.readLine());
    }

    private static void readEnd(Http1Client client, boolean chunked) throws Exception {
        if (chunked) {
            assertEquals("0", client.readLine());
            assertEquals("", client.readLine());
        }
    }

    @AfterEach
    void stopServer() {
        MuAssert.stopAndCheck(server);
    }
}
