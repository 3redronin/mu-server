package io.muserver;

import jakarta.ws.rs.RedirectionException;
import okhttp3.*;
import okio.BufferedSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import scaffolding.Http1Client;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;
import scaffolding.StringUtils;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.*;
import static scaffolding.MuAssert.assertEventually;

@Timeout(30)
public class AsyncTest {

    private MuServer server;

    @Test
    public void canWriteAsync() throws Exception {
        byte[] bytes = StringUtils.randomBytes(120000);
        StringBuffer result = new StringBuffer();
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.contentType(ContentTypes.APPLICATION_OCTET_STREAM);
                AsyncHandle asyncHandle = request.handleAsync();
                asyncHandle.write(ByteBuffer.wrap(bytes), error -> {
                    if (error == null) {
                        result.append("success");
                    } else {
                        result.append("fail ").append(error);
                    }
                    asyncHandle.complete();
                });
            })
            .start();
        try (Response resp = call(request().url(server.uri().toString()))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().bytes(), equalTo(bytes));
            assertThat(result.toString(), equalTo("success"));
        }
    }

    @Test
    public void errorCallbackInvokedWhenTimeoutOccurs() {
        AtomicReference<ResponseInfo> infoRef = new AtomicReference<>();
        server = ServerUtils.httpsServerForTest()
            .withIdleTimeout(50, TimeUnit.MILLISECONDS)
            .addHandler((request, response) -> {
                AsyncHandle handle = request.handleAsync();
                handle.addResponseCompleteHandler(infoRef::set);
                return true;
            })
            .start();
        try (Response resp = call(request(server.uri()))) {
            resp.body().string();
            Assertions.fail("Should not succeed");
        } catch (Exception ex) {
            MuAssert.assertIOException(ex);
        }
        assertEventually(infoRef::get, not(nullValue()));
        assertThat(infoRef.get().completedSuccessfully(), is(false));
    }

    @Test
    public void responsesCanBeAsync() throws IOException {

        DatabaseListenerSimulator changeListener = new DatabaseListenerSimulator(10);

        server = ServerUtils.httpsServerForTest()
            .addHandler((request, response) -> {
                response.headers().add("X-Pre-Header", "Hello");
                return false;
            })
            .addHandler((request, response) -> {
                AsyncHandle ctx = request.handleAsync();

                changeListener.addListener(new ChangeListener() {
                    @Override
                    public void onData(String data) {
                        ctx.write(Mutils.toByteBuffer(data + "\n"));
                    }

                    @Override
                    public void onClose() {
                        ctx.complete();
                    }
                });

                changeListener.start();

                return true;
            })
            .addHandler((request, response) -> {
                response.headers().add("X-Post-Header", "Goodbye");
                return false;
            })
            .start();

        try (Response resp = call(request().url(server.uri().toString()))) {
            assertThat(changeListener.errors, is(empty()));
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("X-Pre-Header"), equalTo("Hello"));
            assertThat(resp.header("X-Post-Header"), is(nullValue()));
            assertThat(resp.body().string(), equalTo("Loop 0\nLoop 1\nLoop 2\nLoop 3\nLoop 4\nLoop 5\nLoop 6\nLoop 7\nLoop 8\nLoop 9\n"));
        }
    }

    @Test
    public void errorCallbacksHappenIfTheClientDisconnects() throws IOException, InterruptedException {

        DatabaseListenerSimulator changeListener = new DatabaseListenerSimulator(Integer.MAX_VALUE);
        CountDownLatch timedOutLatch = new CountDownLatch(1);
        CountDownLatch ctxClosedLatch = new CountDownLatch(1);
        List<Throwable> writeErrors = new ArrayList<>();

        AtomicLong connectionsDuringListening = new AtomicLong();

        server = ServerUtils.httpsServerForTest()
            .addHandler((request, response) -> {
                AsyncHandle ctx = request.handleAsync();
                connectionsDuringListening.set(request.server().stats().activeConnections());
                changeListener.addListener(new ChangeListener() {
                    public void onData(String data) {
                        try {
                            ByteBuffer text = Mutils.toByteBuffer(data + "\n");
                            ctx.write(text, error -> {
                                if (error != null) {
                                    changeListener.stop();
                                    ctxClosedLatch.countDown();
                                }
                            });
                        } catch (Throwable e) {
                            writeErrors.add(e);
                        }
                    }

                    public void onClose() {
                    }
                });

                timedOutLatch.await(1, TimeUnit.MINUTES);
                changeListener.start();

                return true;
            })
            .start();

        OkHttpClient impatientClient = client.newBuilder()
            .readTimeout(100, TimeUnit.MILLISECONDS)
            .build();
        try (Response resp = impatientClient.newCall(request().url(server.uri().toString()).build()).execute()) {
            assertThat(changeListener.errors, is(empty()));
            assertThat(resp.code(), equalTo(200));
            resp.body().string();
            Assertions.fail("Should have timeout out");
        } catch (SocketTimeoutException to) {
            timedOutLatch.countDown();
            assertThat("Timed out waiting for failure callback to happen",
                ctxClosedLatch.await(30, TimeUnit.SECONDS), is(true));
            assertThat(writeErrors, is(empty()));
        }

        assertThat(connectionsDuringListening.get(), is(1L));
        assertEventually(() -> server.stats().completedRequests(), is(1L));
        assertThat(server.stats().activeRequests().size(), is(0));
    }

    @Test
    public void halfClosingAnHttp1ClientDoesNotCancelASuspendedRequest() throws Exception {
        var requestStarted = new CountDownLatch(1);
        var requestCompleted = new CountDownLatch(1);
        var handleRef = new AtomicReference<AsyncHandle>();
        var responseRef = new AtomicReference<MuResponse>();
        var responseInfoRef = new AtomicReference<ResponseInfo>();
        server = ServerUtils.httpsServerForTest("http")
            .addHandler((request, response) -> {
                AsyncHandle handle = request.handleAsync();
                handleRef.set(handle);
                responseRef.set(response);
                handle.addResponseCompleteHandler(info -> {
                    responseInfoRef.set(info);
                    requestCompleted.countDown();
                });
                requestStarted.countDown();
                return true;
            })
            .start();

        try (var http1Client = Http1Client.connect(server)) {
            http1Client.writeRequestLine(Method.GET, "/").flushHeaders();
            assertThat("The request did not start", requestStarted.await(5, TimeUnit.SECONDS), is(true));
            http1Client.shutdownOutput();

            responseRef.get().write("Hello");
            handleRef.get().complete();

            assertThat(http1Client.readLine(), is("HTTP/1.1 200 OK"));
            Headers headers = http1Client.readHeaders();
            assertThat(http1Client.readBody(headers), is("Hello"));
        }

        assertThat("The suspended request did not complete", requestCompleted.await(3, TimeUnit.SECONDS), is(true));
        ResponseInfo responseInfo = responseInfoRef.get();
        assertThat(responseInfo, is(notNullValue()));
        assertThat(responseInfo.completedSuccessfully(), is(true));
    }

    @Test
    @Disabled("HTTP/1 waits for an async response before reading again, so it cannot observe the connection reset until the response completes")
    public void resettingAnHttp1ConnectionCompletesASuspendedRequest() throws Exception {
        var requestStarted = new CountDownLatch(1);
        var requestCompleted = new CountDownLatch(1);
        var handleRef = new AtomicReference<AsyncHandle>();
        var responseInfoRef = new AtomicReference<ResponseInfo>();
        server = ServerUtils.httpsServerForTest("http")
            .addHandler((request, response) -> {
                AsyncHandle handle = request.handleAsync();
                handleRef.set(handle);
                handle.addResponseCompleteHandler(info -> {
                    responseInfoRef.set(info);
                    requestCompleted.countDown();
                });
                requestStarted.countDown();
                return true;
            })
            .start();

        boolean completedAfterReset;
        ResponseInfo responseInfo;
        try (var http1Client = Http1Client.connect(server)) {
            http1Client.writeRequestLine(Method.GET, "/").flushHeaders();
            assertThat("The request did not start", requestStarted.await(5, TimeUnit.SECONDS), is(true));
            http1Client.abort();
            completedAfterReset = requestCompleted.await(3, TimeUnit.SECONDS);
            responseInfo = responseInfoRef.get();
        } finally {
            AsyncHandle handle = handleRef.get();
            if (handle != null) {
                handle.complete();
            }
        }

        assertThat("Resetting the HTTP/1 connection did not complete the suspended request", completedAfterReset, is(true));
        assertThat(responseInfo, is(notNullValue()));
        assertThat(responseInfo.completedSuccessfully(), is(false));
    }

    @Test
    public void anHttp1RequestReadWhileAnAsyncResponseIsPendingIsProcessedNext() throws Exception {
        var firstRequestStarted = new CountDownLatch(1);
        var secondRequestStarted = new CountDownLatch(1);
        var firstHandle = new AtomicReference<AsyncHandle>();
        var firstResponse = new AtomicReference<MuResponse>();
        server = ServerUtils.httpsServerForTest("http")
            .addHandler((request, response) -> {
                if (request.relativePath().equals("/one")) {
                    firstResponse.set(response);
                    firstHandle.set(request.handleAsync());
                    firstRequestStarted.countDown();
                } else {
                    secondRequestStarted.countDown();
                    response.write("Two");
                }
                return true;
            })
            .start();

        try (var http1Client = Http1Client.connect(server)) {
            http1Client.writeRequestLine(Method.GET, "/one").flushHeaders();
            http1Client.writeRequestLine(Method.GET, "/two").flushHeaders();
            assertThat("The first request did not start", firstRequestStarted.await(5, TimeUnit.SECONDS), is(true));
            assertThat("The second request ran before the first response completed",
                secondRequestStarted.await(200, TimeUnit.MILLISECONDS), is(false));

            firstResponse.get().write("One");
            firstHandle.get().complete();

            assertThat(http1Client.readLine(), is("HTTP/1.1 200 OK"));
            Headers firstHeaders = http1Client.readHeaders();
            assertThat(http1Client.readBody(firstHeaders), is("One"));
            assertThat("The second request did not start", secondRequestStarted.await(5, TimeUnit.SECONDS), is(true));
            assertThat(http1Client.readLine(), is("HTTP/1.1 200 OK"));
            Headers secondHeaders = http1Client.readHeaders();
            assertThat(http1Client.readBody(secondHeaders), is("Two"));
        }
    }

    @Test
    public void blockingWritesCanBeUsed() throws IOException {

        DatabaseListenerSimulator changeListener = new DatabaseListenerSimulator(10);

        server = ServerUtils.httpsServerForTest()
            .addHandler((request, response) -> {
                AsyncHandle ctx = request.handleAsync();
                changeListener.addListener(new ChangeListener() {
                    public void onData(String data) {
                        response.writer().write(data + "\n");
                    }

                    public void onClose() {
                        ctx.complete();
                    }
                });
                changeListener.start();
                return true;
            })
            .start();

        try (Response resp = call(request().url(server.uri().toString()))) {
            assertThat(changeListener.errors.size(), equalTo(0));
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("Loop 0\nLoop 1\nLoop 2\nLoop 3\nLoop 4\nLoop 5\nLoop 6\nLoop 7\nLoop 8\nLoop 9\n"));
        }
    }

    @Test
    public void requestBodiesCanBeReadAsynchronously() throws IOException {
        List<Throwable> errors = new ArrayList<>();
        server = ServerUtils.httpsServerForTest()
            .addHandler((request, response) -> {
                AsyncHandle handle = request.handleAsync();
                handle.setReadListener(new RequestBodyListener() {
                    @Override
                    public void onDataReceived(ByteBuffer bb, DoneCallback doneCallback) throws Exception {
                        handle.write(bb, error -> {
                            if (error != null) {
                                errors.add(error);
                            }
                            doneCallback.onComplete(error);
                        });
                    }

                    @Override
                    public void onComplete() {
                        handle.complete();
                    }

                    @Override
                    public void onError(Throwable t) {
                        errors.add(t);
                    }
                });

                return true;
            })
            .start();

        Request.Builder request = request()
            .url(server.uri().toString())
            .post(new RequestBody() {
                @Override
                public MediaType contentType() {
                    return MediaType.parse("text/plain");
                }

                @Override
                public void writeTo(BufferedSink sink) throws IOException {
                    for (int i = 0; i < 10; i++) {
                        sink.writeUtf8("Loop " + i + "\n");
                        try {
                            Thread.sleep(70);
                        } catch (InterruptedException e) {
                            errors.add(e);
                        }
                    }
                }
            });

        try (Response resp = call(request)) {
            assertThat(errors, is(empty()));
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("Loop 0\nLoop 1\nLoop 2\nLoop 3\nLoop 4\nLoop 5\nLoop 6\nLoop 7\nLoop 8\nLoop 9\n"));
        }

    }


    interface ChangeListener {
        void onData(String data);
        void onClose();
    }

    static class DatabaseListenerSimulator {
        private final int eventsToFire;
        private List<ChangeListener> listeners = new ArrayList<>();

        private final Random rng = new Random();
        public final List<Throwable> errors = new ArrayList<>();
        private AtomicBoolean stopped = new AtomicBoolean(false);
        private Thread thread;

        DatabaseListenerSimulator(int eventsToFire) {
            this.eventsToFire = eventsToFire;
        }

        public void start() {
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int i = 0; i < eventsToFire; i++) {
                        if (stopped.get()) {
                            break;
                        }
                        try {
                            Thread.sleep(rng.nextInt(100));
                        } catch (InterruptedException e) {
                            break;
                        }
                        for (ChangeListener listener : listeners) {
                            try {
                                listener.onData("Loop " + i);
                            } catch (Throwable e) {
                                errors.add(e);
                            }
                        }
                    }
                    for (ChangeListener listener : listeners) {
                        try {
                            listener.onClose();
                        } catch (Throwable e) {
                            errors.add(e);
                        }
                    }

                }
            });
            thread.start();
        }

        public void addListener(ChangeListener listener) {
            this.listeners.add(listener);
        }

        public void stop() throws InterruptedException {
            stopped.set(true);
            thread.join();
        }
    }

    @Test
    public void webApplicationExceptionsCanBeSetOnCompletion() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                AsyncHandle handle = request.handleAsync();
                handle.complete(new RedirectionException("Blah not here!", 301, request.uri().resolve("/blah")));
            })
            .start();
        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.code(), is(301));
            assertThat(resp.header("location"), is(server.uri().resolve("/blah").toString()));
            assertThat(resp.body().string(), containsString("Blah not here!"));
        }
    }

    @AfterEach
    public void destroy() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}
