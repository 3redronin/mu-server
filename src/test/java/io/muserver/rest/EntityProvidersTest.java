package io.muserver.rest;

import io.muserver.MuServer;
import io.muserver.MuServerBuilder;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.example.MyStringReaderWriter;
import org.junit.After;
import org.junit.Test;
import scaffolding.ClientUtils;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.GenericEntity;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.MessageBodyWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;

import static io.muserver.Mutils.NEWLINE;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static scaffolding.ClientUtils.call;
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
        stringCheck("text/plain", randomStringOfLength(128 * 1024), "text/plain", "/samples");
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

        this.server = MuServerBuilder.httpsServer().addHandler(
            RestHandlerBuilder.restHandler(new Sample())
                .addCustomReader(new MyStringReaderWriter())
                .addCustomWriter(new MyStringReaderWriter())
                .build()).start();
        try (Response resp = call(ClientUtils.request()
            .post(RequestBody.create(MediaType.parse("text/plain"), "hello world"))
            .url(server.uri().resolve("/samples").toString())
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("Content-Type"), equalTo("text/plain"));
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

        this.server = MuServerBuilder.httpsServer().addHandler(
            RestHandlerBuilder.restHandler(new Sample())
                .addCustomWriter(new DogWriter())
                .addCustomWriter(new DogListWriter())
                .build()).start();
        try (Response resp = call(ClientUtils.request()
            .post(RequestBody.create(MediaType.parse("text/plain"), "Little"))
            .url(server.uri().resolve("/dogs").toString())
        )) {
            assertThat(resp.body().string(), equalTo("Dog: Little (Unknown)"));
        }

        try (Response resp = call(ClientUtils.request().url(server.uri().resolve("/dogs/all").toString()))) {
            assertThat(resp.body().string(), equalTo("Little (Chihuahua)" + NEWLINE + "Mangle (Mongrel)" + NEWLINE));
        }
    }

    private void stringCheck(String requestBodyType, String content, String expectedResponseType, String requestPath) throws IOException {
        try (Response resp = call(ClientUtils.request()
            .post(RequestBody.create(MediaType.parse(requestBodyType), content))
            .url(server.uri().resolve(requestPath).toString())
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("Content-Type"), equalTo(expectedResponseType));
            assertThat(resp.body().string(), equalTo(content));
        }
    }

    private void startServer(Object restResource) {
        this.server = MuServerBuilder.httpsServer().addHandler(RestHandlerBuilder.restHandler(restResource).build()).start();
    }

    @After
    public void stop() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}