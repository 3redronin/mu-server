package io.muserver;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;

import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.RFCTestUtils.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("RFC 9113 6.4 Frame Definitions: RST_STREAM")
class RFC9113_6_4_RstStreamTest {

    private @Nullable MuServer server;

    @Test
    void resettingAStreamCompletesASuspendedRequest() throws Exception {
        var requestStarted = new CountDownLatch(1);
        var completedStreams = new LinkedBlockingQueue<ResponseInfo>(1);
        var handleRef = new AtomicReference<AsyncHandle>();
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                AsyncHandle handle = request.handleAsync();
                handleRef.set(handle);
                handle.addResponseCompleteHandler(completedStreams::add);
                requestStarted.countDown();
            })
            .start();

        ResponseInfo completedStream;
        try (var client = new H2Client();
             var con = client.connect(server)) {
            try {
                con.handshake()
                    .writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders(getPort())))
                    .flush();
                assertThat("The request did not start", requestStarted.await(5, TimeUnit.SECONDS), is(true));

                con.writeFrame(new Http2ResetStreamFrame(1, Http2ErrorCode.CANCEL.code()))
                    .flush();
                completedStream = completedStreams.poll(3, TimeUnit.SECONDS);
            } finally {
                AsyncHandle handle = handleRef.get();
                if (handle != null) {
                    handle.complete();
                }
            }
        }

        assertThat("Resetting the HTTP/2 stream did not complete the suspended request", completedStream, is(notNullValue()));
        assertThat(completedStream.completedSuccessfully(), is(false));
        assertThat(completedStream.response().responseState(), is(ResponseState.CLIENT_CANCELLED));
    }

    @Test
    void dataFollowingResetInTheSameWriteIsNotDeliveredToTheRequestBody() throws Exception {
        var requestStarted = new CountDownLatch(1);
        var releaseHandler = new CountDownLatch(1);
        var bodyResults = new LinkedBlockingQueue<Throwable>(1);
        byte[] opaqueData = {1, 2, 3, 4, 5, 6, 7, 8};
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> {
                requestStarted.countDown();
                try {
                    int value = request.body().read();
                    bodyResults.add(new AssertionError("Expected reset, but read " + value));
                } catch (Throwable e) {
                    bodyResults.add(e);
                }
                assertThat(releaseHandler.await(10, TimeUnit.SECONDS), is(true));
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {
            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, false, postHelloHeaders(getPort())))
                .flush();
            assertThat("The request did not start", requestStarted.await(5, TimeUnit.SECONDS), is(true));

            con.writeFrame(new Http2ResetStreamFrame(1, Http2ErrorCode.CANCEL.code()))
                .writeFrame(utf8DataFrame(1, false, "late"))
                .writeFrame(new Http2Ping(false, opaqueData))
                .flush();

            assertThat(bodyResults.poll(5, TimeUnit.SECONDS), instanceOf(EOFException.class));
            var reset = readIgnoringWindowUpdates(con, Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.STREAM_CLOSED));
            assertThat(con.readLogicalFrame(Http2Ping.class), equalTo(new Http2Ping(true, opaqueData)));
        } finally {
            releaseHandler.countDown();
        }
    }

    @Test
    void afterAClientResetsAStreamNoMoreDataShouldBeSent() throws Exception {
        var sendMoreDataLatch = new CountDownLatch(1);
        var completedStreams = new LinkedBlockingQueue<ResponseInfo>(1);
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                response.status(200);
                response.headers().set("content-type", request.contentType());
                response.sendChunk("Chunk one");
                // Do not write the output, otherwise we would move to closed-local or closed.
                // Rather, we want the request to be complete but not the response.
                if (sendMoreDataLatch.await(10, TimeUnit.SECONDS)) {
                    response.sendChunk("Chunk two");
                }
            })
            .addResponseCompleteListener(completedStreams::add)
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders(getPort())))
                .flush();

            con.readLogicalFrame(Http2HeadersFrame.class);
            con.readLogicalFrame(Http2DataFrame.class);

            con.writeFrame(new Http2ResetStreamFrame(1, Http2ErrorCode.NO_ERROR.code()))
                .flush();

            // how to make sure the server has received it?
            Thread.sleep(500);

            sendMoreDataLatch.countDown();

            assertNothingToRead(con.socket());

            var info = completedStreams.poll(10, TimeUnit.SECONDS);
            assertThat(info, notNullValue());
            assertThat(info.completedSuccessfully(), equalTo(false));
        }

    }

    @Test
    void resetFenceRejectsWritesBeforeTheResetCommandReachesTheCoordinator() throws Exception {
        var requestStarted = new CountDownLatch(1);
        var connectionRef = new AtomicReference<Http2Connection>();
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                connectionRef.set((Http2Connection) request.connection());
                request.handleAsync();
                requestStarted.countDown();
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {
            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders(getPort())))
                .flush();
            assertThat("The request did not start", requestStarted.await(5, TimeUnit.SECONDS), is(true));

            Http2Connection connection = connectionRef.get();
            Http2StreamRegistry streamRegistry =
                getField(connection, "streamRegistry", Http2StreamRegistry.class);
            Http2Stream stream =
                Objects.requireNonNull(streamRegistry.applicationStream(1));
            Lock stateLock = getField(connection, "stateLock", Lock.class);
            stateLock.lock();
            try {
                con.writeFrame(new Http2ResetStreamFrame(1, Http2ErrorCode.CANCEL.code()))
                    .flush();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!stream.resetWasInitiated() && System.nanoTime() < deadline) {
                    Thread.sleep(1);
                }
                assertThat("The reader did not publish the reset fence", stream.resetWasInitiated(), is(true));

                var lateWrite = new WriteTask(utf8DataFrame(1, false, "late"), true);
                connection.write(lateWrite);
                var failure = assertThrows(
                    IOException.class,
                    () -> lateWrite.await(1, TimeUnit.SECONDS)
                );
                assertThat(failure.getMessage(), containsString("stream is closed"));
            } finally {
                stateLock.unlock();
            }

            assertNothingToRead(con.socket());
        }
    }

    @Test
    void peerCancellationIsPublishedBeforeBlockedResponseWritersAreReleased() throws Exception {
        var requestStarted = new CountDownLatch(1);
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                request.handleAsync();
                requestStarted.countDown();
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {
            con.handshake()
                .writeFrame(new Http2Settings(false, 4096, 100, 0, 16384, 32768))
                .flush();
            assertThat(con.readLogicalFrame(), equalTo(Http2Settings.ACK));

            con.writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders(getPort()))).flush();
            assertThat("The request did not start", requestStarted.await(5, TimeUnit.SECONDS), is(true));

            Http2Connection connection = (Http2Connection) server.activeConnections().iterator().next();
            Http2StreamRegistry streamRegistry =
                getField(connection, "streamRegistry", Http2StreamRegistry.class);
            Http2Stream stream =
                Objects.requireNonNull(streamRegistry.applicationStream(1));
            var stateAtWriteFailure = new AtomicReference<ResponseState>();
            var blockedWrite = new WriteTask(utf8DataFrame(1, false, "blocked"), true) {
                @Override
                public void fail(Exception ex) {
                    stateAtWriteFailure.set(stream.response().responseState());
                    super.fail(ex);
                }
            };
            connection.write(blockedWrite);

            byte[] barrierData = {1, 2, 3, 4, 5, 6, 7, 8};
            var barrier = new WriteTask(new Http2Ping(false, barrierData), true);
            connection.write(barrier);
            barrier.await(5, TimeUnit.SECONDS);
            assertThat(con.readLogicalFrame(Http2Ping.class), equalTo(new Http2Ping(false, barrierData)));

            con.writeFrame(new Http2ResetStreamFrame(1, Http2ErrorCode.CANCEL.code())).flush();
            assertThrows(IOException.class, () -> blockedWrite.await(5, TimeUnit.SECONDS));
            assertThat(stateAtWriteFailure.get(), equalTo(ResponseState.CLIENT_CANCELLED));
        }
    }

    @Test
    void whenTheClientSendsAResetFrameTheRequestInputStreamShouldThrow() throws Exception {
        var completedStreams = new LinkedBlockingQueue<ResponseInfo>(1);
        var exceptions = new LinkedBlockingQueue<Exception>();
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                response.write("Get got");
            })
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> {
                response.status(200);
                response.headers().set("content-type", request.contentType());

                try (var input = request.body()) {
                    var buffer = new byte[1024];
                    var read = input.read(buffer);
                    var msg = read == -1 ? "(EOF)" : read == 0 ? "(empty)" : new String(buffer, 0, read, StandardCharsets.UTF_8);
                    response.sendChunk(msg);

                    // next read (and subsequent should throw because the client sends a reset before we read
                    for (int i = 0; i < 2; i++) {
                        try {
                            read = input.read(buffer);
                            response.sendChunk("Read again " + read);
                        } catch (Exception ex) {
                            exceptions.add(ex);
                        }
                    }
                }
            })
            .addResponseCompleteListener(completedStreams::add)
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            // Post a request and first bit of data to /hello
            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, false, postHelloHeaders(getPort())))
                .writeFrame(new Http2DataFrame(1, false, "Hello".getBytes(StandardCharsets.UTF_8), 0, 5))
                .flush();

            // Expect the response and first bit of data back
            readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            var data = readIgnoringWindowUpdates(con, Http2DataFrame.class);
            assertThat(data.toUTF8(), equalTo("Hello"));

            // The request body is not finished, but reset the frame
            con.writeFrame(new Http2ResetStreamFrame(1, Http2ErrorCode.CANCEL.code()))
                .flush();

            // We expect the read() in the handler to throw
            for (int i = 0; i < 2; i++) {
                var writeException = exceptions.poll(10, TimeUnit.SECONDS);
                assertThat(writeException, instanceOf(EOFException.class));
                assertNothingToRead(con.socket());
            }

            // The http exchange is considered failed
            var info = completedStreams.poll(10, TimeUnit.SECONDS);
            assertThat(info, notNullValue());
            assertThat(info.completedSuccessfully(), equalTo(false));

            // new requests still work on the same connection though
            con.writeFrame(new Http2HeadersFrame(3, true, getHelloHeaders(getPort())))
                .flush();
            readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            Http2DataFrame getGot = readIgnoringWindowUpdates(con, Http2DataFrame.class);
            assertThat(getGot.toUTF8(), equalTo("Get got"));

            info = completedStreams.poll(10, TimeUnit.SECONDS);
            assertThat(info, notNullValue());
            assertThat(info.completedSuccessfully(), equalTo(true));

        }

    }

    @Test
    void resettingAStreamRefundsUnreadConnectionCredit() throws Exception {
        var firstRequestStarted = new CountDownLatch(1);
        var letFirstRequestFinish = new CountDownLatch(1);
        var completedStreams = new LinkedBlockingQueue<ResponseInfo>(2);
        var requestCount = new AtomicInteger();

        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled().withInitialWindowSize(8))
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> {
                if (requestCount.incrementAndGet() == 1) {
                    firstRequestStarted.countDown();
                    assertThat(letFirstRequestFinish.await(10, TimeUnit.SECONDS), equalTo(true));
                    try (var body = request.body()) {
                        body.readAllBytes();
                    }
                } else {
                    response.write(request.readBodyAsString());
                }
            })
            .addResponseCompleteListener(completedStreams::add)
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, false, postHelloHeaders(getPort())))
                .writeFrame(utf8DataFrame(1, false, "Hell"))
                .writeFrame(utf8DataFrame(1, false, "o wo"))
                .flush();

            assertThat(firstRequestStarted.await(10, TimeUnit.SECONDS), equalTo(true));

            con.writeFrame(new Http2ResetStreamFrame(1, Http2ErrorCode.CANCEL.code()))
                .writeFrame(new Http2HeadersFrame(3, false, postHelloHeaders(getPort())))
                .writeFrame(utf8DataFrame(3, true, "Goodbye!"))
                .flush();

            var headers = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(headers.streamId(), equalTo(3));
            assertThat(headers.headers().get(":status"), equalTo("200"));
            assertThat(readIgnoringWindowUpdates(con, Http2DataFrame.class).toUTF8(), equalTo("Goodbye!"));
            assertThat(readIgnoringWindowUpdates(con, Http2DataFrame.class).endStream(), equalTo(true));

            letFirstRequestFinish.countDown();

            var results = new ArrayList<Boolean>(2);
            results.add(completedStreams.poll(10, TimeUnit.SECONDS).completedSuccessfully());
            results.add(completedStreams.poll(10, TimeUnit.SECONDS).completedSuccessfully());
            assertThat(results, containsInAnyOrder(false, true));
        }
    }

    @Test
    void lateDataAfterAResetDoesNotStrandConnectionCredit() throws Exception {
        var firstRequestStarted = new CountDownLatch(1);
        var resetSeenByHandler = new CountDownLatch(1);
        var letFirstRequestFinish = new CountDownLatch(1);
        var completedStreams = new LinkedBlockingQueue<ResponseInfo>(2);
        var requestCount = new AtomicInteger();
        byte[] largeBody = new byte[16384];
        Arrays.fill(largeBody, (byte) 'x');
        byte[] lastConnectionWindowChunk = new byte[16383];
        Arrays.fill(lastConnectionWindowChunk, (byte) 'x');

        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> {
                if (requestCount.incrementAndGet() == 1) {
                    firstRequestStarted.countDown();
                    try (var body = request.body()) {
                        try {
                            body.read();
                            throw new AssertionError("Expected reset to make the request body unreadable");
                        } catch (EOFException expected) {
                            resetSeenByHandler.countDown();
                            assertThat(letFirstRequestFinish.await(10, TimeUnit.SECONDS), equalTo(true));
                        }
                    }
                } else {
                    response.write(Integer.toString(request.readBodyAsString().length()));
                }
            })
            .addResponseCompleteListener(completedStreams::add)
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, false, postHelloHeaders(getPort())))
                .flush();

            assertThat(firstRequestStarted.await(10, TimeUnit.SECONDS), equalTo(true));

            con.writeFrame(new Http2ResetStreamFrame(1, Http2ErrorCode.CANCEL.code()))
                .flush();

            assertThat(resetSeenByHandler.await(10, TimeUnit.SECONDS), equalTo(true));

            con.writeFrame(new Http2DataFrame(1, false, largeBody, 0, largeBody.length))
                .writeFrame(new Http2DataFrame(1, false, largeBody, 0, largeBody.length))
                .writeFrame(new Http2DataFrame(1, false, largeBody, 0, largeBody.length))
                .writeFrame(new Http2DataFrame(
                    1,
                    false,
                    lastConnectionWindowChunk,
                    0,
                    lastConnectionWindowChunk.length
                ))
                .flush();

            int resetsSeen = 0;
            int returnedConnectionCredit = 0;
            while (returnedConnectionCredit < largeBody.length) {
                LogicalHttp2Frame frame = con.readLogicalFrame();
                if (frame instanceof Http2WindowUpdate) {
                    var update = (Http2WindowUpdate) frame;
                    if (update.streamId() == 0) {
                        returnedConnectionCredit += update.windowSizeIncrement();
                    }
                    continue;
                }
                if (frame instanceof Http2ResetStreamFrame) {
                    var reset = (Http2ResetStreamFrame) frame;
                    assertThat(reset.streamId(), equalTo(1));
                    assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.STREAM_CLOSED));
                    resetsSeen++;
                    continue;
                }
                throw new AssertionError(
                    "Expected returned connection credit, got " + frame
                );
            }

            con.writeFrame(new Http2HeadersFrame(3, false, postHelloHeaders(getPort())))
                .writeFrame(new Http2DataFrame(3, true, largeBody, 0, largeBody.length))
                .flush();

            Http2HeadersFrame headers = null;
            Http2DataFrame data = null;
            Http2DataFrame eos = null;
            while (resetsSeen < 4 || headers == null || data == null || eos == null) {
                var frame = con.readLogicalFrame();
                if (frame instanceof Http2WindowUpdate) {
                    continue;
                }
                if (frame instanceof Http2ResetStreamFrame) {
                    var reset = (Http2ResetStreamFrame) frame;
                    assertThat(reset.streamId(), equalTo(1));
                    assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.STREAM_CLOSED));
                    resetsSeen++;
                    continue;
                }
                if (frame instanceof Http2HeadersFrame) {
                    headers = (Http2HeadersFrame) frame;
                    continue;
                }
                if (frame instanceof Http2DataFrame) {
                    var dataFrame = (Http2DataFrame) frame;
                    if (dataFrame.endStream()) {
                        eos = dataFrame;
                    } else {
                        data = dataFrame;
                    }
                    continue;
                }
                throw new AssertionError("Unexpected frame: " + frame);
            }

            assertThat(headers.streamId(), equalTo(3));
            assertThat(headers.headers().get(":status"), equalTo("200"));
            assertThat(data.toUTF8(), equalTo(Integer.toString(largeBody.length)));
            assertThat(eos.streamId(), equalTo(3));
            assertThat(eos.endStream(), equalTo(true));

            letFirstRequestFinish.countDown();

            var results = new ArrayList<Boolean>(2);
            results.add(completedStreams.poll(10, TimeUnit.SECONDS).completedSuccessfully());
            results.add(completedStreams.poll(10, TimeUnit.SECONDS).completedSuccessfully());
            assertThat(results, containsInAnyOrder(false, true));
        }
    }

    @Test
    void cannotResetIdleStreams() throws Exception {
        // a stream in an 'idle' state is, in mu-server, a stream that has not been created yet
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeFrame(new Http2ResetStreamFrame(1, Http2ErrorCode.NO_ERROR.code()))
                .flush();

            var goaway = con.readLogicalFrame(Http2GoAway.class);
            assertThat(goaway.lastStreamId(), equalTo(0));
            assertThat(goaway.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));
        }

    }

    @Test
    void cannotResetStream0() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeFrame(new Http2ResetStreamFrame(0, Http2ErrorCode.NO_ERROR.code()))
                .flush();

            var goaway = con.readLogicalFrame(Http2GoAway.class);
            assertThat(goaway.lastStreamId(), equalTo(0));
            assertThat(goaway.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));
        }

    }

    @Test
    void ifLengthIsNotFourThenConnectionError() throws Exception {
        var requestStarted = new CountDownLatch(1);
        var handleRef = new AtomicReference<AsyncHandle>();
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> {
                handleRef.set(request.handleAsync());
                requestStarted.countDown();
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            var streamId = 1;
            var errorCode = Http2ErrorCode.NO_ERROR.code();
            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, true, postHelloHeaders(getPort())))
                .flush();
            assertThat("The request did not start", requestStarted.await(5, TimeUnit.SECONDS), is(true));

            try {
                con.writeRaw(new byte[] {
                    // payload length
                    0b00000000,
                    0b00000000,
                    0b00000101, // length 5

                    // type
                    0x3,

                    // unused flags
                    0b00000000,

                    // stream id
                    (byte) (streamId >> 24),
                    (byte) (streamId >> 16),
                    (byte) (streamId >> 8),
                    (byte) streamId,

                    // error code
                    (byte) (errorCode >> 24),
                    (byte) (errorCode >> 16),
                    (byte) (errorCode >> 8),
                    (byte) errorCode,

                    // the invalid extra payload
                    (byte)1
                })
                    .flush();

                var goaway = con.readLogicalFrame(Http2GoAway.class);
                assertThat(goaway.lastStreamId(), equalTo(1));
                assertThat(goaway.errorCodeEnum(), equalTo(Http2ErrorCode.FRAME_SIZE_ERROR));
            } finally {
                handleRef.get().complete();
            }
        }

    }

    @Test
    void priorityFramesAreIgnored() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> {
                response.status(200);
                response.headers().set("content-type", request.contentType());
                response.write(request.readBodyAsString());
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake();

            con.writeFrame(new Http2HeadersFrame(1, false, postHelloHeaders(getPort())));
            con.writeFrame(new Http2DataFrame(1, true, "Hello".getBytes(StandardCharsets.UTF_8), 0, 5));
            con.flush();

            var resp = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(resp.streamId(), equalTo(1));
            assertThat(resp.headers().get(":status"), equalTo("200"));

            var data = readIgnoringWindowUpdates(con, Http2DataFrame.class);
            assertThat(data.streamId(), equalTo(1));
            assertThat(new String(data.payload(), data.payloadOffset(), data.payloadLength(), StandardCharsets.UTF_8), equalTo("Hello"));

            var eos = readIgnoringWindowUpdates(con, Http2DataFrame.class);
            assertThat(eos.endStream(), equalTo(true));
        }

    }

    private int getPort() {
        return server.uri().getPort();
    }

    private static <T> T getField(Object target, String name, Class<T> type) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }

    @AfterEach
    public void stop() {
        if (server != null) server.stop();
    }

}
