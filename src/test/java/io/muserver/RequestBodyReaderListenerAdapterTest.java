package io.muserver;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import scaffolding.Http1Client;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;
import scaffolding.SlowBodySender;

import javax.ws.rs.ClientErrorException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.muserver.MuServerBuilder.httpServer;
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
    public void requestBodiesCanBeReadAsynchronouslyOverHttp() throws IOException {
        server = httpServer()
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
        assertThat("All: " + readListener.events, readListener.events, contains("data received: 7 bytes", "data written", "data received: 7 bytes", "data written", "onComplete"));
    }

    @Test
    @Timeout(20)
    public void requestBodiesCanBeReadAsynchronouslyOverHttps() throws IOException {
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
        assertThat("All: " + readListener.events, readListener.events, contains("data received: 7 bytes", "data written", "data received: 7 bytes", "data written", "onComplete"));
    }


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

        String body = "*".repeat(20000);
        Request.Builder request = request()
            .url(server.uri().toString())
            .post(RequestBody.create(body, MediaType.get("text/plain")));

        try (Response resp = call(request)) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo(body));
        }
        // this seems a bit weird to assert on message sizes - it was originally for testing http2. Maybe delete this?
        assertThat("All: " + readListener.events, readListener.events, contains("data received: 8192 bytes", "data written", "data received: 8192 bytes", "data written", "data received: 3616 bytes", "data written", "onComplete"));
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
    public void ifTheRequestBodyIsTooSlowAnErrorIsReturnedOrConnectionIsKilled() {
        server = ServerUtils.httpsServerForTest()
            .withRequestTimeout(50, TimeUnit.MILLISECONDS)
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
            resp.body().string();
            assertThat(resp.code(), equalTo(408));
        } catch (Exception ex) {
            MuAssert.assertIOException(ex);
        }

        assertEventually(() -> readListener.events, allOf(
            hasItems("data received: 7 bytes", "data written", "Error for onError: InterruptedByTimeoutException")
        ));
    }

    @Test
    public void exceedingMaxContentLengthWillResultIn413() throws IOException {

        server = ServerUtils.httpsServerForTest()
            .withMaxRequestSize(1000)
            .addHandler((request, response) -> {
                request.readBodyAsString();
                response.write("hello");
                return true;
            })
            .start();

        String bigString = randomAsciiStringOfLength(1024);

        Request.Builder request = request()
            .url(server.uri().toString())
            .post(RequestBody.create(bigString, MediaType.get("text/plain")));

        try (Response resp = call(request)) {
            assertThat(resp.code(), equalTo(413));
            assertThat(resp.body().string(), containsString("413 Request Entity Too Large"));
        }

    }

    @Test
    public void exceedingUploadSizeResultsIn413OrKilledConnectionForChunkedRequestWhereResponseNotStarted() {
        AtomicReference<Throwable> exception = new AtomicReference<>();
        server = httpServer()
            .withMaxRequestSize(1000)
            .addHandler((request, response) -> {
                AsyncHandle handle = request.handleAsync();
                handle.setReadListener(new RequestBodyListener() {
                    @Override
                    public void onDataReceived(ByteBuffer buffer, DoneCallback doneCallback) {
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

        Request.Builder request = request()
            .url(server.uri().toString())
            .post(new SlowBodySender(1000, 0));

        try (Response resp = call(request)) {
            assertThat(resp.code(), equalTo(413));
            assertThat(resp.body().string(), containsString("413 Request Entity Too Large"));
        } catch (Exception e) {
            // The HttpServerKeepAliveHandler will probably close the connection before the full request body is read, which is probably a good thing in this case.
            // So allow a valid 413 response or an error
            MuAssert.assertIOException(e);
        }
        assertEventually(exception::get, instanceOf(ClientErrorException.class));
        assertThat(((ClientErrorException) exception.get()).getResponse().getStatus(), equalTo(413));
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
    public void ifRequestBodyNotConsumedButItIsOverSizeThenConnectionIsClosed() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .withMaxRequestSize(1000)
            .withRequestBodyTooLargeAction(RequestBodyErrorAction.KILL_CONNECTION)
            .addHandler((request, response) -> {
                response.write("Hello there");
                return true;
            })
            .start();

        try (var client = Http1Client.connect(server)) {
            client.writeRequestLine(Method.POST, "/")
                .writeHeader("transfer-encoding", "chunked")
                .writeHeader("content-type", "text/plain;charset=utf-8")
                .flushHeaders();
            client.writeAscii("8\r\n12345678\r\n").flush();
            assertThat(client.readLine(), equalTo("HTTP/1.1 200 OK"));
            client.writeAscii("3E8\r\n" + ("0".repeat(1000) + "\r\n")).flush();
            var ex = assertThrows(UncheckedIOException.class, () -> {
                client.readBody(client.readHeaders());
                for (int i = 0; i < 100; i++) {
                    client.writeAscii("1").flush();
                    Thread.sleep(10);
                }
            });
            assertThat(ex.getCause(), instanceOf(SocketException.class));
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
