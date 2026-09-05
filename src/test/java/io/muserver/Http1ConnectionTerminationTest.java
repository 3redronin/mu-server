package io.muserver;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scaffolding.Http1Client;
import scaffolding.MuAssert;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.muserver.MuServerBuilder.httpServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

class Http1ConnectionTerminationTest {

    private @Nullable MuServer server;

    @Test
    void abortedUploadPublishesClientDisconnectBeforeTheReadFails()
        throws Exception {
        var reading = new CountDownLatch(1);
        var stateAtFailure = new CompletableFuture<ResponseState>();
        var completed = new CompletableFuture<ResponseInfo>();
        server = httpServer()
            .addResponseCompleteListener(completed::complete)
            .addHandler(Method.POST, "/", (request, response, pathParams) -> {
                reading.countDown();
                try {
                    request.readBodyAsString();
                } catch (IOException failure) {
                    stateAtFailure.complete(response.responseState());
                    throw failure;
                }
            })
            .start();

        try (var client = Http1Client.connect(server)) {
            client.writeRequestLine(Method.POST, "/")
                .writeHeader(HeaderNames.CONTENT_LENGTH.toString(), 10)
                .endHeaders()
                .writeAscii("x")
                .flush();
            assertThat(reading.await(5, TimeUnit.SECONDS), equalTo(true));

            client.abort();

            assertThat(
                stateAtFailure.get(5, TimeUnit.SECONDS),
                equalTo(ResponseState.CLIENT_DISCONNECTED)
            );
            assertThat(
                completed.get(5, TimeUnit.SECONDS).response().responseState(),
                equalTo(ResponseState.CLIENT_DISCONNECTED)
            );
        }
    }

    @Test
    void abortedResponseWriteCompletesAsClientDisconnected() throws Exception {
        var handlerStarted = new CountDownLatch(1);
        var writeResponse = new CountDownLatch(1);
        var completed = new CompletableFuture<ResponseInfo>();
        server = httpServer()
            .addResponseCompleteListener(completed::complete)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                handlerStarted.countDown();
                assertThat(
                    writeResponse.await(5, TimeUnit.SECONDS),
                    equalTo(true)
                );
                response.write("too late");
            })
            .start();

        try (var client = Http1Client.connect(server)) {
            client.writeRequestLine(Method.GET, "/").endHeaders().flush();
            assertThat(
                handlerStarted.await(5, TimeUnit.SECONDS),
                equalTo(true)
            );

            client.abort();
            writeResponse.countDown();

            assertThat(
                completed.get(5, TimeUnit.SECONDS).response().responseState(),
                equalTo(ResponseState.CLIENT_DISCONNECTED)
            );
        } finally {
            writeResponse.countDown();
        }
    }

    @AfterEach
    void stopServer() {
        MuAssert.stopAndCheck(server);
    }
}
