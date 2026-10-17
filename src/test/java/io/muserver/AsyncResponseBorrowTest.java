package io.muserver;

import okhttp3.Protocol;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ClientUtils.client;
import static scaffolding.ClientUtils.request;

@Timeout(15)
class AsyncResponseBorrowTest {
    @ParameterizedTest
    @CsvSource({"plain,false,false", "plain,true,false", "TLSv1.2,false,false", "TLSv1.2,true,false",
        "TLSv1.3,false,false", "TLSv1.3,true,false", "plain,false,true", "plain,true,true",
        "TLSv1.2,false,true", "TLSv1.2,true,true", "TLSv1.3,false,true", "TLSv1.3,true,true"})
    void unchangedBodiesAndEncoderScratchReuseKeepTheirBytes(String protocol, boolean h2, boolean encoded) throws Exception {
        byte[] expected = new byte[3 * 16384 + 37];
        new Random(6237).nextBytes(expected);
        boolean secure = !protocol.equals("plain");
        MuServerBuilder builder = MuServerBuilder.muServer().withThreadingMode(ThreadingMode.PLATFORM)
            .withHttp2Config(Http2ConfigBuilder.http2Enabled());
        builder.useChannelTransport = true;
        if (secure) builder.withHttpsPort(0).withHttpsConfig(HttpsConfigBuilder.unsignedLocalhost().withProtocols(protocol));
        else builder.withHttpPort(0);
        if (encoded) builder.withContentEncoders(List.of(new ContentEncoder() {
            @Override public String contentCoding() { return "test"; }
            @Override public boolean prepare(MuRequest req, MuResponse res) { return true; }
            @Override public OutputStream wrapStream(MuRequest req, MuResponse res, OutputStream output) {
                return new OutputStream() {
                    @Override public void write(int value) throws IOException { output.write(value); }
                    @Override public void write(byte[] bytes, int offset, int count) throws IOException {
                        output.write(bytes, offset, count);
                        // Even an encoder that emits its input unchanged may reuse that storage
                        // before returning. It must never qualify for the unencoded borrow path.
                        Arrays.fill(bytes, offset, offset + count, (byte) 0);
                    }
                    @Override public void flush() throws IOException { output.flush(); }
                    @Override public void close() throws IOException { output.close(); }
                };
            }
        }));
        builder.addHandler((req, res) -> {
            res.headers().set(HeaderNames.CONTENT_LENGTH, expected.length);
            AsyncHandle handle = req.handleAsync();
            // Read-only input makes the renderer use its own bounded scratch array. Custom
            // encoding may overwrite that scratch, but must not corrupt captured output.
            handle.write(ByteBuffer.wrap(expected).asReadOnlyBuffer()).get(5, TimeUnit.SECONDS);
            handle.complete();
            return true;
        });
        var protocols = !h2 ? List.of(Protocol.HTTP_1_1) : secure
            ? List.of(Protocol.HTTP_2, Protocol.HTTP_1_1) : List.of(Protocol.H2_PRIOR_KNOWLEDGE);
        var transport = client.newBuilder().protocols(protocols).build();
        try (MuServer server = builder.start(); var response = transport.newCall(request(server.uri()).build()).execute()) {
            assertEquals(!h2 ? Protocol.HTTP_1_1 : secure ? Protocol.HTTP_2 : Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
            assertEquals(200, response.code());
            assertArrayEquals(expected, response.body().bytes());
        } finally { transport.connectionPool().evictAll(); }
    }
}
