package io.muserver;

import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import scaffolding.MuAssert;
import scaffolding.RawClient;
import scaffolding.ServerUtils;
import scaffolding.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static io.muserver.MuServerBuilder.httpServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.assertEventually;

public class ExpectContinueTest {

    private MuServer server;

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    public void continueIsReturnedIfExpectIsSent() throws IOException {
        server = httpServer()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> response.write(request.readBodyAsString()))
            .start();

        String toSend = StringUtils.randomAsciiStringOfLength(1024);

        try (RawClient rawClient = RawClient.create(server.uri())) {
            rawClient.sendStartLine("GET", "/")
                .sendHeader("host", server.uri().getAuthority())
                .sendHeader("expect", "100-continue")
                .sendHeader("content-length", "1024")
                .endHeaders()
                .flushRequest();

            assertEventually(rawClient::responseString, containsString("HTTP/1.1 100 Continue\r\n"));

            rawClient
                .sendUTF8(toSend)
                .flushRequest();

            assertEventually(rawClient::responseString, startsWith("HTTP/1.1 100 Continue\r\n" +
                "\r\nHTTP/1.1 200 OK\r\n"));
            assertEventually(rawClient::responseString, endsWith(toSend));
        }

    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    public void ifAutoHandlingIsOffThenItCanBeManual() throws IOException {
        server = httpServer()
            .withAutoHandleExpectContinue(false)
            .addHandler(Method.POST, "/", (request, response, pathParams) -> {
                response.sendInformationalResponse(HttpStatus.CONTINUE_100, null);
                response.write(request.readBodyAsString());
            })
            .start();

        String toSend = StringUtils.randomAsciiStringOfLength(1024);

        try (RawClient rawClient = RawClient.create(server.uri())) {
            rawClient.sendStartLine("POST", "/")
                .sendHeader("host", server.uri().getAuthority())
                .sendHeader("expect", "100-continue")
                .sendHeader("content-length", "1024")
                .endHeaders()
                .flushRequest();

            assertEventually(rawClient::responseString, containsString("HTTP/1.1 100 Continue\r\n"));

            rawClient
                .sendUTF8(toSend)
                .flushRequest();

            assertEventually(rawClient::responseString, startsWith("HTTP/1.1 100 Continue\r\n" +
                "\r\nHTTP/1.1 200 OK\r\n"));
            assertEventually(rawClient::responseString, endsWith(toSend));
        }

    }


    @Test
    @Timeout(value = 20)
    public void a413IsReturnedIfExpectationFails() throws IOException {
        var received = new ArrayList<ResponseInfo>();
        server = httpServer()
            .withMaxRequestSize(1023)
            .addResponseCompleteListener(received::add)
            .start();


        try (RawClient rawClient = RawClient.create(server.uri())) {
            rawClient.sendStartLine("POST", "/")
                .sendHeader("host", server.uri().getAuthority())
                .sendHeader("expect", "100-continue")
                .sendHeader("content-length", "1024")
                .endHeaders()
                .flushRequest();


            assertEventually(rawClient::responseString, startsWith("HTTP/1.1 413 Content Too Large\r\n"));

            String toSend = StringUtils.randomAsciiStringOfLength(1024);
            rawClient
                .sendUTF8(toSend)
                .flushRequest();
        }
        assertEventually(received::size, equalTo(1));
        assertThat(received.get(0).completedSuccessfully(), is(false));
        assertThat(received.get(0).response().statusValue(), is(HttpStatus.CONTENT_TOO_LARGE_413));
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    public void a413IsReturnedIfExpectationFailsAndSuccessIsFalseIfClientAborts() throws IOException {
        var received = new ArrayList<ResponseInfo>();
        server = httpServer()
            .withMaxRequestSize(1023)
            .addResponseCompleteListener(received::add)
            .start();


        try (RawClient rawClient = RawClient.create(server.uri())) {
            rawClient.sendStartLine("POST", "/")
                .sendHeader("host", server.uri().getAuthority())
                .sendHeader("expect", "100-continue")
                .sendHeader("content-length", "1024")
                .endHeaders()
                .flushRequest();


            assertEventually(rawClient::responseString, startsWith("HTTP/1.1 413 Content Too Large\r\n"));
        } // close the connection - the request was closed early
        assertEventually(received::size, equalTo(1));
        assertThat(received.get(0).completedSuccessfully(), is(false));
        assertThat(received.get(0).response().statusValue(), is(HttpStatus.CONTENT_TOO_LARGE_413));
    }


    @Test
    public void expectContinueWorksOverHttpsWithoutContentLength() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> response.write("Hi there"))
            .start();

        try (Response resp = call(request(server.uri()).header("expect", "100-continue"))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("Hi there"));
        }

    }


    @AfterEach
    public void cleanup() {
        MuAssert.stopAndCheck(server);
    }

}
