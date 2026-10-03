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

public class ResponseWriterCarrierStarvationTest {
    private static boolean gzip = true;
    @TempDir Path temporary;

    @ParameterizedTest
    @CsvSource({"false,write,true,VIRTUAL,false", "true,write,true,VIRTUAL,false",
        "false,println,true,VIRTUAL,false", "true,println,true,VIRTUAL,false",
        "false,format,true,VIRTUAL,false", "true,format,true,VIRTUAL,false",
        "false,write,false,VIRTUAL,false", "true,write,false,VIRTUAL,false",
        "false,println,false,VIRTUAL,false", "true,println,false,VIRTUAL,false",
        "false,format,false,VIRTUAL,false", "true,format,false,VIRTUAL,false",
        "false,write,true,AUTO,true", "true,write,true,AUTO,true",
        "false,println,true,AUTO,true", "true,println,true,AUTO,true",
        "false,format,true,AUTO,true", "true,format,true,AUTO,true",
        "false,write,true,PLATFORM,true", "true,write,true,PLATFORM,true", "false,println,true,PLATFORM,true", "true,println,true,PLATFORM,true", "false,format,true,PLATFORM,true", "true,format,true,PLATFORM,true"})
    void writerAndNewConnectionProgressWithOneCarrier(boolean tls, String operation, boolean compressed,
                                                     ThreadingMode mode, boolean stockWrapper) throws Exception {
        assumeTrue(Runtime.version().feature() >= 21, "Virtual threads require Java 21");
        assumeTrue(!stockWrapper || mode != ThreadingMode.AUTO || Runtime.version().feature() >= 24,
            "Stock application wrappers can pin virtual threads before Java 24; PLATFORM is the escape hatch");
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path log = temporary.resolve("child.log");
        Process child = new ProcessBuilder(java,
            "-Djdk.virtualThreadScheduler.parallelism=1", "-Djdk.virtualThreadScheduler.maxPoolSize=1",
            "-cp", classPath, ResponseWriterCarrierStarvationTest.class.getName(), Boolean.toString(tls), operation,
            Boolean.toString(compressed), mode.name(), Boolean.toString(stockWrapper))
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
        String operation = args[1];
        gzip = Boolean.parseBoolean(args[2]);
        ThreadingMode mode = args.length > 3 ? ThreadingMode.valueOf(args[3]) : ThreadingMode.VIRTUAL;
        boolean stockWrapper = args.length > 4 && Boolean.parseBoolean(args[4]);
        byte[] body = new byte[65536];
        var random = new Random(73);
        for (int i = 0; i < body.length; i++) body[i] = (byte) (32 + random.nextInt(95));
        byte[] expected = operation.equals("println")
            ? (new String(body, java.nio.charset.StandardCharsets.US_ASCII) + System.lineSeparator()).getBytes(java.nio.charset.StandardCharsets.US_ASCII) : body;
        var server = httpsServerForTest(tls ? "h2" : "http")
            .withThreadingMode(mode)
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler((request, response) -> {
                response.contentType("text/plain");
                response.headers().set(HeaderNames.CONTENT_LENGTH, expected.length);
                var out = stockWrapper ? new java.io.PrintWriter(response.outputStream(), false,
                    java.nio.charset.StandardCharsets.US_ASCII) : response.writer();
                String text = new String(body, java.nio.charset.StandardCharsets.US_ASCII);
                if (operation.equals("println")) out.println(text);
                else if (operation.equals("format")) out.format("%s", text);
                else {
                    for (int offset = 0; offset < body.length; offset += 8192) out.write(text, offset, 8192);
                }
                out.flush();
                return true;
            }).start();
        try (var client = new H2Client();
             var first = connect(client, server, tls);
             var second = connect(client, server, tls)) {
            send(first, server, tls);
            send(second, server, tls);
            verify(first, expected);
            verify(second, expected);
            // Response writes must also leave the server able to accept a new connection.
            try (var probe = connect(client, server, tls)) {
                send(probe, server, tls);
                verify(probe, expected);
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
        headers.set("accept-encoding", gzip ? "gzip" : "identity");
        connection.writeFrame(new Http2HeadersFrame(1, true, headers)).flush();
    }

    private static void verify(H2ClientConnection connection, byte[] body) throws Exception {
        var headers = readIgnoringWindowUpdates(connection, Http2HeadersFrame.class);
        assertEquals("200", headers.headers().get(":status"));
        assertEquals(gzip ? "gzip" : null, headers.headers().get("content-encoding"));
        var bytes = new ByteArrayOutputStream();
        while (true) {
            var frame = connection.readLogicalFrame();
            if (frame instanceof Http2WindowUpdate) continue;
            assertInstanceOf(Http2DataFrame.class, frame);
            var data = (Http2DataFrame) frame;
            bytes.write(data.payload());
            if (data.endStream()) break;
            if (data.payload().length > 0) {
                connection.writeFrame(new Http2WindowUpdate(0, data.payload().length));
                connection.writeFrame(new Http2WindowUpdate(1, data.payload().length)).flush();
            }
        }
        if (!gzip) { assertArrayEquals(body, bytes.toByteArray()); return; }
        try (var decoded = new GZIPInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertArrayEquals(body, decoded.readAllBytes());
        }
    }
}
