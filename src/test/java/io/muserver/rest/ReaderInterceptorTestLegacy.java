package io.muserver.rest;

import io.muserver.MuRequest;
import io.muserver.MuServer;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.ServerUtils;

import javax.ws.rs.*;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.ext.ReaderInterceptor;
import javax.ws.rs.ext.ReaderInterceptorContext;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.stopAndCheck;

public class ReaderInterceptorTestLegacy {

    private MuServer server;

    private static class UpperCaserInputStream extends FilterInputStream {
        public UpperCaserInputStream(InputStream in) {
            super(in);
        }
        @Override
        public int read(byte[] original, int off, int len) throws IOException {
            // this is buggy because it assumes the byte buffer ends at a character boundary, but come on, it's a test and everything is ASCII
            byte[] temp = new byte[len];
            int read = super.read(temp, 0, len);
            if (read < 1) return read;
            String s = new String(temp, 0, read, StandardCharsets.UTF_8);
            byte[] transformed = s.toUpperCase().getBytes(StandardCharsets.UTF_8);
            ByteBuffer bb = ByteBuffer.wrap(original);
            bb.position(off);
            bb.put(transformed, 0, read);
            return read;
        }
    }

    @Test
    public void interceptorsCanChangeTheEntityInOrderRegistered() throws Exception {
        @Path("/greetings")
        class GreetingResource {
            @POST
            public String hello(String body) {
                return body;
            }
        }

        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new GreetingResource())
                .addReaderInterceptor((ReaderInterceptor) context -> {
                    context.proceed();
                    return "new entity!";
                })
                .addReaderInterceptor((ReaderInterceptor) context -> ((String)context.proceed()).toUpperCase())
            )
            .start();

        try (Response resp = call(request(server.uri().resolve("/greetings"))
            .post(requestBody("hello"))
        )) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("NEW ENTITY!"));
        }
    }

    @Test
    public void interceptorsCanChangeTheRequestEntityStreamAndWrapEachOtherInOrderRegistered() throws Exception {
        @Path("/greetings")
        class GreetingResource {
            @POST
            @Produces("text/plain;charset=utf-8")
            @Consumes("text/plain;charset=utf-8")
            public String hello(String body) {
                return body;
            }
        }

        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new GreetingResource())
                .addReaderInterceptor((ReaderInterceptor) context -> {
                    context.setInputStream(new UpperCaserInputStream(context.getInputStream()));
                    return context.proceed();
                })
            )
            .start();

        try (Response resp = call(request(server.uri().resolve("/greetings"))
        .post(requestBody("hello"))
        )) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("content-type"), is("text/plain;charset=utf-8"));
            assertThat(resp.body().string(), equalTo("HELLO"));
        }
    }

    @Test
    public void resourceInfoAndMuRequestCanBeExtractedFromProperties() throws Exception {
        @Path("/greetings")
        class GreetingResource {
            @POST
            @Produces("text/plain;charset=utf-8")
            @Consumes("text/plain;charset=utf-8")
            public String hello(String body) {
                return body;
            }
        }
        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new GreetingResource())
                .addReaderInterceptor((ReaderInterceptor) context -> {
                    ResourceInfo resourceInfo = (ResourceInfo) context.getProperty(MuRuntimeDelegate.RESOURCE_INFO_PROPERTY);
                    MuRequest muRequest = (MuRequest) context.getProperty(MuRuntimeDelegate.MU_REQUEST_PROPERTY);
                    String val = resourceInfo.getResourceClass().getSimpleName() + " " + muRequest.uri().getPath();
                    context.setInputStream(new ByteArrayInputStream(val.getBytes(StandardCharsets.UTF_8)));
                    return context.proceed();
                })
            )
            .start();

        try (Response resp = call(request(server.uri().resolve("/greetings"))
        .post(requestBody("Hi")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("content-type"), is("text/plain;charset=utf-8"));
            assertThat(resp.body().string(), equalTo("GreetingResource /greetings"));
        }
    }

    static RequestBody requestBody(String value) {
        return RequestBody.create(value, MediaType.parse("text/plain"));
    }


    @Test
    public void emptyRequestsDoNotTriggerInterceptors() throws IOException {
        @Path("/nothing")
        class NoResource {
            @GET
            public void hello() {
            }
        }
        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new NoResource())
                .addReaderInterceptor((ReaderInterceptor) context -> {
                    throw new RuntimeException("This should not happen");
                })
            )
            .start();

        try (Response resp = call(request(server.uri().resolve("/nothing")))) {
            assertThat(resp.code(), is(204));
            assertThat(resp.body().string(), equalTo(""));
        }
    }

    @Test
    public void exceptionsAreBubbledToClient() throws IOException {
        @Path("/errors")
        class ErrorResource {
            @POST
            public String clientException(String body) {
                return body;
            }
        }
        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new ErrorResource())
                .addReaderInterceptor((ReaderInterceptor) context -> {
                    Object entity = context.proceed();
                    if (entity.equals("clientException")) {
                        throw new BadRequestException("Bad request!!");
                    } else if (entity.equals("runtimeException")) {
                        throw new RuntimeException("Runtime exception!!");
                    }
                    return entity;
                })
            )
            .start();

        try (Response resp = call(request(server.uri().resolve("/errors")).post(requestBody("clientException")))) {
            assertThat(resp.code(), is(400));
            assertThat(resp.body().string(), containsString("Bad request!!"));
        }
        try (Response resp = call(request(server.uri().resolve("/errors")).post(requestBody("runtimeException")))) {
            assertThat(resp.code(), is(500));
            assertThat(resp.body().string(), containsString("500 Internal Server Error"));
        }
    }

    @NameBinding
    @Target({ ElementType.TYPE, ElementType.METHOD })
    @Retention(value = RetentionPolicy.RUNTIME)
    public @interface UppercaseBinding { }

    @Test
    public void nameBindingIsCanBeUsedToTargetSpecificMethods() throws IOException {

        @UppercaseBinding
        class Uppercaser implements ReaderInterceptor {
            @Override
            public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException, WebApplicationException {
                String body = (String) context.proceed();
                return body.toUpperCase();
            }
        }

        @Path("something")
        class TheWay {
            @POST
            @Path("she")
            @UppercaseBinding
            public String moves(String body) {
                return body;
            }

            @POST
            @Path("like")
            public String noOther(String body) {
                return body;
            }
        }

        MuServer server = ServerUtils.httpsServerForTest()
            .addHandler(
                restHandler(new TheWay())
                    .addReaderInterceptor(new Uppercaser())
            ).start();
        try (Response resp = call(request(server.uri().resolve("/something/she")).post(requestBody("she moves")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("SHE MOVES"));
        }
        try (Response resp = call(request(server.uri().resolve("/something/like")).post(requestBody("lover")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("lover"));
        }
    }

    @After
    public void stop() {
        stopAndCheck(server);
    }
}
