package io.muserver;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scaffolding.Http1Client;
import scaffolding.MuAssert;

import java.net.Socket;

import static io.muserver.MuServerBuilder.httpServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

class Http1AbsoluteFormAuthorityTest {

    private @Nullable MuServer server;

    @Test
    void absoluteFormRequestTargetAuthorityTakesPrecedenceOverHost() throws Exception {
        server = httpServer()
            .addHandler(Method.GET, "/absolute-form-authority", (request, response, pathParams) -> {
                response.contentType(ContentTypes.TEXT_PLAIN_UTF8);
                response.write(request.uri().getAuthority());
            })
            .start();

        String target = "http://trusted.example/absolute-form-authority";
        assertThat(requestAuthority(target, "attacker.example"), equalTo("trusted.example"));
        assertThat(requestAuthority(target, "trusted.example"), equalTo("trusted.example"));
    }

    private String requestAuthority(String target, String host) throws Exception {
        try (var socket = new Socket("127.0.0.1", server.uri().getPort())) {
            socket.setSoTimeout(3000);
            var client = new Http1Client(socket, socket.getInputStream(), socket.getOutputStream(), server.uri());
            client.writeAscii("GET " + target + " HTTP/1.1\r\n")
                .writeHeader("Host", host)
                .writeHeader("Connection", "close")
                .endHeaders()
                .flush();
            assertThat(client.readLine(), startsWith("HTTP/1.1 200 "));
            return client.readBody(client.readHeaders());
        }
    }

    @AfterEach
    void stopServer() {
        MuAssert.stopAndCheck(server);
    }
}
