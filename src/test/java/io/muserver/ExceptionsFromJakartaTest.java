package io.muserver;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.RedirectionException;
import jakarta.ws.rs.ServerErrorException;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;

import java.net.URI;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static io.muserver.Mutils.htmlEncode;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class ExceptionsFromJakartaTest {
    private MuServer server;

    @Test
    public void notFoundExceptionsConvertTo404() throws Exception {
        this.server = ServerUtils.httpsServerForTest().addHandler(Method.GET, "/samples", (req, res, pp) -> {
            throw new NotFoundException("I could not find the thing");
        }).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.code(), is(404));
            String body = resp.body().string();
            assertThat(body, containsString("<h1>404 Not Found</h1>"));
            assertThat(body, containsString("<p>I could not find the thing</p>"));
        }
    }
    @Test
    public void notFoundExceptionsConvertTo404WithDefaultMessage() throws Exception {
        this.server = ServerUtils.httpsServerForTest().addHandler(Method.GET, "/samples", (req, res, pp) -> {
            throw new NotFoundException();
        }).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.code(), is(404));
            String body = resp.body().string();
            assertThat(body, containsString("<h1>404 Not Found</h1>"));
            assertThat(body, containsString("<p>This page is not available. Sorry about that.</p>"));
        }
    }

    @Test
    public void redirectExceptionsReallyRedirect() throws Exception {
        this.server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/secured", (request, response, pathParams) -> {
                throw new RedirectionException("Not logged in!", 302, URI.create("/target"));
            })
            .addHandler(Method.GET, "/target", (request, response, pathParams) -> {
                response.write("You were redirected");
            })
            .start();
        try (Response resp = call(request(server.uri().resolve("/secured")))) {
            assertThat(resp.code(), is(302));
            assertThat(resp.headers("location"), is(Collections.singletonList(server.uri().resolve("/target").toString())));
        }
    }

    @Test
    public void exceptionHandlersCanCustomiseResponse() throws Exception {
        AtomicReference<MuResponse> capturedResponse = new AtomicReference<>();
        this.server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                capturedResponse.set(response);
                throw new ServerErrorException("ARGHHHHHHH", 500);
            })
            .withExceptionHandler((request, response, exception) -> {
                capturedResponse.set(response);
                if (!request.query().getBoolean("useCustom")) return false;
                response.contentType(ContentTypes.TEXT_PLAIN_UTF8);
                response.writer().write("Oh I'm worry, there was a problem: " + exception.getMessage());
                return true;
            })
            .start();
        try (Response resp = call(request(server.uri().resolve("?useCustom=true")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("Oh I'm worry, there was a problem: ARGHHHHHHH"));
            MuAssert.assertEventually(capturedResponse.get()::responseState, equalTo(ResponseState.FINISHED));
        }
        try (Response resp = call(request(server.uri().resolve("?useCustom=false")))) {
            assertThat(resp.code(), is(500));
            assertThat(resp.body().string(), allOf(
                containsString("ARGHHHH"),
                not(containsString("Oh I'm worry"))
            ));
            MuAssert.assertEventually(capturedResponse.get()::responseState, equalTo(ResponseState.FULL_SENT));
        }
    }

    @Test
    public void exceptionsThrownFromExceptionHandlersAreBubbledToClient() throws Exception {
        AtomicReference<MuResponse> capturedResponse = new AtomicReference<>();
        this.server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                capturedResponse.set(response);
                throw new ServerErrorException("ARGHHHHHHH", 500);
            })
            .withExceptionHandler((request, response, exception) -> {
                capturedResponse.set(response);
                throw new BadRequestException("It's bad");
            })
            .start();
        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.code(), is(400));
            assertThat(resp.body().string(), containsString(htmlEncode("It's bad")));
            MuAssert.assertEventually(capturedResponse.get()::responseState, equalTo(ResponseState.FULL_SENT));
        }
    }

    @AfterEach
    public void stop() {
        MuAssert.stopAndCheck(server);
    }

}