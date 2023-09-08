package io.muserver;


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import scaffolding.Http1Client;
import scaffolding.MuAssert;

import java.util.ArrayList;
import java.util.List;

import static io.muserver.MuServerBuilder.muServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class InvalidRequestsTest {

    private MuServer server;
    private final List<ResponseInfo> completed = new ArrayList<>();


    @ParameterizedTest
    @ValueSource(strings = { "http_GET", "https_GET", "http_HEAD", "https_HEAD", "http_POST", "https_POST" })
    public void a400IsReturnedWhenAUriIsInvalid(String type) throws Exception {
        var method = Method.valueOf(type.split("_")[1]);
        server = serverBuilder(type.split("_")[0]).start();
        String expectedBody = "400 Bad Request - Invalid URI";
        try (var client = Http1Client.connect(server)
            .writeAscii(method + " /<blah>/ HTTP/1.1\r\n")
            .writeHeader("host", server.uri().getAuthority())
            .writeHeader("content-length", method == Method.POST ? "5" : "0")
            .flushHeaders()) {
            assertThat(client.readLine(), equalTo("HTTP/1.1 400 Bad Request"));
            if (method == Method.POST) {
                client.writeAscii("Hello");
            }
            var headers = client.readHeaders();
            if (method == Method.HEAD) {
                assertThat(client.available(), equalTo(0));
            } else {
                var body = client.readBody(headers);
                assertThat(body, equalTo(expectedBody));
            }
            assertThat(headers.getAll("connection"), contains("close"));
            assertThat(headers.getAll("content-length"), contains(String.valueOf(expectedBody.length())));
            assertThat(headers.getAll("content-type"), contains("text/plain;charset=utf-8"));
            assertThat(completed, empty()); // because it can't even start to be a request
            assertThat(server.stats().invalidHttpRequests(), equalTo(1L));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "http_GET", "https_GET", "http_HEAD", "https_HEAD", "http_POST", "https_POST" })
    public void a414IsReturnedWhenAUriIsTooLong(String type) throws Exception {
        var method = Method.valueOf(type.split("_")[1]);
        server = serverBuilder(type.split("_")[0])
            .withMaxUrlSize(30)
            .start();
        String expectedBody = "414 URI Too Long - URI Too Long";
        try (var client = newRequest(method, "/0123456789012345678901234567890")
            .writeHeader("content-length", method == Method.POST ? "5" : "0")
            .flushHeaders()) {
            assertThat(client.readLine(), equalTo("HTTP/1.1 414 URI Too Long"));
            if (method == Method.POST) {
                client.writeAscii("Hello");
            }
            var headers = client.readHeaders();
            if (method == Method.HEAD) {
                assertThat(client.available(), equalTo(0));
            } else {
                var body = client.readBody(headers);
                assertThat(body, equalTo(expectedBody));
            }
            assertThat(headers.getAll("connection"), contains("close"));
            assertThat(headers.getAll("content-length"), contains(String.valueOf(expectedBody.length())));
            assertThat(headers.getAll("content-type"), contains("text/plain;charset=utf-8"));
            assertThat(completed, empty()); // because it can't even start to be a request
            assertThat(server.stats().invalidHttpRequests(), equalTo(1L));
        }
    }

    private Http1Client newRequest(Method method, String uri) {
        return Http1Client.connect(server)
            .writeRequestLine(method, uri)
            .writeHeader("host", server.uri().getAuthority());
    }

    private MuServerBuilder serverBuilder(String type) {
        return muServer()
            .withHttpPort(type.equals("http") ? 0 : -1)
            .withHttpsPort(type.equals("https") ? 0 : -1)
            .addResponseCompleteListener(completed::add)
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.write("Hello"));
    }

    @AfterEach
    public void stopIt() {
        MuAssert.stopAndCheck(server);
    }
}
