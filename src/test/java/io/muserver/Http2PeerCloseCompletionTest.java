package io.muserver;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static io.muserver.RFCTestUtils.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.MuAssert.assertEventually;
import static scaffolding.ServerUtils.httpsServerForTest;

@Timeout(20)
class Http2PeerCloseCompletionTest {
    enum EncoderFailure { NONE, INITIALIZE, WRITE, CLOSE }

    static Stream<Arguments> encoderCases() {
        return Stream.of(false, true).flatMap(tls -> Arrays.stream(EncoderFailure.values())
            .map(failure -> Arguments.of(tls, failure)));
    }

    @ParameterizedTest
    @MethodSource("encoderCases")
    void peerCloseAfter304PreservesTheEncoderOutcome(boolean tls, EncoderFailure failure) throws Exception {
        var initializing = new CountDownLatch(1);
        var releaseInitialization = new CountDownLatch(1);
        var completed = new CompletableFuture<ResponseInfo>();
        var connection = new CompletableFuture<Http2Connection>();
        var notifications = new AtomicInteger();
        var encoderCloses = new AtomicInteger();
        var callbackError = new CompletableFuture<Throwable>();
        var gzip = GZIPEncoderBuilder.gzipEncoder().withMinGzipSize(0)
            .withMimeTypesToGzip(Set.of("text/plain")).build();
        var encoder = new ContentEncoder() {
            @Override public String contentCoding() { return gzip.contentCoding(); }
            @Override public boolean prepare(MuRequest request, MuResponse response) {
                return gzip.prepare(request, response);
            }
            @Override public OutputStream wrapStream(MuRequest request, MuResponse response, OutputStream out) throws IOException {
                initializing.countDown();
                await(releaseInitialization);
                if (failure == EncoderFailure.INITIALIZE) throw new IOException("Encoder initialization failed");
                return new FilterOutputStream(gzip.wrapStream(request, response, out)) {
                    @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                        if (failure == EncoderFailure.WRITE) throw new IOException("Encoder write failed");
                        out.write(bytes, offset, length);
                    }
                    @Override public void close() throws IOException {
                        try { super.close(); } finally { encoderCloses.incrementAndGet(); }
                        if (failure == EncoderFailure.CLOSE) throw new IOException("Encoder close failed");
                    }
                };
            }
        };
        var server = httpsServerForTest(tls ? "h2" : "http")
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .withContentEncoders(List.of(encoder))
            .addResponseCompleteListener(info -> {
                notifications.incrementAndGet();
                completed.complete(info);
            })
            .addHandler((request, response) -> {
                connection.complete((Http2Connection) request.connection());
                response.status(304);
                response.contentType("text/plain");
                var async = request.handleAsync();
                async.write(ByteBuffer.wrap(new byte[]{'x'}), error -> {
                    callbackError.complete(error);
                    async.complete(error);
                });
                return true;
            }).start();
        try (var client = new H2Client();
             var con = tls ? client.connect(server) : client.connectClearText(server)) {
            var headers = getHelloHeaders(tls ? "https" : "http", server.uri().getPort());
            headers.set("accept-encoding", "gzip");
            con.handshake().writeFrame(new Http2HeadersFrame(1, true, headers)).flush();
            var response = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertEquals("304", response.headers().get(":status"));
            assertEquals("gzip", response.headers().get("content-encoding"));
            assertTrue(response.endStream());
            assertTrue(initializing.await(5, TimeUnit.SECONDS));
            var serverConnection = connection.get(5, TimeUnit.SECONDS);
            con.socket().close();
            assertEventually(() -> serverConnection.testProbe().inputState(), not(equalTo("ACTIVE")));
            assertFalse(completed.isDone(), "Completion must wait for encoder and application cleanup");
            releaseInitialization.countDown();
            assertEquals(failure == EncoderFailure.NONE, completed.get(5, TimeUnit.SECONDS).completedSuccessfully());
            if (failure == EncoderFailure.NONE || failure == EncoderFailure.CLOSE) {
                assertNull(callbackError.get(5, TimeUnit.SECONDS));
                assertEquals(1, encoderCloses.get());
            } else {
                assertNotNull(callbackError.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, notifications.get());
            assertEventually(() -> serverConnection.testProbe().streams().isEmpty(), is(true));
        } finally {
            releaseInitialization.countDown();
            server.stop();
        }
    }

