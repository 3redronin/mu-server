package io.muserver;

import okhttp3.Response;
import okhttp3.internal.http2.StreamResetException;
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
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

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
                    throw ex;
                }

                return true;
            }).start();

        try (Response resp = call(request(server.uri().resolve("/blah")))) {
            resp.body().string();
            Assert.fail("Should have failed due to invalid HTTP response");
        } catch (Exception e) {
            assertThat(e, anyOf(instanceOf(StreamResetException.class), instanceOf(ProtocolException.class)));
        }

        MuAssert.assertNotTimedOut("exception", errorSetLatch);
        assertThat(errors.toString(), equalTo("The declared content length for GET " + server.uri().resolve("/blah") + " was 20 bytes. " +
            "The current write is being aborted and the connection is being closed because it would have resulted in " +
            "59 bytes being sent."));
    }

    @Test
    public void ifLessThanDeclaredAreSentThenAnExceptionIsThrownAndConnectionIsClosed() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .addHandler((req, resp) -> {
                resp.contentType("text/plain");
                resp.headers().set(HeaderNames.CONTENT_LENGTH, 20);
                PrintWriter writer = resp.writer();
                writer.print("01234");
                return true;
            }).start();

        try (Response resp = call(request(server.uri().resolve("/blah")))) {
            resp.body().string();
            Assert.fail("Should have failed due to invalid HTTP response");
        } catch (Exception e) {
            assertThat(e, anyOf(instanceOf(StreamResetException.class), instanceOf(ProtocolException.class)));
        }
    }

    @After
    public void destroy() {
        scaffolding.MuAssert.stopAndCheck(server);
    }
}
