package io.muserver;

import okhttp3.Response;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.ProtocolException;
import java.util.concurrent.CountDownLatch;

import static io.muserver.MuServerBuilder.httpServer;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class FixedLengthTest {

    static {
        Toggles.fixedLengthResponsesEnabled = true;
    }

    private MuServer server;
    private StringBuilder errors = new StringBuilder();
    private CountDownLatch errorSetLatch = new CountDownLatch(1);

    @Test
    public void ifMoreThanDeclaredAreSentThenAnExceptionIsThrownAndConnectionIsClosed() throws IOException {
        server = httpServer()
            .addHandler((req, resp) -> {
                resp.contentType("text/plain");
                resp.headers().set(HeaderNames.CONTENT_LENGTH, 20);
                PrintWriter writer = resp.writer();
                writer.print("01234");
                writer.flush();

                try {
                    writer.println("this will push the response over 20 bytes in size");
                    writer.flush();
                } catch (Exception ex) {
                    errors.append(ex.getMessage());
                    errorSetLatch.countDown();
                }

                return true;
            }).start();

        try (Response resp = call(request().url(server.uri().resolve("/blah").toString()))) {
            resp.body().string();
            Assert.fail("Should have failed due to invalid HTTP response");
        } catch (ProtocolException pe) {
            // yay
        }

        assertThat(errors.toString(), equalTo("The declared content length for GET " + server.uri().resolve("/blah") + " was 20 bytes. " +
            "The current write is being aborted and the connection is being closed because it would have resulted in " +
            "56 bytes being sent."));
    }

    @Test
    public void ifLessThanDeclaredAreSentThenAnExceptionIsThrownAndConnectionIsClosed() throws IOException {
        server = httpServer()
            .addHandler((req, resp) -> {
                resp.contentType("text/plain");
                resp.headers().set(HeaderNames.CONTENT_LENGTH, 20);
                PrintWriter writer = resp.writer();
                writer.print("01234");
                return true;
            }).start();

        try (Response resp = call(request().url(server.uri().resolve("/blah").toString()))) {
            resp.body().string();
            Assert.fail("Should have failed due to invalid HTTP response");
        } catch (ProtocolException pe) {
            // yay
        }
    }

    @After
    public void destroy() {
        scaffolding.MuAssert.stopAndCheck(server);
    }
}