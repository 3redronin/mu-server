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

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.ext.MessageBodyWriter;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

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
        stringCheck("text/plain", randomStringOfLength(128 * 1024), "application/octet-stream", "/samples");
    }
    @Test
    public void customConvertersComeBeforeBuiltInOnes() throws Exception {
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
        String content = "hello world";
        try (Response resp = call(ClientUtils.request()
            .post(RequestBody.create(MediaType.parse("text/plain"), content))
            .url(server.uri().resolve("/samples").toString())
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("Content-Type"), equalTo("application/octet-stream"));
            assertThat(resp.body().string(), equalTo("--HELLO WORLD--"));
        }
    }

    @Test
    public void canFigureOutGenericTypes() {
        for (MessageBodyWriter messageBodyWriter : EntityProviders.builtInWriters()) {

            System.out.println("messageBodyWriter = " + messageBodyWriter);

            Class<? extends MessageBodyWriter> writerClass = messageBodyWriter.getClass();
            for (Type type : writerClass.getGenericInterfaces()) {

                if (type instanceof ParameterizedType) {
                    ParameterizedType pt = (ParameterizedType) type;
                    if (pt.getRawType().equals(MessageBodyWriter.class)) {
                        Type genericType = pt.getActualTypeArguments()[0];
                        System.out.println("genericType = " + genericType + " (" + genericType.getTypeName() + " - " + genericType.getClass().getName());
                    }
                }
            }

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
        this.server = MuServerBuilder.httpsServer().addHandler(RestHandlerBuilder.create(restResource)).start();
    }

    @After
    public void stop() {
        if (server != null) server.stop();
    }

}