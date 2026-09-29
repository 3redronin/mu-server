package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import scaffolding.ServerTypeArgs;

import java.util.ArrayList;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.stopAndCheck;
import static scaffolding.ServerUtils.httpsServerForTest;

class HttpDateCacheTest {
    @Test
    void reusesTheValueOnlyWithinTheSameWallClockSecond() {
        var cache = new HttpDateCache();
        long millis = 1532785855000L;
        var first = cache.at(millis);
        assertEquals("Sat, 28 Jul 2018 13:50:55 GMT", first.toString());
        assertSame(first, cache.at(millis + 999));
        assertEquals("Sat, 28 Jul 2018 13:50:56 GMT", cache.at(millis + 1000).toString());
        // A backwards clock adjustment must refresh, rather than retain a future date.
        assertEquals(first.toString(), cache.at(millis).toString());
        assertEquals(Mutils.toHttpDate(new Date(-1)), cache.at(-1).toString());
        assertEquals(Mutils.toHttpDate(new Date(0)), cache.at(0).toString());
    }

    @Test
    void concurrentRefreshesKeepTimestampAndValueConsistent() throws Exception {
        var cache = new HttpDateCache();
        var pool = Executors.newFixedThreadPool(8);
        var futures = new ArrayList<Future<?>>();
        try {
            for (int thread = 0; thread < 8; thread++) {
                int offset = thread;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 1000; i++) {
                        long millis = 1532785855000L + ((i + offset) % 7) * 1000L;
                        assertEquals(Mutils.toHttpDate(new Date(millis)), cache.at(millis).toString());
                    }
                }));
            }
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ServerTypeArgs.class)
    void generatedDatesAreCurrentAndApplicationDatesArePreserved(String protocol) throws Exception {
        String custom = "Sun, 13 Sep 2026 00:00:00 GMT";
        var server = httpsServerForTest(protocol).addHandler((request, response) -> {
            if (request.relativePath().equals("/custom")) response.headers().set(HeaderNames.DATE, custom);
            if (request.relativePath().equals("/error")) {
                throw new HttpException(HttpStatus.BAD_REQUEST_400, "bad request");
            }
            if (request.relativePath().equals("/redirect")) {
                response.redirect("/target");
            }
            response.write("hello");
            return true;
        }).start();
        try {
            long before = System.currentTimeMillis();
            try (var response = call(request(server.uri()))) {
                assertEquals("hello", response.body().string());
                String date = response.header("date");
                assertNotNull(date);
                long generated = Mutils.fromHttpDate(date).getTime();
                assertTrue(generated >= before / 1000 * 1000);
                assertTrue(generated <= System.currentTimeMillis());
            }
            try (var response = call(request(server.uri().resolve("/custom")))) {
                assertEquals(custom, response.header("date"));
                assertEquals("hello", response.body().string());
            }
            for (String path : new String[]{"/error", "/redirect"}) {
                long started = System.currentTimeMillis();
                try (var response = call(request(server.uri().resolve(path)))) {
                    assertEquals(path.equals("/error") ? 400 : 302, response.code());
                    String date = response.header("date");
                    assertNotNull(date);
                    long generated = Mutils.fromHttpDate(date).getTime();
                    assertTrue(generated >= started / 1000 * 1000);
                    assertTrue(generated <= System.currentTimeMillis());
                }
            }
        } finally {
            stopAndCheck(server);
        }
    }
}
