package io.muserver;

import io.netty.util.HashedWheelTimer;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scaffolding.ServerUtils;

import java.io.UncheckedIOException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static io.muserver.RateLimitBuilder.rateLimit;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.assertEventually;

public class RateLimiterTest {

    private final HashedWheelTimer timer = new HashedWheelTimer();

    @Test
    public void returnsFalseIfLimitExceeded() throws Exception {
        RateLimiterImpl limiter = new RateLimiterImpl(
            request -> rateLimit().withBucket("blah").withRate(3).withWindow(100, TimeUnit.MILLISECONDS).build(),
            timer);
        limiter.record(null);
        limiter.record(null);
        limiter.record(null);
        assertThrows(RateLimitedException.class, () -> limiter.record(null));
        Thread.sleep(250);
        limiter.record(null);
        assertEventually(() -> limiter.currentBuckets().keySet(), is(empty()));
    }

    @Test
    public void returningNullMeansAlwaysAllow() throws Exception {
        RateLimiterImpl limiter = new RateLimiterImpl(request -> null, timer);
        for (int i = 0; i < 10; i++) {
            limiter.record(null);
        }
        assertThat(limiter.currentBuckets().keySet(), is(empty()));
    }

    @Test
    public void ignoreActionDoesNotBlock() throws Exception {
        RateLimiterImpl limiter = new RateLimiterImpl(request -> rateLimit().withBucket("blah")
            .withRate(1).withRejectionAction(RateLimitRejectionAction.IGNORE)
            .build(), timer);
        for (int i = 0; i < 10; i++) {
            limiter.record(null);
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
                .withBucket(request.clientIP())
                .withRate(100000) // this will not have an effect because it allows so many requests
                .withWindow(1, TimeUnit.MILLISECONDS)
                .build())
            .withRateLimiter(request -> RateLimit.builder()
                .withBucket(request.clientIP())
                .withRate(2) // this will just allow 2 through for this test before returning 429s
                .withWindow(1, TimeUnit.MINUTES)
                .build())
            .withRateLimiter(request -> rateLimit()
                .withBucket(request.clientIP())
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
                assertThat(resp.headers("retry-after").stream().map(Long::parseLong).collect(Collectors.toList()), contains(greaterThan(0L)));
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

    @Test
    public void connectionCanBeKilled() throws Exception {
        MuServer server = ServerUtils.httpsServerForTest()
            .withRateLimiter(request -> RateLimit.builder()
                .withBucket(request.clientIP())
                .withRate(1) // this will just allow 1 through for this test before closing connections
                .withWindow(1, TimeUnit.MINUTES)
                .withRejectionAction(RateLimitRejectionAction.CLOSE_CONNECTION)
                .build())
            .addHandler(Method.GET, "/", (request, response, pathParams) -> response.write("hi"))
            .start();
        try (Response resp = call(request(server.uri()))) {
            assertThat("req 0", resp.code(), is(200));
            assertThat("req 0", resp.body().string(), is("hi"));
        }

        assertThrows(UncheckedIOException.class, () -> call(request(server.uri())).close());

        assertThat(server.stats().rejectedDueToOverload(), is(1L));
        assertThat(server.rateLimiters().size(), is(1));
        assertThat(server.rateLimiters().get(0).currentBuckets(), aMapWithSize(1));
        assertThat(server.rateLimiters().get(0).currentBuckets().get("127.0.0.1"), equalTo(1L));
    }

    @AfterEach
    public void cleanup() {
        timer.stop();
    }

}