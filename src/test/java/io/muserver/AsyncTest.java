package io.muserver;

import okhttp3.*;
import okio.BufferedSink;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static io.muserver.MuServerBuilder.httpsServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.*;

public class AsyncTest {
    private MuServer server;


    @Test
    public void responsesCanBeAsync() throws IOException {

        DatabaseListenerSimulator changeListener = new DatabaseListenerSimulator(10);

        server = httpsServer()
            .addHandler((request, response) -> {
                response.headers().add("X-Pre-Header", "Hello");
                return false;
            })
            .addHandler((request, response) -> {
                AsyncHandle ctx = request.handleAsync();

                changeListener.addListener(new ChangeListener() {
                    @Override
                    public void onData(String data) {
                        ctx.write(ByteBuffer.wrap((data + "\n").getBytes(StandardCharsets.UTF_8)));
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

        server = httpsServer()
            .addHandler((request, response) -> {
                AsyncHandle ctx = request.handleAsync();
                connectionsDuringListening.set(request.server().stats().activeConnections());
                changeListener.addListener(new ChangeListener() {
                    public void onData(String data) {
                        try {
                            ByteBuffer text = ByteBuffer.wrap((data + "\n").getBytes(StandardCharsets.UTF_8));
                            ctx.write(text, new WriteCallback() {
                                @Override
                                public void onFailure(Throwable reason) throws Exception {
                                    changeListener.stop();
                                    ctx.complete();
                                    ctxClosedLatch.countDown();
                                }
                                @Override
                                public void onSuccess() {
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
            Assert.fail("Should have timeout out");
        } catch (SocketTimeoutException to) {
            timedOutLatch.countDown();
            assertThat("Timed out waiting for failure callback to happen",
                ctxClosedLatch.await(30, TimeUnit.SECONDS), is(true));
            assertThat(writeErrors, is(empty()));
        }

        assertThat(connectionsDuringListening.get(), is(1L));
        assertThat(server.stats().activeConnections(), is(0L));
        assertThat(server.stats().completedRequests(), is(1L));
    }

    @Test
    public void blockingWritesCanStillBeUsed() throws IOException {

        DatabaseListenerSimulator changeListener = new DatabaseListenerSimulator(10);

        server = httpsServer()
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
            assertThat(changeListener.errors, is(empty()));
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("Loop 0\nLoop 1\nLoop 2\nLoop 3\nLoop 4\nLoop 5\nLoop 6\nLoop 7\nLoop 8\nLoop 9\n"));
        }
    }

    @Test
    public void requestBodiesCanBeReadAsynchronously() throws IOException {
        List<Throwable> errors = new ArrayList<>();
        server = httpsServer()
            .addHandler((request, response) -> {
                AsyncHandle ctx = request.handleAsync();
                ctx.setReadListener(new RequestBodyListener() {
                    @Override
                    public void onDataReceived(ByteBuffer bb) {
                        byte[] b = new byte[bb.remaining()];
                        bb.get(b);
                        try {
                            response.outputStream().write(b);
                        } catch (IOException e) {
                            errors.add(e);
                        }
                    }

                    @Override
                    public void onComplete() {
                        ctx.complete();
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
            })
            ;

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
            thread.setName("DatabaseListenerSimulator");
            thread.start();
        }

        public void addListener(ChangeListener listener) {
            this.listeners.add(listener);
        }

        public void stop() throws InterruptedException {
            stopped.set(true);
        }
    }


    @After
    public void destroy() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}
