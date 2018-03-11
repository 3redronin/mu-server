package io.muserver.rest;

import io.muserver.MuServer;
import io.muserver.MuServerBuilder;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;

import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.Path;

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
        startServer(new Sample());
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.code(), is(404));
            assertThat(resp.body().string(), equalTo("404 Not Found"));
        }
    }

    private void startServer(Object restResource) {
        this.server = MuServerBuilder.httpsServer()
            .addHandler(RestHandlerBuilder.restHandler(restResource).build()).start();
    }

    @After
    public void stop() {
        if (server != null) server.stop();
    }

}