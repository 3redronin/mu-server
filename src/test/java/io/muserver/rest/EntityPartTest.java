package io.muserver.rest;

import io.muserver.MuServer;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.MessageBodyReader;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThrows;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class EntityPartTest {

    static {
        MuRuntimeDelegate.ensureSet();
    }

    private MuServer server;

    @Test
    public void runtimeDelegateBuildsEntityParts() throws Exception {
        EntityPart part = EntityPart.withName("message")
            .fileName("hello.txt")
            .mediaType(MediaType.TEXT_PLAIN_TYPE)
            .header("X-Test", "one", "two")
            .content(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)))
            .build();

        assertThat(part.getName(), is("message"));
        assertThat(part.getFileName().orElse(null), is("hello.txt"));
        assertThat(part.getMediaType(), is(MediaType.TEXT_PLAIN_TYPE));
        assertThat(part.getHeaders().get("X-Test"), contains("one", "two"));
        assertThat(new String(part.getContent().readAllBytes(), StandardCharsets.UTF_8), is("hello"));
        assertThrows(IllegalStateException.class, () -> part.getContent(String.class));
        assertThrows(UnsupportedOperationException.class,
            () -> part.getHeaders().put("Other", Arrays.asList("value")));
        assertThrows(UnsupportedOperationException.class,
            () -> part.getHeaders().get("X-Test").add("three"));
    }

    @Test
    public void entityPartBuildersRejectMissingAndInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> EntityPart.withName(null));
        assertThrows(IllegalArgumentException.class,
            () -> EntityPart.withName("part").content((ByteArrayInputStream) null));
        assertThrows(IllegalArgumentException.class,
            () -> EntityPart.withName("part").content((Object) null));
        assertThrows(IllegalStateException.class, () -> EntityPart.withName("part").build());
    }

    @Test
    public void readsMultipartBodiesAsEntityPartLists() throws Exception {
        @Path("/parts")
        class PartsResource {
            @POST
            @Consumes(MediaType.MULTIPART_FORM_DATA)
            public String post(List<EntityPart> parts) throws Exception {
                EntityPart description = parts.get(0);
                EntityPart attachment = parts.get(1);
                return description.getName() + "=" + description.getContent(String.class)
                    + "; " + attachment.getName() + "=" + attachment.getFileName().orElse(null)
                    + ":" + new String(attachment.getContent().readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new PartsResource()))
            .start();

        MultipartBody body = new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("description", "hello")
            .addFormDataPart("attachment", "hello.txt",
                RequestBody.create("file contents", okhttp3.MediaType.parse("text/plain")))
            .build();
        try (Response response = call(request(server.uri().resolve("/parts")).post(body))) {
            assertThat(response.code(), is(200));
            assertThat(response.body().string(), is("description=hello; attachment=hello.txt:file contents"));
        }
    }

    @Test
    public void injectsNamedEntityPartsAsFormParams() throws Exception {
        @Path("/parts")
        class PartsResource {
            @POST
            @Consumes(MediaType.MULTIPART_FORM_DATA)
            public String post(@FormParam("description") EntityPart description,
                               @FormParam("attachment") EntityPart attachment) throws Exception {
                return description.getContent(String.class)
                    + "; " + attachment.getFileName().orElse(null)
                    + ":" + new String(attachment.getContent().readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new PartsResource()))
            .start();

        MultipartBody body = new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("description", "hello")
            .addFormDataPart("attachment", "hello.txt",
                RequestBody.create("file contents", okhttp3.MediaType.parse("text/plain")))
            .build();
        try (Response response = call(request(server.uri().resolve("/parts")).post(body))) {
            assertThat(response.code(), is(200));
            assertThat(response.body().string(), is("hello; hello.txt:file contents"));
        }
    }

    @Test
    public void writesEntityPartListsAsMultipartBodies() throws Exception {
        @Path("/parts")
        class PartsResource {
            @GET
            @Produces(MediaType.MULTIPART_FORM_DATA)
            public GenericEntity<List<EntityPart>> get() throws Exception {
                List<EntityPart> parts = Arrays.asList(
                    EntityPart.withName("description").content("hello").build(),
                    EntityPart.withFileName("hello.txt")
                        .mediaType(MediaType.TEXT_PLAIN_TYPE)
                        .content(new ByteArrayInputStream("file contents".getBytes(StandardCharsets.UTF_8)))
                        .build()
                );
                return new GenericEntity<List<EntityPart>>(parts) {
                };
            }
        }

        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new PartsResource()))
            .start();

        try (Response response = call(request(server.uri().resolve("/parts")))) {
            assertThat(response.code(), is(200));
            assertThat(response.header("content-type"), startsWith("multipart/form-data;boundary="));
            String body = response.body().string();
            assertThat(body, containsString("Content-Disposition: form-data; name=\"description\""));
            assertThat(body, containsString("hello"));
            assertThat(body, containsString("Content-Disposition: form-data; name=\"hello.txt\"; filename=\"hello.txt\""));
            assertThat(body, containsString("file contents"));
        }
    }

    @Test
    public void writesAnyEntityPartCollectionAsMultipartBodies() throws Exception {
        @Path("/parts")
        class PartsResource {
            @GET
            @Produces(MediaType.MULTIPART_FORM_DATA)
            public GenericEntity<Collection<EntityPart>> get() throws Exception {
                Collection<EntityPart> parts = new LinkedHashSet<>();
                parts.add(EntityPart.withName("description").content("hello").build());
                return new GenericEntity<Collection<EntityPart>>(parts) {
                };
            }
        }

        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new PartsResource()))
            .start();

        try (Response response = call(request(server.uri().resolve("/parts")))) {
            assertThat(response.code(), is(200));
            assertThat(response.header("content-type"), startsWith("multipart/form-data;boundary="));
            assertThat(response.body().string(), containsString("hello"));
        }
    }

    @Test
    public void entityPartBuildersUseCustomMessageBodyWritersInResources() throws Exception {
        class Greeting {
            final String value;

            Greeting(String value) {
                this.value = value;
            }
        }
        MessageBodyWriter<Greeting> writer = new MessageBodyWriter<Greeting>() {
            @Override
            public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
                return type == Greeting.class;
            }

            @Override
            public void writeTo(Greeting greeting, Class<?> type, Type genericType, Annotation[] annotations,
                                MediaType mediaType, MultivaluedMap<String, Object> httpHeaders,
                                OutputStream entityStream) throws IOException {
                entityStream.write(("custom:" + greeting.value).getBytes(StandardCharsets.UTF_8));
            }
        };
        @Path("/parts")
        class PartsResource {
            @GET
            @Produces(MediaType.MULTIPART_FORM_DATA)
            public GenericEntity<List<EntityPart>> get() throws Exception {
                EntityPart part = EntityPart.withName("greeting")
                    .mediaType("application/x-greeting")
                    .content(new Greeting("hello"))
                    .build();
                return new GenericEntity<List<EntityPart>>(Arrays.asList(part)) {
                };
            }
        }

        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new PartsResource()).addCustomWriter(writer))
            .start();

        try (Response response = call(request(server.uri().resolve("/parts")))) {
            assertThat(response.code(), is(200));
            assertThat(response.body().string(), containsString("custom:hello"));
        }
    }

    @Test
    public void receivedEntityPartsUseCustomMessageBodyReaders() throws Exception {
        class Greeting {
            final String value;

            Greeting(String value) {
                this.value = value;
            }
        }
        MessageBodyReader<Greeting> reader = new MessageBodyReader<Greeting>() {
            @Override
            public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
                return type == Greeting.class;
            }

            @Override
            public Greeting readFrom(Class<Greeting> type, Type genericType, Annotation[] annotations,
                                     MediaType mediaType, MultivaluedMap<String, String> httpHeaders,
                                     InputStream entityStream) throws IOException {
                return new Greeting("custom:" + new String(entityStream.readAllBytes(), StandardCharsets.UTF_8));
            }
        };
        @Path("/parts")
        class PartsResource {
            @POST
            @Consumes(MediaType.MULTIPART_FORM_DATA)
            public String post(List<EntityPart> parts) throws Exception {
                return parts.get(0).getContent(Greeting.class).value;
            }
        }

        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new PartsResource()).addCustomReader(reader))
            .start();

        MultipartBody body = new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("greeting", null,
                RequestBody.create("hello", okhttp3.MediaType.parse("application/x-greeting")))
            .build();
        try (Response response = call(request(server.uri().resolve("/parts")).post(body))) {
            assertThat(response.code(), is(200));
            assertThat(response.body().string(), is("custom:hello"));
        }
    }

    @After
    public void stop() {
        MuAssert.stopAndCheck(server);
    }
}
