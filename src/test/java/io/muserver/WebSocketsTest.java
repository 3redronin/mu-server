package io.muserver;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.junit.After;
import org.junit.Assert;
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
import java.util.concurrent.*;

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

        ClientListener clientListener = new ClientListener();
        WebSocket clientSocket = ClientUtils.client.newWebSocket(webSocketRequest(server.uri().resolve("/blah")), clientListener);

        MuAssert.assertNotTimedOut("Connecting", serverSocket.connectedLatch);

        clientSocket.send("This is a message");
        clientSocket.send(ByteString.encodeUtf8("This is a binary message"));
        clientSocket.send("Another text");
        clientSocket.close(1000, "Finished");
        MuAssert.assertNotTimedOut("Closing", serverSocket.closedLatch);
        assertThat(serverSocket.received, contains("connected", "onText: This is a message",
            "onBinary: This is a binary message", "onText: Another text", "onClose: 1000 Finished"));

        assertThat(clientListener.toString(), clientListener.events,
            contains("onOpen", "onMessage text: THIS IS A MESSAGE", "onMessage binary: This is a binary message", "onMessage text: ANOTHER TEXT"));

        try (Response resp = call(request(server.uri().resolve("/not-blah")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("not a blah"));
        }
    }

    @Test
    public void pathsWorkForWebsockets() {
        server = ServerUtils.httpsServerForTest()
            .addHandler(webSocketHandler(request -> serverSocket).withPath("/routed-websocket"))
            .start();

        WebSocket clientSocket = ClientUtils.client.newWebSocket(webSocketRequest(server.uri().resolve("/routed-websocket")), new ClientListener());

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
    public void sendingMessagesAfterTheClientsCloseResultInExceptions() throws InterruptedException, ExecutionException, TimeoutException, IOException {
        CompletableFuture<MuWebSocketSession> sessionFuture = new CompletableFuture<>();
        server = ServerUtils.httpsServerForTest()
            .addHandler(webSocketHandler(request -> new BaseWebSocket() {
                @Override
                public void onConnect(MuWebSocketSession session) {
                    super.onConnect(session);
                    sessionFuture.complete(session);
                }
            }).withPath("/routed-websocket"))
            .start();

        WebSocket clientSocket = ClientUtils.client.newWebSocket(webSocketRequest(server.uri().resolve("/routed-websocket")), new ClientListener());
        MuWebSocketSession serverSession = sessionFuture.get(10, TimeUnit.SECONDS);
        clientSocket.close(1000, "Closing");

        for (int i = 0; i < 100; i++) {
            try {
                serverSession.sendText("This shouldn't work");
            } catch (IOException ignored) {
                return; // IOException, as expected. Might take a couple of attempts to get there though, hence the loop
            }
        }

        Assert.fail("No exceptions thrown");

    }

    @Test
    public void pingAndPongWork() {
        server = ServerUtils.httpsServerForTest()
            .addHandler(webSocketHandler(request -> serverSocket).withPath("/ws"))
            .start();

        WebSocket clientSocket = ClientUtils.client.newBuilder()
            .pingInterval(50, TimeUnit.MILLISECONDS)
            .build()
            .newWebSocket(webSocketRequest(server.uri().resolve("/ws")), new ClientListener());

        MuAssert.assertNotTimedOut("Connecting", serverSocket.connectedLatch);
        MuAssert.assertNotTimedOut("Pinging", serverSocket.pingLatch);
        clientSocket.close(1000, "Finished");
        MuAssert.assertNotTimedOut("Closing", serverSocket.closedLatch);
        assertThat(serverSocket.received, contains("connected", "onPing: ", "onClose: 1000 Finished"));
    }

    @Test
    public void theServerCanCloseSockets() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler(webSocketHandler(request -> serverSocket).withPath("/ws"))
            .start();
        ClientListener clientListener = new ClientListener();
        ClientUtils.client.newWebSocket(webSocketRequest(server.uri().resolve("/ws")), clientListener);
        MuAssert.assertNotTimedOut("Connecting", serverSocket.connectedLatch);
        serverSocket.session.close(1001, "Umm");
        MuAssert.assertNotTimedOut("Closing", clientListener.closedLatch);
        assertThat(clientListener.toString(), clientListener.events,
            contains("onOpen", "onClosing 1001 Umm", "onClosed 1001 Umm"));
    }


    @Test
    public void ifNotMatchedThenProtocolExceptionIsReturned() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .addHandler(webSocketHandler(request -> serverSocket).withPath("/routed-websocket"))
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
        public void onText(String message) throws IOException {
            received.add("onText: " + message);
            session.sendText(message.toUpperCase());
        }

        @Override
        public void onBinary(ByteBuffer buffer) throws IOException {
            int initial = buffer.position();
            session.sendBinary(buffer);
            buffer.position(initial);
            received.add("onBinary: " + UTF_8.decode(buffer));

        }

        @Override
        public void onClose(int statusCode, String reason) {
            received.add("onClose: " + statusCode + " " + reason);
            closedLatch.countDown();
        }

        @Override
        public void onPing(ByteBuffer payload) throws IOException {
            received.add("onPing: " + UTF_8.decode(payload));
            session.sendPong(payload);
            pingLatch.countDown();
        }

        @Override
        public void onPong(ByteBuffer payload) {
            received.add("onPong: " + UTF_8.decode(payload));
        }
    }

    private static class ClientListener extends WebSocketListener {

        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch closedLatch = new CountDownLatch(1);

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            events.add("onOpen");
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            events.add("onMessage text: " + text);
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            events.add("onMessage binary: " + bytes.string(UTF_8));
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            events.add("onClosing " + code + " " + reason);
            webSocket.close(code, reason);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            events.add("onClosed " + code + " " + reason);
            closedLatch.countDown();
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            events.add("onFailure: " + t.getMessage());
        }

        @Override
        public String toString() {
            return events.toString();
        }
    }
}
