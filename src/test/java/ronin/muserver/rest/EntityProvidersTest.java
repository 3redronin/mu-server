package ronin.muserver.rest;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import ronin.muserver.MuServer;
import scaffolding.ClientUtils;

import javax.ws.rs.POST;
import javax.ws.rs.Path;

import java.io.IOException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static ronin.muserver.MuServerBuilder.httpsServer;
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

    private void stringCheck(String requestBodyType, String content, String expectedResponseType, String requestPath) throws IOException {
        Response resp = call(ClientUtils.request()
            .post(RequestBody.create(MediaType.parse(requestBodyType), content))
            .url(server.uri().resolve(requestPath).toString())
        );
        assertThat(resp.code(), equalTo(200));
        assertThat(resp.header("Content-Type"), equalTo(expectedResponseType));
        assertThat(resp.body().string(), equalTo(content));
    }

    private void startServer(Object restResource) {
        this.server = httpsServer().addHandler(new RestHandler(restResource)).start();
    }

    @After
    public void stop() {
        if (server != null) server.stop();
    }

}