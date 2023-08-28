package io.muserver;


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import scaffolding.Http1Client;
import scaffolding.MuAssert;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.muserver.MuServerBuilder.muServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class StateChangesTest {


    public static void main(String[] args) {

        var actions = List.of("read timeout", "write timeout", "request completed", "response completed", "cancel");

        for (RequestState req : RequestState.values()) {
            for (ResponseState resp : ResponseState.values()) {
                for (String action : actions) {
                    System.out.println(req.name() + '\t' + resp.name() + '\t' + action);
                }
            }
        }
    }

    private MuServer server;

    @ParameterizedTest
    @ValueSource(strings = { "http" })
//    @ValueSource(strings = { "http", "https" })
    public void when_request_HEADERS_RECEIVED_and_response_NOTHING_action_READ_TIMEOUT_leadsTo(String type) throws Exception {
        server = muServer()
            .withRequestTimeout(500, TimeUnit.MILLISECONDS)
            .withHttpPort(type.equals("http") ? 0 : -1)
            .withHttpsPort(type.equals("https") ? 0 : -1)
            .addHandler(new MuHandler() {
                @Override
                public boolean handle(MuRequest request, MuResponse response) throws Exception {
                    int read = request.inputStream().get().read();
                    response.write("Hi " + read);
                    return true;
                }
            })
            .start();


        try (var client = Http1Client.connect(server.uri())
            .writeRequestLine(Method.POST, server.uri().resolve("/"))
            .writeHeader("content-length", 20)
            .endHeaders()
            .flush()) {
            var resp = new String(client.in().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(resp, equalTo("Hello"));
        }
    }

    private HttpURLConnection request(URI requestUri) throws IOException {
        var conn = (HttpURLConnection) requestUri.toURL().openConnection();
        if (conn instanceof HttpsURLConnection httpsOne) {
            httpsOne.setHostnameVerifier((hostname, session) -> true); // disable the client side handshake verification
        }
        return conn;
    }

    @AfterEach
    public void stopIt() {

        System.out.println("Stopping");
        MuAssert.stopAndCheck(server);
    }
}
