package io.muserver;

import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThrows;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class StopTest {

    private static final Logger log = LoggerFactory.getLogger(StopTest.class);

    private MuServer server;

    @After
    public void tearDown() throws Exception {
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
            } catch (ConnectException expected) {
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

}
