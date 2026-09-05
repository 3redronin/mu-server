package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import okhttp3.Protocol;
import okhttp3.Response;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ClientUtils.client;
import static scaffolding.ClientUtils.request;

@Timeout(15)
class AsyncOutputIntegrationTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void aHandlerCanWaitForWriteIoWithOneApplicationWorker(boolean h2) throws Exception {
        var application = Executors.newSingleThreadExecutor();
        MuServer server = MuServerBuilder.httpServer().withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .withHandlerExecutor(application).addHandler((req, resp) -> {
                AsyncHandle handle = req.handleAsync();
                handle.write(ByteBuffer.wrap(new byte[]{1, 2, 3})).get(5, TimeUnit.SECONDS);
                handle.complete();
                return true;
            }).start();
        var transport = client.newBuilder().protocols(h2 ? List.of(Protocol.H2_PRIOR_KNOWLEDGE) : List.of(Protocol.HTTP_1_1)).build();
        try (Response response = transport.newCall(request(server.uri()).build()).execute()) {
            assertArrayEquals(new byte[]{1, 2, 3}, response.body().bytes());
        } finally { server.stop(); application.shutdownNow(); transport.connectionPool().evictAll(); }
    }

    @Test void cancellingFlowControlledOutputResetsTheStreamAndKeepsTheConnectionUsable() throws Exception {
        var handles = new CompletableFuture<AsyncHandle>();
        var writing = new CompletableFuture<Future<?>>();
        MuServer server = MuServerBuilder.httpServer().withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler((req, resp) -> {
                AsyncHandle handle = req.handleAsync();
                writing.complete(handle.write(ByteBuffer.wrap(new byte[20])));
                handles.complete(handle);
                return true;
            }).start();
        try (var client = new H2Client(); var con = client.connectClearText(server)) {
            con.socket().setSoTimeout(5000);
            con.handshake(new Http2Settings(false, 4096, 100, 0, 16384, 32768));
            con.writeFrame(new Http2HeadersFrame(1, true, RFCTestUtils.getHelloHeaders("http", server.uri().getPort()))).flush();
            con.readLogicalFrame(Http2HeadersFrame.class);
            Future<?> output = writing.get(5, TimeUnit.SECONDS);
            assertFalse(output.isDone());
            handles.get(5, TimeUnit.SECONDS).complete(new IOException("cancel"));
            assertEquals(1, con.readLogicalFrame(Http2ResetStreamFrame.class).streamId());
            assertThrows(ExecutionException.class, () -> output.get(5, TimeUnit.SECONDS));
            byte[] ping = {1, 2, 3, 4, 5, 6, 7, 8};
            con.writeFrame(new Http2WindowUpdate(1, 100)).writeFrame(new Http2Ping(false, ping)).flush();
            assertEquals(new Http2Ping(true, ping), con.readLogicalFrame(Http2Ping.class));
        } finally { server.stop(); }
    }
}
