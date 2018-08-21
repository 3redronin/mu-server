package io.muserver;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scaffolding.RawClient;

import java.io.IOException;
import java.net.URI;

import static java.nio.charset.StandardCharsets.UTF_8;

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
}