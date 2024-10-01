package io.muserver;

import jakarta.ws.rs.ClientErrorException;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scaffolding.MuAssert;
import scaffolding.RawClient;
import scaffolding.ServerUtils;
import scaffolding.StringUtils;

import java.net.ProtocolException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;

import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.WebSocketHandlerBuilder.webSocketHandler;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.*;
import static scaffolding.MuAssert.assertEventually;
import static scaffolding.MuAssert.assertNotTimedOut;

@Disabled("No websockets yet")
public class WebSocketsTest {

    private MuServer server;
    private RecordingMuWebSocket serverSocket = new RecordingMuWebSocket();

    @Test
    public void handlersCanReturnNullOrWebSocketToHandleAsAWebSocket() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .addHandler(webSocketHandler().withWebSocketFactory((request, responseHeaders) -> {
                if (!request.relativePath().equals("/blah")) {
                    return null;
                }
                responseHeaders.set("upgrade-response-header", "hello");
                return serverSocket;
            }))
            .addHandler(Method.GET, "/not-blah", (request, response, pathParams) -> response.write("not a blah"))
            .start();

        ClientListener clientListener = new ClientListener();
        WebSocket clientSocket = client.newWebSocket(webSocketRequest(server.uri().resolve("/blah")), clientListener);

        assertNotTimedOut("Connecting", clientListener.connectedLatch);
        assertEventually(() -> serverSocket.state(), equalTo(WebsocketSessionState.OPEN));

        assertThat(clientListener.response.header("upgrade-response-header"), equalTo("hello"));

        clientSocket.send("This is a message");
        clientSocket.send(ByteString.encodeUtf8("This is a binary message"));
        clientSocket.send("Another text");
        clientSocket.close(1000, "Finished");
        assertNotTimedOut("Closing server socket", serverSocket.closedLatch);
        assertEventually(() -> serverSocket.state(), equalTo(WebsocketSessionState.CLIENT_CLOSED));
        assertThat(serverSocket.received, contains("connected", "onText (true): This is a message",
            "onBinary (true): This is a binary message", "onText (true): Another text", "onClientClosed: 1000 Finished"));

        assertEventually(() -> clientListener.events,
            contains("onOpen", "onMessage text: THIS IS A MESSAGE", "onMessage binary: This is a binary message", "onMessage text: ANOTHER TEXT", "onClosing 1000 Finished", "onClosed 1000 Finished"));

        try (Response resp = call(request(server.uri().resolve("/not-blah")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("not a blah"));
        }
    }

    @Test
    public void largeFramesCanBeSent() {
        server = ServerUtils.httpsServerForTest()
            .addHandler(webSocketHandler((request, responseHeaders) -> serverSocket))
            .start();
        ClientListener listener = new ClientListener();
        WebSocket clientSocket = client.newWebSocket(webSocketRequest(server.uri()), listener);
        assertNotTimedOut("Connecting", serverSocket.connectedLatch);
        String largeText = StringUtils.randomAsciiStringOfLength(60000);
        clientSocket.send(largeText);
        assertNotTimedOut("messageLatch", listener.messageLatch);
        assertThat(listener.events, contains("onOpen", "onMessage text: " + largeText.toUpperCase()));
        clientSocket.close(1000, "Done");
    }

    @Test
    public void ifMaxFrameLengthExceededThenSocketIsClosed() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .addHandler(webSocketHandler((request, responseHeaders) -> serverSocket)
                .withPath("/routed-websocket")
                .withMaxFramePayloadLength(1024)
            )
            .start();

        ClientListener listener = new ClientListener();
        WebSocket clientSocket = client.newWebSocket(webSocketRequest(server.uri().resolve("/routed-websocket")), listener);

        assertNotTimedOut("Connecting", serverSocket.connectedLatch);

        String largeText = StringUtils.randomStringOfLength(2000);
        clientSocket.send(largeText);

        assertNotTimedOut("Erroring", listener.failureLatch);
        assertEventually(() -> serverSocket.state(), equalTo(WebsocketSessionState.ERRORED));

