package io.muserver.rest;

import io.muserver.MuServer;
import io.muserver.Mutils;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.example.MyStringReaderWriter;
import org.junit.After;
import org.junit.Test;

import javax.ws.rs.*;
import javax.ws.rs.core.GenericEntity;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.MessageBodyWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;

import static io.muserver.Mutils.NEWLINE;
import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.ServerUtils.httpsServerForTest;
import static scaffolding.StringUtils.randomStringOfLength;

public class EntityProvidersTest {

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
                return new Dog(new String(Mutils.toByteArray(entityStream, 2048), EntityProviders.charsetFor(mediaType)));
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

    private void startServer(Object restResource) {
        this.server = httpsServerForTest().addHandler(restHandler(restResource).build()).start();
    }

    @After
    public void stop() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}