package io.muserver;

import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scaffolding.MuAssert;
import scaffolding.RawClient;
import scaffolding.ServerUtils;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;

import static io.muserver.MuServerBuilder.httpServer;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.assertEventually;

public class RequestQueryTest {

    private MuServer server;

    @Test
    public void queryStringsCanBeGot() throws MalformedURLException {
        Object[] actual = new Object[4];
        server = ServerUtils.httpsServerForTest().addHandler((request, response) -> {
            actual[0] = request.query().get("value1");
            actual[1] = request.query().get("value2");
            actual[2] = request.query().get("unspecified");
            actual[3] = request.uri().getPath();
            return true;
        }).start();

        try (Response ignored = call(request().url(http("/something/here.html?value1=something&value1=somethingAgain&value2=something%20else+i+think")))) {
        }
        assertThat(actual[0], equalTo("something"));
        assertThat(actual[1], equalTo("something else i think"));
        assertThat(actual[2], is(nullValue()));
        assertThat(actual[3], equalTo("/something/here.html"));
    }

    @Test
    public void queryStringParametersCanAppearMultipleTimes() throws MalformedURLException {
        Object[] actual = new Object[3];
        server = ServerUtils.httpsServerForTest().addHandler((request, response) -> {
            actual[0] = request.query().getAll("value1");
            actual[1] = request.query().getAll("value2");
            actual[2] = request.query().getAll("unspecified");
            return true;
        }).start();

        try (Response ignored = call(request().url(http("/something/here.html?value1=something&value1=somethingAgain&value2=something%20else+i+think")))) {
        }
        assertThat(actual[0], equalTo(asList("something", "somethingAgain")));
        assertThat(actual[1], equalTo(asList("something else i think")));
        assertThat(actual[2], equalTo(Collections.<String>emptyList()));
    }


    @Test
    public void charactersAreDecodedInPaths() throws Exception {
        server = httpServer()
            .addHandler((request, response) -> {
                response.write("|path=" + request.uri().getPath() + "|\n" +
                    "|rawPath=" + request.uri().getRawPath() + "|\n" +
                    "|serverPath=" + request.serverURI().getPath() + "|\n" +
                    "|serverRawPath=" + request.serverURI().getRawPath() + "|\n");
                return true;
            }).start();
        String r;
        try (RawClient client = RawClient.create(server.uri())) {
            client.sendStartLine("GET", "/a%20space/a+plus?a%20space=a%20value&a+space=a+value2&a%2Bplus=s%2Bplus");
            client.sendHeader("Host", server.uri().getAuthority());
            client.endHeaders();
            client.flushRequest();

            while (client.bytesReceived() == 0) {
                Thread.sleep(10);
            }
            r = client.responseString();
        }
        assertThat(r, containsString("|path=/a space/a+plus|"));
        assertThat(r, containsString("|rawPath=/a%20space/a+plus|"));
        assertThat(r, containsString("|serverPath=/a space/a+plus|"));
        assertThat(r, containsString("|serverRawPath=/a%20space/a+plus|"));
    }

    @Test
    public void charactersAreDecodedInQueryStrings() throws Exception {
        server = httpServer()
            .addHandler((request, response) -> {
                RequestParameters q = request.query();
                response.write("|qs=" + request.uri().getQuery() + "|\n" +
                    "|rawQS=" + request.uri().getRawQuery() + "|\n" +
                    "|serverQS=" + request.serverURI().getQuery() + "|\n" +
                    "|serverRawQS=" + request.serverURI().getRawQuery() + "|\n" +
                    "|a space=" + q.getAll("a space") + "|\n" +
                    "|a+plus=" + q.getAll("a+plus") + "|\n" +
                    "");
                return true;
            }).start();
        String r;
        try (RawClient client = RawClient.create(server.uri())) {
            client.sendStartLine("GET", "/a%20space/a+plus?a%20space=a%20value&a+space=a+value2&a%2Bplus=a%2Bplus");
            client.sendHeader("Host", server.uri().getAuthority());
            client.endHeaders();
            client.flushRequest();

            assertEventually(client::responseString, containsString("|a space=[a value, a value2]|"));
            r = client.responseString();
        }
        assertThat(r, containsString("|a+plus=[a+plus]|"));
        assertThat(r, containsString("|serverQS=a space=a value&a+space=a+value2&a+plus=a+plus|"));
        assertThat(r, containsString("|serverRawQS=a%20space=a%20value&a+space=a+value2&a%2Bplus=a%2Bplus|"));
        assertThat(r, containsString("|qs=a space=a value&a+space=a+value2&a+plus=a+plus|"));
        assertThat(r, containsString("|rawQS=a%20space=a%20value&a+space=a+value2&a%2Bplus=a%2Bplus|"));
    }

    @AfterEach
    public void stopIt() {
        MuAssert.stopAndCheck(server);
    }

    URL http(String str) throws MalformedURLException {
        return server.uri().resolve(str).toURL();
    }
}
