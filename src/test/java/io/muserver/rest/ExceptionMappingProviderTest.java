package io.muserver.rest;

import io.muserver.MuServer;
import io.muserver.MuServerBuilder;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.MessageBodyWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

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


    @Test
    public void customWritersCanBeUsedInExceptions() throws Exception {

        class Dog {
            String toDoggle() {
                return "Doggloggle";
            }
        }

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
                            return javax.ws.rs.core.Response.status(400).entity(new Dog()).build();
                        }
                    })
                .addCustomWriter(new MessageBodyWriter<Dog>() {
                    @Override
                    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
                        return type.isAssignableFrom(Dog.class);
                    }
                    @Override
                    public void writeTo(Dog dog, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
                        entityStream.write(dog.toDoggle().getBytes("UTF-8"));
                    }
                })
            ).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.code(), is(400));
            assertThat(resp.body().string(), equalTo("Doggloggle"));
        }
    }

    @After
    public void stop() {
        stopAndCheck(server);
    }

}