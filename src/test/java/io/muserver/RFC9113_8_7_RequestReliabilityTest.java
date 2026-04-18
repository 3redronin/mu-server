package io.muserver;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.RFCTestUtils.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static scaffolding.MuAssert.assertNotTimedOut;

/**
 * Tests for RFC 9113 §8.7 Request Reliability.
 */
@DisplayName("RFC 9113 §8.7 Request Reliability")
class RFC9113_8_7_RequestReliabilityTest {

    private @Nullable MuServer server;

    @Test
    void streamsAcceptedBeforeTheFinalGoAwayMayBeProcessedButLaterOnesAreRefused() throws Exception {
        var releaseResponses = new CountDownLatch(1);
        var stream1Started = new CountDownLatch(1);
        var stream3Started = new CountDownLatch(1);
        var processedPaths = new CopyOnWriteArrayList<String>();

        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler((request, response) -> {
                processedPaths.add(request.relativePath());
                if (request.relativePath().equals("/one")) {
                    stream1Started.countDown();
                } else if (request.relativePath().equals("/grace")) {
                    stream3Started.countDown();
                }
                assertThat(releaseResponses.await(5, TimeUnit.SECONDS), equalTo(true));
                response.write(request.relativePath());
                return true;
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeFrame(getFrame(1, "/one"))
                .flush();

            assertNotTimedOut("waiting for stream 1 to start", stream1Started);

            var stopper = Executors.newSingleThreadExecutor();
            try {
                var stopped = stopper.submit(() -> server.stop(5, TimeUnit.SECONDS));

                assertThat(con.readLogicalFrame(Http2GoAway.class),
                    equalTo(goAway(0x7FFFFFFF, Http2ErrorCode.NO_ERROR)));

                con.writeFrame(getFrame(3, "/grace")).flush();
                assertNotTimedOut("waiting for grace-period stream to start", stream3Started);

                var finalGoAway = con.readLogicalFrame(Http2GoAway.class);
                assertThat(finalGoAway.lastStreamId(), equalTo(3));
                assertThat(finalGoAway.errorCodeEnum(), equalTo(Http2ErrorCode.NO_ERROR));

                con.writeFrame(getFrame(5, "/after-final")).flush();
                assertThat(con.readLogicalFrame(Http2ResetStreamFrame.class),
                    equalTo(new Http2ResetStreamFrame(5, Http2ErrorCode.REFUSED_STREAM.code())));

                releaseResponses.countDown();

                var nextFrames = List.of(
                    con.readLogicalFrame(), con.readLogicalFrame(), con.readLogicalFrame(),
                    con.readLogicalFrame(), con.readLogicalFrame(), con.readLogicalFrame()
                );

                assertThat(nextFrames.stream()
                        .filter(Http2HeadersFrame.class::isInstance)
                        .map(Http2HeadersFrame.class::cast)
                        .map(Http2HeadersFrame::streamId)
                        .collect(Collectors.toList()),
                    containsInAnyOrder(1, 3));

                assertThat(nextFrames.stream()
                        .filter(Http2DataFrame.class::isInstance)
                        .map(Http2DataFrame.class::cast)
                        .map(Http2DataFrame::toUTF8)
                        .filter(text -> !text.isEmpty())
                        .collect(Collectors.toList()),
                    containsInAnyOrder("/one", "/grace"));

                assertThat(processedPaths, containsInAnyOrder("/one", "/grace"));
                assertThat(processedPaths, not(hasItem("/after-final")));

                assertThat(stopped.get(5, TimeUnit.SECONDS), equalTo(true));
                assertThrows(Exception.class, con::readFrameHeader);
            } finally {
                stopper.shutdownNow();
            }
        }
    }

    @Test
    void refusedStreamIsSafeToRetryOnANewConnection() throws Exception {
        var releaseHeldRequest = new CountDownLatch(1);
        var heldRequestStarted = new CountDownLatch(1);
        var processedPaths = new CopyOnWriteArrayList<String>();

        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled().withMaxConcurrentStreams(1))
            .addHandler((request, response) -> {
                processedPaths.add(request.relativePath());
                if (request.relativePath().equals("/hold")) {
                    heldRequestStarted.countDown();
                    assertThat(releaseHeldRequest.await(5, TimeUnit.SECONDS), equalTo(true));
                }
                response.write(request.relativePath());
                return true;
            })
            .start();

        try (var client1 = new H2Client();
             var con1 = client1.connect(server)) {

            con1.handshake()
                .writeFrame(getFrame(1, "/hold"))
                .flush();

            assertNotTimedOut("waiting for held request to start", heldRequestStarted);

            con1.writeFrame(getFrame(3, "/retry-me")).flush();

            assertThat(con1.readLogicalFrame(Http2ResetStreamFrame.class),
                equalTo(new Http2ResetStreamFrame(3, Http2ErrorCode.REFUSED_STREAM.code())));

            assertThat(processedPaths, hasItem("/hold"));
            assertThat(processedPaths, not(hasItem("/retry-me")));

            try (var client2 = new H2Client();
                 var con2 = client2.connect(server)) {

                con2.handshake()
                    .writeFrame(getFrame(1, "/retry-me"))
                    .flush();

                var responseHeaders = readIgnoringWindowUpdates(con2, Http2HeadersFrame.class);
                assertThat(responseHeaders.streamId(), equalTo(1));
                assertThat(responseHeaders.headers().get(":status"), equalTo("200"));

                var responseData = readIgnoringWindowUpdates(con2, Http2DataFrame.class);
                assertThat(responseData.toUTF8(), equalTo("/retry-me"));
            }

            assertThat(processedPaths, containsInAnyOrder("/hold", "/retry-me"));

            releaseHeldRequest.countDown();

            assertThat(readIgnoringWindowUpdates(con1, Http2HeadersFrame.class).streamId(), equalTo(1));
            assertThat(readIgnoringWindowUpdates(con1, Http2DataFrame.class).toUTF8(), equalTo("/hold"));
        }
    }

    @Test
    void failuresAfterAResponseStartsAreNotRetrySafeRefusedStreams() throws Exception {
        var started = new CountDownLatch(1);

        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                started.countDown();
                response.sendChunk("partial");
                throw new RuntimeException("boom after response started");
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders(getPort())))
                .flush();

            assertNotTimedOut("waiting for request to start", started);

            var headers = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(headers.streamId(), equalTo(1));
            assertThat(headers.headers().get(":status"), equalTo("200"));

            var partial = readIgnoringWindowUpdates(con, Http2DataFrame.class);
            assertThat(partial.toUTF8(), equalTo("partial"));

            var reset = readIgnoringWindowUpdates(con, Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.INTERNAL_ERROR));
        }
    }

    private FieldBlock getFrameHeaders(String path) {
        FieldBlock headers = getHelloHeaders(getPort());
        headers.set(":path", path);
        return headers;
    }

    private Http2HeadersFrame getFrame(int streamId, String path) {
        return new Http2HeadersFrame(streamId, true, getFrameHeaders(path));
    }

    private int getPort() {
        return server.uri().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }
}
