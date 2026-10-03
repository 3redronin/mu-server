package io.muserver.rest;

import io.muserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import scaffolding.NotImplementedMuRequest;
import scaffolding.SingleCarrier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class StreamCarrierStarvationTest {
    @TempDir Path temporary;

    @ParameterizedTest @ValueSource(strings = {"close", "mark", "reset", "mark-reset"})
    void delegatedStreamOperationsLeaveCarrierAvailable(String operation) throws Exception {
        SingleCarrier.run(StreamCarrierStarvationTest.class, temporary.resolve("child.log"), operation);
    }

    @Test void failedCloseIsNotRetried() throws Exception {
        var closes = new AtomicInteger();
        var failure = new IOException("close failed");
        InputStream stream = new ByteArrayInputStream(new byte[0]) {
            @Override public void close() throws IOException {
                closes.incrementAndGet();
                throw failure;
            }
        };
        JaxRSRequest request = request(stream);
        assertSame(failure, assertThrows(IOException.class, request::closeEntityStream));
        request.closeEntityStream();
        assertEquals(1, closes.get());
    }

    @Test void lazyStreamPreservesMarkResetAndDoesNotOpenOnClose() throws Exception {
        var opens = new AtomicInteger();
        var delegate = new ByteArrayInputStream(new byte[]{1, 2, 3});
        var stream = new LazyAccessInputStream(new NotImplementedMuRequest() {
            @Override public Optional<InputStream> inputStream() {
                opens.incrementAndGet();
                return Optional.of(delegate);
            }
        });
        stream.close();
        assertEquals(0, opens.get());
        assertEquals(1, stream.read());
        assertTrue(stream.markSupported());
        stream.mark(2);
        assertEquals(2, stream.read());
        stream.reset();
        assertEquals(2, stream.read());
        assertEquals(1, opens.get());
        stream.close();
    }

    private static MuRequest muRequest(InputStream stream) {
        return new NotImplementedMuRequest() {
            @Override public URI uri() { return URI.create("http://localhost/"); }
            @Override public Method method() { return Method.POST; }
            @Override public String contextPath() { return "/"; }
            @Override public Headers headers() { return Headers.create(); }
            @Override public List<Cookie> cookies() { return List.of(); }
            @Override public Optional<InputStream> inputStream() { return Optional.of(stream); }
        };
    }

    private static JaxRSRequest request(InputStream stream) {
        return new JaxRSRequest(muRequest(stream), null, stream, "", null, List.of(), null);
    }

    public static void main(String[] args) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var active = new AtomicInteger();
        var maxActive = new AtomicInteger();
        InputStream delegate = new InputStream() {
            private void block() {
                calls.incrementAndGet();
                maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                entered.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS), "Stream was not released");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                } finally { active.decrementAndGet(); }
            }
            @Override public int read() { return -1; }
            @Override public void close() { block(); }
            @Override public void mark(int readlimit) { assertEquals(17, readlimit); block(); }
            @Override public void reset() { block(); }
        };
        var lazy = new LazyAccessInputStream(muRequest(delegate));
        var request = request(delegate);
        Callable<Void> operation = () -> {
            switch (args[0]) {
                case "close": request.closeEntityStream(); break;
                case "mark":
                case "mark-reset": lazy.mark(17); break;
                case "reset": lazy.reset(); break;
                default: throw new AssertionError(args[0]);
            }
            return null;
        };
        var executor = SingleCarrier.executor();
        try {
            var first = executor.submit(operation);
            assertTrue(entered.await(5, TimeUnit.SECONDS), "First operation did not enter the stream");
            var second = executor.submit(() -> {
                secondStarted.countDown();
                if (args[0].equals("mark-reset")) {
                    lazy.reset();
                    return null;
                }
                return operation.call();
            });
            assertTrue(secondStarted.await(3, TimeUnit.SECONDS), "Blocked stream pinned the only carrier");
            assertEquals("progress", executor.submit(() -> "progress").get(3, TimeUnit.SECONDS));
            assertFalse(second.isDone(), "Concurrent operation must wait for the first to finish");
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertEquals(args[0].equals("close") ? 1 : 2, calls.get());
            assertEquals(1, maxActive.get(), "Delegated operations must remain serialized");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
