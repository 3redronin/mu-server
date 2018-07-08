package io.muserver.rest;

import io.muserver.MuServer;
import io.muserver.MuServerBuilder;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.ext.ExceptionMapper;

import java.io.IOException;

import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.stopAndCheck;

public class ExceptionMappingProviderTest {
    private MuServer server;
    private static class UpdateException extends Exception {}
    private static class ConcurrentUpdateException extends UpdateException {}

    @Test
    public void mappersCanBeUsedToMapThings() throws Exception {
        @Path("samples")
        class Sample {
            @GET
            public String get() throws ConcurrentUpdateException {
                throw new ConcurrentUpdateException();
            }
        }
        this.server = MuServerBuilder.httpsServer()
            .addHandler(
                restHandler(new Sample())
                    .addExceptionMapper(UpdateException.class, new ExceptionMapper<UpdateException>() {
                        @Override
                        public javax.ws.rs.core.Response toResponse(UpdateException exception) {
                            return javax.ws.rs.core.Response.status(400).entity("Could not update").build();
                        }
                    })
                    .addExceptionMapper(ConcurrentUpdateException.class, new ExceptionMapper<ConcurrentUpdateException>() {
                        @Override
                        public javax.ws.rs.core.Response toResponse(ConcurrentUpdateException exception) {
                            return javax.ws.rs.core.Response.status(409).entity("There was a concurrent update").build();
                        }
                    })
            ).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.code(), is(409));
            assertThat(resp.body().string(), equalTo("There was a concurrent update"));
        }
    }

    @Test
    public void unmappedExceptionsGetPassedBackToNormalMuServerUnmappedExceptionHandling() throws IOException {
        @Path("samples")
        class Sample {
            @GET
            public String get() throws ConcurrentUpdateException {
                throw new ConcurrentUpdateException();
            }
        }
        this.server = MuServerBuilder.httpsServer()
            .addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.code(), is(500));
            assertThat(resp.body().string(), startsWith("<h1>500 Internal Server Error</h1><p>ErrorID=ERR-"));
        }
    }

    @After
    public void stop() {
        stopAndCheck(server);
    }

}