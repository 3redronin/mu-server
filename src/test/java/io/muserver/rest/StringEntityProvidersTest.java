package io.muserver.rest;

import io.muserver.MuServer;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.StringUtils;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MultivaluedMap;
import java.io.IOException;
import java.io.Reader;

import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class StringEntityProvidersTest {
    private MuServer server;

    @Test
    public void stringsSupported() throws Exception {
        @Path("samples")
        class Sample {
            @POST
            @Produces("text/plain")
            public String echo(String value) {
                return value;
            }
        }
        this.server = httpsServer().addHandler(restHandler(new Sample())).start();
        check(StringUtils.randomStringOfLength(64 * 1024));
        check("");
    }

    @Test
    public void charArraysSupported() throws Exception {
        @Path("samples")
        class Sample {
            @POST
            @Produces("text/plain")
            public char[] echo(char[] value) {
                return value;
            }
        }
        this.server = httpsServer().addHandler(restHandler(new Sample())).start();
        check(StringUtils.randomStringOfLength(64 * 1024));
        check("");
    }

    @Test
    public void readersSupported() throws Exception {
        @Path("samples")
        class Sample {
            @POST
            @Produces("text/plain")
            public String echo(Reader reader) throws IOException {
                StringBuilder sb = new StringBuilder();
                char[] buffer = new char[8192];
                int read;
                while ((read = reader.read(buffer)) > -1) {
                    sb.append(buffer, 0, read);
                }
                return sb.toString();
            }
        }
        this.server = httpsServer().addHandler(restHandler(new Sample())).start();
        check(StringUtils.randomStringOfLength(64 * 1024));
        check("");
    }

    @Test
    public void formParametersCanBeExtractedWithAMapParam() throws IOException {
        @Path("samples")
        class Sample {

            @POST
            @Produces("text/plain")
            public String printValues(MultivaluedMap<String, String> form) {
                StringBuilder sb = new StringBuilder();
                for (String key : form.keySet().stream().sorted().collect(toList())) {
                    sb.append("Got ").append(key).append(" = ").append(form.get(key)).append(" - ");
                }
                return sb.toString();
            }

            @POST
            @Produces("application/x-www-form-urlencoded")
            public MultivaluedMap<String, String> echo(MultivaluedMap<String, String> form) {
                return form;
            }
        }
        this.server = httpsServer().addHandler(restHandler(new Sample())).start();
        try (Response resp = call(
            request()
                .url(server.uri().resolve("/samples").toString())
                .addHeader("Accept", "text/plain")
                .post(new FormBody.Builder()
                    .add("Blah", "hello 1")
                    .add("Blah", "hello 2")
                    .add("Umm", "umm what?\"\\/:")
                    .build())
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("Got Blah = [hello 1, hello 2] - Got Umm = [umm what?\"\\/:] - "));
        }
        try (Response resp = call(
            request()
                .url(server.uri().resolve("/samples").toString())
                .addHeader("Accept", "application/x-www-form-urlencoded")
                .post(new FormBody.Builder()
                    .add("Blah", "hello 1")
                    .add("Blah", "hello 2")
                    .add("Umm", "umm what?\"\\/:")
                    .build())
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("Content-Type"), equalTo("application/x-www-form-urlencoded"));
            assertThat(resp.body().string(), equalTo("Umm=umm%20what%3F%22%5C%2F%3A&Blah=hello%201&Blah=hello%202"));
        }

    }

    private void check(String value) throws IOException {
        check(value, "text/plain");
    }

    private void check(String value, String mimeType) throws IOException {
        try (Response resp = call(
            request(server.uri().resolve("/samples"))
                .post(RequestBody.create(MediaType.parse(mimeType), value))
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("Content-Type"), equalTo(mimeType));
            assertThat(resp.body().string(), equalTo(value));
        }
    }

    @After
    public void stop() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}