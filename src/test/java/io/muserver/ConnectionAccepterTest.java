package io.muserver;

import okhttp3.Response;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scaffolding.RawClient;

import javax.net.ssl.SSLContext;
import java.net.URI;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class ConnectionAccepterTest {
    private static final Logger log = LoggerFactory.getLogger(ConnectionAccepterTest.class);

    @Test
    public void go() throws Exception {
        ConnectionAccepter selector = new ConnectionAccepter(null);
        selector.start();

        URI targetURI = URI.create("http://localhost:" + selector.address.getPort());
        RawClient client = RawClient.create(targetURI);

        String message = "Hello, world";

        client.sendStartLine("GET", "/something?aquery=what&huh");
        client.sendHeader("Host", targetURI.getAuthority());
        client.sendHeader("Content-Length", String.valueOf(message.getBytes(UTF_8).length));
        client.endHeaders();
        client.sendUTF8(message);
        client.flushRequest();

        Thread.sleep(2000);

        System.out.println("Got back " + client.responseString());

        client.closeRequest();
        client.closeResponse();
    }

    @Test
    public void keepAliveTest() {
        assertThat(MuSelector.keepAlive(HttpVersion.HTTP_1_0, new MuHeaders()), is(false));
        assertThat(MuSelector.keepAlive(HttpVersion.HTTP_1_1, new MuHeaders()), is(true));
        assertThat(MuSelector.keepAlive(HttpVersion.HTTP_1_0, new MuHeaders().set("Connection", "keep-alive")), is(true));
        assertThat(MuSelector.keepAlive(HttpVersion.HTTP_1_1, new MuHeaders().set("Connection", "Blah, close, Another")), is(false));
    }

    @Test
    public void worksWithOKHttpClient() throws Exception {
        SSLContext sslContext = SSLContextBuilder.unsignedLocalhostCert();
        ConnectionAccepter selector = new ConnectionAccepter(sslContext);
        try {
            selector.start();
            String targetURI = "https://localhost:" + selector.address.getPort();
            long start = System.currentTimeMillis();


            for (int i = 0; i < 1000; i++) {
                try (Response resp = call(request().url(targetURI))) {
                    assertThat(resp.code(), is(200));
                }
            }

            System.out.println("Call took " + (System.currentTimeMillis() - start) + "ms");
        } finally {
            selector.stop();
        }

    }

}