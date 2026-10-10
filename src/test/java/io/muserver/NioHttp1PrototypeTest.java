package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class NioHttp1PrototypeTest {
    private static final byte[] GET = ascii("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n");

    @Test
    void idleKeepaliveConnectionsDoNotAllocateConnectionWorkers() throws Exception {
        try (NioHttp1Prototype server = server((request, body, response) -> respond(response, ascii("ok")))) {
            List<Socket> clients = new ArrayList<>();
            try {
                for (int i = 0; i < 128; i++) {
                    Socket client = connect(server);
                    clients.add(client);
                    client.getOutputStream().write(GET);
                    assertArrayEquals(ascii("ok"), readResponse(client));
                }
                await(() -> server.connectionCount.get() == 128);
                assertTrue(server.loop.isAlive());
                assertEquals(2, server.workerThreads.get());
                for (Socket client : clients) {
                    client.getOutputStream().write(GET);
                    assertArrayEquals(ascii("ok"), readResponse(client));
                }
                System.out.println("NIO prototype: 128 verified keepalive connections; 1 selector thread; "
                    + server.workerThreads.get() + " application threads");
            } finally {
                for (Socket client : clients) client.close();
            }
            await(() -> server.connectionCount.get() == 0);
        }
    }

    @Test
    void fixedChunkedAndPipelinedBodiesSurviveBufferReuseAndBackpressure() throws Exception {
        byte[] payload = new byte[100_000];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31);
        List<String> targets = new CopyOnWriteArrayList<>();
        try (NioHttp1Prototype server = server((request, body, response) -> {
            assertNotEquals("nio-prototype-loop", Thread.currentThread().getName());
            targets.add(request.getUrl());
            respond(response, body.readAllBytes());
        }); Socket client = connect(server)) {
            ByteArrayOutputStream batch = new ByteArrayOutputStream();
            batch.write(ascii("POST /fixed HTTP/1.1\r\nHost: localhost\r\nContent-Length: " + payload.length + "\r\n\r\n"));
            batch.write(payload);
            batch.write(ascii("POST /chunked HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"));
            batch.write(ascii(Integer.toHexString(payload.length) + ";name=value\r\n"));
            batch.write(payload);
            batch.write(ascii("\r\n0\r\nX-Result: done\r\n\r\n"));
            batch.write(GET);
            client.getOutputStream().write(batch.toByteArray());
            assertArrayEquals(payload, readResponse(client));
            assertArrayEquals(payload, readResponse(client));
            assertArrayEquals(new byte[0], readResponse(client));
            // A fourth response establishes retirement of the previous exchange before inspecting the log.
            client.getOutputStream().write(GET);
            assertArrayEquals(new byte[0], readResponse(client));
            assertEquals(List.of("/fixed", "/chunked", "/", "/"), targets);
            assertTrue(server.bodyHighWater.get() <= NioHttp1Prototype.BUFFER_SIZE);
            assertTrue(server.outputHighWater.get() <= NioHttp1Prototype.BUFFER_SIZE);
        }
    }

    @Test
    void fullBodyQueuePausesReadsAndConsumptionResumesBufferedInput() throws Exception {
        CountDownLatch consume = new CountDownLatch(1);
        byte[] payload = new byte[40_000];
        Arrays.fill(payload, (byte) 'x');
        try (NioHttp1Prototype server = server((request, body, response) -> {
            if (request.getUrl().equals("/upload")) {
                assertTrue(consume.await(5, TimeUnit.SECONDS));
                respond(response, body.readAllBytes());
            } else respond(response, ascii("healthy"));
        }); Socket slow = connect(server); Socket healthy = connect(server)) {
            slow.getOutputStream().write(ascii("POST /upload HTTP/1.1\r\nHost: localhost\r\nContent-Length: " + payload.length + "\r\n\r\n"));
            slow.getOutputStream().write(payload);
            await(() -> server.bodyHighWater.get() == NioHttp1Prototype.BUFFER_SIZE && server.readPauses.get() > 0);
            healthy.getOutputStream().write(GET);
            assertArrayEquals(ascii("healthy"), readResponse(healthy));
            consume.countDown();
            assertArrayEquals(payload, readResponse(slow));
            slow.getOutputStream().write(GET);
            assertArrayEquals(ascii("healthy"), readResponse(slow));
        } finally { consume.countDown(); }
    }

    @Test
    void stalledOutputDoesNotHoldLoopAndStopReleasesBlockedWriter() throws Exception {
        CountDownLatch writerFinished = new CountDownLatch(1);
        AtomicReference<IOException> writeFailure = new AtomicReference<>();
        try (NioHttp1Prototype server = server((request, body, response) -> {
            if (request.getUrl().equals("/slow")) {
                try {
                    response.write(ascii("HTTP/1.1 200 OK\r\nContent-Length: 67108864\r\n\r\n"));
                    byte[] block = new byte[8192];
                    for (int i = 0; i < 8192; i++) response.write(block);
                } catch (IOException failure) { writeFailure.set(failure); }
                finally { writerFinished.countDown(); }
            } else respond(response, ascii("healthy"));
        }); Socket slow = new Socket(); Socket healthy = connect(server)) {
            slow.setReceiveBufferSize(1024);
            slow.connect(server.address());
            slow.getOutputStream().write(ascii("GET /slow HTTP/1.1\r\nHost: localhost\r\n\r\n"));
            await(() -> server.zeroWrites.get() > 0);
            assertEquals(1, writerFinished.getCount());
            healthy.getOutputStream().write(GET);
            assertArrayEquals(ascii("healthy"), readResponse(healthy));
            assertEquals(NioHttp1Prototype.BUFFER_SIZE, server.outputHighWater.get());
            long start = System.nanoTime();
            server.close();
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(3));
            assertTrue(writerFinished.await(1, TimeUnit.SECONDS));
            assertNotNull(writeFailure.get());
            assertEquals(0, server.connectionCount.get());
        }
    }

    @Test
    void halfCloseCompletesResponseAndTruncatedUploadWakesReader() throws Exception {
        CountDownLatch truncated = new CountDownLatch(1);
        try (NioHttp1Prototype server = server((request, body, response) -> {
            try { respond(response, body.readAllBytes()); }
            catch (IOException failure) { truncated.countDown(); }
        }); Socket complete = connect(server); Socket broken = connect(server)) {
            complete.getOutputStream().write(ascii("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\n\r\nhello"));
            complete.shutdownOutput();
            assertArrayEquals(ascii("hello"), readResponse(complete));
            assertEquals(-1, complete.getInputStream().read());
            broken.getOutputStream().write(ascii("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\n\r\nhe"));
            broken.shutdownOutput();
            assertTrue(truncated.await(3, TimeUnit.SECONDS));
            assertEquals(-1, broken.getInputStream().read());
        }
    }

    @Test
    void earlyResponseClosesUndrainedUploadWithoutWaitingForMoreBody() throws Exception {
        try (NioHttp1Prototype server = server((request, body, response) -> respond(response, ascii("early")));
             Socket client = connect(server)) {
            client.getOutputStream().write(ascii("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 100000\r\n\r\n"));
            assertArrayEquals(ascii("early"), readResponse(client));
            assertEquals(-1, client.getInputStream().read());
        }
    }

    @Test
    void admissionAndPartialHeaderDeadlineReleaseConnections() throws Exception {
        try (NioHttp1Prototype server = new NioHttp1Prototype(1, 1, Duration.ofMillis(500),
            (request, body, response) -> respond(response, ascii("ok"))); Socket first = connect(server)) {
            first.getOutputStream().write(ascii("GET / HTTP/1.1\r\nHost:"));
            await(() -> server.connectionCount.get() == 1);
            try (Socket excess = connect(server)) { assertEquals(-1, excess.getInputStream().read()); }
            assertEquals(-1, first.getInputStream().read());
            await(() -> server.connectionCount.get() == 0);
            try (Socket next = connect(server)) {
                next.getOutputStream().write(GET);
                assertArrayEquals(ascii("ok"), readResponse(next));
            }
        }
    }

    @Test
    void pipeOwnsCopiesAndPreservesOrderThroughZeroAndPartialWrites() throws Exception {
        AtomicInteger highWater = new AtomicInteger();
        NioHttp1Prototype.Pipe pipe = new NioHttp1Prototype.Pipe(8, () -> { }, highWater);
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        WritableByteChannel sink = new WritableByteChannel() {
            int calls;
            @Override public int write(ByteBuffer input) {
                if (++calls % 3 == 0) return 0;
                int count = Math.min(2, input.remaining());
                for (int i = 0; i < count; i++) wire.write(input.get());
                return count;
            }
            @Override public boolean isOpen() { return true; }
            @Override public void close() { }
        };
        for (int batch = 0; batch < 10; batch++) {
            byte[] bytes = ascii("abcdef");
            pipe.write(bytes, 0, bytes.length);
            Arrays.fill(bytes, (byte) 'x'); // Reusing caller storage must not change pending output.
            while (pipe.available() > 0) pipe.drain(sink, 3);
        }
        pipe.awaitDrained();
        assertArrayEquals(ascii("abcdef".repeat(10)), wire.toByteArray());
        assertEquals(6, highWater.get());
    }

    @Test
    void abortWakesBodyReaderOutputProducerAndFlushWaiter() throws Exception {
        NioHttp1Prototype.Pipe body = new NioHttp1Prototype.Pipe(4, () -> { }, new AtomicInteger());
        NioHttp1Prototype.Pipe output = new NioHttp1Prototype.Pipe(4, () -> { }, new AtomicInteger());
        output.write(ascii("full"), 0, 4);
        IOException cancelled = new IOException("cancelled");
        CountDownLatch entered = new CountDownLatch(3);
        ExecutorService waiters = Executors.newFixedThreadPool(3);
        try {
            List<CompletableFuture<Void>> tasks = List.of(
                CompletableFuture.runAsync(() -> { entered.countDown(); assertSame(cancelled, assertThrows(IOException.class, () -> body.read(new byte[1], 0, 1))); }, waiters),
                CompletableFuture.runAsync(() -> { entered.countDown(); assertSame(cancelled, assertThrows(IOException.class, () -> output.write(new byte[1], 0, 1))); }, waiters),
                CompletableFuture.runAsync(() -> { entered.countDown(); assertSame(cancelled, assertThrows(IOException.class, output::awaitDrained)); }, waiters));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
            } finally {
                body.fail(cancelled);
                output.fail(cancelled);
            }
            for (CompletableFuture<Void> task : tasks) task.get(2, TimeUnit.SECONDS);
        } finally {
            waiters.shutdownNow();
            assertTrue(waiters.awaitTermination(2, TimeUnit.SECONDS));
        }
        assertEquals(0, output.available());
    }

    private static NioHttp1Prototype server(NioHttp1Prototype.Handler handler) throws IOException {
        return new NioHttp1Prototype(256, 2, Duration.ofSeconds(10), handler);
    }

    private static Socket connect(NioHttp1Prototype server) throws IOException {
        Socket socket = new Socket();
        socket.connect(server.address());
        socket.setSoTimeout(3000);
        return socket;
    }

    private static void respond(OutputStream output, byte[] payload) throws IOException {
        output.write(ascii("HTTP/1.1 200 OK\r\nContent-Length: " + payload.length + "\r\n\r\n"));
        output.write(payload);
        output.flush();
    }

    private static byte[] readResponse(Socket socket) throws IOException {
        InputStream input = socket.getInputStream();
        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        int tail = 0;
        do {
            int value = input.read();
            assertTrue(value >= 0, "EOF before response headers");
            headers.write(value);
            tail = (tail << 8) | value;
            assertTrue(headers.size() < 8192);
        } while (tail != 0x0d0a0d0a);
        String text = headers.toString(US_ASCII);
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"), text);
        int start = text.indexOf("Content-Length: ") + "Content-Length: ".length();
        int length = Integer.parseInt(text.substring(start, text.indexOf("\r\n", start)));
        byte[] payload = input.readNBytes(length);
        assertEquals(length, payload.length);
        return payload;
    }

    private static byte[] ascii(String value) { return value.getBytes(US_ASCII); }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "Condition not reached before deadline");
    }
}
