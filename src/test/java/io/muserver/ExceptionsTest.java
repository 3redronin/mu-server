package io.muserver;

import okhttp3.Response;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;
import scaffolding.StringUtils;

import java.io.EOFException;
import java.net.URI;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static io.muserver.Mutils.htmlEncode;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class ExceptionsTest {
    private MuServer server;

    @Test
    public void notFoundExceptionsConvertTo404() throws Exception {
        this.server = ServerUtils.httpsServerForTest().addHandler(Method.GET, "/samples", (req, res, pp) -> {
            throw HttpException.notFound("I could not find the thing");
        }).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.code(), is(404));
            String body = resp.body().string();
            assertThat(body, containsString("<h1>404 Not Found</h1>"));
            assertThat(body, containsString("<p>I could not find the thing</p>"));
        }
    }

    @Test
    public void whenTheMessageIsNullThereIsNoParagraph() throws Exception {
        this.server = ServerUtils.httpsServerForTest().addHandler(Method.GET, "/samples", (req, res, pp) -> {
            throw new HttpException(HttpStatus.BAD_REQUEST_400, (String)null);
        }).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.code(), is(400));
            String body = resp.body().string();
            assertThat(body, containsString("<h1>400 Bad Request</h1>"));
            assertThat(body, not(containsString("<p>")));
        }
    }

    @Test
    public void notFoundExceptionsConvertTo404WithDefaultMessage() throws Exception {
        this.server = ServerUtils.httpsServerForTest().addHandler(Method.GET, "/samples", (req, res, pp) -> {
            throw new HttpException(HttpStatus.NOT_FOUND_404);
        }).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.code(), is(404));
            String body = resp.body().string();
            assertThat(body, containsString("<h1>404 Not Found</h1>"));
            assertThat(body, containsString("<p>This page is not available. Sorry about that.</p>"));
        }
    }

    @Test
    public void nonHttpExceptionsShowAs500WithoutOriginalInfo() throws Exception {
        this.server = ServerUtils.httpsServerForTest().addHandler(Method.GET, "/samples", (req, res, pp) -> {
            throw new RuntimeException("This is some secret information");
        }).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.code(), is(500));
            String body = resp.body().string();
            assertThat(body, containsString("500 Internal Server Error"));
            assertThat(body, containsString("Oops! An unexpected error occurred. The ErrorID="));
            assertThat(body, not(containsString("This is some secret information")));
        }
    }


    @Test
    public void default404IsANotFoundException() throws Exception {
        this.server = ServerUtils.httpsServerForTest().addHandler((req, resp) -> false).start();
        try (Response resp = call(request().url(server.uri().resolve("/does-not-exist").toString()))) {
            assertThat(resp.code(), is(404));
            String body = resp.body().string();
            assertThat(body, containsString("404 Not Found"));
            assertThat(body, containsString("This page is not available. Sorry about that."));
        }
    }

    @Test
    public void redirectExceptionsReallyRedirect() throws Exception {
        this.server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/secured", (request, response, pathParams) -> {
                throw HttpException.redirect(URI.create("/target"));
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
                throw new HttpException(HttpStatus.INTERNAL_SERVER_ERROR_500, "ARGHHHHHHH");
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
                throw new HttpException(HttpStatus.INTERNAL_SERVER_ERROR_500, "ARGHHHHHHH");
            })
            .withExceptionHandler((request, response, exception) -> {
                capturedResponse.set(response);
                throw new HttpException(HttpStatus.BAD_REQUEST_400, "It's bad");
            })
            .start();
        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.code(), is(400));
            assertThat(resp.body().string(), containsString(htmlEncode("It's bad")));
            MuAssert.assertEventually(capturedResponse.get()::responseState, equalTo(ResponseState.FULL_SENT));
        }
    }

    @Test
    public void unhandledExceptionsResultInEOFWhenChunkedResponseStarted() throws Exception {
        this.server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.sendChunk(StringUtils.randomAsciiStringOfLength(16389));
                throw new RuntimeException("I'm an exception from " + request.connection().protocol());
            })
            .start();
        // This test passes in OkHttpClient 4.9 as it doesn't seem to care about a truncated chunked response
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client(true);
        sslContextFactory.setEndpointIdentificationAlgorithm("https");
        HttpClient client = new HttpClient(sslContextFactory);
        client.start();
        try {
            ContentResponse get = client.GET(server.uri());
            assertThat(get.getStatus(), is(200));
            get.getContentAsString();
            Assertions.fail("Should have thrown!");
        } catch (ExecutionException e) {
            assertThat(e.getCause(), instanceOf(EOFException.class));
        } finally {
            client.stop();
        }
    }

    @AfterEach
    public void stop() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}