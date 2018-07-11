package io.muserver.rest;

import io.muserver.MuServer;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;

import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;

import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class ExceptionsTest {
    private MuServer server;

    @Test
    public void notFoundExceptionsConvertTo404() throws Exception {
        @Path("samples")
        class Sample {
            @GET
            public String get() {
                throw new NotFoundException("This is ignored");
            }
        }
        this.server = httpsServer().addHandler(restHandler((Object) new Sample())).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.code(), is(404));
            assertThat(resp.body().string(), equalTo("404 Not Found"));
        }
    }

    @Test
    public void ifNoSuitableMethodThen405Returned() throws Exception {
        @Path("samples")
        class Sample {
            @POST
            public String get() {
                throw new NotFoundException("This is ignored");
            }
        }
        this.server = httpsServer().addHandler(restHandler((Object) new Sample())).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.code(), is(405));
            assertThat(resp.body().string(), equalTo("<h1>405 Method Not Allowed</h1>HTTP 405 Method Not Allowed"));
        }
    }

    @After
    public void stop() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}