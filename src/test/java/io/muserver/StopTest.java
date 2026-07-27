package io.muserver;

import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.muserver.WebSocketHandlerBuilder.webSocketHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.client;
import static scaffolding.ClientUtils.request;

@Timeout(30)
public class StopTest {

    private static final Logger log = LoggerFactory.getLogger(StopTest.class);

    private @Nullable MuServer server;

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private static MuServer startBlockedServer(CountDownLatch serverReceivedLatch, CountDownLatch sendResponseLatch) {
        return MuServerBuilder
            .httpServer()
            .addHandler((request, response) -> {
                log.info("received request {}", request);
                serverReceivedLatch.countDown();

                AsyncHandle asyncHandle = request.handleAsync();
                asyncHandle.addResponseCompleteHandler(info -> log.info("request completed {}", info));

                sendResponseLatch.await();
                response.status(200);
                asyncHandle.write(Mutils.toByteBuffer("Hello"));
                asyncHandle.complete();

                return true;
            })
            .start();
    }

    private static void awaitServerStopsAcceptingConnections(MuServer server) throws Exception {
        InetSocketAddress address = new InetSocketAddress(server.uri().getHost(), server.uri().getPort());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(address, 200);
            } catch (IOException expected) {
                // Depending on the race with ServerSocket.close(), the platform may report
                // either connection refused or connection reset. Both mean connect failed.
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Server continued accepting connections for 5 seconds after shutdown started");
    }

    @Test
    public void gracefulShutdown_withinGracefulPeriod() throws Exception {
        CountDownLatch serverReceivedLatch = new CountDownLatch(1);
        CountDownLatch sendResponseLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            server = startBlockedServer(serverReceivedLatch, sendResponseLatch);
            String serverUri = server.uri().toString();

            Future<Integer> clientResponse = executor.submit(() -> {
                try (Response response = call(request().url(serverUri))) {
                    return response.code();
                }
            });

            assertThat(serverReceivedLatch.await(5, TimeUnit.SECONDS), is(true));

            Future<Boolean> stopResult = executor.submit(() -> server.stop(10, TimeUnit.SECONDS));
            awaitServerStopsAcceptingConnections(server);

            sendResponseLatch.countDown();

            assertThat(clientResponse.get(5, TimeUnit.SECONDS), is(200));
            assertThat(stopResult.get(5, TimeUnit.SECONDS), is(true));
            server = null;
        } finally {
            sendResponseLatch.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void gracefulShutdown_inFlightRequestAbortedWhenGracefulPeriodExceed() throws Exception {
        CountDownLatch serverReceivedLatch = new CountDownLatch(1);
        CountDownLatch sendResponseLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            server = startBlockedServer(serverReceivedLatch, sendResponseLatch);
            String serverUri = server.uri().toString();

            Future<UncheckedIOException> clientResult = executor.submit(() ->
                assertThrows(UncheckedIOException.class, () -> {
                    try (Response ignored = call(request().url(serverUri))) {
                    }
                })
            );

            assertThat(serverReceivedLatch.await(5, TimeUnit.SECONDS), is(true));

            boolean stopResult = server.stop(500, TimeUnit.MILLISECONDS);
            server = null;

            assertThat(stopResult, is(false));
            UncheckedIOException clientException = clientResult.get(5, TimeUnit.SECONDS);
            assertThat(clientException.getCause().getCause(), is(instanceOf(java.io.EOFException.class)));
        } finally {
            sendResponseLatch.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void gracefulShutdown_notifiesActiveWebsocket() throws InterruptedException {
        CountDownLatch serverSocketConnected = new CountDownLatch(1);
        CountDownLatch clientSocketOpened = new CountDownLatch(1);
        CountDownLatch serverShuttingDown = new CountDownLatch(1);

        server = MuServerBuilder
            .httpServer()
            .addHandler(webSocketHandler((request, responseHeaders) -> new SimpleWebSocket() {
                @Override
                public void onConnect(MuWebSocketSession session) throws Exception {
                    super.onConnect(session);
                    serverSocketConnected.countDown();
                }

                @Override
                public void onText(String message) {
                }

                @Override
                public void onBinary(java.nio.ByteBuffer buffer) {
                }

                @Override
                public void onServerShuttingDown() throws Exception {
                    serverShuttingDown.countDown();
                    session().close(1001, "Going away");
                }
            }))
            .start();

        String wsUri = server.uri().toString().replaceFirst("^http", "ws");
        WebSocket socket = client.newWebSocket(new okhttp3.Request.Builder().url(wsUri).build(), new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                clientSocketOpened.countDown();
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, @Nullable Response response) {
            }
        });

        assertThat(serverSocketConnected.await(2, TimeUnit.SECONDS), is(true));
        assertThat(clientSocketOpened.await(2, TimeUnit.SECONDS), is(true));
        server.stop(2, TimeUnit.SECONDS);
        assertThat(serverShuttingDown.await(2, TimeUnit.SECONDS), is(true));

        socket.cancel();
    }

}
