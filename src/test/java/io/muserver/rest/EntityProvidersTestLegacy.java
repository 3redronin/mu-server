package io.muserver.rest;

import io.muserver.MuServer;
import io.muserver.Mutils;
import io.muserver.ResponseInfo;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import org.example.MyStringReaderWriter;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Test;
import scaffolding.StringUtils;

import javax.ws.rs.*;
import javax.ws.rs.core.GenericEntity;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.MessageBodyWriter;
import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static io.muserver.Mutils.NEWLINE;
import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.ServerUtils.httpsServerForTest;
import static scaffolding.StringUtils.randomStringOfLength;

public class EntityProvidersTestLegacy {

    private MuServer server;

    @Test
    public void stringsSupported() throws Exception {
        @Path("samples")
        class Sample {
            @POST
            public String echo(String body) {
                return body;
            }
        }
        startServer(new Sample());
        stringCheck("text/plain", randomStringOfLength(128 * 1024), "text/plain;charset=utf-8", "/samples");
    }

    @Test
    public void customConvertersComeBeforeBuiltInOnesIfAllOtherThingsMatch() throws Exception {
        @Path("samples")
        class Sample {
            @POST
            public String echo(String body) {
                return body;
            }
        }

        this.server = httpsServerForTest().addHandler(
            restHandler(new Sample())
                .addCustomReader(new MyStringReaderWriter())
                .addCustomWriter(new MyStringReaderWriter())
                .build()).start();
        try (Response resp = call(request()
            .post(RequestBody.create("hello world", MediaType.parse("text/plain")))
            .url(server.uri().resolve("/samples").toString())
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("Content-Type"), equalTo("text/plain;charset=utf-8"));
            assertThat(resp.body().string(), equalTo("--HELLO WORLD--"));
        }
    }

