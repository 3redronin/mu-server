package io.muserver;

import okhttp3.Response;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import scaffolding.ClientUtils;
import scaffolding.ServerUtils;

import java.io.IOException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static scaffolding.ClientUtils.*;

class ConnectionTest {

    @ParameterizedTest
    @ValueSource(strings = {"http", "https"})
    public void ifTheRequestAsksToCloseTheConnectionThenItIsClosed(String protocol) throws IOException {
        MuStats stats;
        try (MuServer server = ServerUtils.httpsServerForTest(protocol)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.write(request.query().get("message"));
            })
            .start()) {

            for (int i = 0; i <= 1; i++) {
                try (Response resp = call(client, request(server.uri().resolve("?message=my-first-message"))
                    .header("connection", "close")
                )) {
                    assertThat(resp.body().string(), equalTo("my-first-message"));
                }
            }
            stats = server.stats();
        }
        assertThat(stats.completedConnections(), equalTo(2L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "https"})
    public void ifTheResponseAsksToCloseTheConnectionThenItIsClosed(String protocol) throws IOException {
        MuStats stats;
        try (MuServer server = ServerUtils.httpsServerForTest(protocol)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.headers().set("connection", "close");
                response.write(request.query().get("message"));
            })
            .start()) {

            for (int i = 0; i <= 1; i++) {
                try (Response resp = call(client, request(server.uri().resolve("?message=my-first-message")))) {
                    assertThat(resp.body().string(), equalTo("my-first-message"));
                }
            }
            stats = server.stats();
        }
        assertThat(stats.completedConnections(), equalTo(2L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "https"})
    public void ifNothingAsksToCloseTheConnectionThenItIsReused(String protocol) throws IOException {
        MuStats stats;
        try (MuServer server = ServerUtils.httpsServerForTest(protocol)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.write(request.query().get("message"));
            })
            .start()) {

            for (int i = 0; i <= 5; i++) {
                try (Response resp = call(client, request(server.uri().resolve("?message=my-first-message")))) {
                    assertThat(resp.body().string(), equalTo("my-first-message"));
                }
            }
            stats = server.stats();
        }
        assertThat(stats.completedConnections(), equalTo(1L));
    }

}