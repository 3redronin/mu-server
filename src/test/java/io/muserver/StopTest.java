package io.muserver;

import okhttp3.Response;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UncheckedIOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

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

        server = startLongDelayServer(serverReceivedLatch, 1000L);
        AtomicBoolean stopResult = new AtomicBoolean();
        CountDownLatch stopReturnedLatch = new CountDownLatch(1);

        new Thread(() -> {
            try (Response resp = call(request().url(server.uri().toString()))) {
                clientReceivedStatus.set(resp.code());
                clientReceivedLatch.countDown();
            }
        }).start();

        assertThat(serverReceivedLatch.await(2, TimeUnit.SECONDS), is(true));

        new Thread(() -> {
            stopResult.set(server.stop(2, TimeUnit.SECONDS));
            stopReturnedLatch.countDown();
        }).start();

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
        assertThat(stopReturnedLatch.await(2, TimeUnit.SECONDS), is(true));
        assertThat(stopResult.get(), is(true));
    }

    @Test
    public void gracefulShutdown_inFlightRequestAbortedWhenGracefulPeriodExceed() throws InterruptedException {

        CountDownLatch serverReceivedLatch = new CountDownLatch(1);
        CountDownLatch clientReceivedLatch = new CountDownLatch(1);
        AtomicReference<Exception> clientException = new AtomicReference<>();
        AtomicBoolean stopResult = new AtomicBoolean(true);
        CountDownLatch stopReturnedLatch = new CountDownLatch(1);

        server = startLongDelayServer(serverReceivedLatch, 2000L);

        new Thread(() -> {
            UncheckedIOException exception = assertThrows(UncheckedIOException.class, () ->{
                try (Response ignore = call(request().url(server.uri().toString())) ) {}
            });
            clientException.set(exception);
            clientReceivedLatch.countDown();
        }).start();

        assertThat(serverReceivedLatch.await(2, TimeUnit.SECONDS), is(true));

        new Thread(() -> {
            stopResult.set(server.stop(500, TimeUnit.MILLISECONDS));
            stopReturnedLatch.countDown();
        }).start();

        // the previous in flight request should be aborted
        assertThat(clientReceivedLatch.await(2, TimeUnit.SECONDS), is(true));
        assertThat(clientException.get().getCause().getCause(), is(instanceOf(java.io.EOFException.class)));
        assertThat(stopReturnedLatch.await(2, TimeUnit.SECONDS), is(true));
        assertThat(stopResult.get(), is(false));
    }

    @Test
    public void gracefulShutdown_http2IdleConnectionFinishesQuickly() throws Exception {
        server = MuServerBuilder.httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();

        var stopResult = new AtomicBoolean();
        var stopReturnedLatch = new CountDownLatch(1);

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake();

            new Thread(() -> {
                stopResult.set(server.stop(2, TimeUnit.SECONDS));
                stopReturnedLatch.countDown();
            }).start();

            assertThat(con.readLogicalFrame(), equalTo(new Http2GoAway(0x7FFFFFFF, Http2ErrorCode.NO_ERROR.code(), new byte[0])));
            assertThat(con.readLogicalFrame(), equalTo(new Http2GoAway(0, Http2ErrorCode.NO_ERROR.code(), new byte[0])));
            assertThat(stopReturnedLatch.await(2, TimeUnit.SECONDS), is(true));
            assertThat(stopResult.get(), is(true));
            assertThrows(java.io.IOException.class, con::readFrameHeader);
        }
    }

    @Test
    public void gracefulShutdown_http2InFlightRequestsCompleteQuickly() throws Exception {
        var goTime = new CountDownLatch(1);
        var requestStarted = new CountDownLatch(1);
        server = MuServerBuilder.httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                requestStarted.countDown();
                assertThat(goTime.await(2, TimeUnit.SECONDS), is(true));
                response.write("done");
            })
            .start();

        var stopResult = new AtomicBoolean();
        var stopReturnedLatch = new CountDownLatch(1);

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, true, RFCTestUtils.getHelloHeaders(server.uri().getPort())))
                .flush();

            assertThat(requestStarted.await(2, TimeUnit.SECONDS), is(true));

            new Thread(() -> {
                stopResult.set(server.stop(2, TimeUnit.SECONDS));
                stopReturnedLatch.countDown();
            }).start();

            assertThat(con.readLogicalFrame(), equalTo(new Http2GoAway(0x7FFFFFFF, Http2ErrorCode.NO_ERROR.code(), new byte[0])));

            goTime.countDown();

            assertThat(con.readLogicalFrame(Http2HeadersFrame.class).headers().get(":status"), equalTo("200"));
            assertThat(con.readLogicalFrame(Http2DataFrame.class).toUTF8(), equalTo("done"));
            assertThat(con.readLogicalFrame(Http2DataFrame.class).endStream(), is(true));
            assertThat(con.readLogicalFrame(), equalTo(new Http2GoAway(1, Http2ErrorCode.NO_ERROR.code(), new byte[0])));
            assertThat(stopReturnedLatch.await(2, TimeUnit.SECONDS), is(true));
            assertThat(stopResult.get(), is(true));
            assertThrows(java.io.IOException.class, con::readFrameHeader);
        }
    }

}
