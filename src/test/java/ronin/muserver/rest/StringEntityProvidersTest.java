package ronin.muserver.rest;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import ronin.muserver.MuServer;
import scaffolding.StringUtils;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import java.io.IOException;
import java.io.Reader;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static ronin.muserver.MuServerBuilder.httpsServer;
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
        startServer(new Sample());
        check(StringUtils.randomStringOfLength(64 * 1024));
        checkNoBody();
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
        startServer(new Sample());
        check(StringUtils.randomStringOfLength(64 * 1024));
        checkNoBody();
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
        startServer(new Sample());
        check(StringUtils.randomStringOfLength(64 * 1024));
        checkNoBody();
    }


    private void check(String value) throws IOException {
        check(value, "text/plain");
    }

    private void check(String value, String mimeType) throws IOException {
        try (Response resp = call(
            request()
                .post(RequestBody.create(MediaType.parse(mimeType), value))
                .url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("Content-Type"), equalTo(mimeType));
            assertThat(resp.body().string(), equalTo(value));
        }
    }

    private void checkNoBody() throws IOException {
        try (Response resp = call(request()
            .post(RequestBody.create(MediaType.parse("text/plain"), ""))
            .url(server.uri().resolve("/samples").toString())
        )) {
            assertThat(resp.code(), equalTo(400));
            assertThat(resp.body().string(), equalTo("400 Bad Request"));
        }

    }


    private void startServer(Object restResource) {
        this.server = httpsServer().addHandler(new RestHandler(restResource)).start();
    }

    @After
    public void stop() {
        if (server != null) server.stop();
    }

}