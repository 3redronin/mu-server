package io.muserver;

import org.junit.After;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class MuServer2Test {

    private static final Logger log = LoggerFactory.getLogger(MuServer2Test.class);
    private MuServer server;

    @Test
    public void canStart() throws Exception {
        server = MuServerBuilder.muServer()
            .withHttpsPort(10100)
            .addHandler(Method.GET, "/blah", (request, response, pathParams) -> response.write("Hello"))
            .start2();
        log.info("Started at " + server.uri());

        try (var resp = call(request(server.uri().resolve("/blah")))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("Hello"));
        }

    }

    @After
    public void stopIt() {
        if (server != null) {
            server.stop();
        }
    }

}