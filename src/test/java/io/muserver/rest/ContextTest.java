package io.muserver.rest;

import io.muserver.MuServer;
import io.muserver.MuServerBuilder;
import org.junit.After;
import org.junit.Test;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.UriInfo;

public class ContextTest {
    private MuServer server;

    @Test
    public void uriInfoCanBeGotten() throws Exception {
        @Path("samples")
        class Sample {
            @GET
            public String get(@Context UriInfo uri) {
                return "";
            }
        }
        startServer(new Sample());
    }

    private void startServer(Object restResource) {
        this.server = MuServerBuilder.httpsServer().addHandler(RestHandlerBuilder.create(restResource)).start();
    }

    @After
    public void stop() {
        if (server != null) server.stop();
    }

}