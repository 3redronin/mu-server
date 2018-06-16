package io.muserver;

import okhttp3.Response;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static java.util.Arrays.asList;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static io.muserver.MuServerBuilder.httpServer;
import static scaffolding.ClientUtils.*;

public class MuServerTest {

    private MuServer server;

    @Test
    public void portZeroCanBeUsed() {
        server = httpServer().start();
        try (Response resp = call(request().url(server.httpUri().toString()))) {
            assertThat(resp.code(), is(404));
        }
    }

    @Test
    public void unhandledExceptionsResultIn500sIfNoResponseSent() {
        server = httpServer()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                throw new RuntimeException("I'm the fire starter");
            })
            .start();
        try (Response resp = call(request().url(server.uri().toString()))) {
            assertThat(resp.code(), is(500));
        }
    }

    @Test
    public void unhandledExceptionsAreJustLoggedIfResponsesAreAlreadyStarted() {
        server = httpServer()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.status(200);
                response.writer().print("Hello");
                throw new RuntimeException("I'm the fire starter");
            })
            .start();
        try (Response resp = call(request().url(server.uri().toString()))) {
            assertThat(resp.code(), is(200));
        }
    }


    @Test
    public void syncHandlersSupportedAndStateCanBePassedThroughHandlers() throws IOException {
        List<String> handlersHit = new ArrayList<>();
        String randomText = UUID.randomUUID().toString();

        server = httpServer()
            .withHttpPort(12809)
            .addHandler((request, response) -> {
                handlersHit.add("Logger");
                request.state(randomText);
                return false;
            })
            .addHandler(Method.GET, "/blah", (request, response, pathParams) -> {
                handlersHit.add("BlahHandler");
                response.status(202);
                response.write("This is a test and this is the state: " + request.state());
            })
            .addHandler((request, response) -> {
                handlersHit.add("LastHandler");
                return true;
            })
            .start();

        Response resp = call(request().url("http://localhost:12809/blah"));
        assertThat(resp.code(), is(202));
        assertThat(resp.body().string(), equalTo("This is a test and this is the state: " + randomText));
        assertThat(handlersHit, equalTo(asList("Logger", "BlahHandler")));
    }


    @After
    public void stopIt() {
        server.stop();
    }
}