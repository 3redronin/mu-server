package io.muserver;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static io.muserver.RFCTestUtils.getHelloHeaders;
import static io.muserver.RFCTestUtils.readIgnoringWindowUpdates;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ServerUtils.httpsServerForTest;

@Timeout(15)
class Http2CompleteResponseTest {
    @ParameterizedTest
    @CsvSource({"false,0", "true,0", "false,5", "true,5", "false,1024", "true,1024", "false,1025", "true,1025",
        "false,8191", "true,8191", "false,8192", "true,8192", "false,8193", "true,8193", "false,16384", "true,16384"})
    void knownBodiesRemainCorrectAcrossBatchBoundary(boolean tls, int length) throws Exception {
        String text = "x".repeat(length);
        try (var server = httpsServerForTest(tls ? "h2" : "http")
            .withHttp2Config(Http2ConfigBuilder.http2Enabled()).withGzipEnabled(false)
            .addHandler((request, response) -> { response.write(text); return true; }).start();
             var client = new H2Client();
             var con = tls ? client.connect(server) : client.connectClearText(server)) {
            con.socket().setSoTimeout(3000);
            con.handshake().writeFrame(new Http2HeadersFrame(1, true,
                getHelloHeaders(tls ? "https" : "http", server.uri().getPort()))).flush();
            var headers = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertEquals(Integer.toString(length), headers.headers().get("content-length"));
            var body = new ByteArrayOutputStream();
            boolean done = headers.endStream();
            while (!done) {
                var data = readIgnoringWindowUpdates(con, Http2DataFrame.class);
                body.write(data.payload(), data.payloadOffset(), data.payloadLength());
                done = data.endStream();
            }
            assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), body.toByteArray());
        }
    }

    @ParameterizedTest
    @CsvSource({"false,4096", "true,4096", "false,30000", "true,30000"})
    void largeHeadersRetainTheirValuesAndCompleteTheBody(boolean tls, int length) throws Exception {
        String value = "a".repeat(length);
        try (var server = httpsServerForTest(tls ? "h2" : "http")
            .withHttp2Config(Http2ConfigBuilder.http2Enabled()).withGzipEnabled(false)
            .addHandler((request, response) -> {
                response.headers().set("x-large", value);
                response.write("hello");
                return true;
            }).start(); var client = new H2Client();
             var con = tls ? client.connect(server) : client.connectClearText(server)) {
            con.socket().setSoTimeout(3000);
            con.handshake().writeFrame(new Http2HeadersFrame(1, true,
                getHelloHeaders(tls ? "https" : "http", server.uri().getPort()))).flush();
            assertEquals(value, readIgnoringWindowUpdates(con, Http2HeadersFrame.class).headers().get("x-large"));
            assertEquals("hello", readIgnoringWindowUpdates(con, Http2DataFrame.class).toUTF8());
            assertTrue(readIgnoringWindowUpdates(con, Http2DataFrame.class).endStream());
        }
    }

    @ParameterizedTest
    @CsvSource({"0,5", "2,5", "0,8192", "4096,8192"})
    void headersReachPeerBeforeBodyCreditAndOtherStreamsStillProgress(int credit, int length) throws Exception {
        String text = length == 5 ? "hello" : "x".repeat(length);
        var written = new CountDownLatch(1);
        try (var server = httpsServerForTest("h2").withGzipEnabled(false)
            .addHandler((request, response) -> {
                if (request.relativePath().equals("/hello")) {
                    response.write(text);
                    written.countDown();
                } else response.status(204);
                return true;
            }).start(); var client = new H2Client(); var con = client.connect(server)) {
            con.socket().setSoTimeout(3000);
            con.handshake(new Http2Settings(false, 4096, 100, credit, 16384, 32768))
                .writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders(server.uri().getPort()))).flush();
            assertEquals("200", readIgnoringWindowUpdates(con, Http2HeadersFrame.class).headers().get(":status"));
            if (credit > 0) {
                var prefix = readIgnoringWindowUpdates(con, Http2DataFrame.class);
                assertEquals(text.substring(0, credit), prefix.toUTF8());
                assertFalse(prefix.endStream());
            }
            assertEquals(1, written.getCount(), "Whole-response write still waits for transport completion");
            var healthy = getHelloHeaders(server.uri().getPort());
            healthy.set(":path", "/healthy");
            con.writeFrame(new Http2HeadersFrame(3, true, healthy)).flush();
            var other = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertEquals(3, other.streamId());
            assertTrue(other.endStream());
            con.writeFrame(new Http2WindowUpdate(1, length - credit)).flush();
            var tail = readIgnoringWindowUpdates(con, Http2DataFrame.class);
            assertEquals(text.substring(credit), tail.toUTF8());
            assertFalse(tail.endStream());
            assertTrue(readIgnoringWindowUpdates(con, Http2DataFrame.class).endStream());
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void customEncoderIsPreparedOnceAndWrappedOnlyWhenSelected(boolean tls, boolean selected) throws Exception {
        var prepares = new AtomicInteger();
        var wraps = new AtomicInteger();
        var encoder = new ContentEncoder() {
            @Override public String contentCoding() { return "test"; }
            @Override public boolean prepare(MuRequest request, MuResponse response) {
                prepares.incrementAndGet();
                if (!selected) response.headers().set("x-large", "a".repeat(4096));
                return selected;
            }
            @Override public OutputStream wrapStream(MuRequest request, MuResponse response, OutputStream stream) {
                wraps.incrementAndGet(); return stream;
            }
        };
        try (var server = httpsServerForTest(tls ? "h2" : "http")
            .withHttp2Config(Http2ConfigBuilder.http2Enabled()).withContentEncoders(List.of(encoder))
            .addHandler((request, response) -> { response.write("hello"); return true; }).start();
             var client = new H2Client(); var con = tls ? client.connect(server) : client.connectClearText(server)) {
            con.socket().setSoTimeout(3000);
            con.handshake().writeFrame(new Http2HeadersFrame(1, true,
                getHelloHeaders(tls ? "https" : "http", server.uri().getPort()))).flush();
            readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertEquals("hello", readIgnoringWindowUpdates(con, Http2DataFrame.class).toUTF8());
            assertTrue(readIgnoringWindowUpdates(con, Http2DataFrame.class).endStream());
            assertEquals(1, prepares.get());
            assertEquals(selected ? 1 : 0, wraps.get());
        }
    }
}