    @Test
    public void customWritersGetTheAnnotationsOfTheResourceMethod() throws Exception {

        @Path("dummy")
        class Thing {}

        @Path("samples")
        class Sample {
            @GET
            @Description("A description")
            @Produces({"text/vnd.custom.txt;charset=utf-8", "*/*"})
            public javax.ws.rs.core.Response echo() {
                return javax.ws.rs.core.Response.ok().entity(new Thing(), Thing.class.getAnnotations()).build();
            }
        }

        this.server = httpsServerForTest().addHandler(
            restHandler(new Sample())
                .addCustomWriter(new MessageBodyWriter<Object>() {
                    @Override
                    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, javax.ws.rs.core.MediaType mediaType) {
                        return type.equals(Thing.class);
                    }
                    @Override
                    public void writeTo(Object o, Class<?> type, Type genericType, Annotation[] annotations, javax.ws.rs.core.MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
                        try (OutputStreamWriter writer = new OutputStreamWriter(entityStream)) {
                            Description description = Arrays.stream(annotations).filter(a -> a.annotationType().equals(Description.class))
                                .map(a -> (Description) a)
                                .findFirst().orElse(null);
                            Path path = Arrays.stream(annotations).filter(a -> a.annotationType().equals(Path.class))
                                .map(a -> (Path) a)
                                .findFirst().orElse(null);
                            writer.write(mediaType + " "
                                + (description == null ? "null" : description.value()) + " "
                                + (path == null ? "null" : path.value())
                            );
                        }
                    }
                })
                .build()).start();
        try (Response resp = call(request(server.uri().resolve("/samples")))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("Content-Type"), equalTo("text/vnd.custom.txt;charset=utf-8"));
            assertThat(resp.body().string(), equalTo("text/vnd.custom.txt;charset=utf-8 A description dummy"));
        }
    }

    @Test
    public void customReadersGetTheAnnotationsOfTheResourceMethod() throws Exception {

        @Path("dummy")
        class Thing {
            final String value;

            Thing(String value) {
                this.value = value;
            }
        }

        @Path("samples")
        class Sample {
            @POST
            public String echo(@Description("A description") @Required Thing thing) {
                return thing.value;
            }
        }

        @Consumes({"text/vnd.custom.txt;charset=utf-8", "*/*"})
        class ThingReader implements MessageBodyReader<Thing> {
            @Override
            public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, javax.ws.rs.core.MediaType mediaType) {
                return type.equals(Thing.class);
            }

            @Override
            public Thing readFrom(Class<Thing> type, Type genericType, Annotation[] annotations, javax.ws.rs.core.MediaType mediaType, MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException, WebApplicationException {
                Description description = Arrays.stream(annotations).filter(a -> a.annotationType().equals(Description.class))
                    .map(a -> (Description) a)
                    .findFirst().orElse(null);
                Required required = Arrays.stream(annotations).filter(a -> a.annotationType().equals(Required.class))
                    .map(a -> (Required) a)
                    .findFirst().orElse(null);
                return new Thing(mediaType.toString() + " " + description.value() + " " + (required != null));
            }
        }

        this.server = httpsServerForTest().addHandler(
            restHandler(new Sample())
                .addCustomReader(new ThingReader())
                .build()).start();
        try (Response resp = call(request(server.uri().resolve("/samples")).post(RequestBody.create("dummy", MediaType.parse("text/vnd.custom.txt;charset=utf-8"))))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("text/vnd.custom.txt;charset=utf-8 A description true"));
        }
    }

    @Test
    public void customConvertersCanBeUsedEvenWithGenerics() throws Exception {

        class Dog {
            public final String breed;
            public final String name;
            Dog(String name, String breed) {
                this.name = name;
                this.breed = breed;
            }
        }

        @Path("dogs")
        class Sample {
            @POST
            public Dog get(String name) {
                return new Dog(name, "Unknown");
            }

            @GET
            @Path("all")
            public javax.ws.rs.core.Response all() {
                List<Dog> dogs = asList(new Dog("Little", "Chihuahua"), new Dog("Mangle", "Mongrel"));
                GenericEntity<List<Dog>> dogList = new GenericEntity<List<Dog>>(dogs) {};
                return javax.ws.rs.core.Response.ok(dogList).build();
            }
        }

        class DogWriter implements MessageBodyWriter<Dog> {
            public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, javax.ws.rs.core.MediaType mediaType) {
                return type.equals(Dog.class);
            }
            public void writeTo(Dog dog, Class<?> type, Type genericType, Annotation[] annotations, javax.ws.rs.core.MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
                try (PrintStream stream = new PrintStream(entityStream)) {
                    stream.print("Dog: " + dog.name + " (" + dog.breed + ")");
                }
            }
        }
        class DogListWriter implements MessageBodyWriter<List<Dog>> {
            public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, javax.ws.rs.core.MediaType mediaType) {
                return List.class.isAssignableFrom(type) && genericType instanceof ParameterizedType
                    && ((ParameterizedType)genericType).getActualTypeArguments()[0].equals(Dog.class);
            }
            public void writeTo(List<Dog> dogs, Class<?> type, Type genericType, Annotation[] annotations, javax.ws.rs.core.MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
                try (PrintStream stream = new PrintStream(entityStream)) {
                    for (Dog dog : dogs) {
                        stream.println(dog.name + " (" + dog.breed + ")");
                    }
                }
            }
        }

        this.server = httpsServerForTest().addHandler(
            restHandler(new Sample())
                .addCustomWriter(new DogWriter())
                .addCustomWriter(new DogListWriter())
                .build()).start();
        try (Response resp = call(request()
            .post(RequestBody.create("Little", MediaType.parse("text/plain")))
            .url(server.uri().resolve("/dogs").toString())
        )) {
            assertThat(resp.body().string(), equalTo("Dog: Little (Unknown)"));
        }

        try (Response resp = call(request().url(server.uri().resolve("/dogs/all").toString()))) {
            assertThat(resp.body().string(), equalTo("Little (Chihuahua)" + NEWLINE + "Mangle (Mongrel)" + NEWLINE));
        }
    }

    private void stringCheck(String requestBodyType, String content, String expectedResponseType, String requestPath) throws IOException {
        try (Response resp = call(request()
            .post(RequestBody.create(content, MediaType.parse(requestBodyType)))
            .url(server.uri().resolve(requestPath).toString())
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("Content-Type"), equalTo(expectedResponseType));
            assertThat(resp.body().string(), equalTo(content));
        }
    }

    @Test
    public void customConvertersCanBeUsedInSubResources() throws Exception {
        class Dog {
            public final String breed;
            Dog(String breed) {
                this.breed = breed;
            }
        }
        class Dogs {
            @POST
            public Dog get(Dog input) {
                return new Dog("Papillon");
            }
        }
        @Path("api")
        class DogFather {
            @Path("dogs")
            public Dogs dogs() {
                return new Dogs();
            }
        }
        @Produces("text/plain")
        class DogWriter implements MessageBodyWriter<Dog> {
            public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, javax.ws.rs.core.MediaType mediaType) {
                return type.equals(Dog.class);
            }
            public void writeTo(Dog dog, Class<?> type, Type genericType, Annotation[] annotations, javax.ws.rs.core.MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
                try (PrintStream stream = new PrintStream(entityStream)) {
                    stream.print("Dog: " + dog.breed);
                }
            }
        }
        @Consumes("text/plain")
        class DogReader implements MessageBodyReader<Dog> {
            @Override
            public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, javax.ws.rs.core.MediaType mediaType) {
                return type.equals(Dog.class);
            }
            @Override
            public Dog readFrom(Class<Dog> type, Type genericType, Annotation[] annotations, javax.ws.rs.core.MediaType mediaType, MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException, WebApplicationException {
                return new Dog(new String(Mutils.toByteArray(entityStream, 2048), charsetFor(mediaType)));
            }
        }

        this.server = httpsServerForTest().addHandler(
            restHandler(new DogFather())
                .addCustomWriter(new DogWriter())
                .addCustomReader(new DogReader())
                .build()).start();
        try (Response resp = call(request()
            .post(RequestBody.create("Yaptal", MediaType.parse("text/plain")))
            .url(server.uri().resolve("/api/dogs").toString())
        )) {
            assertThat(resp.body().string(), equalTo("Dog: Papillon"));
        }
    }
    static Charset charsetFor(javax.ws.rs.core.MediaType mediaType) {
        String charset = mediaType.getParameters().get("charset");
        if (charset == null) {
            return StandardCharsets.UTF_8;
        } else {
            return Charset.forName(charset);
        }
    }
    @Test
    public void entityStreamsAreClosedAfter() throws Exception {
        String body = "Yaptal";

        class Dog {
            public final String breed;
            Dog(String breed) {
                this.breed = breed;
            }
        }
        @Path("api")
        class DogFather {
            @POST
            @Path("dogs")
            public String dogs(Dog dog) {
                return dog.breed;
            }
        }
        @Consumes("text/plain")
        class DogReader implements MessageBodyReader<Dog> {
            @Override
            public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, javax.ws.rs.core.MediaType mediaType) {
                return type.equals(Dog.class);
            }
            @Override
            public Dog readFrom(Class<Dog> type, Type genericType, Annotation[] annotations, javax.ws.rs.core.MediaType mediaType, MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException, WebApplicationException {
                // gonna read the exact body length, but won't close the stream
                byte[] buffer = new byte[body.length()];
                entityStream.read(buffer);
                return new Dog(new String(buffer, charsetFor(mediaType)));
            }
        }

        CompletableFuture<ResponseInfo> info = new CompletableFuture<>();
        this.server = httpsServerForTest().addHandler(
            restHandler(new DogFather())
                .addCustomReader(new DogReader())
                .build())
            .addResponseCompleteListener(info::complete)
            .start();
        try (Response resp = call(request()
            .post(new RequestBody() {
                @Override
                public MediaType contentType() {
                    return MediaType.get("text/plain;charset=utf-8");
                }

                @Override
                public void writeTo(BufferedSink bufferedSink) throws IOException {
                    bufferedSink.write(body.getBytes(StandardCharsets.UTF_8));
                    bufferedSink.flush(); // force an HTTP chunk to be sent that will cause the body reader to read the bytes, but not have a complete request
                    bufferedSink.close();
                }
            })
            .url(server.uri().resolve("/api/dogs").toString())
        )) {
            assertThat(resp.body().string(), equalTo(body));
        }
        ResponseInfo ri = info.get(5, TimeUnit.SECONDS);
        assertThat(ri.completedSuccessfully(), Matchers.is(true));
    }

    @Test
    public void inputStreamsCanBeZipped() throws Exception {

        @Path("/zipper")
        class Zipper {
            @POST
            @Consumes({"application/octet-stream", "application/zip"})
            public String getIt(@Required InputStream requestBody) throws Exception {
                List<String> files = new ArrayList<>();
                try (ZipInputStream zis = new ZipInputStream(requestBody)) {
                    ZipEntry nextEntry;
                    while ((nextEntry = zis.getNextEntry()) != null) {
                        if (!nextEntry.isDirectory()) {
                            files.add(nextEntry.getName());
                            try (ByteArrayOutputStream fos = new ByteArrayOutputStream()) {
                                Mutils.copy(zis, fos, 8192);
                            }
                        }
                    }
                }
                requestBody.close();
                return files.stream().sorted().collect(Collectors.joining(", "));
            }
        }
        this.server = httpsServerForTest().addHandler(
                restHandler(new Zipper())
                    .build())
            .start();
        try (Response resp = call(request()
            .post(RequestBody.create(new File("src/test/resources/sample-static/maven.zip"), MediaType.get("application/zip")))
            .url(server.uri().resolve("/zipper").toString())
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo(".editorconfig, .gitignore, pom.xml, src/main/java/samples/App.java, src/main/resources/logback.xml, src/main/resources/web/index.html"));
        }
    }

    @Test
    public void itIsFineIfTheReaderClosesTheStream() throws Exception {
        String body = StringUtils.randomAsciiStringOfLength(68000);

        class Dog {
            public final String breed;
            Dog(String breed) {
                this.breed = breed;
            }
        }
        @Path("api")
        class DogFather {
            @POST
            @Path("dogs")
            public String dogs(Dog dog) {
                return dog.breed;
            }
        }
        @Consumes("text/plain")
        class DogReader implements MessageBodyReader<Dog> {
            @Override
            public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, javax.ws.rs.core.MediaType mediaType) {
                return type.equals(Dog.class);
            }
            @Override
            public Dog readFrom(Class<Dog> type, Type genericType, Annotation[] annotations, javax.ws.rs.core.MediaType mediaType, MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException, WebApplicationException {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                Mutils.copy(entityStream, baos, 8192);
                entityStream.close();
                return new Dog(baos.toString("utf-8"));
            }
        }

        CompletableFuture<ResponseInfo> info = new CompletableFuture<>();
        this.server = httpsServerForTest().addHandler(
            restHandler(new DogFather())
                .addCustomReader(new DogReader())
                .build())
            .addResponseCompleteListener(info::complete)
            .start();
        try (Response resp = call(request()
            .post(new RequestBody() {
                @Override
                public MediaType contentType() {
                    return MediaType.get("text/plain;charset=utf-8");
                }

                @Override
                public void writeTo(BufferedSink bufferedSink) throws IOException {
                    bufferedSink.write(body.getBytes(StandardCharsets.UTF_8));
                    bufferedSink.flush(); // force an HTTP chunk to be sent that will cause the body reader to read the bytes, but not have a complete request
                    bufferedSink.close();
                }
            })
            .url(server.uri().resolve("/api/dogs").toString())
        )) {
            assertThat(resp.body().string(), equalTo(body));
        }
        ResponseInfo ri = info.get(10, TimeUnit.SECONDS);
        assertThat(ri.completedSuccessfully(), Matchers.is(true));
    }

    private void startServer(Object restResource) {
        this.server = httpsServerForTest().addHandler(restHandler(restResource).build()).start();
    }

    @After
    public void stop() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}
