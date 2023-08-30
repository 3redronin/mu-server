package io.muserver;


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import scaffolding.Http1Client;
import scaffolding.MuAssert;

import javax.ws.rs.ClientErrorException;
import java.io.IOException;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.muserver.MuServerBuilder.muServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static scaffolding.MuAssert.assertEventually;

public class StateChangesTest {


    public static void main(String[] args) {

        var actions = List.of("READ_TIMEOUT", "WRITE_TIMEOUT", "REQUEST_COMPLETED", "RESPONSE_COMPLETED", "CANCEL");

        List<String> testNames = Arrays.stream(StateChangesTest.class.getMethods()).map(java.lang.reflect.Method::getName).toList();

        for (RequestState req : RequestState.values()) {
            for (ResponseState resp : ResponseState.values()) {
                for (String action : actions) {
                    var testPrefix = "when_request_" + req.name() + "_and_response_" + resp.name() + "_action_" + action + "_";
                    var existingTest = testNames.stream().filter(name -> name.startsWith(testPrefix)).findFirst();
                    var tickOrCross = existingTest.map(s -> "✅ " + s).orElseGet(() -> "❌ " + testPrefix);
                    System.out.println(tickOrCross);
                }
            }
        }
    }

    private MuServer server;

    @ParameterizedTest
    @ValueSource(strings = { "http", "https" })
    public void when_request_HEADERS_RECEIVED_and_response_NOTHING_action_READ_TIMEOUT_leadsTo_Connection_Dropped(String type) throws Exception {
        server = muServer()
            .withRequestTimeout(500, TimeUnit.MILLISECONDS)
            .withHttpPort(type.equals("http") ? 0 : -1)
            .withHttpsPort(type.equals("https") ? 0 : -1)
            .addHandler((request, response) -> {
                request.inputStream().get().read();
                return true;
            })
            .start();

        try (var client = Http1Client.connect(server)
            .writeRequestLine(Method.POST, "/")
            .writeHeader("content-length", 20)
            .endHeaders()
            .flush()) {
            assertThrows(SocketException.class, () -> client.in().readAllBytes());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "http", "https" })
    public void when_request_HEADERS_RECEIVED_and_response_NOTHING_exception_thrown_then_error_is_sent_to_client_and_connection_reusable(String type) throws Exception {
        var completed = new ArrayList<ResponseInfo>();
        server = muServer()
            .withHttpPort(type.equals("http") ? 0 : -1)
            .withHttpsPort(type.equals("https") ? 0 : -1)
            .addResponseCompleteListener(completed::add)
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.write("Hello"))
            .addHandler((request, response) -> {
                AsyncHandle asyncHandle = request.handleAsync();
                asyncHandle.complete(new ClientErrorException("You are a bad client", 400));
                return true;
            })
            .start();

        try (var client = Http1Client.connect(server)
            .writeRequestLine(Method.POST, "/")
            .writeHeader("content-length", 5)
            .writeHeader("content-type", "text/plain")
            .endHeaders()
            .flush()) {

            assertThat(client.readLine(), equalTo("HTTP/1.1 400 OK")); // TODO set the status message!!
            var headers = client.readHeaders();
            assertThat(headers.getAll("content-type"), contains("text/html;charset=utf-8"));
            assertThat(headers.getAll("connection"), empty());
            assertThat(client.readBody(headers), equalTo("<h1>400 Bad Request</h1><p>You are a bad client</p>"));

            client.out().write("hello".getBytes(StandardCharsets.UTF_8));
            client.out().flush();
            assertEventually(() -> completed, hasSize(1));
            var info = completed.get(0);
            assertThat(((MuRequestImpl)info.request()).requestState(), equalTo(RequestState.COMPLETE));
            assertThat(info.response().responseState(), equalTo(ResponseState.FULL_SENT));
            assertThatClientCanStillSendRequests(client);
        }
    }

    private void assertThatClientCanStillSendRequests(Http1Client client) throws IOException {
        client.writeRequestLine(Method.GET, "/hello").endHeaders().flush();
        assertThat(client.readLine(), equalTo("HTTP/1.1 200 OK"));
        assertThat(client.readBody(client.readHeaders()), equalTo("Hello"));
    }

    @AfterEach
    public void stopIt() {

        System.out.println("Stopping");
        MuAssert.stopAndCheck(server);
    }
}
