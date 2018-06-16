package io.muserver;

import okhttp3.Response;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static io.muserver.MuServerBuilder.httpsServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class AsyncTest {
    private MuServer server;


    @Test
    public void responsesCanBeAsync() throws IOException {

        server = httpsServer()
            .addHandler((request, response) -> {
                response.headers().add("X-Pre-Header", "Hello");
                return false;
            })
            .addHandler((request, response) -> {
                AsyncHandle ctx = request.handleAsync();


                DatabaseListenerSimulator changeListener = new DatabaseListenerSimulator();
                changeListener.addListener(new ChangeListener() {
                    @Override
                    public void onData(String data) {
                        response.writer().write(data + "\n");
                    }

                    @Override
                    public void onClose() {
                        ctx.complete();
                    }
                });

                return true;
            })
            .addHandler((request, response) -> {
                response.headers().add("X-Post-Header", "Goodbye");
                return false;
            })
            .start();

        try (Response resp = call(request().url(server.uri().toString()))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("X-Pre-Header"), equalTo("Hello"));
            assertThat(resp.header("X-Post-Header"), is(nullValue()));
            assertThat(resp.body().string(), equalTo("Loop 0\nLoop 1\nLoop 2\nLoop 3\nLoop 4\nLoop 5\nLoop 6\nLoop 7\nLoop 8\nLoop 9\n"));
        }

    }


    interface ChangeListener {
        void onData(String data);
        void onClose();
    }

    static class DatabaseListenerSimulator {
        private List<ChangeListener> listeners = new ArrayList<>();

        private final Random rng = new Random();
        public final List<Throwable> errors = new ArrayList<>();

        public DatabaseListenerSimulator() {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int i = 0; i < 10; i++) {
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
            }).start();
        }

        public void addListener(ChangeListener listener) {
            this.listeners.add(listener);
        }
    }


    @After
    public void destroy() {
        if (server != null) server.stop();
    }

}
