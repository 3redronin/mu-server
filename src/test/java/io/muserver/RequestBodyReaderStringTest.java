package io.muserver;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.internal.http2.StreamResetException;
import okio.BufferedSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import scaffolding.*;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.assertEventually;

public class RequestBodyReaderStringTest {
    private MuServer server;

    @Test
    public void requestBodiesCanBeReadAsStrings() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler((request, response) -> {
                String body = request.readBodyAsString();
                response.write(body);
                return true;
            })
            .start();

        int messagesToSend = 200;
        Request.Builder request = request()
            .url(server.uri().toString())
            .post(new SlowBodySender(messagesToSend, 2));

        try (Response resp = call(request)) {
            assertThat(resp.code(), equalTo(200));
            StringBuilder expected = new StringBuilder();
            for (int i = 0; i < messagesToSend; i++) {
                expected.append("Loop " + i + "\n");
            }
            assertThat(resp.body().string(), equalTo(expected.toString()));
        }
    }

    @Test
    public void requestBodiesCanBeReadAsStringsWithLargeChunks() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler((request, response) -> {
                String body = request.readBodyAsString();
                response.write(body);
                return true;
            })
            .start();

        String m1 = StringUtils.randomAsciiStringOfLength(100000);
        String m2 = StringUtils.randomAsciiStringOfLength(100000);
        Request.Builder request = request()
            .url(server.uri().toString())
            .post(new RequestBody() {
                @Override
                public MediaType contentType() {
                    return MediaType.get("text/plain");
                }

                @Override
                public void writeTo(BufferedSink bufferedSink) throws IOException {
                    bufferedSink.write(m1.getBytes(StandardCharsets.UTF_8)).flush();
                    bufferedSink.write(m2.getBytes(StandardCharsets.UTF_8)).flush();
                }
            });

        try (Response resp = call(request)) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo(m1 + m2));
        }
    }

    @Test
    public void requestBodiesCanBeReadAsStringsWithJustOneMessage() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler((request, response) -> {
                String body = request.readBodyAsString();
                response.write(body);
                return true;
            })
            .start();

        try (Response resp = call(request(server.uri())
            .post(RequestBody.create("Hi", MediaType.get("text/plain"))))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("Hi"));
        }
    }

    @Test
    public void smallRequestBodiesCanBeReadAsStrings() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler((request, response) -> {
                String body = request.readBodyAsString();
                response.write(body);
                return true;
            })
            .start();

        int messagesToSend = 200;
        Request.Builder request = request()
            .url(server.uri().toString())
            .post(new SlowBodySender(messagesToSend, 2));

        try (Response resp = call(request)) {
            assertThat(resp.code(), equalTo(200));
            StringBuilder expected = new StringBuilder();
            for (int i = 0; i < messagesToSend; i++) {
                expected.append("Loop " + i + "\n");
            }
            assertThat(resp.body().string(), equalTo(expected.toString()));
        }
    }

    @Test
    public void emptyStringsAreOkay() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler((request, response) -> {
                String requestBody = request.readBodyAsString();
                response.write(String.valueOf(requestBody.length()));
                return true;
            })
            .start();
        Request.Builder request = request()
            .url(server.uri().toString())
            .post(RequestBody.create("", MediaType.get("text/plain")));

        try (Response resp = call(request)) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("0"));
        }
    }

    @Test
    public void stringsInNonUTF8AreFine() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler((request, response) -> {
                String requestBody = request.readBodyAsString();
                response.write(requestBody);
                return true;
            })
            .start();
        Request.Builder request = request()
            .url(server.uri().toString())
            .post(RequestBody.create("ЧАСТЬ ПЕРВАЯ.".getBytes(Charset.forName("ISO-8859-5")), MediaType.get("text/plain;charset=ISO-8859-5")));

        try (Response resp = call(request)) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("ЧАСТЬ ПЕРВАЯ."));
        }
    }

    @Test
    public void largeStringsInNonUTF8AreFine() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler((request, response) -> {
                String requestBody = request.readBodyAsString();
                response.write(requestBody);
                return true;
            })
            .start();
        File warAndPeaceInRussian = FileUtils.warAndPeaceInRussian();
        Request.Builder request = request()
            .url(server.uri().toString())
            .post(RequestBody.create(warAndPeaceInRussian, MediaType.get("text/plain;charset=ISO-8859-5")));

        try (Response resp = call(request)) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo(new String(Files.readAllBytes(warAndPeaceInRussian.toPath()), "ISO-8859-5")));
        }
    }

    @Test
    public void chineseWorks() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .withHttpsPort(8443)
            .addHandler((request, response) -> {
                response.contentType(ContentTypes.TEXT_PLAIN_UTF8);
                String requestBody = request.readBodyAsString();
                response.write(requestBody);
                return true;
            })
            .start();
        for (int i = 0; i < 200; i++) {
            Request.Builder request = request()
                .url(server.uri().toString())
                .post(RequestBody.create("怎么样", MediaType.get("text/plain;charset=UTF-8")));
            try (Response resp = call(request)) {
                assertThat(resp.code(), equalTo(200));
                assertThat(resp.body().string(), equalTo("怎么样"));
                assertThat(resp.body().contentLength(), is(9L));
            }
        }
        assertThat(server.stats().completedConnections(), lessThan(2L));
    }

    @Test
    public void largeUTF8CharactersAreFine() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .withGzipEnabled(false)
            .addHandler((request, response) -> {
                String requestBody = request.readBodyAsString();
                response.write(requestBody);
                return true;
            })
            .start();
        String actual = StringUtils.randomStringOfLength(100000);
        Request.Builder request = request()
            .url(server.uri().toString())
            .post(RequestBody.create(actual, MediaType.get("text/plain;charset=UTF-8")));

        try (Response resp = call(request)) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo(actual));
            assertThat(Integer.parseInt(resp.header("content-length")), greaterThanOrEqualTo(actual.length()));
        }
    }

    @Test
    public void largeUTF8CharactersAreFineGzipped() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler((request, response) -> {
                String requestBody = request.readBodyAsString();
                response.write(requestBody);
                return true;
            })
            .start();
        String actual = StringUtils.randomStringOfLength(100000);
        Request.Builder request = request()
            .url(server.uri().toString())
            .post(RequestBody.create(actual, MediaType.get("text/plain;charset=UTF-8")));

        try (Response resp = call(request)) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo(actual));
            assertThat(resp.header("content-length"), nullValue());
        }
    }

    @Test
    public void aSlowReadResultsInACompleted408OrKilledConnectionIfResponseNotStarted() {
        AtomicReference<Throwable> exception = new AtomicReference<>();
        AtomicReference<ResponseInfo> responseInfo = new AtomicReference<>();
        server = ServerUtils.httpsServerForTest()
            .withRequestTimeout(100, TimeUnit.MILLISECONDS)
            .addHandler((request, response) -> {
                try {
                    request.readBodyAsString();
                } catch (Throwable e) {
                    exception.set(e);
                    throw e;
                }
                return true;
            })
            .addResponseCompleteListener(responseInfo::set)
            .start();

        Request.Builder request = request()
            .url(server.uri().toString())
            .post(new SlowBodySender(2, 200));

        try (Response resp = call(request)) {
            assertThat(resp.code(), equalTo(408));
            assertThat(resp.body().string(), containsString("408 Request Timeout"));
        } catch (Exception e) {
            // The HttpServerKeepAliveHandler will probably close the connection before the full request body is read, which is probably a good thing in this case.
            // So allow a valid 408 response or an error
            assertThat(e.getCause(), instanceOf(IOException.class));
        }
        assertThat(exception.get(), instanceOf(HttpException.class));
        assertThat(((HttpException) exception.get()).status(), equalTo(HttpStatus.REQUEST_TIMEOUT_408));
        assertThat(responseInfo.get(), notNullValue());
        assertThat(responseInfo.get().completedSuccessfully(), equalTo(false));
    }

    @Test
    public void aSlowReadResultsInAKilledConnectionIfResponseStarted() {
        AtomicReference<Throwable> exception = new AtomicReference<>();
        server = ServerUtils.httpsServerForTest()
            .withRequestTimeout(50, TimeUnit.MILLISECONDS)
            .addHandler((request, response) -> {
                response.sendChunk("starting");
                try {
                    request.readBodyAsString();
                } catch (Throwable e) {
                    exception.set(e);
                    throw e;
                }
                return true;
            })
            .start();

        Request.Builder request = request()
            .url(server.uri().toString())
            .post(new SlowBodySender(5, 80));

        try (Response resp = call(request)) {
            resp.body().string();
            Assertions.fail("Should not complete successfully");
        } catch (Exception e) {
            MuAssert.assertIOException(e);
        }
        assertThat(exception.get(), instanceOf(HttpException.class));
        assertThat(((HttpException) exception.get()).status(), equalTo(HttpStatus.REQUEST_TIMEOUT_408));
    }

    @Test
    public void exceedingUploadSizeResultsIn413OrKilledConnectionForChunkedRequestWhereResponseNotStarted() throws Exception {
        AtomicReference<Throwable> exception = new AtomicReference<>();
        List<ResponseInfo> infos = new ArrayList<>();
        server = ServerUtils.httpsServerForTest()
            .withMaxRequestSize(1000)
            .addHandler((request, response) -> {
                try {
                    request.readBodyAsString();
                } catch (Throwable e) {
                    exception.set(e);
                    throw e;
                }
                return true;
            })
            .addResponseCompleteListener(infos::add)
            .start();

        Request.Builder request = request()
            .url(server.uri().toString())
            .post(new RequestBody() {
                public MediaType contentType() {
                    return MediaType.get("text/plain;charset=UTF-8");
                }
                public void writeTo(BufferedSink bufferedSink) throws IOException {
                    bufferedSink.write("!".repeat(999).getBytes(StandardCharsets.UTF_8));
                    bufferedSink.flush();
                    bufferedSink.write("##".getBytes(StandardCharsets.UTF_8));
                    bufferedSink.flush();
                }
            });

        try (Response resp = call(request)) {
            assertThat(resp.code(), equalTo(413));
            assertThat(resp.body().string(), containsString("413 Content Too Large"));
        }
        assertEventually(exception::get, instanceOf(HttpException.class));
        assertThat(((HttpException) exception.get()).status(), equalTo(HttpStatus.CONTENT_TOO_LARGE_413));

        assertEventually(() -> infos, not(empty()));
        assertThat(infos.size(), equalTo(1));
        Http1Response ri = (Http1Response) infos.get(0);
        assertThat(ri.completedSuccessfully(), equalTo(true));
        assertThat(ri.response().responseState(), equalTo(ResponseState.FULL_SENT));
    }

    @Test
    public void exceedingUploadSizeResultsInKilledConnectionForChunkedRequestWhereResponseStarted() throws Exception {
        AtomicReference<Throwable> exception = new AtomicReference<>();
        AtomicBoolean isHttp2 = new AtomicBoolean();
        List<ResponseInfo> infos = new ArrayList<>();
        server = ServerUtils.httpsServerForTest()
            .withMaxRequestSize(1000)
            .addHandler((request, response) -> {
                isHttp2.set(request.connection().protocol().equals("HTTP/2"));
                response.sendChunk("starting");
                try {
                    request.readBodyAsString();
                } catch (Throwable e) {
                    exception.set(e);
                    throw e;
                }
                return true;
            })
            .addResponseCompleteListener(infos::add)
            .start();

        Request.Builder request = request()
            .url(server.uri().toString())
            .post(new SlowBodySender(1000, 0));

        try (Response resp = call(request)) {
            resp.body().string();
            Assertions.fail("Should not succeed but got " + resp);
        } catch (Exception e) {
            if (isHttp2.get()) {
                assertThat(Mutils.coalesce(e.getCause(), e), instanceOf(StreamResetException.class));
            } else {
                if (e instanceof UncheckedIOException) {
                    assertThat(e.getCause(), instanceOf(IOException.class));
                } else {
                    assertThat(e, instanceOf(IOException.class));
                }
            }
        }
        assertThat(exception.get(), instanceOf(HttpException.class));
        assertThat(((HttpException) exception.get()).status(), equalTo(HttpStatus.CONTENT_TOO_LARGE_413));

        assertEventually(() -> infos, not(empty()));
        assertThat(infos.size(), equalTo(1));
        Http1Response ri = (Http1Response) infos.get(0);
        assertThat(ri.completedSuccessfully(), equalTo(false));
        assertThat(ri.response().responseState(), equalTo(ResponseState.ERRORED));
    }

    @AfterEach
    public void destroy() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}