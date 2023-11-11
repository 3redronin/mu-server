package io.muserver;

import okhttp3.Response;
import okhttp3.internal.http2.StreamResetException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class FixedLengthTest {

    private MuServer server;
    private final StringBuilder errors = new StringBuilder();
    private final CountDownLatch errorSetLatch = new CountDownLatch(1);

    @ParameterizedTest
    @ValueSource(strings = { "http_throw", "http_swallow", "https_throw", "https_swallow" })
    public void ifMoreThanDeclaredAreSentThenAnExceptionIsThrownAndConnectionIsClosedForHttp1(String type) throws IOException {
        server = ServerUtils.testServer(type)
            .addHandler((req, resp) -> {
                resp.contentType("text/plain");
                resp.headers().set(HeaderNames.CONTENT_LENGTH, 20);
                var output = resp.outputStream();
                output.write("01234".getBytes(StandardCharsets.UTF_8));
                output.flush();

                try {
                    output.write(" and this will push the response over 20 bytes in size".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    output.write("For http, subsequent calls will fail".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                } catch (Exception ex) {
                    errors.append(ex.getMessage());
                    errorSetLatch.countDown();
                    if (type.endsWith("throw")) {
                        throw ex;
                    }
                }

                return true;
            }).start();

        try (Response resp = call(request(server.uri().resolve("/blah")))) {
            var actual = resp.body().string();
            Assertions.fail("Should have failed due to invalid HTTP response but got: " + actual);
        } catch (Exception e) {
            assertThat(e, anyOf(instanceOf(StreamResetException.class), instanceOf(SocketException.class)));
        }

        MuAssert.assertNotTimedOut("exception", errorSetLatch);
        assertThat(errors.toString(), equalTo("The declared content length for GET " + server.uri().resolve("/blah") + " was 20 bytes. " +
            "The current write is being aborted and the connection is being closed because it would have resulted in " +
            "59 bytes being sent."));
    }

    @ParameterizedTest
    @ValueSource(strings = { "http", "https" })
    public void whenUsingAPrintWriterWhichSwallowsExceptionsAndTooMuchDataIsWrittenThenTheConnectionIsClosed(String type) throws IOException {
        server = ServerUtils.testServer(type)
            .addHandler((req, resp) -> {
                resp.contentType("text/plain");
                resp.headers().set(HeaderNames.CONTENT_LENGTH, 20);
                try (PrintWriter writer = resp.writer()) {
                    writer.println("01234");
                    writer.flush();
                    writer.println(" and this will push the response over 20 bytes in size");
                    writer.flush();
                    writer.println("For http, subsequent calls will fail");
                    writer.flush();
                } catch (Exception ex) {
                    errors.append(ex.getMessage());
                } finally {
                    errorSetLatch.countDown();
                }

                return true;
            }).start();

        try (Response resp = call(request(server.uri().resolve("/blah")))) {
            var actual = resp.body().string();
            Assertions.fail("Should have failed due to invalid HTTP response but got: " + actual);
        } catch (Exception e) {
            assertThat(e, anyOf(instanceOf(StreamResetException.class), instanceOf(SocketException.class)));
        }

        MuAssert.assertNotTimedOut("exception", errorSetLatch);
        assertThat(errors.toString(), equalTo(""));
    }


    @ParameterizedTest
    @ValueSource(strings = { "http", "https" })
    public void ifLessThanDeclaredAreSentThenAnExceptionIsThrownAndConnectionIsClosed(String type) throws Exception {
        server = ServerUtils.testServer(type)
            .addHandler((req, resp) -> {
                resp.contentType("text/plain");
                resp.headers().set(HeaderNames.CONTENT_LENGTH, 20);
                PrintWriter writer = resp.writer();
                writer.print("01234");
                return true;
            }).start();

        try (Response resp = call(request(server.uri().resolve("/blah")))) {
            resp.body().string();
            Assertions.fail("Should have failed due to invalid HTTP response");
        } catch (Exception e) {
            assertThat(e, anyOf(instanceOf(StreamResetException.class), instanceOf(SocketException.class)));
        }
    }

    @AfterEach
    public void destroy() {
        scaffolding.MuAssert.stopAndCheck(server);
    }
}
