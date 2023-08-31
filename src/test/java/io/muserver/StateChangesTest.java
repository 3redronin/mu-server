package io.muserver;


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import scaffolding.Http1Client;
import scaffolding.MuAssert;

import javax.ws.rs.ClientErrorException;
import java.io.EOFException;
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
    private final List<ResponseInfo> completed = new ArrayList<>();

    @ParameterizedTest
    @ValueSource(strings = { "http", "https" })
    public void when_request_HEADERS_RECEIVED_and_response_NOTHING_action_READ_TIMEOUT_leadsTo_Connection_Dropped(String type) throws Exception {
        server = serverBuilder(type)
            .withRequestTimeout(500, TimeUnit.MILLISECONDS)
            .addHandler(Method.POST, "/read", (request, response, pp) -> {
                request.inputStream().get().read();
            })
            .addHandler(Method.POST, "/discard", (request, response, pp) -> {
            })
            .start();
        try (var client = POST("/read")
            .contentHeader("text/plain", 20)
            .flushHeaders()) {
            assertThrows(SocketException.class, () -> client.in().readAllBytes());
        }
        assertOneCompleted(RequestState.ERRORED, ResponseState.ERRORED);
        completed.clear();
        try (var client = POST("/discard")
            .contentHeader("text/plain", 20)
            .flushHeaders()) {
            assertThrows(SocketException.class, () -> client.in().readAllBytes());
        }
        assertOneCompleted(RequestState.ERRORED, ResponseState.ERRORED);
    }

    @ParameterizedTest
    @ValueSource(strings = { "http", "https" })
    public void when_request_HEADERS_RECEIVED_and_response_FULL_SENT_action_READ_TIMEOUT_leadsTo_Connection_Dropped(String type) throws Exception {
        server = serverBuilder(type)
            .withRequestTimeout(500, TimeUnit.MILLISECONDS)
            .addHandler(Method.POST, "/read", (request, response, pp) -> {
                response.write("hi");
                request.inputStream().get().read();
            })
            .addHandler(Method.POST, "/discard", (request, response, pp) -> {
                response.write("hi");
            })
            .start();
        try (var client = POST("/read")
            .contentHeader("text/plain", 20)
            .flushHeaders()) {
            assertThrows(SocketException.class, () -> client.in().readAllBytes());
        }
        assertOneCompleted(RequestState.ERRORED, ResponseState.FULL_SENT);
        completed.clear();
        try (var client = POST("/discard")
            .contentHeader("text/plain", 20)
            .flushHeaders()) {
            assertThrows(SocketException.class, () -> client.in().readAllBytes());
        }
        assertOneCompleted(RequestState.ERRORED, ResponseState.FULL_SENT);

    }

    @ParameterizedTest
    @ValueSource(strings = { "http", "https" })
    public void when_request_HEADERS_RECEIVED_and_response_NOTHING_action_CANCEL_then_errors_happen(String type) throws Exception {
        server = serverBuilder(type)
            .addHandler((request, response) -> {
                request.abort();
                return true;
            })
            .start();
        try (var client = POST("/")
            .contentHeader("text/plain", 20)
            .flushHeaders()) {
            assertThat(client.in().readAllBytes().length, equalTo(0));
        }
        assertOneCompleted(RequestState.ERRORED, ResponseState.ERRORED);
    }

    @ParameterizedTest
    @ValueSource(strings = { "http", "https" })
    public void when_request_COMPLETE_and_response_STREAMING_action_CANCEL_response_killed_early(String type) throws Exception {
        server = serverBuilder(type)
            .addHandler((request, response) -> {
                response.headers().set(HeaderNames.CONTENT_LENGTH, 20);
                response.sendChunk("Hello");
                request.abort();
                return true;
            })
            .start();
        try (var client = GET("/").flushHeaders()) {
            var headers = assert200(client);
            assertThrows(EOFException.class, () -> client.readBody(headers));
        }
        assertOneCompleted(RequestState.COMPLETE, ResponseState.ERRORED);
    }

    @ParameterizedTest
    @ValueSource(strings = { "http", "https" })
    public void when_request_HEADERS_RECEIVED_and_response_STREAMING_action_CANCEL_response_killed_early(String type) throws Exception {
        server = serverBuilder(type)
            .addHandler((request, response) -> {
                response.headers().set(HeaderNames.CONTENT_LENGTH, 20);
                response.sendChunk("Hello");
                request.abort();
                return true;
            })
            .start();
        try (var client = POST("/")
            .contentHeader("text/plain", 20)
            .flushHeaders()) {
            var headers = assert200(client);
            assertThrows(EOFException.class, () -> client.readBody(headers));
        }
        assertOneCompleted(RequestState.ERRORED, ResponseState.ERRORED);
    }

    private static Headers assert200(Http1Client client) throws IOException {
        assertThat(client.readLine(), equalTo("HTTP/1.1 200 OK"));
        return client.readHeaders();
    }

    @ParameterizedTest
    @ValueSource(strings = { "http", "https" })
    public void when_request_HEADERS_RECEIVED_and_response_NOTHING_exception_thrown_then_error_is_sent_to_client_and_connection_reusable(String type) throws Exception {
        server = serverBuilder(type)
            .addHandler((request, response) -> {
                AsyncHandle asyncHandle = request.handleAsync();
                asyncHandle.complete(new ClientErrorException("You are a bad client", 400));
                return true;
            })
            .start();

        try (var client = POST("/")
            .contentHeader("text/plain", 5)
            .flushHeaders()) {

            assertThat(client.readLine(), equalTo("HTTP/1.1 400 OK")); // TODO set the status message!!
            var headers = client.readHeaders();
            assertThat(headers.getAll("content-type"), contains("text/html;charset=utf-8"));
            assertThat(headers.getAll("connection"), empty());
            assertThat(client.readBody(headers), equalTo("<h1>400 Bad Request</h1><p>You are a bad client</p>"));

            client.out().write("hello".getBytes(StandardCharsets.UTF_8));
            client.out().flush();

            assertOneCompleted(RequestState.COMPLETE, ResponseState.FULL_SENT);
            assertThatClientCanStillSendRequests(client);
        }
    }

    private void assertOneCompleted(RequestState expectedRequestState, ResponseState expectedResponseState) {
        assertEventually(() -> completed, hasSize(1));
        ResponseInfo info = completed.get(0);
        assertThat(info.request().requestState(), equalTo(expectedRequestState));
        assertThat(info.response().responseState(), equalTo(expectedResponseState));
        var requestGood = expectedRequestState == RequestState.COMPLETE;
        var responseGood = expectedResponseState == ResponseState.FINISHED || expectedResponseState == ResponseState.FULL_SENT;
        assertThat(info.completedSuccessfully(), equalTo(requestGood && responseGood));
    }

    private MuServerBuilder serverBuilder(String type) {
        return muServer()
            .withHttpPort(type.equals("http") ? 0 : -1)
            .withHttpsPort(type.equals("https") ? 0 : -1)
            .addResponseCompleteListener(completed::add)
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.write("Hello"));
    }

    private Http1Client POST(String uri) {
        return Http1Client.connect(server)
            .writeRequestLine(Method.POST, uri);
    }
    private Http1Client GET(String uri) {
        return Http1Client.connect(server)
            .writeRequestLine(Method.GET, uri);
    }

    private void assertThatClientCanStillSendRequests(Http1Client client) throws IOException {
        client.writeRequestLine(Method.GET, "/hello").endHeaders().flush();
        var headers = assert200(client);
        assertThat(client.readBody(headers), equalTo("Hello"));
    }

    @AfterEach
    public void stopIt() {
        MuAssert.stopAndCheck(server);
    }
}
