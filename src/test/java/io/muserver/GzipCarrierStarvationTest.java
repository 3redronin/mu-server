package io.muserver;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static io.muserver.RFCTestUtils.getHelloHeaders;
import static io.muserver.RFCTestUtils.readIgnoringWindowUpdates;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static scaffolding.MuAssert.stopAndCheck;
import static scaffolding.ServerUtils.httpsServerForTest;

public class GzipCarrierStarvationTest {
    @TempDir Path temporary;

    @ParameterizedTest
    @CsvSource({"false,VIRTUAL,false", "true,VIRTUAL,false", "false,AUTO,true", "true,AUTO,true"})
    void concurrentGzipAndNewConnectionProgressWithOneCarrier(boolean tls, ThreadingMode mode, boolean stockWrapper) throws Exception {
        assumeTrue(Runtime.version().feature() >= 21, "Virtual threads require Java 21");
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path log = temporary.resolve("child.log");
        Process child = new ProcessBuilder(java,
            "-Djdk.virtualThreadScheduler.parallelism=1", "-Djdk.virtualThreadScheduler.maxPoolSize=1",
            "-cp", classPath, GzipCarrierStarvationTest.class.getName(), Boolean.toString(tls), mode.name(), Boolean.toString(stockWrapper))
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished;
        try {
            finished = child.waitFor(20, TimeUnit.SECONDS);
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                assertTrue(child.waitFor(5, TimeUnit.SECONDS), "Child JVM did not terminate");
            }
        }
        String output = Files.readString(log);
        assertTrue(finished, "Child JVM stalled:\n" + output);
        assertEquals(0, child.exitValue(), "Child JVM failed:\n" + output);
    }

    public static void main(String[] args) throws Exception {
        boolean tls = Boolean.parseBoolean(args[0]);
        ThreadingMode mode = args.length > 1 ? ThreadingMode.valueOf(args[1]) : ThreadingMode.VIRTUAL;
        boolean stockWrapper = args.length > 2 && Boolean.parseBoolean(args[2]);
        byte[] body = new byte[65536];
        var random = new Random(73);
        for (int i = 0; i < body.length; i++) body[i] = (byte) (32 + random.nextInt(95));
        var server = httpsServerForTest(tls ? "h2" : "http")
            .withThreadingMode(mode)
            .withContentEncoders(stockWrapper ? java.util.List.of() : null)
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler((request, response) -> {
                response.contentType("text/plain");
                if (stockWrapper) response.headers().set(HeaderNames.CONTENT_ENCODING, "gzip");
                else response.headers().set(HeaderNames.CONTENT_LENGTH, body.length);
                var out = stockWrapper ? new java.util.zip.GZIPOutputStream(response.outputStream()) : response.outputStream();
                for (int offset = 0; offset < body.length; offset += 8192) {
                    out.write(body, offset, 8192);
                }
                if (stockWrapper) out.close();
                return true;
            }).start();
        try (var client = new H2Client();
             var first = connect(client, server, tls);
             var second = connect(client, server, tls)) {
            send(first, server, tls);
            send(second, server, tls);
            verify(first, body);
            verify(second, body);
            // The gzip workload must also leave the server able to accept a new TLS/H2 connection.
            try (var probe = connect(client, server, tls)) {
                send(probe, server, tls);
                verify(probe, body);
            }
        } finally {
            stopAndCheck(server);
        }
    }

    private static H2ClientConnection connect(H2Client client, MuServer server, boolean tls) throws Exception {
        var connection = tls ? client.connect(server) : client.connectClearText(server);
        connection.socket().setSoTimeout(5000);
        connection.handshake();
        return connection;
    }

    private static void send(H2ClientConnection connection, MuServer server, boolean tls) throws Exception {
        var headers = getHelloHeaders(tls ? "https" : "http", server.uri().getPort());
        headers.set("accept-encoding", "gzip");
        connection.writeFrame(new Http2HeadersFrame(1, true, headers)).flush();
    }

    private static void verify(H2ClientConnection connection, byte[] body) throws Exception {
        var headers = readIgnoringWindowUpdates(connection, Http2HeadersFrame.class);
        assertEquals("200", headers.headers().get(":status"));
        assertEquals("gzip", headers.headers().get("content-encoding"));
        var bytes = new ByteArrayOutputStream();
        while (true) {
            var frame = connection.readLogicalFrame();
            if (frame instanceof Http2WindowUpdate) continue;
            assertInstanceOf(Http2DataFrame.class, frame);
            var data = (Http2DataFrame) frame;
            bytes.write(data.payload());
            if (data.endStream()) break;
        }
        try (var decoded = new GZIPInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertArrayEquals(body, decoded.readAllBytes());
        }
    }
}
