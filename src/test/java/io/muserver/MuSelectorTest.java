package io.muserver;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scaffolding.RawClient;

import java.io.IOException;
import java.net.URI;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class MuSelectorTest {
    private static final Logger log = LoggerFactory.getLogger(MuSelectorTest.class);

    @Test
    public void go() throws IOException, InterruptedException {
        MuSelector selector = new MuSelector();
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
}