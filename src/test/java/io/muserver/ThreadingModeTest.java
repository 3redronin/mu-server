package io.muserver;

import okhttp3.Protocol;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ClientUtils.client;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.stopAndCheck;

@Timeout(20)
class ThreadingModeTest {
    private static boolean virtualThread() throws Exception {
        return Runtime.version().feature() >= 21
            && (Boolean) Thread.class.getMethod("isVirtual").invoke(Thread.currentThread());
    }

    private static boolean expectedVirtual(ThreadingMode mode) {
        return mode == ThreadingMode.VIRTUAL || (mode == ThreadingMode.AUTO && Runtime.version().feature() >= 21);
    }

    @Test void builderPolicyIsExplicitAndResettable() {
        var builder = MuServerBuilder.httpServer();
        assertEquals(ThreadingMode.AUTO, builder.threadingMode());
        assertSame(builder, builder.withThreadingMode(ThreadingMode.PLATFORM));
        assertEquals(ThreadingMode.PLATFORM, builder.threadingMode());
        assertSame(builder, builder.withThreadingMode(ThreadingMode.AUTO));
        assertThrows(NullPointerException.class, () -> builder.withThreadingMode(null));
        assertEquals(ThreadingMode.AUTO, builder.threadingMode());
    }

    @ParameterizedTest @EnumSource(ThreadingMode.class)
    void policySelectsBothOwnedExecutors(ThreadingMode mode) throws Exception {
        if (mode == ThreadingMode.VIRTUAL && Runtime.version().feature() < 21) {
            assertThrows(UnsupportedOperationException.class, () -> ExecutionResources.create(null, mode));
            return;
        }
        var resources = ExecutionResources.create(null, mode);
        try {
            assertNotSame(resources.application, resources.internal);
            assertEquals(expectedVirtual(mode), resources.application.submit(ThreadingModeTest::virtualThread).get(5, TimeUnit.SECONDS));
            assertEquals(expectedVirtual(mode), resources.internal.submit(ThreadingModeTest::virtualThread).get(5, TimeUnit.SECONDS));
            assertFalse(resources.timer.submit(ThreadingModeTest::virtualThread).get(5, TimeUnit.SECONDS));
        } finally { resources.shutdown(); }
        assertTrue(resources.application.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(resources.application.isShutdown());
        assertTrue(resources.internal.isShutdown());
        assertTrue(resources.timer.isShutdown());
    }

    @ParameterizedTest @EnumSource(ThreadingMode.class)
    void handlersAndCompletionCallbacksUseSelectedModeAcrossProtocols(ThreadingMode mode) throws Exception {
        var completed = new CompletableFuture<Boolean>();
        var builder = MuServerBuilder.muServer().withHttpPort(0).withHttpsPort(0)
            .withHttp2Config(Http2ConfigBuilder.http2Enabled()).withThreadingMode(mode)
            .addHandler((req, resp) -> { resp.write(Boolean.toString(virtualThread())); return true; })
            .addResponseCompleteListener(info -> {
                try { completed.complete(virtualThread()); }
                catch (Exception error) { completed.completeExceptionally(error); }
            });
        if (mode == ThreadingMode.VIRTUAL && Runtime.version().feature() < 21) {
            assertThrows(UnsupportedOperationException.class, builder::start);
            return;
        }
        MuServer server = builder.start();
        var http1 = client.newBuilder().protocols(List.of(Protocol.HTTP_1_1)).build();
        try {
            for (var uri : List.of(server.httpUri(), server.httpsUri())) {
                try (Response response = http1.newCall(request(uri).build()).execute()) {
                    assertEquals(200, response.code());
                    assertEquals(Boolean.toString(expectedVirtual(mode)), response.body().string());
                }
            }
            try (Response response = client.newCall(request(server.httpsUri()).build()).execute()) {
                assertEquals(Protocol.HTTP_2, response.protocol());
                assertEquals(Boolean.toString(expectedVirtual(mode)), response.body().string());
            }
            assertEquals(expectedVirtual(mode), completed.get(5, TimeUnit.SECONDS));
        } finally { stopAndCheck(server); }
    }

    @ParameterizedTest @EnumSource(ThreadingMode.class)
    void suppliedHandlerExecutorIsRetainedAndNeverShutdown(ThreadingMode mode) throws Exception {
        var supplied = Executors.newSingleThreadExecutor();
        try {
            if (mode == ThreadingMode.VIRTUAL && Runtime.version().feature() < 21) {
                assertThrows(UnsupportedOperationException.class, () -> ExecutionResources.create(supplied, mode));
                assertFalse(supplied.isShutdown());
                assertFalse(supplied.submit(ThreadingModeTest::virtualThread).get(5, TimeUnit.SECONDS));
                return;
            }
            var resources = ExecutionResources.create(supplied, mode);
            try {
                assertSame(supplied, resources.application);
                assertFalse(resources.application.submit(ThreadingModeTest::virtualThread).get(5, TimeUnit.SECONDS));
                assertEquals(expectedVirtual(mode), resources.internal.submit(ThreadingModeTest::virtualThread).get(5, TimeUnit.SECONDS));
            } finally { resources.shutdown(); }
            assertFalse(supplied.isShutdown());
            assertTrue(resources.internal.isShutdown());
        } finally { supplied.shutdownNow(); }
    }
}
