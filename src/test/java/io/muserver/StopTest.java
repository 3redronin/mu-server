package io.muserver;

import okhttp3.Response;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UncheckedIOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

@Disabled("Not implemented yet")
public class StopTest {

    private static final Logger log = LoggerFactory.getLogger(StopTest.class);

    private @Nullable MuServer server;

    @AfterEach
    public void tearDown() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    private static MuServer startLongDelayServer(CountDownLatch serverReceivedLatch, long delayTime) {
        return MuServerBuilder
            .httpServer()
            .addHandler((request, response) -> {
                log.info("received request {}", request);
                serverReceivedLatch.countDown();

                AsyncHandle asyncHandle = request.handleAsync();
                asyncHandle.addResponseCompleteHandler(info -> log.info("request completed {}", info));

                Thread.sleep(delayTime);
                response.status(200);
                asyncHandle.write(Mutils.toByteBuffer("Hello"));
                asyncHandle.complete();

                return true;
            })
            .start();
    }

    @Test
    public void gracefulShutdown_withinGracefulPeriod() throws InterruptedException {

        CountDownLatch serverReceivedLatch = new CountDownLatch(1);
        CountDownLatch clientReceivedLatch = new CountDownLatch(1);
        AtomicInteger clientReceivedStatus = new AtomicInteger();

        server = startLongDelayServer(serverReceivedLatch, 2000L);

        new Thread(() -> {
            try (Response resp = call(request().url(server.uri().toString()))) {
                clientReceivedStatus.set(resp.code());
                clientReceivedLatch.countDown();
            }
        }).start();

        assertThat(serverReceivedLatch.await(2, TimeUnit.SECONDS), is(true));

        new Thread(() -> server.stop(2, TimeUnit.SECONDS)).start();

        // new request should fail
        Thread.sleep(200L);
        UncheckedIOException exception = assertThrows(UncheckedIOException.class, () -> {
            try (Response ignored = call(request().url(server.uri().toString())) ) {}
        });
        Throwable rootCause = exception.getCause().getCause();
        assertThat(rootCause, is(instanceOf(java.net.ConnectException.class)));
        assertThat(rootCause.getMessage(), containsString("Connection refused"));

        // the previous in flight request should complete
        assertThat(clientReceivedLatch.await(2, TimeUnit.SECONDS), is(true));
        assertThat(clientReceivedStatus.get(), is(200));
    }

    @Test
    public void gracefulShutdown_inFlightRequestAbortedWhenGracefulPeriodExceed() throws InterruptedException {

        CountDownLatch serverReceivedLatch = new CountDownLatch(1);
        CountDownLatch clientReceivedLatch = new CountDownLatch(1);
        AtomicReference<Exception> clientException = new AtomicReference<>();

        server = startLongDelayServer(serverReceivedLatch, 2000L);

        new Thread(() -> {
            UncheckedIOException exception = assertThrows(UncheckedIOException.class, () ->{
                try (Response ignore = call(request().url(server.uri().toString())) ) {}
            });
            clientException.set(exception);
            clientReceivedLatch.countDown();
        }).start();

        assertThat(serverReceivedLatch.await(2, TimeUnit.SECONDS), is(true));

        new Thread(() -> server.stop(500, TimeUnit.MILLISECONDS)).start();

        // the previous in flight request should be aborted
        assertThat(clientReceivedLatch.await(2, TimeUnit.SECONDS), is(true));
        assertThat(clientException.get().getCause().getCause(), is(instanceOf(java.io.EOFException.class)));
    }

}
