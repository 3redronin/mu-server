package io.muserver;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.*;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;
import scaffolding.SlowBodySender;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.assertEventually;
import static scaffolding.StringUtils.randomAsciiStringOfLength;

public class RequestBodyReaderListenerAdapterTest {
    private MuServer server;
    private RecordingRequestBodyListener readListener;

    @Test
    public void requestBodiesCanBeReadAsynchronously() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler((request, response) -> {
                AsyncHandle handle = request.handleAsync();
                readListener = new RecordingRequestBodyListener(handle);
                handle.setReadListener(readListener);
                return true;
            })
            .start();

        Request.Builder request = request()
            .url(server.uri().toString())
            .post(new SlowBodySender(2));

        try (Response resp = call(request)) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("Loop 0\nLoop 1\n"));
        }
        assertThat("All: " + readListener.events.toString(), readListener.events, contains("data received: 7 bytes", "data written", "data received: 7 bytes", "data written", "onComplete"));
    }

    @Test
    @Timeout(30)
    public void emptyBodiesAreOkay() {
        server = ServerUtils.httpsServerForTest()
            .withRequestTimeout(100, TimeUnit.MILLISECONDS)
            .addHandler((request, response) -> {
                AsyncHandle handle = request.handleAsync();
                readListener = new RecordingRequestBodyListener(handle);
                handle.setReadListener(readListener);
                return true;
            })
            .start();

        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.code(), equalTo(200));
        }
        assertThat("All: " + readListener.events, readListener.events, hasItems("onComplete"));
    }

    @Test
    @Timeout(20)
    public void ifTheRequestBodyIsTooSlowAnErrorIsReturnedOrConnectionIsKilled() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .withRequestTimeout(100, TimeUnit.MILLISECONDS)
            .addHandler((request, response) -> {
                AsyncHandle handle = request.handleAsync();
                readListener = new RecordingRequestBodyListener(handle);
                handle.setReadListener(readListener);
                return true;
            })
            .start();

        Request.Builder request = request()
            .url(server.uri().toString())
            .post(new SlowBodySender(100, 200));

        try (Response resp = call(request)) {
            assertThat(resp.code(), equalTo(408));
        } catch (Exception ex) {
            MuAssert.assertIOException(ex);
        }

        assertEventually(() -> {
            System.out.println("readListener.events = " + readListener.events);
            return readListener.events;
        }, allOf(
            hasItems("data received: 7 bytes", "data written", "Error for onError: 408 Request Timeout")
        ));
    }

    @Test
    public void exceedingMaxContentLengthWillResultIn413WithFixedSize() throws IOException {
        int contentLength = 1024;

        server = ServerUtils.httpsServerForTest()
            .withMaxRequestSize(1000)
            .start();

        final String bigString = randomAsciiStringOfLength(contentLength);

        Request.Builder request = request()
            .url(server.uri().toString())
            .post(RequestBody.create(bigString, MediaType.get("text/plain")));

        try (Response resp = call(request)) {
            assertThat(resp.code(), equalTo(413));
            assertThat(resp.body().string(), containsString("413 Content Too Large"));
        }
    }
    @Test
    public void exceedingMaxContentLengthWillResultIn413WithChunkedIfBodyIsReadInHandler() throws IOException {
        int contentLength = 1024;

        server = ServerUtils.httpsServerForTest()
            .withMaxRequestSize(1000)
            .addHandler((request, response) -> {
                request.readBodyAsString();
                response.write("hello");
                return true;
            })
            .start();

        final String bigString = randomAsciiStringOfLength(contentLength);

        Request.Builder request = request()
            .url(server.uri().toString())
            .post(new RequestBody() {
                public MediaType contentType() {
                    return MediaType.parse("text/plain");
                }
                public void writeTo(BufferedSink bufferedSink) throws IOException {
                    bufferedSink.write(bigString.getBytes(StandardCharsets.UTF_8));
                    bufferedSink.flush();
                }
            });

        try (Response resp = call(request)) {
            assertThat(resp.code(), equalTo(413));
            assertThat(resp.body().string(), containsString("413 Content Too Large"));
        }
    }

    @Test
    public void exceedingMaxContentLengthWillResultInClosedConnectionIfRequestBodyChunkedAndResponseAlreadyStarted() throws IOException {
        int contentLength = 1024;

        server = ServerUtils.httpsServerForTest()
            .withMaxRequestSize(1000)
            .addHandler((request, response) -> {
                response.write("hello");
                request.readBodyAsString();
                return true;
            })
            .start();

        final String bigString = randomAsciiStringOfLength(contentLength);

        Request.Builder request = request()
            .url(server.uri().toString())
            .post(new RequestBody() {
                public MediaType contentType() {
                    return MediaType.parse("text/plain");
                }
                public void writeTo(BufferedSink bufferedSink) throws IOException {
                    for (int i = 0; i < 100; i++) {
                        bufferedSink.write(bigString.getBytes(StandardCharsets.UTF_8));
                        bufferedSink.flush();
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            });

        var uioe = assertThrows(UncheckedIOException.class, () -> {
            try (var resp = call(request)) {
                Assertions.fail("Got a valid response " + resp);
            }
        });
        assertThat(uioe.getCause(), instanceOf(SocketException.class));
    }


    @Test
    public void exceedingUploadSizeResultsIn413OrKilledConnectionForChunkedRequestWhereResponseNotStarted() throws Exception {
        AtomicReference<Throwable> exception = new AtomicReference<>();
        server = ServerUtils.httpsServerForTest()
            .withMaxRequestSize(1000)
            .addHandler((request, response) -> {
                AsyncHandle handle = request.handleAsync();
                handle.setReadListener(new RequestBodyListener() {
                    @Override
                    public void onDataReceived(ByteBuffer buffer, DoneCallback doneCallback) throws Exception {
                        doneCallback.onComplete(null);
                    }

                    @Override
                    public void onComplete() {
                        handle.complete();
                    }

                    @Override
                    public void onError(Throwable t) {
                        exception.set(t);
                        handle.complete(t);
                    }
                });
                return true;
            })
            .start();

        Request.Builder request = request(server.uri().resolve("/?debug=here"))
            .post(new SlowBodySender(1000, 0));

        try (Response resp = call(request)) {
            assertThat(resp.code(), equalTo(413));
            assertThat(resp.body().string(), containsString("413 Content Too Large"));
        } catch (Exception e) {
            assertThat(e.getCause(), instanceOf(SocketException.class));
        }
        assertEventually(exception::get, instanceOf(HttpException.class));
        assertThat(((HttpException) exception.get()).status(), equalTo(HttpStatus.CONTENT_TOO_LARGE_413));
    }

    @Test
    public void ifRequestBodyNotConsumedItIsJustDiscarded() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler((request, response) -> {
                response.write("Hello there");
                return true;
            })
            .start();

        Request.Builder request = request()
            .url(server.uri().toString())
            .post(new SlowBodySender(1000, 0));

        try (Response resp = call(request)) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("Hello there"));
        }
    }

    @Test
    public void ifRequestBodyNotConsumedButItIsOverSizeThenConnectionIsClosed() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .withMaxRequestSize(1000)
            .addHandler((request, response) -> {
                response.write("Hello there");
                return true;
            })
            .start();

        Request.Builder request = request()
            .url(server.uri().toString())
            .post(new RequestBody() {
                public MediaType contentType() {
                    return MediaType.parse("text/plain");
                }
                public void writeTo(BufferedSink bufferedSink) throws IOException {
                    for (int i = 0; i < 100; i++) {
                        bufferedSink.write("!".repeat(999).getBytes(StandardCharsets.UTF_8));
                        bufferedSink.flush();
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                        }
                    }
                }
            });

        try (Response resp = call(request)) {
            String read = resp.body().string();
            Assertions.fail("Should not be able to read body but got " + read + " and " + resp.isSuccessful());
        } catch (Exception ex) {
            MuAssert.assertIOException(ex);
        }
    }


    @AfterEach
    public void destroy() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

    private static class RecordingRequestBodyListener implements RequestBodyListener {

        final List<String> events;
        private final AsyncHandle handle;

        public RecordingRequestBodyListener(AsyncHandle handle) {
            this.handle = handle;
            events = new CopyOnWriteArrayList<>();
        }

        private void record(String message, Throwable err) {
            if (err == null) {
                events.add(message);
            } else {
                events.add("Error for " + message + ": " + Mutils.coalesce(err.getMessage(), err.getClass().getSimpleName()));
            }
        }

        @Override
        public void onDataReceived(ByteBuffer bb, DoneCallback doneCallback) {
            record("data received: " + bb.remaining() + " bytes", null);
            handle.write(bb, error -> {
                record("data written", error);
                doneCallback.onComplete(error);
            });
        }

        @Override
        public void onComplete() {
            record("onComplete", null);
            handle.complete();
        }

        @Override
        public void onError(Throwable t) {
            record("onError", t);
        }
    }

}