        clientSocket.close(1000, "Finished");
    }

    @Test
    public void pathsWorkForWebsockets() {
        server = ServerUtils.httpsServerForTest()
            .addHandler(webSocketHandler((request, responseHeaders) -> serverSocket).withPath("/routed-websocket"))
            .start();

        WebSocket clientSocket = client.newWebSocket(webSocketRequest(server.uri().resolve("/routed-websocket")), new ClientListener());

        assertNotTimedOut("Connecting", serverSocket.connectedLatch);

        String largeText = StringUtils.randomStringOfLength(10000);

        clientSocket.send(largeText);
        clientSocket.send(ByteString.encodeUtf8(largeText));
        clientSocket.send("Another text");
        clientSocket.close(1000, "Finished");
        assertNotTimedOut("Closing", serverSocket.closedLatch);
        assertThat(serverSocket.received, contains("connected", "onText (true): " + largeText,
            "onBinary (true): " + largeText, "onText (true): Another text", "onClientClosed: 1000 Finished"));
    }

    @Test
    public void splitFramesAreSentWithIsLastFalse() throws Exception {
        serverSocket.logErrors = true;
        server = ServerUtils.httpsServerForTest()
            .addHandler(webSocketHandler((request, responseHeaders) -> serverSocket).withPath("/routed-websocket"))
            .start();

        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client(true);
        sslContextFactory.setEndpointIdentificationAlgorithm("https");
        HttpClient httpClient = new HttpClient(sslContextFactory);

        WebSocketClient client = new WebSocketClient(httpClient);

        class TestWebSocketClient extends WebSocketAdapter {
            private final CountDownLatch closureLatch = new CountDownLatch(1);

            @Override
            public void onWebSocketClose(int statusCode, String reason) {
                super.onWebSocketClose(statusCode, reason);
                closureLatch.countDown();
            }


            public void awaitClosure() throws InterruptedException {
                closureLatch.await();
            }
        }

        try {
            client.start();
            TestWebSocketClient socket = new TestWebSocketClient();
            Future<Session> fut = client.connect(socket, URI.create(server.uri().resolve("/routed-websocket").toString().replace("http", "ws")));
            Session session = fut.get();
            assertNotTimedOut("Connecting", serverSocket.connectedLatch);

            session.getRemote().sendPartialString("Hello, ", false);
            session.getRemote().sendPartialString("How you doin? ", false);
            session.getRemote().sendPartialString("Sorry you can't get through.", true);
            session.getRemote().sendString("Goodbye");
            session.getRemote().sendPartialBytes(Mutils.toByteBuffer("binary1"), false);
            session.getRemote().sendPartialBytes(Mutils.toByteBuffer("binary2"), true);
            session.getRemote().flush();

            assertEventually(() -> serverSocket.received, contains("connected", "onText (false): Hello, ", "onText (false): How you doin? ", "onText (true): Sorry you can't get through.", "onText (true): Goodbye", "onBinary (false): binary1", "onBinary (true): binary2"));
            session.close(1000, "Finished");
            socket.awaitClosure();
            assertNotTimedOut("Closing", serverSocket.closedLatch);
            assertThat(serverSocket.received.toString(), serverSocket.received, contains("connected", "onText (false): Hello, ", "onText (false): How you doin? ", "onText (true): Sorry you can't get through.", "onText (true): Goodbye", "onBinary (false): binary1", "onBinary (true): binary2", "onClientClosed: 1000 Finished"));
        } finally {
            client.stop();
            httpClient.stop();
        }
    }

    @Test
    public void asyncWritesWork() throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<String> result = new CompletableFuture<>();
        server = ServerUtils.httpsServerForTest()
            .addHandler(webSocketHandler((request, responseHeaders) -> new BaseWebSocket() {
                public void onText(String message, boolean isLast, DoneCallback onComplete) {
                    session().sendText("This is message one", error1 -> {
                        if (error1 != null) {
                            result.completeExceptionally(error1);
                        } else {
                            session().sendBinary(Mutils.toByteBuffer("Async binary"), error -> {
                                onComplete.onComplete(error);
                                if (error == null) {
                                    result.complete("Success");
                                } else {
                                    result.completeExceptionally(error);
                                }
                            });
                        }
                    });
                }
            }).withPath("/routed-websocket"))
            .start();

        ClientListener listener = new ClientListener();
        WebSocket clientSocket = client.newWebSocket(webSocketRequest(server.uri().resolve("/routed-websocket")), listener);
        clientSocket.send("Hey hey");
        clientSocket.close(1000, "Done");
        assertThat(result.get(10, TimeUnit.SECONDS), is("Success"));
        assertNotTimedOut("Client closed", listener.closedLatch);
        assertThat(listener.toString(), listener.events, contains(
            "onOpen", "onMessage text: This is message one",
            "onMessage binary: Async binary", "onClosing 1000 Done", "onClosed 1000 Done"));
    }

    @Test
    public void partialWritesArePossible() throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<String> result = new CompletableFuture<>();
        server = ServerUtils.httpsServerForTest()
            .addHandler(webSocketHandler((request, responseHeaders) -> new BaseWebSocket() {
                @Override
                public void onConnect(MuWebSocketSession session) throws Exception {
                    super.onConnect(session);
                    session.sendText("Partial one ", false, error -> {
                        session.sendText("Partial two ", false, error1 -> {
                            session.sendText("Last one", true, error2 -> {
                                session.sendBinary(Mutils.toByteBuffer("Hello "),false, error3 -> {
                                    session.sendBinary(Mutils.toByteBuffer("from binary"),true, error4 -> {
                                        result.complete("Success");
                                    });
                                });
                            });
                        });
                    });
                }
            }).withPath("/routed-websocket"))
            .start();

        ClientListener listener = new ClientListener();
        WebSocket clientSocket = client.newWebSocket(webSocketRequest(server.uri().resolve("/routed-websocket")), listener);
        assertThat(result.get(10, TimeUnit.SECONDS), is("Success"));
        clientSocket.close(1000, "Done");
        assertNotTimedOut("Client closed (received events: " + listener.events + ")", listener.closedLatch);
        assertThat(listener.toString(), listener.events, contains(
            "onOpen", "onMessage text: Partial one Partial two Last one", "onMessage binary: Hello from binary",
            "onClosing 1000 Done", "onClosed 1000 Done"));
    }

    @Test
    public void ifTheFactoryThrowsAnExceptionThenItIsReturnedToTheClient() {
        server = ServerUtils.httpsServerForTest()
            .addHandler(webSocketHandler((request, responseHeaders) -> {
                throw new ClientErrorException(409);
            }).withPath("/409"))
            .start();
        ClientListener listener = new ClientListener();
        client.newWebSocket(webSocketRequest(server.uri().resolve("/409")), listener);
        assertNotTimedOut("Failure", listener.failureLatch);
        assertThat(listener.events, contains("onFailure: Expected HTTP 101 response but was '409 Conflict'"));
    }

    @Test
    @Timeout(30)
    public void ifTheVersionIsNotSupportedThenA406IsReturned() throws Exception {
        server = httpServer()
            .addHandler(webSocketHandler((request, responseHeaders) -> serverSocket).withPath("/ws"))
            .start();
        RawClient rawClient = RawClient.create(server.uri())
            .sendStartLine("GET", "ws" + server.uri().resolve("/ws").toString().substring(4))
            .sendHeader("host", server.uri().getAuthority())
            .sendHeader("connection", "upgrade")
            .sendHeader("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
            .sendHeader("Sec-WebSocket-Version", "100")
            .sendHeader("Upgrade", "websocket")
            .endHeaders()
            .flushRequest();

        while (!rawClient.responseString().contains("HTTP/1.1 426 Upgrade Required")) {
            Thread.sleep(10);
        }

        assertThat(serverSocket.received, is(empty()));
    }

    @Test
    public void sendingMessagesAfterTheClientsCloseResultInFailureCallBacksForAsyncCalls() throws Exception {
        CompletableFuture<MuWebSocketSession> sessionFuture = new CompletableFuture<>();
        server = ServerUtils.httpsServerForTest()
            .addHandler(webSocketHandler((request, responseHeaders) -> new BaseWebSocket() {
                @Override
                public void onConnect(MuWebSocketSession session) throws Exception {
                    super.onConnect(session);
                    sessionFuture.complete(session);
                }
            }).withPath("/routed-websocket"))
            .start();

        WebSocket clientSocket = client.newWebSocket(webSocketRequest(server.uri().resolve("/routed-websocket")), new ClientListener());
        MuWebSocketSession serverSession = sessionFuture.get(10, TimeUnit.SECONDS);
        clientSocket.cancel();

        for (int i = 0; i < 100; i++) {
            CompletableFuture<String> result = new CompletableFuture<>();
            serverSession.sendText("This shouldn't work", error -> {
                if (error == null) {
                    result.complete("Success");
                } else {
                    result.completeExceptionally(error);
                }
            });
            try {
                result.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                return; // as expected
            }
        }
        Assertions.fail("This should have failed");
    }

    @Test
    public void clientLeavingUnexpectedlyResultsInOnErrorWithClientDisconnectedException() {
        server = ServerUtils.httpsServerForTest()
            .addHandler(webSocketHandler((request, responseHeaders) -> serverSocket))
            .start();

        ClientListener listener = new ClientListener();
        WebSocket clientSocket = client.newWebSocket(webSocketRequest(server.uri()), listener);
        assertNotTimedOut("connecting", listener.connectedLatch);
        assertNotTimedOut("connecting", serverSocket.connectedLatch);
        clientSocket.cancel();
        assertNotTimedOut("erroring", serverSocket.errorLatch);
        assertThat(serverSocket.received, hasItems("onError ClientDisconnectedException"));
    }

    @Test
    public void pingAndPongWork() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .addHandler(webSocketHandler((request, responseHeaders) -> serverSocket).withPath("/ws"))
            .start();

        ClientListener listener = new ClientListener();
        WebSocket clientSocket = client.newBuilder()
            .pingInterval(50, TimeUnit.MILLISECONDS)
            .build()
            .newWebSocket(webSocketRequest(server.uri().resolve("/ws")), listener);

        assertNotTimedOut("Connecting", serverSocket.connectedLatch);
        assertNotTimedOut("Pinging", serverSocket.pingLatch);

        assertThat(serverSocket.received, contains("connected", "onPing: "));

        serverSocket.session.sendPing(Mutils.toByteBuffer("pingping"), DoneCallback.NoOp);
        assertNotTimedOut("Pong wait", serverSocket.pongLatch);
        assertThat(serverSocket.received, hasItem("onPong: pingping"));

        clientSocket.close(1000, "Finished");
        assertNotTimedOut("Closing", serverSocket.closedLatch);
    }

    @Test
    public void theServerCanCloseSockets() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .addHandler(webSocketHandler((request, responseHeaders) -> serverSocket).withPath("/ws"))
            .start();
        ClientListener clientListener = new ClientListener();
        client.newWebSocket(webSocketRequest(server.uri().resolve("/ws")), clientListener);
        assertNotTimedOut("Connecting", serverSocket.connectedLatch);
        assertEventually(() -> serverSocket.state(), equalTo(WebsocketSessionState.OPEN));
        serverSocket.session.close(1001, "Umm");
        assertNotTimedOut("Closing", clientListener.closedLatch);
        assertThat(clientListener.toString(), clientListener.events,
            contains("onOpen", "onClosing 1001 Umm", "onClosed 1001 Umm"));
        assertEventually(() -> serverSocket.state(), equalTo(WebsocketSessionState.SERVER_CLOSED));
    }

    @Test
    public void ifNotMatchedThenProtocolExceptionIsReturned() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .addHandler(webSocketHandler((request, responseHeaders) -> serverSocket).withPath("/routed-websocket"))
            .start();

        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        client.newWebSocket(webSocketRequest(server.uri().resolve("/non-existant")), new WebSocketListener() {
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                failure.complete(t);
            }
        });

        Throwable actual = failure.get(10, TimeUnit.SECONDS);
        assertThat(actual, instanceOf(ProtocolException.class));
    }

    @Test
    public void ifNoMessagesSentOrReceivedExceedIdleTimeoutThenItDisconnects() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .addHandler(webSocketHandler((request, responseHeaders) -> serverSocket)
                .withPath("/routed-websocket")
                .withIdleReadTimeout(100, TimeUnit.MILLISECONDS)
            )
            .start();
        client.newWebSocket(webSocketRequest(server.uri().resolve("/routed-websocket")), new ClientListener());
        assertNotTimedOut("onError", serverSocket.errorLatch);
        assertThat(serverSocket.received, contains("connected", "onError TimeoutException"));
        assertEventually(() -> serverSocket.state(), equalTo(WebsocketSessionState.TIMED_OUT));
    }

    @Test
    public void exceptionsThrownByHandlersResultInOnErrorBeingCalled() {
        serverSocket = new RecordingMuWebSocket() {
            public void onText(String message, boolean isLast, DoneCallback onComplete) {
                throw new MuException("Oops");
            }
        };
        server = ServerUtils.httpsServerForTest()
            .addHandler(webSocketHandler((request, responseHeaders) -> serverSocket))
            .start();
        WebSocket webSocket = client.newWebSocket(webSocketRequest(server.uri()), new ClientListener());
        assertNotTimedOut("Connecting", serverSocket.connectedLatch);
        webSocket.send("Hello there");
        assertNotTimedOut("onError", serverSocket.errorLatch);
        assertThat(serverSocket.received, contains("connected", "onError MuException"));
        assertEventually(() -> serverSocket.state(), equalTo(WebsocketSessionState.ERRORED));
    }

    @Test
    public void ifAWriteTimeoutIsSetThenPingsAreSent() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .addHandler(
                webSocketHandler((request, responseHeaders) -> serverSocket)
                    .withPingSentAfterNoWritesFor(50, TimeUnit.MILLISECONDS)
            )
            .start();
        ClientListener listener = new ClientListener();
        client.newWebSocket(webSocketRequest(server.uri()), listener);
        assertNotTimedOut("Connecting", serverSocket.connectedLatch);
        serverSocket.session.sendText("Hi", DoneCallback.NoOp);
        Thread.sleep(200);
        serverSocket.session.sendText("Bye", DoneCallback.NoOp);
        serverSocket.session.close(1000, "Done");
        assertNotTimedOut("Closing", listener.closedLatch);
        assertThat(serverSocket.received, hasItem("onPong: mu"));
    }

    private static Request webSocketRequest(URI httpVersionOfUri) {
        return request().url("ws" + httpVersionOfUri.toString().substring(4)).build();
    }


    @AfterEach
    public void clean() {
        MuAssert.stopAndCheck(server);
    }

    private static class RecordingMuWebSocket extends BaseWebSocket {
        private static final Logger log = LoggerFactory.getLogger(RecordingMuWebSocket.class);
        private MuWebSocketSession session;
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch connectedLatch = new CountDownLatch(1);
        CountDownLatch closedLatch = new CountDownLatch(1);
        CountDownLatch pingLatch = new CountDownLatch(1);
        CountDownLatch pongLatch = new CountDownLatch(1);
        CountDownLatch errorLatch = new CountDownLatch(1);
        public boolean logErrors = false;

        @Override
        public void onConnect(MuWebSocketSession session) throws Exception {
            super.onConnect(session);
            this.session = session;
            received.add("connected");
            connectedLatch.countDown();
        }

        @Override
        public void onText(String message, boolean isLast, DoneCallback onComplete) {
            received.add("onText (" + isLast + "): " + message);
            session.sendText(message.toUpperCase(), onComplete);
        }

        @Override
        public void onBinary(ByteBuffer buffer, boolean isLast, DoneCallback onComplete) {
            int initial = buffer.position();
            received.add("onBinary (" + isLast + "): " + UTF_8.decode(buffer));
            buffer.position(initial);
            session.sendBinary(buffer, onComplete);
        }

        @Override
        public void onClientClosed(int statusCode, String reason) throws Exception {
            received.add("onClientClosed: " + statusCode + " " + reason);
            super.onClientClosed(statusCode, reason);
            closedLatch.countDown();
        }

        @Override
        public void onPing(ByteBuffer payload, DoneCallback onComplete) {
            received.add("onPing: " + UTF_8.decode(payload));
            session.sendPong(payload, onComplete);
            pingLatch.countDown();
        }

        @Override
        public void onPong(ByteBuffer payload, DoneCallback onComplete) throws Exception {
            received.add("onPong: " + UTF_8.decode(payload));
            pongLatch.countDown();
            onComplete.onComplete(null);
        }

        @Override
        public void onError(Throwable cause) throws Exception {
            if (logErrors) {
                log.error("Error on server websocket", cause);
            }
            super.onError(cause);
            received.add("onError " + cause.getClass().getSimpleName());
            errorLatch.countDown();
        }
    }

    private static class ClientListener extends WebSocketListener {

        final List<String> events = new CopyOnWriteArrayList<>();
        final CountDownLatch closedLatch = new CountDownLatch(1);
        final CountDownLatch failureLatch = new CountDownLatch(1);
        final CountDownLatch connectedLatch = new CountDownLatch(1);
        final CountDownLatch messageLatch;
        Response response;

        private ClientListener() {
            this(1);
        }

        private ClientListener(int expectedMessages) {
            messageLatch = new CountDownLatch(expectedMessages);
        }


        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            this.response = response;
            events.add("onOpen");
            connectedLatch.countDown();
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            events.add("onMessage text: " + text);
            messageLatch.countDown();
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            events.add("onMessage binary: " + bytes.string(UTF_8));
            messageLatch.countDown();
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
            failureLatch.countDown();
        }

        @Override
        public String toString() {
            return events.toString();
        }
    }
}
