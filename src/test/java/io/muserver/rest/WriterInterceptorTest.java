package io.muserver.rest;

import io.muserver.MuRequest;
import io.muserver.MuServer;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.ServerUtils;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.stopAndCheck;

public class WriterInterceptorTest {

    private MuServer server;

    @Test
    public void interceptorsCanChangeTheResponseEntityAndWrapEachOtherInOrderRegistered() throws Exception {
        @Path("/greetings")
        class GreetingResource {
            @GET
            @Produces("text/plain;charset=utf-8")
            public String hello() {
                return "hello";
            }
        }
        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new GreetingResource())
                .addWriterInterceptor((WriterInterceptor) context -> {
                    context.setEntity("prefix-" + context.getEntity()); // should be uppercased by wrapped interceptor
                    context.proceed();
                    context.setEntity(context.getEntity() + "-suffix"); // should run after the uppercaser so should remain lowercase
                })
                .addWriterInterceptor((WriterInterceptor) context -> {
                    String body = (String) context.getEntity();
                    context.setEntity(body.toUpperCase());
                    context.proceed();
                })
            )
            .start();

        try (Response resp = call(request(server.uri().resolve("/greetings")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("content-type"), is("text/plain;charset=utf-8"));
            assertThat(resp.body().string(), equalTo("PREFIX-HELLO-suffix"));
        }
    }

    @Test
    public void interceptorsCanChangeTheResponseEntityStreamAndWrapEachOtherInOrderRegistered() throws Exception {
        @Path("/greetings")
        class GreetingResource {
            @GET
            @Produces("text/plain;charset=utf-8")
            public String hello() {
                return "hello";
            }
        }
        class UpperCaserOutputStream extends FilterOutputStream {
            public UpperCaserOutputStream(OutputStream out) {
                super(out);
            }
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                // this is buggy because it assumes the byte buffer ends at a character boundary, but come on, it's a test and everything is ASCII
                String s = new String(b, off, len, StandardCharsets.UTF_8);
                byte[] transformed = s.toUpperCase().getBytes(StandardCharsets.UTF_8);
                super.write(transformed, 0, transformed.length);
            }
        }
        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new GreetingResource())
                .addWriterInterceptor((WriterInterceptor) context -> {
                    context.setOutputStream(new UpperCaserOutputStream(context.getOutputStream()));
                    context.proceed();
                })
            )
            .start();

        try (Response resp = call(request(server.uri().resolve("/greetings")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("content-type"), is("text/plain;charset=utf-8"));
            assertThat(resp.body().string(), equalTo("HELLO"));
        }
    }

    @Test
    public void resourceInfoAndMuRequestCanBeExtractedFromProperties() throws Exception {
        @Path("/greetings")
        class GreetingResource {
            @GET
            @Produces("text/plain;charset=utf-8")
            public String hello() {
                return "hello";
            }
        }
        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new GreetingResource())
                .addWriterInterceptor((WriterInterceptor) context -> {
                    ResourceInfo resourceInfo = (ResourceInfo) context.getProperty(MuRuntimeDelegate.RESOURCE_INFO_PROPERTY);
                    MuRequest muRequest = (MuRequest) context.getProperty(MuRuntimeDelegate.MU_REQUEST_PROPERTY);
                    context.getHeaders().putSingle("X-Resource-Info", resourceInfo.getResourceClass().getSimpleName());
                    context.getHeaders().putSingle("X-Mu-Request", muRequest.uri().getPath());
                    context.proceed();
                })
            )
            .start();

        try (Response resp = call(request(server.uri().resolve("/greetings")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("content-type"), is("text/plain;charset=utf-8"));
            assertThat(resp.body().string(), equalTo("hello"));
            assertThat(resp.header("X-Resource-Info"), equalTo("GreetingResource"));
            assertThat(resp.header("X-Mu-Request"), equalTo("/greetings"));
        }
    }


    @Test
    public void emptyResponsesDoNotTriggerInterceptors() throws IOException {
        @Path("/nothing")
        class NoResource {
            @GET
            public void hello() {
            }
        }
        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new NoResource())
                .addWriterInterceptor((WriterInterceptor) context -> {
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
            @GET
            @Path("clientException")
            public String clientException() {
                return "clientException";
            }
            @GET
            @Path("runtimeException")
            public String runtimeException() {
                return "runtimeException";
            }
            @GET
            @Path("responseException")
            public String responseException() {
                return "responseException";
            }
            @GET
            @Path("entityException")
            public String entityException() {
                return "entityException";
            }
        }
        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new ErrorResource())
                .addWriterInterceptor((WriterInterceptor) context -> {
                    if (context.getEntity().equals("clientException")) {
                        throw new BadRequestException("Bad request!!");
                    } else if (context.getEntity().equals("runtimeException")) {
                        throw new RuntimeException("Runtime exception!!");
                    } else if (context.getEntity().equals("responseException")) {
                        context.setEntity(jakarta.ws.rs.core.Response.status(409).entity("Response exception!!").build());
                    } else if (context.getEntity().equals("entityException")) {
                        context.setEntity(new ClientErrorException("Entity exception!!", 488));
                    }
                })
            )
            .start();

        try (Response resp = call(request(server.uri().resolve("/errors/clientException")))) {
            assertThat(resp.code(), is(400));
            assertThat(resp.body().string(), containsString("Bad request!!"));
        }
        try (Response resp = call(request(server.uri().resolve("/errors/runtimeException")))) {
            assertThat(resp.code(), is(500));
            assertThat(resp.body().string(), containsString("500 Internal Server Error"));
        }
        try (Response resp = call(request(server.uri().resolve("/errors/responseException")))) {
            assertThat(resp.code(), is(409));
            assertThat(resp.body().string(), containsString("Response exception!!"));
        }
        try (Response resp = call(request(server.uri().resolve("/errors/entityException")))) {
            assertThat(resp.code(), is(488));
            assertThat(resp.body().string(), containsString("Entity exception!!"));
        }
    }

    @NameBinding
    @Target({ ElementType.TYPE, ElementType.METHOD })
    @Retention(value = RetentionPolicy.RUNTIME)
    public @interface UppercaseBinding { }

    @Test
    public void nameBindingIsCanBeUsedToTargetSpecificMethods() throws IOException {

        @UppercaseBinding
        class Uppercaser implements WriterInterceptor {
            @Override
            public void aroundWriteTo(WriterInterceptorContext context) throws IOException, WebApplicationException {
                String body = (String) context.getEntity();
                context.setEntity(body.toUpperCase());
                context.proceed();
            }
        }

        @Path("something")
        class TheWay {
            @GET
            @Path("she")
            @UppercaseBinding
            public String moves() {
                return "she moves";
            }

            @GET
            @Path("like")
            public String noOther() {
                return "lover";
            }
        }

        MuServer server = ServerUtils.httpsServerForTest()
            .addHandler(
                restHandler(new TheWay())
                    .addWriterInterceptor(new Uppercaser())
            ).start();
        try (Response resp = call(request().url(server.uri().resolve("/something/she").toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("SHE MOVES"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/something/like").toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("lover"));
        }
    }


    @After
    public void stop() {
        stopAndCheck(server);
    }
}
