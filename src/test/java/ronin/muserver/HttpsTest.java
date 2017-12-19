package ronin.muserver;

import okhttp3.Response;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static ronin.muserver.MuServerBuilder.httpServer;
import static ronin.muserver.MuServerBuilder.httpsServer;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class HttpsTest {

    private MuServer server;

    @Test public void canCreate() throws IOException {
        server = httpServer()
            .withHttpsConnection(8443, SSLContextBuilder.unsignedLocalhostCert())
            .addHandler((request, response) -> {
                response.write("This is encrypted and the URL is " + request.uri());
                return true;
            })
            .start();

        Response resp = call(request().url(server.httpsUrl()));
        assertThat(resp.body().string(), equalTo("This is encrypted and the URL is https://localhost:8443/"));
    }

    @Test public void httpCanBeDisabled() {
        server = httpsServer()
            .withHttpDisabled()
            .addHandler((request, response) -> {
                response.write("This is encrypted");
                return true;
            })
            .start();

        assertThat(server.uri(), is(nullValue()));
    }

    @After public void stopIt() {
        server.stop();
    }
}
