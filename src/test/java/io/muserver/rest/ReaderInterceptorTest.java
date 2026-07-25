package io.muserver.rest;

import io.muserver.MuRequest;
import io.muserver.MuServer;
import jakarta.annotation.Priority;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.ServerUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.stopAndCheck;

public class ReaderInterceptorTest {

    private MuServer server;

    @Test
    public void lowerPriorityValueExecutesFirstRegardlessOfRegistrationOrder() throws Exception {
        List<String> calls = new ArrayList<>();
        @Priority(100)
        class First implements ReaderInterceptor {
            @Override
            public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException {
                calls.add("first");
                return context.proceed();
            }
        }
        @Priority(200)
        class Second implements ReaderInterceptor {
            @Override
            public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException {
                calls.add("second");
                return context.proceed();
            }
        }
        @Path("/priority")
        class PriorityResource {
            @POST
            public String post(String body) {
                return body;
            }
        }

        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new PriorityResource())
                .addReaderInterceptor(new First())
                .addReaderInterceptor(new Second()))
            .start();

        try (Response resp = call(request(server.uri().resolve("/priority")).post(requestBody("hello")))) {
            assertThat(resp.code(), is(200));
            assertThat(calls, contains("first", "second"));
        }
    }

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
                .addReaderInterceptor(context -> {
                    context.proceed();
                    return "new entity!";
                })
                .addReaderInterceptor(context -> ((String)context.proceed()).toUpperCase())
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
                .addReaderInterceptor(context -> {
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
    public void interceptorsCanChangeTheRequestMediaType() throws Exception {
        @Path("/greetings")
        class GreetingResource {
            @POST
            public String hello(String body) {
                return body;
            }
        }

        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new GreetingResource())
                .addReaderInterceptor(context -> {
                    context.setMediaType(jakarta.ws.rs.core.MediaType.APPLICATION_JSON_TYPE);
                    assertThat(context.getMediaType(), is(jakarta.ws.rs.core.MediaType.APPLICATION_JSON_TYPE));
                    return context.proceed();
                })
            )
            .start();

        try (Response resp = call(request(server.uri().resolve("/greetings"))
            .post(requestBody("hello"))
        )) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("hello"));
        }
    }

    @Test
    public void interceptorsRunForEverySupportedBlockedTckReaderType() throws Exception {
        AtomicInteger interceptorCalls = new AtomicInteger();
        @Path("/reader-types")
        class Resource {
            @POST
            @Path("bytes")
            public String bytes(byte[] body) {
                return new String(body, StandardCharsets.UTF_8);
            }

            @POST
            @Path("file")
            public String file(File body) throws IOException {
                return Files.readString(body.toPath());
            }

            @POST
            @Path("stream")
            public String stream(InputStream body) throws IOException {
                return new String(body.readAllBytes(), StandardCharsets.UTF_8);
            }

            @POST
            @Path("reader")
            public String reader(Reader body) throws IOException {
                StringBuilder value = new StringBuilder();
                char[] buffer = new char[32];
                int read;
                while ((read = body.read(buffer)) >= 0) {
                    value.append(buffer, 0, read);
                }
                return value.toString();
            }
        }
        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new Resource())
                .addReaderInterceptor(context -> {
                    interceptorCalls.incrementAndGet();
                    return context.proceed();
                }))
            .start();

        for (String path : List.of("bytes", "file", "stream", "reader")) {
            try (Response response = call(request(server.uri().resolve("/reader-types/" + path))
                .post(requestBody(path)))) {
                assertThat(response.body().string(), is(path));
            }
        }
        assertThat(interceptorCalls.get(), is(4));
    }

    @Test
    public void lastReaderInterceptorValuesControlProviderSelection() throws Exception {
        @Priority(200)
        class ExpectedValuesReader implements MessageBodyReader<ArrayList<String>> {
            @Override
            public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations,
                                      jakarta.ws.rs.core.MediaType mediaType) {
                return type == ArrayList.class
                    && genericType.getTypeName().equals("java.util.List<java.lang.String>")
                    && mediaType.equals(jakarta.ws.rs.core.MediaType.TEXT_PLAIN_TYPE)
                    && annotations.length == 1
                    && annotations[0] instanceof Priority
                    && ((Priority) annotations[0]).value() == 200;
            }

            @Override
            public ArrayList<String> readFrom(Class<ArrayList<String>> type, Type genericType,
                                              Annotation[] annotations,
                                              jakarta.ws.rs.core.MediaType mediaType,
                                              MultivaluedMap<String, String> httpHeaders,
                                              InputStream entityStream) throws IOException {
                return new ArrayList<>(List.of(
                    new String(entityStream.readAllBytes(), StandardCharsets.UTF_8)));
            }
        }
        @Path("/last-reader-values")
        class Resource {
            @POST
            public String post(List<String> value) {
                return value.get(0);
            }
        }
        @Priority(100)
        class First implements ReaderInterceptor {
            @Override
            public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException {
                context.setType(java.util.LinkedList.class);
                context.setMediaType(jakarta.ws.rs.core.MediaType.TEXT_HTML_TYPE);
                context.setAnnotations(getClass().getAnnotations());
                context.setInputStream(new ByteArrayInputStream("first".getBytes(StandardCharsets.UTF_8)));
                return context.proceed();
            }
        }
        @Priority(200)
        class Second implements ReaderInterceptor {
            @Override
            public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException {
                context.setType(ArrayList.class);
                context.setMediaType(jakarta.ws.rs.core.MediaType.TEXT_PLAIN_TYPE);
                context.setAnnotations(getClass().getAnnotations());
                context.setInputStream(new ByteArrayInputStream("second".getBytes(StandardCharsets.UTF_8)));
                return context.proceed();
            }
        }
        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new Resource())
                .addCustomReader(new ExpectedValuesReader())
                .addReaderInterceptor(new Second())
                .addReaderInterceptor(new First()))
            .start();

        try (Response response = call(request(server.uri().resolve("/last-reader-values"))
            .post(requestBody("original")))) {
            assertThat(response.code(), is(200));
            assertThat(response.body().string(), is("second"));
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
                .addReaderInterceptor(context -> {
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
                .addReaderInterceptor(context -> {
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
                .addReaderInterceptor(context -> {
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

    @NameBinding
    @Target({ ElementType.TYPE, ElementType.METHOD })
    @Retention(value = RetentionPolicy.RUNTIME)
    public @interface UnusedBinding { }

    @Test
    public void nonMatchingNameBoundInterceptorDoesNotStopTheChain() throws IOException {
        @UnusedBinding
        class UnusedInterceptor implements ReaderInterceptor {
            @Override
            public Object aroundReadFrom(ReaderInterceptorContext context) {
                throw new AssertionError("This interceptor should not run");
            }
        }

        @UppercaseBinding
        class Uppercaser implements ReaderInterceptor {
            @Override
            public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException {
                return ((String) context.proceed()).toUpperCase();
            }
        }

        @Path("something")
        class Resource {
            @POST
            @UppercaseBinding
            public String post(String body) {
                return body;
            }
        }

        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new Resource())
                .addReaderInterceptor(new Uppercaser())
                .addReaderInterceptor(new UnusedInterceptor()))
            .start();

        try (Response resp = call(request(server.uri().resolve("/something")).post(requestBody("hello")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("HELLO"));
        }
    }

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
