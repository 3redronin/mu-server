package io.muserver;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.RFCTestUtils.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@DisplayName("RFC 9113 6.4 Frame Definitions: RST_STREAM")
class RFC9113_6_4_RstStreamTest {

    private @Nullable MuServer server;

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
            con.readLogicalFrame(Http2HeadersFrame.class);
            var data = con.readLogicalFrame(Http2DataFrame.class);
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
            con.readLogicalFrame(Http2HeadersFrame.class);
            Http2DataFrame getGot = con.readLogicalFrame(Http2DataFrame.class);
            assertThat(getGot.toUTF8(), equalTo("Get got"));

            info = completedStreams.poll(10, TimeUnit.SECONDS);
            assertThat(info, notNullValue());
            assertThat(info.completedSuccessfully(), equalTo(true));

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
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            var streamId = 1;
            var errorCode = Http2ErrorCode.NO_ERROR.code();
            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, true, postHelloHeaders(getPort())))
                .writeRaw(new byte[] {
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

            var resp = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(resp.streamId(), equalTo(1));
            assertThat(resp.headers().get(":status"), equalTo("200"));

            var data = con.readLogicalFrame(Http2DataFrame.class);
            assertThat(data.streamId(), equalTo(1));
            assertThat(new String(data.payload(), data.payloadOffset(), data.payloadLength(), StandardCharsets.UTF_8), equalTo("Hello"));

            var eos = con.readLogicalFrame(Http2DataFrame.class);
            assertThat(eos.endStream(), equalTo(true));
        }

    }

    private int getPort() {
        return server.uri().getPort();
    }

    @AfterEach
    public void stop() {
        if (server != null) server.stop();
    }

}
