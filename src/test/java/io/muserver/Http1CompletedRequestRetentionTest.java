package io.muserver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import scaffolding.Http1Client;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import java.io.BufferedOutputStream;
import java.net.Socket;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ClientUtils.veryTrustingTrustManager;

class Http1CompletedRequestRetentionTest {
    private MuServer server;

    static Stream<Arguments> requests() {
        return Stream.of("http", "https").flatMap(protocol ->
            Stream.of("none", "fixed", "chunked").flatMap(body ->
                Stream.of(1, 10).map(batchSize -> Arguments.of(protocol, body, batchSize))));
    }

    @ParameterizedTest(name = "{0}, body={1}, pipeline={2}")
    @MethodSource("requests")
    void completedRequestsAreNotRetainedByTheLiveConnection(String protocol, String body, int batchSize) throws Exception {
        CountDownLatch completed = new CountDownLatch(100);
        AtomicReference<Http1Connection> connection = new AtomicReference<>();
        server = ServerUtils.httpsServerForTest(protocol)
            .addResponseCompleteListener(info -> completed.countDown())
            .addHandler((request, response) -> {
                connection.set((Http1Connection) request.connection());
                response.status(204);
                response.headers().set("X-Request", request.uri().getPath());
                // Deliberately leave the body unread: connection cleanup must drain it.
                return true;
            }).start();

        var queueField = Http1Connection.class.getDeclaredField("requestPipeline");
        queueField.setAccessible(true);
        try (Socket socket = connect();
             Http1Client client = new Http1Client(socket, socket.getInputStream(), new BufferedOutputStream(socket.getOutputStream()), server.uri())) {
            for (int batch = 0; batch < 100; batch += batchSize) {
                for (int i = 1; i <= batchSize; i++) {
                    client.writeRequestLine(body.equals("none") ? Method.GET : Method.POST, "/request-" + (batch + i));
                    if (body.equals("fixed")) {
                        client.writeHeader("Content-Length", 4).endHeaders().writeAscii("body");
                    } else if (body.equals("chunked")) {
                        client.writeHeader("Transfer-Encoding", "chunked").endHeaders().writeAscii("4\r\nbody\r\n0\r\n\r\n");
                    } else {
                        client.endHeaders();
                    }
                }
                client.flush();
                for (int i = 1; i <= batchSize; i++) {
                    assertTrue(client.readLine().startsWith("HTTP/1.1 204 "));
                    assertEquals("/request-" + (batch + i), client.readHeaders().get("X-Request"));
                }
            }
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertEquals(100, server.stats().completedRequests());
            assertTrue(server.stats().activeRequests().isEmpty());
            int retainedAfterHundred = ((Queue<?>) queueField.get(connection.get())).size();
            assertEquals(0, retainedAfterHundred, "Completed request metadata remains reachable from the live connection");
        }
    }

    private Socket connect() throws Exception {
        Socket socket;
        if (server.uri().getScheme().equals("https")) {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{veryTrustingTrustManager()}, null);
            socket = context.getSocketFactory().createSocket("localhost", server.uri().getPort());
        } else {
            socket = new Socket("localhost", server.uri().getPort());
        }
        socket.setSoTimeout(3000);
        return socket;
    }

    @AfterEach
    void stopServer() {
        MuAssert.stopAndCheck(server);
    }
}
