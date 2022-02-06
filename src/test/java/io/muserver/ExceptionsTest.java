package io.muserver;

import okhttp3.Response;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import scaffolding.ServerUtils;
import scaffolding.StringUtils;

import javax.ws.rs.NotFoundException;
import javax.ws.rs.RedirectionException;
import java.io.EOFException;
import java.net.URI;
import java.util.Collections;
import java.util.concurrent.ExecutionException;

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
    public void nonJaxRSExceptionsShowAs500WithoutOriginalInfo() throws Exception {
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
        ClientConnector connector = new ClientConnector();
        connector.setSslContextFactory(sslContextFactory);
        HttpClient client = new HttpClient(new HttpClientTransportOverHTTP(connector));
        client.start();
        try {
            ContentResponse get = client.GET(server.uri());
            assertThat(get.getStatus(), is(200));
            get.getContentAsString();
            Assert.fail("Should have thrown!");
        } catch (ExecutionException e) {
            assertThat(e.getCause(), instanceOf(EOFException.class));
        } finally {
            client.stop();
        }
    }

    @After
    public void stop() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}