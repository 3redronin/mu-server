package ronin.muserver;

import okhttp3.Response;
import org.junit.After;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static ronin.muserver.MuServerBuilder.httpsServer;
import static ronin.muserver.MuServerBuilder.muServer;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class HttpsTest {

    private MuServer server;


    @Test
    public void canCreate() throws Exception {
        server = muServer()
            .withHttpsConnection(8443, SSLContextBuilder.unsignedLocalhostCert())
            .addHandler((request, response) -> {
                response.write("This is encrypted");
                return true;
            })
            .start();

        Response resp = call(request().url(server.httpsUrl()));
        assertThat(resp.body().string(), equalTo("This is encrypted"));
    }

    @Test
    public void httpCanBeDisabled() {
        server = httpsServer()
            .withHttpDisabled()
            .addHandler((request, response) -> {
                response.write("This is encrypted");
                return true;
            })
            .start();

        assertThat(server.uri(), is(nullValue()));
    }


    @After
    public void stopIt() {
        if (server != null) {
            server.stop();
        }
    }
}