    static Stream<Arguments> bodylessCases() {
        return Stream.of(false, true).flatMap(tls -> Stream.of(200, 204, 205, 304)
            .flatMap(status -> Stream.of(false, true).map(fail -> Arguments.of(tls, status, fail))));
    }

    @ParameterizedTest
    @MethodSource("bodylessCases")
    void peerCloseWaitsForTheAsyncCallback(boolean tls, int status, boolean failCallback) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var completed = new CompletableFuture<ResponseInfo>();
        var connection = new CompletableFuture<Http2Connection>();
        var notifications = new AtomicInteger();
        var application = Executors.newSingleThreadExecutor(r -> new Thread(r, "completion-225-application"));
        var completionThread = new AtomicReference<String>();
        var server = httpsServerForTest(tls ? "h2" : "http")
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .withHandlerExecutor(application)
            .withGzipEnabled(false)
            .addResponseCompleteListener(info -> {
                completionThread.set(Thread.currentThread().getName());
                notifications.incrementAndGet();
                completed.complete(info);
            })
            .addHandler((request, response) -> {
                connection.complete((Http2Connection) request.connection());
                response.status(status);
                var async = request.handleAsync();
                async.write(ByteBuffer.wrap(new byte[]{'x'}), error -> {
                    assertNull(error);
                    entered.countDown();
                    await(release);
                    if (failCallback) throw new IOException("Application callback failed");
                    async.complete();
                });
                return true;
            }).start();
        try (var client = new H2Client();
             var con = tls ? client.connect(server) : client.connectClearText(server)) {
            var headers = getHelloHeaders(tls ? "https" : "http", server.uri().getPort());
            if (status == 200) headers.set(":method", "HEAD");
            con.handshake().writeFrame(new Http2HeadersFrame(1, true, headers)).flush();
            var response = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertEquals(Integer.toString(status), response.headers().get(":status"));
            assertTrue(response.endStream());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var serverConnection = connection.get(5, TimeUnit.SECONDS);
            con.socket().close();
            assertEventually(() -> serverConnection.testProbe().inputState(), not(equalTo("ACTIVE")));
            assertFalse(completed.isDone());
            release.countDown();
            assertEquals(!failCallback, completed.get(5, TimeUnit.SECONDS).completedSuccessfully());
            assertEquals("completion-225-application", completionThread.get());
            assertEquals(1, notifications.get());
            assertEventually(() -> serverConnection.testProbe().streams().isEmpty(), is(true));
        } finally {
            release.countDown();
            server.stop();
            application.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void disconnectOutcomesAreIndependentForStreamsSharingAConnection(boolean tls) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var delivered = new CompletableFuture<ResponseInfo>();
        var unfinished = new CompletableFuture<ResponseInfo>();
        var connection = new CompletableFuture<Http2Connection>();
        var notifications = new AtomicInteger();
        var server = httpsServerForTest(tls ? "h2" : "http")
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addResponseCompleteListener(info -> {
                notifications.incrementAndGet();
                (info.request().method().equals(Method.POST) ? unfinished : delivered).complete(info);
            })
            .addHandler((request, response) -> {
                connection.complete((Http2Connection) request.connection());
                response.status(204);
                if (request.method().equals(Method.GET)) {
                    var async = request.handleAsync();
                    async.write(ByteBuffer.wrap(new byte[]{'x'}), error -> {
                        assertNull(error);
                        entered.countDown();
                        await(release);
                        async.complete();
                    });
                }
                return true;
            }).start();
        try (var client = new H2Client();
             var con = tls ? client.connect(server) : client.connectClearText(server)) {
            var headers = getHelloHeaders(tls ? "https" : "http", server.uri().getPort());
            con.handshake().writeFrame(new Http2HeadersFrame(1, true, headers)).flush();
            assertTrue(readIgnoringWindowUpdates(con, Http2HeadersFrame.class).endStream());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            headers.set(":method", "POST");
            con.writeFrame(new Http2HeadersFrame(3, false, headers)).flush();
            var earlyResponse = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertEquals(3, earlyResponse.streamId());
            assertTrue(earlyResponse.endStream());
            var serverConnection = connection.get(5, TimeUnit.SECONDS);
            var uploading = serverConnection.testProbe().streams().applicationStream(3);
            assertNotNull(uploading);
            assertEventually(uploading::applicationExchangeEnded, is(true));
            con.socket().close();
            assertFalse(unfinished.get(5, TimeUnit.SECONDS).completedSuccessfully());
            assertFalse(delivered.isDone());
            release.countDown();
            assertTrue(delivered.get(5, TimeUnit.SECONDS).completedSuccessfully());
            assertEquals(2, notifications.get());
            assertEventually(() -> serverConnection.testProbe().streams().isEmpty(), is(true));
        } finally {
            release.countDown();
            server.stop();
        }
    }

