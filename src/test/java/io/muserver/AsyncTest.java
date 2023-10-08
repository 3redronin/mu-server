package io.muserver;

import okhttp3.*;
import okio.BufferedSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import scaffolding.Http1Client;
import scaffolding.ServerUtils;
import scaffolding.StringUtils;

import javax.ws.rs.RedirectionException;
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

    @ParameterizedTest
    @ValueSource(strings = { "http", "https"})
    public void errorCallbackInvokedWhenTimeoutOccurs(String type) {
        AtomicReference<ResponseInfo> infoRef = new AtomicReference<>();
        server = ServerUtils.testServer(type)
            .withRequestTimeout(50, TimeUnit.MILLISECONDS)
            .addHandler((request, response) -> {
                AsyncHandle handle = request.handleAsync();
                handle.addResponseCompleteHandler(infoRef::set);
                handle.setReadListener(DiscardingRequestBodyListener.INSTANCE);
                return true;
            })
            .start();
        try (var client = Http1Client.connect(server)) {
            client.writeRequestLine(Method.POST, "/")
                .writeHeader("content-length", "1000") // induce a read timeout by promising a body that is never sent
                .flushHeaders();
            client.readLine();
            var headers = client.readHeaders();
            client.readBody(headers);
        } catch (Exception ex) {
            assertThat(ex, instanceOf(IOException.class));
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

        var changeListener = new DatabaseListenerSimulator(Integer.MAX_VALUE);
        var timedOutLatch = new CountDownLatch(1);
        var ctxClosedLatch = new CountDownLatch(1);
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
        assertThat(server.stats().activeRequests().size(), is(0));
        assertThat(server.stats().completedRequests(), is(1L));
    }

    @Test
    public void blockingWritesCannotBeUsed() throws IOException {

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

        try (Response ignored = call(request().url(server.uri().toString()))) {
            assertThat(changeListener.errors.size(), greaterThanOrEqualTo(1));
            assertThat(changeListener.errors.get(0), instanceOf(IllegalStateException.class));
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
            }, "DatabaseSimulator");
            thread.start();
        }

        public void addListener(ChangeListener listener) {
            this.listeners.add(listener);
        }

        public void stop() {
            stopped.set(true);
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
