package io.muserver;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufAllocatorMetric;
import io.netty.buffer.ByteBufAllocatorMetricProvider;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;
import scaffolding.SlowBodySender;

import javax.ws.rs.ClientErrorException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
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

    @Test(timeout = 10000)
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
        assertThat("All: " + readListener.events.toString(), readListener.events, hasItems("onComplete"));
    }

    @Test
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

        assertEventually(() -> readListener.events, allOf(
            hasItems("data received: 7 bytes", "data written", "Error for onError: TimeoutException")
        ));
    }

    private static long getDirectMemory() {
        ByteBufAllocator allocator = ByteBufAllocator.DEFAULT;
        if (allocator instanceof ByteBufAllocatorMetricProvider) {
            ByteBufAllocatorMetric metric = ((ByteBufAllocatorMetricProvider) allocator).metric();
            return metric.usedDirectMemory();
        } else {
            return -1;
        }
    }

    @Test
    public void exceedingMaxContentLengthWillResultIn413() {
        int contentLength = 1024;

        server = ServerUtils.httpsServerForTest()
            .withMaxRequestSize(1000)
            .addHandler((request, response) -> {
                response.write("hello");
                return true;
            })
            .start();

        final String bigString = randomAsciiStringOfLength(contentLength);

        Request.Builder request = request()
            .url(server.uri().toString())
            .post(RequestBody.create(bigString, MediaType.get("text/plain")));

        try (Response resp = call(request)) {
            assertThat(resp.code(), equalTo(413));
            assertThat(resp.body().string(), containsString("413 Payload Too Large"));
        } catch (Exception e) {
            // The HttpServerKeepAliveHandler will probably close the connection before the full request body is read, which is probably a good thing in this case.
            // So allow a valid 413 response or an error
            MuAssert.assertIOException(e);
        }

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
            .post(new SlowBodySender(1000, 10));

        try (Response resp = call(request)) {
            String read = resp.body().string();
            Assert.fail("Should not be able to read body but got " + read + " and " + resp.isSuccessful());
        } catch (Exception ex) {
            MuAssert.assertIOException(ex);
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
