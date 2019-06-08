package io.muserver;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.junit.After;
import org.junit.Test;
import scaffolding.ClientUtils;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;
import scaffolding.StringUtils;

import java.io.IOException;
import java.net.ProtocolException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.muserver.WebSocketHandlerBuilder.webSocketHandler;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class WebSocketsTest {

    private MuServer server;
    private RecordingMuWebSocket serverSocket = new RecordingMuWebSocket();

    @Test
    public void handlersCanReturnNullWebSocketToHandleAsAWebSocket() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler(webSocketHandler().withWebSocketFactory(request -> {
                if (!request.relativePath().equals("/blah")) {
                    return null;
                }
                return serverSocket;
            }))
            .addHandler(Method.GET, "/not-blah", (request, response, pathParams) -> response.write("not a blah"))
            .start();

        WebSocket clientSocket = ClientUtils.client.newWebSocket(webSocketRequest(server.uri().resolve("/blah")), new WebSocketListener() {});

        MuAssert.assertNotTimedOut("Connecting", serverSocket.connectedLatch);

        clientSocket.send("This is a message");
        clientSocket.send(ByteString.encodeUtf8("This is a binary message"));
        clientSocket.send("Another text");
        clientSocket.close(1000, "Finished");
        MuAssert.assertNotTimedOut("Closing", serverSocket.closedLatch);
        assertThat(serverSocket.received, contains("connected", "onText: This is a message",
            "onBinary: This is a binary message", "onText: Another text", "onClose: 1000 Finished"));

        try (Response resp = call(request(server.uri().resolve("/not-blah")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("not a blah"));
        }
    }

    @Test
    public void routesWorkForWebsockets() {
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/routed-websocket", new WebSocketHandler(request -> serverSocket))
            .start();

        WebSocket clientSocket = ClientUtils.client.newWebSocket(webSocketRequest(server.uri().resolve("/routed-websocket")), new WebSocketListener() {});

        MuAssert.assertNotTimedOut("Connecting", serverSocket.connectedLatch);

        String largeText = StringUtils.randomStringOfLength(10000);

        clientSocket.send(largeText);
        clientSocket.send(ByteString.encodeUtf8(largeText));
        clientSocket.send("Another text");
        clientSocket.close(1000, "Finished");
        MuAssert.assertNotTimedOut("Closing", serverSocket.closedLatch);
        assertThat(serverSocket.received, contains("connected", "onText: " + largeText,
            "onBinary: " + largeText, "onText: Another text", "onClose: 1000 Finished"));
    }

    @Test
    public void pingAndPongWork() {
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/ws", new WebSocketHandler(request -> serverSocket))
            .start();

        WebSocket clientSocket = ClientUtils.client.newBuilder()
            .pingInterval(50, TimeUnit.MILLISECONDS)
            .build()
            .newWebSocket(webSocketRequest(server.uri().resolve("/ws")), new WebSocketListener() {});

        MuAssert.assertNotTimedOut("Connecting", serverSocket.connectedLatch);
        MuAssert.assertNotTimedOut("Pinging", serverSocket.pingLatch);
        clientSocket.close(1000, "Finished");
        MuAssert.assertNotTimedOut("Closing", serverSocket.closedLatch);
        assertThat(serverSocket.received, contains("connected", "onPing: ", "onClose: 1000 Finished"));
    }

    @Test
    public void theServerCanCloseSockets() {
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/ws", new WebSocketHandler(request -> serverSocket))
            .start();
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch closedLatch = new CountDownLatch(1);
        ClientUtils.client
            .newWebSocket(webSocketRequest(server.uri().resolve("/ws")), new WebSocketListener() {
                public void onClosing(WebSocket webSocket, int code, String reason) {
                    received.add("Closing " + code + " " + reason);
                    webSocket.close(code, reason);
                }
                public void onClosed(WebSocket webSocket, int code, String reason) {
                    received.add("Closed " + code + " " + reason);
                    closedLatch.countDown();
                }
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    received.add("Exception " + t);
                }
            });
        MuAssert.assertNotTimedOut("Connecting", serverSocket.connectedLatch);
        serverSocket.session.close(1001, "Umm");
        MuAssert.assertNotTimedOut("Closing", closedLatch);
        assertThat(received.toString(), received, contains("Closing 1001 Umm", "Closed 1001 Umm"));
    }


    @Test
    public void ifNotMatchedThenProtocolExceptionIsReturned() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/routed-websocket", new WebSocketHandler(request -> serverSocket))
            .start();

        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        ClientUtils.client.newWebSocket(webSocketRequest(server.uri().resolve("/non-existant")), new WebSocketListener() {
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                failure.complete(t);
            }
        });

        Throwable actual = failure.get(10, TimeUnit.SECONDS);
        assertThat(actual, instanceOf(ProtocolException.class));
    }

    private static Request webSocketRequest(URI httpVersionOfUri) {
        return request().url("ws" + httpVersionOfUri.toString().substring(4)).build();
    }


    @After
    public void clean() {
        MuAssert.stopAndCheck(server);
    }

    private static class RecordingMuWebSocket implements MuWebSocket {
        private MuWebSocketSession session;
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch connectedLatch = new CountDownLatch(1);
        CountDownLatch closedLatch = new CountDownLatch(1);
        CountDownLatch pingLatch = new CountDownLatch(1);

        @Override
        public void onConnect(MuWebSocketSession session) {
            this.session = session;
            received.add("connected");
            connectedLatch.countDown();
        }

        @Override
        public void onText(String message) {
            received.add("onText: " + message);
            session.sendText(message.toUpperCase());
        }

        @Override
        public void onBinary(ByteBuffer buffer) {
            received.add("onBinary: " + UTF_8.decode(buffer));

        }

        @Override
        public void onClose(int statusCode, String reason) {
            received.add("onClose: " + statusCode + " " + reason);
            closedLatch.countDown();
        }

        @Override
        public void onPing(ByteBuffer payload) {
            received.add("onPing: " + UTF_8.decode(payload));
            session.sendPong(payload);
            pingLatch.countDown();
        }

        @Override
        public void onPong(ByteBuffer payload) {
            received.add("onPong: " + UTF_8.decode(payload));
        }
    }
}
