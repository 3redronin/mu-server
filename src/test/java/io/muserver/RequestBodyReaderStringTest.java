package io.muserver;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import org.junit.After;
import org.junit.Assert;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import scaffolding.FileUtils;
import scaffolding.MuAssert;
import scaffolding.SlowBodySender;
import scaffolding.StringUtils;

import javax.ws.rs.ClientErrorException;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.InterruptedByTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.muserver.MuServerBuilder.muServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.assertEventually;

public class RequestBodyReaderStringTest {
    private MuServer server;

    @ParameterizedTest
    @ValueSource(strings = { "http", "https"})
    public void requestBodiesCanBeReadAsStrings(String type) throws IOException {
        server = serverBuilder(type)
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

    @ParameterizedTest
    @ValueSource(strings = { "http", "https"})
    public void requestBodiesCanBeReadAsStringsWithLargeChunks(String type) throws IOException {
        server = serverBuilder(type)
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

    @ParameterizedTest
    @ValueSource(strings = { "http", "https"})
    public void requestBodiesCanBeReadAsStringsWithJustOneMessage(String type) throws IOException {
        server = serverBuilder(type)
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

    @ParameterizedTest
    @ValueSource(strings = { "http", "https"})
    public void smallRequestBodiesCanBeReadAsStrings(String type) throws IOException {
        server = serverBuilder(type)
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

    @ParameterizedTest
    @ValueSource(strings = { "http", "https"})
    public void emptyStringsAreOkay(String type) throws IOException {
        server = serverBuilder(type)
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

    @ParameterizedTest
    @ValueSource(strings = { "http", "https"})
    public void stringsInNonUTF8AreFine(String type) throws IOException {
        server = serverBuilder(type)
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

    @ParameterizedTest
    @ValueSource(strings = { "http", "https"})
    public void largeStringsInNonUTF8AreFine(String type) throws IOException {
        server = serverBuilder(type)
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

    @ParameterizedTest
    @ValueSource(strings = { "http", "https"})
    public void chineseWorks(String type) throws Exception {
        server = serverBuilder(type)
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

    @ParameterizedTest
    @ValueSource(strings = { "http", "https"})
    public void largeUTF8CharactersAreFine(String type) throws IOException {
        server = serverBuilder(type)
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

    @ParameterizedTest
    @ValueSource(strings = { "http", "https"})
    public void bodiesCanBeIgnored(String type) throws IOException {
        server = serverBuilder(type)
            .addHandler((request, response) -> {
                response.write("Hello");
                return true;
            })
            .start();
        Request.Builder request = request()
            .url(server.uri().toString())
            .post(RequestBody.create(StringUtils.randomStringOfLength(100000), MediaType.get("text/plain;charset=UTF-8")));

        try (Response resp = call(request)) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("Hello"));
        }
        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("Hello"));
        }

    }


    @ParameterizedTest
    @ValueSource(strings = { "http", "https"})
    public void largeUTF8CharactersAreFineGzipped(String type) throws IOException {
        server = serverBuilder(type)
            .withGzipEnabled(true)
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

    @ParameterizedTest
    @ValueSource(strings = { "http", "https"})
    public void aSlowReadResultsInAKilledConnectionIfResponseNotStarted(String type) {
        AtomicReference<Throwable> exception = new AtomicReference<>();
        server = serverBuilder(type)
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
            .start();

        Request.Builder request = request()
            .url(server.uri().toString())
            .post(new SlowBodySender(2, 200));

        assertThrows(UncheckedIOException.class, () -> {
            try (Response resp = call(request)) {
                resp.body().string();
            }
        });
        assertThat(exception.get(), instanceOf(InterruptedByTimeoutException.class));
    }

    @ParameterizedTest
    @ValueSource(strings = { "http", "https"})
    public void aSlowReadResultsInAKilledConnectionIfResponseStarted(String type) {
        AtomicReference<Throwable> exception = new AtomicReference<>();
        server = serverBuilder(type)
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
            Assert.fail("Should not complete successfully");
        } catch (Exception e) {
            MuAssert.assertIOException(e);
        }
        assertThat(exception.get(), instanceOf(InterruptedByTimeoutException.class));
    }

    @ParameterizedTest
    @ValueSource(strings = { "https", "http" })
    public void exceedingUploadSizeResultsIn413ForChunkedRequestWhereResponseNotStarted(String type) throws Exception {
        AtomicReference<Throwable> exception = new AtomicReference<>();
        List<ResponseInfo> infos = new ArrayList<>();
        server = serverBuilder(type)
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
            .post(new SlowBodySender(1000, 1));

        try (Response resp = call(request)) {
            assertThat(resp.code(), equalTo(413));
            assertThat(resp.body().string(), containsString("413 Request Entity Too Large"));
        }
        assertEventually(exception::get, instanceOf(IOException.class));
        Throwable cause = exception.get().getCause();
        assertThat(cause, instanceOf(ClientErrorException.class));
        assertThat(((ClientErrorException) cause).getResponse().getStatus(), equalTo(413));

        assertEventually(() -> infos, not(empty()));
        assertThat(infos.size(), equalTo(1));
        var ri = (MuExchange) infos.get(0);
        assertThat(ri.completedSuccessfully(), equalTo(true));
        assertThat(ri.state, equalTo(HttpExchangeState.COMPLETE));
        assertThat(ri.request.requestState(), equalTo(RequestState.COMPLETE));
        assertThat(ri.response.responseState(), equalTo(ResponseState.FULL_SENT));
    }

    @ParameterizedTest
    @ValueSource(strings = { "https", "http" })
    public void exceedingUploadSizeResultsInClosedConnectionForChunkedRequestWhereResponseAlreadyStarted(String type) {
        testConnectionKilledDueToLargeBody(type, (request, response) -> {
            response.sendChunk("Hi");
            request.readBodyAsString();
            return true;
        }, ResponseState.ERRORED, RequestBodyErrorAction.SEND_RESPONSE);
    }

    @ParameterizedTest
    @ValueSource(strings = { "https", "http" })
    public void exceedingUploadSizeResultsInClosedConnectionForChunkedRequestWhereResponseAlreadyCompleted(String type) {
        testConnectionKilledDueToLargeBody(type, (request, response) -> {
            response.write("Hi");
            request.readBodyAsString();
            return true;
        }, ResponseState.FULL_SENT, RequestBodyErrorAction.SEND_RESPONSE);
    }

    @ParameterizedTest
    @ValueSource(strings = { "https", "http" })
    public void exceedingUploadSizeResultsInClosedConnectionForChunkedRequestWhenTooLargeActionIsKill(String type) {
        testConnectionKilledDueToLargeBody(type, (request, response) -> {
            request.readBodyAsString();
            return true;
        }, ResponseState.FULL_SENT /* hmm */, RequestBodyErrorAction.KILL_CONNECTION);
    }


    private void testConnectionKilledDueToLargeBody(String type, MuHandler handler, ResponseState expectedResponseState, RequestBodyErrorAction requestBodyTooLargeAction) {
        AtomicReference<Throwable> exceptionThrownOnRequestRead = new AtomicReference<>();
        List<ResponseInfo> infos = new ArrayList<>();
        server = serverBuilder(type)
            .withRequestBodyTooLargeAction(requestBodyTooLargeAction)
            .withMaxRequestSize(1000)
            .addHandler((request, response) -> {
                try {
                    return handler.handle(request, response);
                } catch (Throwable e) {
                    exceptionThrownOnRequestRead.set(e);
                    throw e;
                }
            })
            .addResponseCompleteListener(infos::add)
            .start();

        Request.Builder request = request()
            .url(server.uri().toString())
            .post(new SlowBodySender(1000, 1));

        var exceptionFromClient = assertThrows(Exception.class, () -> {
            try (Response resp = call(request)) {
                resp.body().string();
            }
        });
        assertThat(exceptionFromClient, anyOf(instanceOf(UncheckedIOException.class), instanceOf(IOException.class)));

        assertEventually(exceptionThrownOnRequestRead::get, instanceOf(IOException.class));
        Throwable cause = exceptionThrownOnRequestRead.get().getCause();
        assertThat(cause, instanceOf(ClientErrorException.class));
        assertThat(((ClientErrorException) cause).getResponse().getStatus(), equalTo(413));

        assertEventually(() -> infos, not(empty()));
        assertThat(infos.size(), equalTo(1));
        var ri = (MuExchange) infos.get(0);
        assertThat(ri.completedSuccessfully(), equalTo(false));
        assertThat(ri.state, equalTo(HttpExchangeState.ERRORED));
        assertThat(ri.request.requestState(), equalTo(RequestState.ERRORED));
        assertThat(ri.response.responseState(), equalTo(expectedResponseState));
    }

    @After
    public void destroy() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

    private MuServerBuilder serverBuilder(String type) {
        return muServer()
            .withHttpPort(type.equals("http") ? 0 : -1)
            .withHttpsPort(type.equals("https") ? 0 : -1);
    }


}