package io.muserver;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MuServer2Test {

    private static final Logger log = LoggerFactory.getLogger(MuServer2Test.class);

    @Test
    public void canStart() throws Exception {
        var server = MuServerBuilder.muServer()
            .withHttpsPort(10100)
            .start2();
        log.info("Started at " + server.uri());

        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            server.stop();
        }
    }

}