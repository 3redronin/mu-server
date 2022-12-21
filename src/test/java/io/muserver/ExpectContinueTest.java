package io.muserver;

import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.MuAssert;
import scaffolding.RawClient;
import scaffolding.ServerUtils;
import scaffolding.StringUtils;

import java.io.IOException;

import static io.muserver.MuServerBuilder.httpServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.assertEventually;

public class ExpectContinueTest {

    private MuServer server;

    @Test(timeout = 20000)
    public void continueIsReturnedIfExpectIsSent() throws IOException, InterruptedException {
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
    public void expectContinueWorksOverHttpsWithoutContentLength() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> response.write("Hi there"))
            .start();

        try (Response resp = call(request(server.uri()).header("expect", "100-continue"))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("Hi there"));
        }

    }


    @After
    public void cleanup() {
        MuAssert.stopAndCheck(server);
    }

}
