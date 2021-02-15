package io.muserver;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.InputStreamContentProvider;
import org.junit.After;
import org.junit.Test;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;
import scaffolding.SlowBodySender;
import scaffolding.StringUtils;

import javax.ws.rs.ClientErrorException;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.*;
import static scaffolding.MuAssert.assertEventually;
import static scaffolding.MuAssert.assertNotTimedOut;

public class RequestBodyReaderInputStreamAdapterTest {
    private MuServer server;

    @Test
    public void hugeBodiesCanBeStreamed() throws IOException {
        int chunkSize = 10000;
        int loops = 6400;

        server = ServerUtils.httpsServerForTest()
            .withMaxRequestSize(loops * (long) chunkSize)
            .addHandler((request, response) -> {
                response.contentType("application/octet-stream");
                try (InputStream is = request.inputStream().orElseThrow(() -> new MuException("No input stream"))) {
                    byte[] buffer = new byte[chunkSize];
                    int read;
                    long received = 0;
                    while ((read = is.read(buffer)) > -1) {
                        received += read;
                    }
                    response.write("Got " + received + " bytes");
                }
                return true;
            })
            .start();

        Request.Builder request = request()
            .url(server.uri().toString())
            .post(new RequestBody() {
                @Override
                public MediaType contentType() {
                    return MediaType.get("application/octet-stream");
                }

                @Override
                public void writeTo(BufferedSink bufferedSink) throws IOException {
                    byte[] ones = new byte[chunkSize];
                    Arrays.fill(ones, (byte) 1);
                    for (int i = 0; i < loops; i++) {
                        ByteBuffer src = ByteBuffer.wrap(ones);
                        bufferedSink.write(src);
                    }
                }
            });

        try (Response resp = call(request)) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("Got " + ((long) chunkSize * (long) loops) + " bytes"));
        }
    }

    @Test(timeout = 30000)
    public void hugeBodiesCanBeStreamedWithJetty() throws Exception {

        long loops = 1000;
        int chunkSize = 64000;

        server = ServerUtils.httpsServerForTest()
            .withMaxRequestSize(loops * (long) chunkSize)
            .addHandler((request, response) -> {
                response.contentType("application/octet-stream");
                try (InputStream is = request.inputStream().orElseThrow(() -> new MuException("No input stream"));
                     OutputStream out = response.outputStream(chunkSize)) {
                    Mutils.copy(is, out, chunkSize);
                }
                return true;
            })
            .start();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Result> clientResult = new AtomicReference<>();
        AtomicLong bytesReceived = new AtomicLong();

        try (PipedOutputStream uploadOutStream = new PipedOutputStream();
             InputStreamContentProvider inputProvider = new InputStreamContentProvider(new PipedInputStream(uploadOutStream, chunkSize), chunkSize)) {

            jettyClient().newRequest(server.uri())
                .method("POST")
                .header("content-type", "application/octet-stream")
                .content(inputProvider)
                .send(new org.eclipse.jetty.client.api.Response.Listener() {
                    @Override
                    public void onBegin(org.eclipse.jetty.client.api.Response response) {
                    }

                    @Override
                    public void onContent(org.eclipse.jetty.client.api.Response response, ByteBuffer content) {
                        bytesReceived.addAndGet(content.remaining());
                    }

                    @Override
                    public void onComplete(Result result) {
                        clientResult.set(result);
                        latch.countDown();
                    }
                });


            byte[] ones = new byte[chunkSize];
            Arrays.fill(ones, (byte) 1);
            for (int i = 0; i < loops; i++) {
                uploadOutStream.write(ones);
                uploadOutStream.flush();
            }
            uploadOutStream.close();
            assertNotTimedOut("Response complete", latch);
            assertThat(bytesReceived.get(), equalTo(loops * (long) chunkSize));
            assertThat(clientResult.get().getFailure(), nullValue());
            assertThat(clientResult.get().getResponse().getStatus(), is(200));
        }
    }

    @Test
    public void requestBodiesCanBeReadAsInputStreams() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler((request, response) -> {
                Optional<InputStream> inputStream = request.inputStream();
                try (OutputStream os = response.outputStream();
                     InputStream is = inputStream.orElseThrow(() -> new MuException("No input stream"))) {
                    Mutils.copy(is, os, 8192);
                }
                return true;
            })
            .start();

        Request.Builder request = request()
            .url(server.uri().toString())
            .post(new SlowBodySender(10, 10));

        try (Response resp = call(request)) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("Loop 0\nLoop 1\nLoop 2\nLoop 3\nLoop 4\nLoop 5\nLoop 6\nLoop 7\nLoop 8\nLoop 9\n"));
        }
    }


    @Test
    public void canReadByteByByte() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler((request, response) -> {
                try (InputStream is = request.inputStream().orElseThrow(() -> new MuException("No input stream"))) {
                    int val;
                    while ((val = is.read()) > -1) {
                        response.sendChunk("" + ((char) val));
                    }
                }
                return true;
            })
            .start();

        Request.Builder request = request()
            .url(server.uri().toString())
            .post(new SlowBodySender(10, 10));

        try (Response resp = call(request)) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("Loop 0\nLoop 1\nLoop 2\nLoop 3\nLoop 4\nLoop 5\nLoop 6\nLoop 7\nLoop 8\nLoop 9\n"));
        }
    }

    @Test
    public void closingTheStreamEarlyCancelsRequest() throws Exception {
        byte[] chunkPayload = StringUtils.randomBytes(2);
        server = ServerUtils.httpsServerForTest()
            .addHandler((request, response) -> {
                try (InputStream is = request.inputStream().orElseThrow(() -> new MuException("No input stream"))) {
                    is.read();
                }
                return true;
            })
            .start();

        Request.Builder request = request()
            .url(server.uri().toString())
            .post(new SlowBodySender(10));

        try (Response resp = call(request.post(new RequestBody() {
            public MediaType contentType() {
                return MediaType.parse("text/plain");
            }

            public void writeTo(BufferedSink bufferedSink) throws IOException {
                bufferedSink.write(chunkPayload);
                bufferedSink.flush();
                bufferedSink.write(chunkPayload);
                bufferedSink.flush();
            }
        }))) {
            resp.body().bytes();
            assertThat(resp.code(), equalTo(500)); // server error or closed connection is fine
        } catch (Exception ex) {
            MuAssert.assertIOException(ex);
        }
    }

    @Test
    public void skipAndAvailableWork() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler((request, response) -> {
                try (InputStream is = request.inputStream().orElseThrow(() -> new MuException("No input stream"))) {
                    response.sendChunk("read " + ((char) is.read()) + " / available " + is.available() + " / skip " + is.skip(6) + " / available " + is.available() + " / read " + ((char) is.read()) + " / available " + is.available() + "\n");
                    while (is.read() > -1) {
                    }
                }
                return true;
            })
            .start();

        Request.Builder request = request()
            .url(server.uri().toString())
            .post(new SlowBodySender(10));

        String msg1 = "Hello from message one";
        try (Response resp = call(request.post(new RequestBody() {
            public MediaType contentType() {
                return MediaType.parse("text/plain");
            }

            public void writeTo(BufferedSink bufferedSink) throws IOException {
                bufferedSink.write(msg1.getBytes(StandardCharsets.US_ASCII));
                bufferedSink.flush();
            }
        }))) {
            assertThat(resp.body().string(), equalTo("read " + msg1.charAt(0) + " / available "
                + (msg1.length() - 1) + " / skip 6 / available " + (msg1.length() - 7) + " / read " + msg1.charAt(7) + " / available " + (msg1.length() - 8) + "\n"));
        }
    }

    @Test
    public void readingAfterFinishedThrows() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler((request, response) -> {
                try (InputStream is = request.inputStream().orElseThrow(() -> new MuException("No input stream"))) {
                    while (is.read() > -1) {
                    }
                    response.write("After it was already closed it returned " + is.read());
                }
                return true;
            })
            .start();

        Request.Builder request = request()
            .url(server.uri().toString())
            .post(new SlowBodySender(10, 2));

        try (Response resp = call(request)) {
            assertThat(resp.body().string(), equalTo("After it was already closed it returned -1"));
        }
    }

    @Test
    public void exceedingUploadSizeResultsIn413OrKilledConnectionForChunkedRequestWhereResponseNotStarted() throws Exception {
        AtomicReference<Throwable> exception = new AtomicReference<>();
        server = ServerUtils.httpsServerForTest()
            .withMaxRequestSize(1000)
            .addHandler((request, response) -> {
                if (request.inputStream().isPresent()) {
                    try (InputStream is = request.inputStream().get()) {
                        while (is.read() > -1) {
                        }
                    } catch (Throwable e) {
                        exception.set(e);
                        throw e;
                    }
                }
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

    @After
    public void destroy() throws Exception {
        MuAssert.stopAndCheck(server);
    }

}