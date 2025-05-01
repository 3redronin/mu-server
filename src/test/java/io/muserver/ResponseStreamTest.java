package io.muserver;

import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import scaffolding.ServerTypeArgs;
import scaffolding.ServerUtils;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class ResponseStreamTest {
    private MuServer server;

    @ParameterizedTest
    @ArgumentsSource(ServerTypeArgs.class)
    public void ifNotFlushedThenItStillWritesIfAllOkay(String protocol) throws Exception {
        server = ServerUtils.httpsServerForTest(protocol)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.writer().print("Hello there");
            })
            .start();

        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Content-Type"), is("text/plain;charset=utf-8"));
            String body = resp.body().string();
            assertThat(body, containsString("Hello there"));
        }
    }

    @AfterEach
    public void stopIt() {
        scaffolding.MuAssert.stopAndCheck(server);
    }
}

