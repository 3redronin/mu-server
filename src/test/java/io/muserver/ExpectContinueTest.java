package io.muserver;

import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import scaffolding.Http1Client;
import scaffolding.MuAssert;
import scaffolding.StringUtils;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.assertEventually;
import static scaffolding.ServerUtils.testServer;

public class ExpectContinueTest {

    private MuServer server;

    @ParameterizedTest
    @ValueSource(strings = { "http", "https" })
    public void continueIsReturnedIfExpectIsSent(String type) throws IOException {
        server = testServer(type)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> response.write(request.readBodyAsString()))
            .start();

        String toSend = StringUtils.randomAsciiStringOfLength(1024);

        try (var rawClient = Http1Client.connect(server)) {
            rawClient.writeRequestLine(Method.GET, "/")
                .writeHeader("expect", "100-continue")
                .writeHeader("content-length", "1024")
                .flushHeaders();
            assertThat(rawClient.readLine(), equalTo("HTTP/1.1 100 Continue"));
            assertThat(rawClient.readLine(), equalTo(""));
            rawClient.writeAscii(toSend).flush();
            assertThat(rawClient.readLine(), equalTo("HTTP/1.1 200 OK"));
            assertThat(rawClient.readBody(rawClient.readHeaders()), equalTo(toSend));
        }

    }

    @ParameterizedTest
    @ValueSource(strings = { "http", "https" })
    public void ifAutoResponseIsOffThenItCanBeDoneInTheResponseHandler(String type) throws IOException, InterruptedException {
        server = testServer(type)
            .withAutoHandleExpectHeaders(false)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                if (request.headers().contains(HeaderNames.EXPECT, "100-continue", true)) {
                    response.sendInformationalResponse(HttpStatusCode.CONTINUE_100);
                }
                response.write(request.readBodyAsString());
            })
            .start();

        String toSend = StringUtils.randomAsciiStringOfLength(1024);

        try (var rawClient = Http1Client.connect(server)) {
            rawClient.writeRequestLine(Method.GET, "/")
                .writeHeader("expect", "100-continue")
                .writeHeader("content-length", "1024")
                .flushHeaders();

            assertThat(rawClient.readLine(), equalTo("HTTP/1.1 100 Continue"));
            assertThat(rawClient.readLine(), equalTo(""));
            rawClient.writeAscii(toSend).flush();
            assertThat(rawClient.readLine(), equalTo("HTTP/1.1 200 OK"));
            assertThat(rawClient.readBody(rawClient.readHeaders()), equalTo(toSend));
        }

    }

    @ParameterizedTest
    @ValueSource(strings = { "http", "https" })
    public void rejectionIsReturnedIfExpectIsSentAndItIsTooBig(String type) throws IOException, InterruptedException {
        server = testServer(type)
            .withMaxRequestSize(1000)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> response.write(request.readBodyAsString()))
            .start();

        try (var rawClient = Http1Client.connect(server)) {
            rawClient.writeRequestLine(Method.GET, "/")
                .writeHeader("expect", "100-continue")
                .writeHeader("content-length", "1024")
                .flushHeaders();

            assertThat(rawClient.readLine(), equalTo("HTTP/1.1 417 Expectation Failed"));
            assertThat(rawClient.readBody(rawClient.readHeaders()), containsString("Expectation Failed - request body too large"));

            // write the promised body so that it is a valid request
            rawClient.writeAscii("!".repeat(1024)).flush();
        }

    }

    @ParameterizedTest
    @ValueSource(strings = { "http", "https" })
    public void ifTheClientClosesConnectionAfterBeingRejectedItIsMarkedAsAFailedRequest(String type) throws IOException, InterruptedException {
        var theInfo = new AtomicReference<ResponseInfo>();
        server = testServer(type)
            .withMaxRequestSize(1000)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> response.write(request.readBodyAsString()))
            .addResponseCompleteListener(theInfo::set)
            .start();

        try (var rawClient = Http1Client.connect(server)) {
            rawClient.writeRequestLine(Method.GET, "/")
                .writeHeader("expect", "100-continue")
                .writeHeader("content-length", "1024")
                .flushHeaders();

            assertThat(rawClient.readLine(), equalTo("HTTP/1.1 417 Expectation Failed"));
        }

        assertEventually(theInfo::get, notNullValue());
        var info = theInfo.get();
        assertThat(info.completedSuccessfully(), equalTo(false));
    }

    @ParameterizedTest
    @ValueSource(strings = { "http", "https" })
    public void expectContinueWorksWithoutContentLength(String type) throws IOException {
        server = testServer(type)
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
