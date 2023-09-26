package io.muserver;


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import scaffolding.Http1Client;
import scaffolding.MuAssert;

import static java.util.Collections.emptyList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static scaffolding.ServerUtils.testServer;

public class InformationalResponseTest {

    private MuServer server;

    @ParameterizedTest
    @ValueSource(strings = { "http", "https" })
    public void ifNoHeadersSetThenJustResponseLineSentAsInformational(String type) throws Exception {
        server = testServer(type)
            .addHandler((request, response) -> {
                response.sendInformationalResponse(HttpStatusCode.PROCESSING_102);
                response.headers().set("something", "hi");
                response.write("Hello");
                return true;
            })
            .start();
        try (var client = Http1Client.connect(server)
            .writeAscii( "GET /blah/ HTTP/1.1\r\n")
            .writeHeader("host", server.uri().getAuthority())
            .flushHeaders()) {
            assertThat(client.readLine(), equalTo("HTTP/1.1 102 Processing"));
            assertThat(client.readLine(), equalTo(""));
            assertThat(client.readLine(), equalTo("HTTP/1.1 200 OK"));
            Headers headers = client.readHeaders();
            assertThat(headers.contains(HeaderNames.DATE), equalTo(true));
            assertThat(headers.getAll("something"), contains("hi"));
            assertThat(headers.getAll("content-length"), contains("5"));
            assertThat(headers.getAll("content-type"), contains("text/plain;charset=utf-8"));
            assertThat(headers.getAll("vary"), contains("accept-encoding"));
            assertThat(headers.toString(emptyList()), headers.size(), equalTo(5));
            assertThat(client.readBody(headers), equalTo("Hello"));
        }
    }


    @ParameterizedTest
    @ValueSource(strings = { "http", "https" })
    public void headersSetBeforeSendingAreReturnedInTheInformationalResponsePortion(String type) throws Exception {
        server = testServer(type)
            .addHandler((request, response) -> {
                response.headers().set("link", "</styles.css>; rel=preload");
                response.sendInformationalResponse(HttpStatusCode.EARLY_HINTS_103);
                response.headers().set("something", "hi");
                response.contentType(ContentTypes.TEXT_HTML_UTF8);
                response.write("Hello");
                return true;
            })
            .start();
        try (var client = Http1Client.connect(server)
            .writeAscii( "GET /blah/ HTTP/1.1\r\n")
            .writeHeader("host", server.uri().getAuthority())
            .flushHeaders()) {
            assertThat(client.readLine(), equalTo("HTTP/1.1 103 Early Hints"));
            assertThat(client.readLine(), equalTo("link: </styles.css>; rel=preload"));
            assertThat(client.readLine(), equalTo(""));
            assertThat(client.readLine(), equalTo("HTTP/1.1 200 OK"));
            Headers headers = client.readHeaders();
            assertThat(headers.contains(HeaderNames.DATE), equalTo(true));
            assertThat(headers.getAll("something"), contains("hi"));
            assertThat(headers.getAll("content-length"), contains("5"));
            assertThat(headers.getAll("content-type"), contains("text/html;charset=utf-8"));
            assertThat(headers.getAll("vary"), contains("accept-encoding"));
            assertThat(headers.toString(emptyList()), headers.size(), equalTo(5));
            assertThat(client.readBody(headers), equalTo("Hello"));
        }
    }


    @AfterEach
    public void stopIt() {
        MuAssert.stopAndCheck(server);
    }
}
