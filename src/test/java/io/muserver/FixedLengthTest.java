package io.muserver;

import okhttp3.Response;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.ProtocolException;
import java.util.concurrent.CountDownLatch;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static scaffolding.ClientUtils.*;

public class FixedLengthTest {

    private MuServer server;
    private StringBuilder errors = new StringBuilder();
    private CountDownLatch errorSetLatch = new CountDownLatch(1);

    @Test
    public void ifMoreThanDeclaredAreSentThenAnExceptionIsThrownAndConnectionIsClosedForHttp1() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler((req, resp) -> {
                resp.contentType("text/plain");
                resp.headers().set(HeaderNames.CONTENT_LENGTH, 20);
                PrintWriter writer = resp.writer();
                writer.print("01234");
                writer.flush();

                try {
                    writer.print(" and this will push the response over 20 bytes in size");
                    writer.flush();

                    writer.print("For http, subsequent calls will fail");
                    writer.flush();
                } catch (Exception ex) {
                    errors.append(ex.getMessage());
                    errorSetLatch.countDown();
                }

                return true;
            }).start();

        boolean http2 = false;
        try (Response resp = call(request(server.uri().resolve("/blah")))) {
            http2 = isHttp2(resp);
            String body = resp.body().string();
            if (http2) {
                assertThat(body, is("01234 and this will push the response over 20 bytes in size"));
            } else {
                Assert.fail("Should have failed due to invalid HTTP response");
            }
        } catch (ProtocolException pe) {
            // yay
        }

        MuAssert.assertNotTimedOut("exception", errorSetLatch);

        if (http2) {
            assertThat(errors.toString(), equalTo("Cannot write data as response has already completed"));
        } else {
            assertThat(errors.toString(), equalTo("The declared content length for GET " + server.uri().resolve("/blah") + " was 20 bytes. " +
                "The current write is being aborted and the connection is being closed because it would have resulted in " +
                "59 bytes being sent."));
        }
    }

    @Test
    public void ifLessThanDeclaredAreSentThenAnExceptionIsThrownAndConnectionIsClosed() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler((req, resp) -> {
                resp.contentType("text/plain");
                resp.headers().set(HeaderNames.CONTENT_LENGTH, 20);
                PrintWriter writer = resp.writer();
                writer.print("01234");
                return true;
            }).start();

        try (Response resp = call(request(server.uri().resolve("/blah")))) {
            String body = resp.body().string();
            if (isHttp2(resp)) {
                assertThat(body, is("01234"));
            } else {
                Assert.fail("Should have failed due to invalid HTTP response");
            }
        } catch (ProtocolException pe) {
            // yay
        }
    }

    @After
    public void destroy() {
        scaffolding.MuAssert.stopAndCheck(server);
    }
}
