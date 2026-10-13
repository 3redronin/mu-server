package io.muserver;

import org.junit.jupiter.api.Test;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

class ResponseStateTest {

    @Test
    void lateSuccessfulCleanupCannotOverwriteAConnectionFailure() throws Exception {
        withResponse(response -> {
            assertThat(response.setState(ResponseState.WRITING_HEADERS), equalTo(true));
            assertThat(response.setState(ResponseState.CLIENT_DISCONNECTED), equalTo(true));

            assertThat(response.setState(ResponseState.FINISHED), equalTo(false));
            assertThat(response.responseState(), equalTo(ResponseState.CLIENT_DISCONNECTED));
        });
    }

    @Test
    void firstTerminalCauseWins() throws Exception {
        withResponse(response -> {
            assertThat(response.setState(ResponseState.TIMED_OUT), equalTo(true));
            assertThat(
                response.setState(ResponseState.CLIENT_DISCONNECTED),
                equalTo(false)
            );
            assertThat(response.responseState(), equalTo(ResponseState.TIMED_OUT));
        });
    }

    private static void withResponse(Consumer<BaseResponse> assertions) throws Exception {
        var checked = new CompletableFuture<Void>();
        try (MuServer server = MuServerBuilder.httpServer().addHandler((request, response) -> {
            try { assertions.accept((BaseResponse) response); checked.complete(null); }
            catch (AssertionError failure) { checked.completeExceptionally(failure); }
            return true;
        }).start(); Socket socket = new Socket("localhost", server.uri().getPort())) {
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                .getBytes(StandardCharsets.US_ASCII));
            checked.get(3, TimeUnit.SECONDS);
        }
    }
}
