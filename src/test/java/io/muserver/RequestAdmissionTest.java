package io.muserver;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static io.muserver.MuServerBuilder.httpServer;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ClientUtils.client;
import static scaffolding.ClientUtils.request;

@Timeout(20)
class RequestAdmissionTest {
    @Test
    void limitValidationAndIdempotentRelease() {
        assertEquals(1000, httpServer().maxConcurrentRequests());
        assertEquals(0, httpServer().withMaxConcurrentRequests(0).maxConcurrentRequests());
        assertThrows(IllegalArgumentException.class, () -> httpServer().withMaxConcurrentRequests(-1));
        RequestAdmission limit = new RequestAdmission(1);
        RequestAdmission.Slot slot = limit.tryAcquire();
        assertNotNull(slot);
        assertNull(limit.tryAcquire());
        slot.close();
        slot.close();
        RequestAdmission.Slot next = limit.tryAcquire();
        assertNotNull(next);
        assertNull(limit.tryAcquire());
        next.close();
        RequestAdmission unlimited = new RequestAdmission(0);
        for (int i = 0; i < 2000; i++) assertNotNull(unlimited.tryAcquire());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void suspendedRequestsCountAndReleaseBeforeDetachedListeners(boolean http2) throws Exception {
        BlockingQueue<AsyncHandle> handles = new LinkedBlockingQueue<>();
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        var callers = Executors.newCachedThreadPool();
        MuServer server = httpServer().withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .withMaxConcurrentRequests(1)
            .addHandler((req, resp) -> {
                handles.add(req.handleAsync());
                return true;
            })
            .addResponseCompleteListener(info -> {
                listenerEntered.countDown();
                try { releaseListener.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }).start();
        OkHttpClient transport = client.newBuilder().protocols(http2
            ? List.of(Protocol.H2_PRIOR_KNOWLEDGE) : List.of(Protocol.HTTP_1_1)).build();
        try {
            var first = callers.submit(() -> transport.newCall(request(server.uri()).build()).execute());
            AsyncHandle active = handles.poll(5, TimeUnit.SECONDS);
            assertNotNull(active);
            try (Response excess = transport.newCall(request(server.uri()).build()).execute()) {
                assertEquals(503, excess.code());
            }
            assertNull(handles.poll());
            active.complete();
            try (Response accepted = first.get(5, TimeUnit.SECONDS)) { assertEquals(200, accepted.code()); }
            assertTrue(listenerEntered.await(5, TimeUnit.SECONDS));
            var next = callers.submit(() -> transport.newCall(request(server.uri()).build()).execute());
            AsyncHandle nextHandle = handles.poll(5, TimeUnit.SECONDS);
            assertNotNull(nextHandle, "Detached completion listener must not retain admission");
            nextHandle.complete();
            try (Response accepted = next.get(5, TimeUnit.SECONDS)) { assertEquals(200, accepted.code()); }
        } finally {
            releaseListener.countDown();
            server.stop();
            callers.shutdownNow();
            transport.connectionPool().evictAll();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void queuedHandlersCountTowardsTheLimit(boolean http2) throws Exception {
        var application = Executors.newSingleThreadExecutor();
        var callers = Executors.newCachedThreadPool();
        var blocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        application.submit(() -> {
            blocked.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(blocked.await(5, TimeUnit.SECONDS));
        MuServer server = httpServer().withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .withHandlerExecutor(application).withMaxConcurrentRequests(1)
            .addHandler((req, resp) -> { resp.write("done"); return true; }).start();
        OkHttpClient transport = client.newBuilder().protocols(http2
            ? List.of(Protocol.H2_PRIOR_KNOWLEDGE) : List.of(Protocol.HTTP_1_1)).build();
        try {
            var queued = callers.submit(() -> transport.newCall(request(server.uri()).build()).execute());
            scaffolding.MuAssert.assertEventually(() -> server.stats().activeRequests().size(), org.hamcrest.Matchers.is(1));
            try (Response excess = transport.newCall(request(server.uri()).build()).execute()) {
                assertEquals(503, excess.code());
            }
            release.countDown();
            try (Response accepted = queued.get(5, TimeUnit.SECONDS)) { assertEquals("done", accepted.body().string()); }
        } finally {
            release.countDown();
            server.stop();
            application.shutdownNow();
            callers.shutdownNow();
            transport.connectionPool().evictAll();
        }
    }
}