    @Test
    void aHandlerFailureAfterACompleteResponseStillFailsTheExchange() throws Exception {
        var ended = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var completed = new CompletableFuture<ResponseInfo>();
        var connection = new CompletableFuture<Http2Connection>();
        var server = httpsServerForTest("http")
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addResponseCompleteListener(completed::complete)
            .addHandler((request, response) -> {
                connection.complete((Http2Connection) request.connection());
                response.status(304);
                response.write("suppressed");
                ended.countDown();
                await(release);
                throw new IOException("Handler failed after sending the response");
            }).start();
        try (var client = new H2Client(); var con = client.connectClearText(server)) {
            con.handshake().writeFrame(new Http2HeadersFrame(1, true,
                getHelloHeaders("http", server.uri().getPort()))).flush();
            assertTrue(readIgnoringWindowUpdates(con, Http2HeadersFrame.class).endStream());
            assertTrue(ended.await(5, TimeUnit.SECONDS));
            var serverConnection = connection.get(5, TimeUnit.SECONDS);
            con.socket().close();
            assertEventually(() -> serverConnection.testProbe().inputState(), not(equalTo("ACTIVE")));
            assertFalse(completed.isDone());
            release.countDown();
            assertFalse(completed.get(5, TimeUnit.SECONDS).completedSuccessfully());
        } finally {
            release.countDown();
            server.stop();
        }
    }

