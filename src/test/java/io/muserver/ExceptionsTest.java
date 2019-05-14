package io.muserver;

import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.ServerUtils;

import javax.ws.rs.NotFoundException;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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

    @After
    public void stop() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}