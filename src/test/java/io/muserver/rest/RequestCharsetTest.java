package io.muserver.rest;

import io.muserver.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.MessageBodyReader;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.RequestBody;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.junit.Assert.*;
import static scaffolding.ClientUtils.*;

@RunWith(Parameterized.class)
public class RequestCharsetTest {
    @Parameterized.Parameters(name = "{0}")
    public static Object[] protocols() { return new Object[]{Protocol.HTTP_1_1, Protocol.HTTP_2}; }
    private final Protocol protocol;
    private final OkHttpClient caller;
    private MuServer server;
    public RequestCharsetTest(Protocol protocol) {
        this.protocol = protocol;
        caller = client.newBuilder().protocols(protocol == Protocol.HTTP_2
            ? Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1) : Collections.singletonList(protocol)).build();
    }
    @After public void stop() { scaffolding.MuAssert.stopAndCheck(server); }

    @Path("/charset") @Consumes("text/plain") @Produces("text/plain")
    public static class Resource {
        final AtomicInteger calls = new AtomicInteger();
        private String result(Object value) { calls.incrementAndGet(); return String.valueOf(value); }
        @QUERY @Path("string") public String queryString(String value) { return result(value); }
        @POST @Path("string") public String postString(String value) { return result(value); }
        @QUERY @Path("chars") public String queryChars(char[] value) { return result(new String(value)); }
        @POST @Path("chars") public String postChars(char[] value) { return result(new String(value)); }
        @QUERY @Path("reader") public String queryReader(Reader value) throws IOException { return result(read(value)); }
        @POST @Path("reader") public String postReader(Reader value) throws IOException { return result(read(value)); }
        @QUERY @Path("primitive") public String queryPrimitive(int value) { return result(value); }
        @POST @Path("primitive") public String postPrimitive(int value) { return result(value); }
        @QUERY @Path("temporal") public String queryTemporal(Instant value) { return result(value); }
        @POST @Path("temporal") public String postTemporal(Instant value) { return result(value); }
        @QUERY @Path("form") @Consumes("application/x-www-form-urlencoded")
        public String queryForm(MultivaluedMap<String, String> value) { return result(value.getFirst("q")); }
        @POST @Path("form") @Consumes("application/x-www-form-urlencoded")
        public String postForm(MultivaluedMap<String, String> value) { return result(value.getFirst("q")); }
        @QUERY @Path("bytes") public String queryBytes(byte[] value) { return result(new String(value, StandardCharsets.UTF_8)); }
        @POST @Path("bytes") public String postBytes(byte[] value) { return result(new String(value, StandardCharsets.UTF_8)); }
        @QUERY @Path("writer") public Response queryWriter(@HeaderParam("X-Charset") String charset, @QueryParam("reader") boolean reader) {
            return writer(charset, reader);
        }
        @POST @Path("writer") public Response postWriter(@HeaderParam("X-Charset") String charset, @QueryParam("reader") boolean reader) {
            return writer(charset, reader);
        }
        private Response writer(String charset, boolean reader) {
            return Response.ok(reader ? new StringReader("result") : "result")
                .type(new MediaType("text", "plain", Collections.singletonMap("charset", charset))).build();
        }
        private static String read(Reader reader) throws IOException {
            StringWriter result = new StringWriter();
            reader.transferTo(result);
            return result.toString();
        }
    }

    private void start(Resource resource, MessageBodyReader<?> customReader) {
        MuServerBuilder builder = MuServerBuilder.httpsServer().withHttp2Config(Http2ConfigBuilder.http2EnabledIfAvailable());
        RestHandlerBuilder rest = restHandler(resource);
        if (customReader != null) rest.addCustomReader(customReader);
        server = builder.addHandler(rest).start();
    }
    private okhttp3.Response send(String method, String path, String type, byte[] body) {
        okhttp3.Response response = call(caller, request(server.uri().resolve("/charset/" + path))
            .header("Content-Type", type).method(method, RequestBody.create(body, (okhttp3.MediaType) null)));
        assertEquals(protocol, response.protocol());
        return response;
    }
    private static String type(String path) { return path.equals("form") ? "application/x-www-form-urlencoded" : "text/plain"; }

    @Test public void unknownAndMalformedRequestCharsetsReturn415BeforeInvocation() {
        Resource resource = new Resource();
        start(resource, null);
        for (String method : Arrays.asList("QUERY", "POST")) {
            for (String path : Arrays.asList("string", "chars", "reader", "primitive", "temporal", "form")) {
                for (String charset : Arrays.asList("not-a-charset", "bad charset")) {
                    try (okhttp3.Response response = send(method, path, type(path) + ";charset=\"" + charset + "\"", new byte[]{'1'})) {
                        assertEquals(method + " " + path + " " + charset, 415, response.code());
                        assertEquals(method.equals("QUERY") ? "\"" + type(path) + "\"" : null, response.header("Accept-Query"));
                    }
                }
            }
        }
        assertEquals(0, resource.calls.get());
    }

    @Test public void validCharsetsStillDecodeRequestBodies() throws Exception {
        Resource resource = new Resource();
        start(resource, null);
        for (String method : Arrays.asList("QUERY", "POST")) {
            for (String charset : Arrays.asList("UTF-8", "ISO-8859-1")) {
                for (String path : Arrays.asList("string", "chars", "reader", "primitive", "temporal", "form")) {
                    String expected = path.equals("primitive") ? "123" : path.equals("temporal") ? "2026-09-22T00:00:00Z" : "café";
                    String body = path.equals("form") ? "q=" + expected : expected;
                    try (okhttp3.Response response = send(method, path, type(path) + ";charset=" + charset, body.getBytes(Charset.forName(charset)))) {
                        assertEquals(200, response.code());
                        assertEquals(expected, response.body().string());
                    }
                }
            }
        }
        assertEquals(24, resource.calls.get());
    }

    @Test public void byteReadersDoNotRequireARecognizedCharset() throws Exception {
        start(new Resource(), null);
        for (String method : Arrays.asList("QUERY", "POST")) {
            try (okhttp3.Response response = send(method, "bytes", "text/plain;charset=not-a-charset", new byte[]{'x'})) {
                assertEquals(200, response.code()); assertEquals("x", response.body().string());
            }
        }
    }

    @Test public void responseCharsetFailuresRemainServerErrorsWithoutQueryAdvertisement() {
        start(new Resource(), null);
        for (String method : Arrays.asList("QUERY", "POST")) {
            for (String charset : Arrays.asList("not-a-charset", "bad charset")) {
                for (boolean reader : new boolean[]{false, true}) {
                    try (okhttp3.Response response = call(caller, request(server.uri().resolve("/charset/writer?reader=" + reader))
                        .header("Content-Type", "text/plain").header("X-Charset", charset)
                        .method(method, RequestBody.create(new byte[0], (okhttp3.MediaType) null)))) {
                        assertEquals(protocol, response.protocol());
                        assertEquals(500, response.code()); assertNull(response.header("Accept-Query"));
                    }
                }
            }
        }
    }

    @Consumes("text/plain") public static class FailingCustomReader implements MessageBodyReader<String> {
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) { return type == String.class; }
        public String readFrom(Class<String> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                               MultivaluedMap<String, String> headers, InputStream stream) {
            throw new java.nio.charset.UnsupportedCharsetException("custom-provider-failure");
        }
    }
    @Test public void customReaderFailuresAreNotReclassified() {
        start(new Resource(), new FailingCustomReader());
        for (String method : Arrays.asList("QUERY", "POST")) {
            try (okhttp3.Response response = send(method, "string", "text/plain;charset=UTF-8", new byte[]{'x'})) {
                assertEquals(500, response.code()); assertNull(response.header("Accept-Query"));
            }
        }
    }
}
