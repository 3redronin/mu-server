package io.muserver;

import okhttp3.Protocol;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;

    @ParameterizedTest
    @CsvSource({"8192,false", "16352,false", "16384,false", "32768,false",
        "8192,true", "16352,true", "16384,true", "32768,true"})
    void aBufferedPrefixDoesNotIntroduceAResponseSpill(int bufferSize, boolean h2) throws Exception {
        byte[] expected = new byte[150000];
        new Random(791).nextBytes(expected);
        var temporary = java.nio.file.Files.createDirectory(directory.resolve("responses"));
        MuServerBuilder builder = MuServerBuilder.muServer().withHttpPort(0)
            .withHttp2Config(Http2ConfigBuilder.http2Enabled()).withTempDirectory(temporary);
        builder.useChannelTransport = true;
        builder.addHandler((req, res) -> {
            res.headers().set(HeaderNames.CONTENT_LENGTH, expected.length);
            res.outputStream(bufferSize).write(expected, 0, bufferSize - 1);
            // A file cannot host a response spill. The old bounded path needs none here.
            java.nio.file.Files.delete(temporary);
            java.nio.file.Files.createFile(temporary);
            var async = req.handleAsync();
            async.write(ByteBuffer.wrap(expected, bufferSize - 1, expected.length - bufferSize + 1))
                .get(5, TimeUnit.SECONDS);
            async.complete();
            return true;
        });
        var transport = client.newBuilder().protocols(List.of(h2 ? Protocol.H2_PRIOR_KNOWLEDGE : Protocol.HTTP_1_1)).build();
        try (MuServer server = builder.start(); var response = transport.newCall(request(server.uri()).build()).execute()) {
            assertEquals(200, response.code());
            assertArrayEquals(expected, response.body().bytes());
        } finally { transport.connectionPool().evictAll(); }
    }

    static java.util.stream.Stream<Arguments> largeBodies() {
        return java.util.stream.Stream.of("plain", "TLSv1.2", "TLSv1.3").flatMap(protocol ->
            java.util.stream.IntStream.range(0, 3).boxed().flatMap(kind ->
                java.util.stream.Stream.of(false, true).flatMap(prefix ->
                    java.util.stream.Stream.of(false, true).map(h2 -> Arguments.of(protocol, kind, prefix, h2)))));
    }

    @ParameterizedTest @MethodSource("largeBodies")
    void largeWritesKeepTheirBytesAcrossWindowsAndAllowReuseOnlyAfterAcknowledgement(String protocol, int kind, boolean bufferedPrefix, boolean h2) throws Exception {
        byte[] expected = new byte[4 * 1048576 + 37];
        new Random(383).nextBytes(expected);
        byte[] prefix = bufferedPrefix ? new byte[]{1, 2, 3, 4, 5} : new byte[0];
        boolean secure = !protocol.equals("plain");
        MuServerBuilder builder = MuServerBuilder.muServer().withThreadingMode(ThreadingMode.PLATFORM)
            .withHttp2Config(Http2ConfigBuilder.http2Enabled());
        builder.useChannelTransport = true;
        if (secure) builder.withHttpsPort(0).withHttpsConfig(HttpsConfigBuilder.unsignedLocalhost().withProtocols(protocol));
        else builder.withHttpPort(0);
        builder.addHandler((req, res) -> {
            res.headers().set(HeaderNames.CONTENT_LENGTH, prefix.length + expected.length);
            if (bufferedPrefix) res.outputStream(8192).write(prefix);
            ByteBuffer storage = kind == 1 ? ByteBuffer.allocateDirect(expected.length + 9)
                : ByteBuffer.allocate(expected.length + 9);
            storage.position(5).put(expected).flip().position(5);
            ByteBuffer source = kind == 2 ? storage.asReadOnlyBuffer() : storage.slice();
            source.mark();
            int position = source.position();
            AsyncHandle handle = req.handleAsync();
            handle.write(source).get(5, TimeUnit.SECONDS);
            assertEquals(source.hasArray() ? position : source.limit(), source.position());
            if (source.hasArray()) { source.reset(); assertEquals(position, source.position()); }
            // Every frame has released the borrow, including the final TLS ciphertext.
            storage.clear();
            while (storage.hasRemaining()) storage.put((byte) 99);
            handle.complete();
            return true;
        });
        var protocols = !h2 ? List.of(Protocol.HTTP_1_1) : secure
            ? List.of(Protocol.HTTP_2, Protocol.HTTP_1_1) : List.of(Protocol.H2_PRIOR_KNOWLEDGE);
        var transport = client.newBuilder().protocols(protocols).build();
        try (MuServer server = builder.start(); var response = transport.newCall(request(server.uri()).build()).execute()) {
            assertEquals(!h2 ? Protocol.HTTP_1_1 : secure ? Protocol.HTTP_2 : Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
            assertEquals(200, response.code());
            byte[] actual = response.body().bytes();
            assertArrayEquals(prefix, Arrays.copyOf(actual, prefix.length));
            assertArrayEquals(expected, Arrays.copyOfRange(actual, prefix.length, actual.length));
        } finally { transport.connectionPool().evictAll(); }
    }

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