    @Test
    void shutdownStillTerminatesAnUncompletedAsyncExchangeAfterPeerClose() throws Exception {
        var outputWritten = new CountDownLatch(1);
        var completed = new CompletableFuture<ResponseInfo>();
        var connection = new CompletableFuture<Http2Connection>();
        var server = httpsServerForTest("http")
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addResponseCompleteListener(completed::complete)
            .addHandler((request, response) -> {
                connection.complete((Http2Connection) request.connection());
                response.status(204);
                request.handleAsync().write(ByteBuffer.wrap(new byte[]{'x'}), error -> {
                    assertNull(error);
                    outputWritten.countDown();
                });
                return true;
            }).start();
        try (var client = new H2Client(); var con = client.connectClearText(server)) {
            con.handshake().writeFrame(new Http2HeadersFrame(1, true,
                getHelloHeaders("http", server.uri().getPort()))).flush();
            assertTrue(readIgnoringWindowUpdates(con, Http2HeadersFrame.class).endStream());
            assertTrue(outputWritten.await(5, TimeUnit.SECONDS));
            var serverConnection = connection.get(5, TimeUnit.SECONDS);
            con.socket().close();
            assertEventually(() -> serverConnection.testProbe().inputState(), not(equalTo("ACTIVE")));
            assertFalse(completed.isDone());
            var stream = serverConnection.testProbe().streams().applicationStream(1);
            assertNotNull(stream);
            server.stop(100, TimeUnit.MILLISECONDS);
            // Once the shutdown deadline expires, application executors may reject
            // listeners. The exchange itself must still terminate and release its state.
            assertEventually(stream::applicationExchangeEnded, is(true));
            assertFalse(stream.completedSuccessfully());
            assertEventually(() -> serverConnection.testProbe().streams().isEmpty(), is(true));
        } finally {
            server.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retainedApplicationWorkStillTimesOutAfterAMixedStreamDisconnect(boolean tls) throws Exception {
        var written = new CountDownLatch(1);
        var completed = new CompletableFuture<ResponseInfo>();
        var connection = new CompletableFuture<Http2Connection>();
        var server = httpsServerForTest(tls ? "h2" : "http")
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .withIdleTimeout(1000, TimeUnit.MILLISECONDS)
            .addResponseCompleteListener(info -> {
                if (info.request().method().equals(Method.GET)) completed.complete(info);
            })
            .addHandler((request, response) -> {
                connection.complete((Http2Connection) request.connection());
                response.status(204);
                if (request.method().equals(Method.GET)) {
                    request.handleAsync().write(ByteBuffer.wrap(new byte[]{'x'}), error -> {
                        assertNull(error);
                        written.countDown();
                    });
                }
                return true;
            }).start();
        try (var client = new H2Client();
             var con = tls ? client.connect(server) : client.connectClearText(server)) {
            var headers = getHelloHeaders(tls ? "https" : "http", server.uri().getPort());
            con.handshake().writeFrame(new Http2HeadersFrame(1, true, headers)).flush();
            assertTrue(readIgnoringWindowUpdates(con, Http2HeadersFrame.class).endStream());
            assertTrue(written.await(5, TimeUnit.SECONDS));
            headers.set(":method", "POST");
            con.writeFrame(new Http2HeadersFrame(3, false, headers)).flush();
            assertTrue(readIgnoringWindowUpdates(con, Http2HeadersFrame.class).endStream());
            con.socket().close();
            var info = completed.get(5, TimeUnit.SECONDS);
            assertFalse(info.completedSuccessfully());
            assertEquals(ResponseState.TIMED_OUT, info.response().responseState());
            var serverConnection = connection.get(5, TimeUnit.SECONDS);
            assertEventually(() -> serverConnection.testProbe().streams().isEmpty(), is(true));
        } finally {
            server.stop();
        }
    }

    @Test
    void publishedCompletionIsStableAcrossLateConnectionTeardown() throws Exception {
        var completed = new CompletableFuture<ResponseInfo>();
        var notifications = new AtomicInteger();
        try (var server = httpsServerForTest("h2")
            .addResponseCompleteListener(info -> {
                notifications.incrementAndGet();
                completed.complete(info);
            })
            .addHandler((request, response) -> {
                response.status(204);
                return true;
            }).start();
             var client = new H2Client(); var con = client.connect(server)) {
            con.handshake().writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders(server.uri().getPort()))).flush();
            assertTrue(readIgnoringWindowUpdates(con, Http2HeadersFrame.class).endStream());
            var info = completed.get(5, TimeUnit.SECONDS);
            assertTrue(info.completedSuccessfully());
            // A connection-termination task may have captured the stream before
            // the writer retired it, then run after its completion was published.
            ((Http2Stream) info).onConnectionTerminated(new IOException("Delayed teardown"), ResponseState.CLIENT_DISCONNECTED);
            assertTrue(info.completedSuccessfully());
            assertEquals(1, notifications.get());
        }
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("Timed out waiting for test release");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for test release", e);
        }
    }
}
