package io.muserver;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scaffolding.RawClient;

import java.io.IOException;
import java.net.URI;

import static org.junit.Assert.*;

public class MuSelectorTest {
    private static final Logger log = LoggerFactory.getLogger(MuSelectorTest.class);

    @Test
    public void go() throws IOException, InterruptedException {
        MuSelector selector = new MuSelector();
        selector.start();

        RawClient client = RawClient.create(URI.create("http://localhost:" + selector.address.getPort()));

        client.sendUTF8("Hello, world");
        client.flushRequest();
        Thread.sleep(100);
        client.sendUTF8("This is something of a longer message");
        client.flushRequest();

        Thread.sleep(2000);

        System.out.println("Got back " + client.responseString());

        client.closeRequest();
        client.closeResponse();
    }
}