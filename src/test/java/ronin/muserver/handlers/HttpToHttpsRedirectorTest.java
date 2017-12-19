package ronin.muserver.handlers;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import ronin.muserver.*;

import java.io.IOException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static ronin.muserver.MuServerBuilder.httpServer;
import static ronin.muserver.MuServerBuilder.muServer;
import static scaffolding.ClientUtils.*;

public class HttpToHttpsRedirectorTest {
    private MuServer server;
    private OkHttpClient client;

    @Before
    public void setupClient() {
        client = new OkHttpClient.Builder()
            .hostnameVerifier((hostname, session) -> true)
            .sslSocketFactory(sslContextForTesting(veryTrustingTrustManager).getSocketFactory(), veryTrustingTrustManager)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();
    }

    @Test
    public void httpRequestsAreRedirectedToTheHttpsUrl() throws IOException {
        server = muServer()
            .withHttpConnection(11080)
            .withHttpsConnection(11443, SSLContextBuilder.unsignedLocalhostCert())
            .addAsyncHandler(new HttpToHttpsRedirector(11443))
            .addHandler(Method.GET, "/blah", (request, response) -> {
                response.write("Hello from " + request.uri());
                return false;
            }).start();

        Request.Builder request = request()
            .url(server.uri().resolve("/blah?ha=yo").toURL());
        Response resp = client.newCall(request.build()).execute();
        assertThat(resp.code(), is(302));
        assertThat(resp.header("Location"), is("https://localhost:11443/blah?ha=yo"));
    }



    @Test
    public void relativeRedirectsGetConvertedToAbsoluteOnes() throws Exception {
        // this test doesn't belong here, but hey, the client was handy
        server = httpServer()
            .addHandler((request, response) -> {
                response.redirect("#hashish");
                return true;
            }).start();

        Response resp = client.newCall(request().url(server.uri().resolve("/some?value=hey").toURL()).build()).execute();
        assertThat(resp.code(), is(302));
        assertThat(resp.header("Location"), is(server.uri().resolve("/some?value=hey").toString() + "#hashish"));

    }

    @After
    public void stopIt() {
        if (server != null) {
            server.stop();
        }
    }

}