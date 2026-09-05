package io.muserver;

import okhttp3.Response;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import scaffolding.ServerUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static io.muserver.RateLimitBuilder.rateLimit;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.assertEventually;

public class RateLimiterTest {


    @Test
    public void returnsActionIfLimitExceeded() {
        var nowNanos = new AtomicLong(100L);
        RateLimiterImpl limiter = new RateLimiterImpl(
            request -> rateLimit().withBucket("blah")
                .withRate(3)
                .withRejectionAction(RateLimitRejectionAction.SEND_429)
                .withWindow(100, TimeUnit.MILLISECONDS).build(),
            nowNanos::get
        );
        assertThat(limiter.record(null), nullValue());
        assertThat(limiter.record(null), nullValue());
        assertThat(limiter.record(null), nullValue());
        assertThat(
            limiter.record(null).action(),
            equalTo(RateLimitRejectionAction.SEND_429)
        );
        nowNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(101L));
        assertThat(limiter.record(null), nullValue());
        nowNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(101L));
        assertThat(limiter.currentBuckets().keySet(), is(empty()));
    }

    @Test
    void windowsExpireAcrossSignedNanoTimeWraparound() {
        var nowNanos = new AtomicLong(Long.MAX_VALUE - 50_000_000L);
        RateLimiterImpl limiter = new RateLimiterImpl(
            request -> rateLimit().withBucket("wrap")
                .withRate(1)
                .withWindow(100, TimeUnit.MILLISECONDS)
                .build(),
            nowNanos::get
        );

        assertThat(limiter.record(null), nullValue());
        nowNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(99L));
        assertThat(
            limiter.record(null).action(),
            is(RateLimitRejectionAction.SEND_429)
        );
        nowNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(2L));
        assertThat(limiter.record(null), nullValue());
    }

    @Test
    void currentBucketsRemovesMultipleExpiredBucketsInOneSnapshot() {
        var nowNanos = new AtomicLong(1L);
        var bucket = new AtomicReference<>("first");
        RateLimiterImpl limiter = new RateLimiterImpl(
            request -> rateLimit().withBucket(bucket.get())
                .withRate(1)
                .withWindow(1, TimeUnit.MILLISECONDS)
                .build(),
            nowNanos::get
        );
        limiter.record(null);
        bucket.set("second");
        limiter.record(null);

        nowNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(2L));

        assertThat(limiter.currentBuckets(), anEmptyMap());
    }

    @Test
    void concurrentDecisionsRemainAtomic() throws Exception {
        var start = new CountDownLatch(1);
        RateLimiterImpl limiter = new RateLimiterImpl(
            request -> rateLimit().withBucket("shared")
                .withRate(1)
                .withWindow(1, TimeUnit.MINUTES)
                .build(),
            () -> 1L
        );
        var executor = Executors.newFixedThreadPool(8);
        try {
            var decisions =
                new ArrayList<Future<RateLimiterImpl.Decision>>();
            for (int i = 0; i < 16; i++) {
                decisions.add(executor.submit(() -> {
                    start.await();
                    return limiter.record(null);
                }));
            }

            start.countDown();

            long allowed = 0L;
            long rejected = 0L;
            for (var decision : decisions) {
                if (decision.get() == null) {
                    allowed++;
                } else {
                    rejected++;
                }
            }
            assertThat(allowed, is(1L));
            assertThat(rejected, is(15L));
            assertThat(limiter.currentBuckets().get("shared"), is(1L));
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "h2"})
    void rateLimitsHaveTheSameObservableBehaviorAcrossHttpVersions(
        String protocol
    ) throws Exception {
        var handlerCalls = new AtomicInteger();
        var rejected = new CompletableFuture<RejectedRequest>();
        try (MuServer limitedServer = ServerUtils.httpsServerForTest(protocol)
            .withRateLimiter(request -> rateLimit()
                .withBucket("shared")
                .withRate(1)
                .withWindow(1, TimeUnit.MINUTES)
                .build())
            .addRequestRejectListener(rejected::complete)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                handlerCalls.incrementAndGet();
                response.write("allowed");
            })
            .start()) {
            try (Response allowed = call(request(limitedServer.uri()))) {
                assertThat(allowed.code(), is(200));
                assertThat(allowed.body().string(), is("allowed"));
            }
            try (Response denied = call(request(limitedServer.uri())
                .header("x-client-only", "secret"))) {
                assertThat(denied.code(), is(429));
                assertThat(
                    denied.body().string(),
                    is("429 Too Many Requests")
                );
                assertThat(
                    denied.header("retry-after"),
                    matchesPattern("\\d+")
                );
                assertThat(denied.header("x-client-only"), nullValue());
            }

            RejectedRequest rejection = rejected.get(2, TimeUnit.SECONDS);
            assertThat(rejection.status(), is(429));
            assertThat(rejection.method().orElseThrow(), is("GET"));
            assertThat(handlerCalls.get(), is(1));
            assertEventually(
                () -> limitedServer.stats().completedRequests(),
                is(1L)
            );
            assertThat(limitedServer.stats().rejectedDueToOverload(), is(1L));
            assertThat(limitedServer.stats().activeRequests(), empty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "h2"})
    void ignoredDecisionDoesNotExposeLaterRetryMetadataToHandler(
        String protocol
    ) throws Exception {
        try (MuServer limitedServer = ServerUtils.httpsServerForTest(protocol)
            .withRateLimiter(request -> rateLimit()
                .withBucket("ignored-first")
                .withRate(1)
                .withWindow(1, TimeUnit.MINUTES)
                .withRejectionAction(RateLimitRejectionAction.IGNORE)
                .build())
            .withRateLimiter(request -> rateLimit()
                .withBucket("send-later")
                .withRate(1)
                .withWindow(1, TimeUnit.MINUTES)
                .build())
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                String retryAfter =
                    request.headers().get(HeaderNames.RETRY_AFTER);
                response.write(retryAfter == null ? "absent" : retryAfter);
            })
            .start()) {
            try (Response first = call(request(limitedServer.uri()))) {
                assertThat(first.body().string(), is("absent"));
            }
            try (Response ignored = call(request(limitedServer.uri()))) {
                assertThat(ignored.code(), is(200));
                assertThat(ignored.body().string(), is("absent"));
            }
        }
    }

    @Test
    void rateLimitedHttp2StreamDiscardsItsRemainingRequestBody() throws Exception {
        try (MuServer limitedServer = ServerUtils.httpsServerForTest("h2")
            .withRateLimiter(request -> rateLimit()
                .withBucket("shared")
                .withRate(1)
                .withWindow(1, TimeUnit.MINUTES)
                .build())
            .addHandler((request, response) -> {
                response.write("allowed");
                return true;
            })
            .start();
             H2Client client = new H2Client();
             H2ClientConnection connection = client.connect(limitedServer)) {
            int port = limitedServer.uri().getPort();
            connection.handshake()
                .writeFrame(new Http2HeadersFrame(
                    1,
                    true,
                    RFCTestUtils.getHelloHeaders(port)
                ))
                .flush();

            assertThat(
                RFCTestUtils.readIgnoringWindowUpdates(
                    connection,
                    Http2HeadersFrame.class
                ).headers().get(":status"),
                is("200")
            );
            assertThat(
                RFCTestUtils.readIgnoringWindowUpdates(
                    connection,
                    Http2DataFrame.class
                ).toUTF8(),
                is("allowed")
            );
            assertThat(
                RFCTestUtils.readIgnoringWindowUpdates(
                    connection,
                    Http2DataFrame.class
                ).endStream(),
                is(true)
            );

            connection.writeFrame(new Http2HeadersFrame(
                    3,
                    false,
                    RFCTestUtils.postHeaders(port, "/limited")
                ))
                .flush();
            Http2HeadersFrame rejected =
                RFCTestUtils.readIgnoringWindowUpdates(
                    connection,
                    Http2HeadersFrame.class
                );
            assertThat(rejected.streamId(), is(3));
            assertThat(rejected.headers().get(":status"), is("429"));
            assertThat(
                RFCTestUtils.readIgnoringWindowUpdates(
                    connection,
                    Http2DataFrame.class
                ).toUTF8(),
                is("429 Too Many Requests")
            );
            assertThat(
                RFCTestUtils.readIgnoringWindowUpdates(
                    connection,
                    Http2DataFrame.class
                ).endStream(),
                is(true)
            );

            byte[] pingPayload = {0, 1, 2, 3, 4, 5, 6, 7};
            connection
                .writeFrame(RFCTestUtils.utf8DataFrame(
                    3,
                    true,
                    "discarded after response"
                ))
                .writeFrame(new Http2Ping(false, pingPayload))
                .flush();
            assertThat(
                RFCTestUtils.readIgnoringWindowUpdates(
                    connection,
                    Http2Ping.class
                ),
                is(new Http2Ping(true, pingPayload))
            );

            connection.writeFrame(new Http2HeadersFrame(
                    5,
                    true,
                    RFCTestUtils.getHelloHeaders(port)
                ))
                .flush();
            Http2HeadersFrame nextResponse =
                RFCTestUtils.readIgnoringWindowUpdates(
                    connection,
                    Http2HeadersFrame.class
                );
            assertThat(nextResponse.streamId(), is(5));
            assertThat(nextResponse.headers().get(":status"), is("429"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "h2"})
    void selectorsRunOnTheHandlerDomain(String protocol) throws Exception {
        ExecutorService handlerExecutor = Executors.newSingleThreadExecutor(
            task -> new Thread(task, "rate-handler")
        );
        ExecutorService connectionExecutor = Executors.newSingleThreadExecutor(
            task -> new Thread(task, "rate-connection")
        );
        var selectorThread = new AtomicReference<String>();
        try (MuServer domainServer = ServerUtils.httpsServerForTest(protocol)
            .withHandlerExecutor(handlerExecutor)
            .withRateLimiter(request -> {
                selectorThread.set(Thread.currentThread().getName());
                return rateLimit()
                    .withBucket("domain")
                    .withRate(2)
                    .build();
            })
            .addHandler(Method.GET, "/", (request, response, pathParams) ->
                response.write("okay")
            )
            .start();
             Response ignored = call(request(domainServer.uri()))) {
            assertThat(ignored.body().string(), is("okay"));
            assertThat(selectorThread.get(), is("rate-handler"));
        } finally {
            handlerExecutor.shutdownNow();
            connectionExecutor.shutdownNow();
        }
    }

    @Test
    public void returningNullMeansAlwaysAllow() throws InterruptedException {
        RateLimiterImpl limiter = new RateLimiterImpl(request -> null);
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.record(null), is(nullValue()));
        }
        assertThat(limiter.currentBuckets().keySet(), is(empty()));
    }

    @Test
    public void ignoreActionDoesNotBlock() throws InterruptedException {
        RateLimiterImpl limiter = new RateLimiterImpl(request -> rateLimit().withBucket("blah")
            .withRate(1).withRejectionAction(RateLimitRejectionAction.IGNORE)
            .build());
        assertThat(limiter.record(null), nullValue());
        for (int i = 1; i < 10; i++) {
            assertThat(
                limiter.record(null).action(),
                equalTo(RateLimitRejectionAction.IGNORE)
            );
        }
        assertEventually(() -> limiter.currentBuckets().keySet(), is(empty()));
    }

    @Test
    public void emptyListReturnedWhenNoLimiters() throws Exception {
        MuServer server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> response.write("hi"))
            .start();
        for (int i = 0; i < 2; i++) {
            try (Response resp = call(request(server.uri()))) {
                assertThat("req " + i, resp.code(), is(200));
                assertThat("req " + i, resp.body().string(), is("hi"));
            }
        }
        for (int i = 0; i < 3; i++) {
            try (Response resp = call(request(server.uri()))) {
                assertThat(resp.code(), is(200));
                assertThat(resp.body().string(), is("hi"));
            }
        }
        assertThat(server.stats().rejectedDueToOverload(), is(0L));
        assertThat(server.rateLimiters().size(), is(0));
    }


    @Test
    public void multipleLimitersCanBeAddedToTheServer() throws Exception {
        MuServer server = ServerUtils.httpsServerForTest()
            .withRateLimiter(request -> rateLimit()
                .withBucket(request.remoteAddress())
                .withRate(100000) // this will not have an effect because it allows so many requests
                .withWindow(1, TimeUnit.MILLISECONDS)
                .build())
            .withRateLimiter(request -> RateLimit.builder()
                .withBucket(request.remoteAddress())
                .withRate(2) // this will just allow 2 through for this test before returning 429s
                .withWindow(1, TimeUnit.MINUTES)
                .build())
            .withRateLimiter(request -> rateLimit()
                .withBucket(request.remoteAddress())
                .withRate(1) // this will have no effect because although the rate will trip, the action is ignore
                .withWindow(1, TimeUnit.MINUTES)
                .withRejectionAction(RateLimitRejectionAction.IGNORE)
                .build())
            .addHandler(Method.GET, "/", (request, response, pathParams) -> response.write("hi"))
            .start();
        for (int i = 0; i < 2; i++) {
            try (Response resp = call(request(server.uri()))) {
                assertThat("req " + i, resp.code(), is(200));
                assertThat("req " + i, resp.body().string(), is("hi"));
            }
        }
        for (int i = 0; i < 3; i++) {
            try (Response resp = call(request(server.uri()))) {
                assertThat(resp.code(), is(429));
                assertThat(resp.body().string(), is("429 Too Many Requests"));
                List<String> retryAfter = resp.headers().values("retry-after");
                assertThat(retryAfter.toString(), retryAfter, contains(Matchers.matchesPattern("\\d{1,2}")));
            }
        }
        assertThat(server.stats().rejectedDueToOverload(), is(3L));
        assertThat(server.rateLimiters().size(), is(3));
        assertEventually(() -> server.rateLimiters().get(0).currentBuckets(), anEmptyMap());
        assertThat(server.rateLimiters().get(1).currentBuckets(), aMapWithSize(1));
        assertThat(server.rateLimiters().get(1).currentBuckets().get("127.0.0.1"), equalTo(2L));
        assertThat(server.rateLimiters().get(2).currentBuckets(), aMapWithSize(1));
        assertThat(server.rateLimiters().get(2).currentBuckets().get("127.0.0.1"), equalTo(1L));
    }

}
