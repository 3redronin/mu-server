package io.muserver;

import okhttp3.Request;
import okhttp3.Response;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import scaffolding.ServerUtils;
import scaffolding.SlowBodySender;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

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
    public void emptyBodiesAreOkay() {
        Assert.fail();
    }

    @Test
    public void ifTheRequestBodyIsTooSlowAnErrorIsReturned() throws IOException {
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
            .post(new SlowBodySender(2, 150));

        try (Response resp = call(request)) {
            assertThat(resp.code(), equalTo(200));
        }
        assertThat("All: " + readListener.events.toString(), readListener.events, hasItems("data received: 7 bytes", "onError"));
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


    @After
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
                events.add("Error for " + message +": " + err.getMessage());
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