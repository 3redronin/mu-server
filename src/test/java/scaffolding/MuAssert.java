package scaffolding;

import io.muserver.MuRequest;
import io.muserver.MuServer;
import io.muserver.StatusLogger;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.Assertions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class MuAssert {


    public static void assertNotTimedOut(String message, CountDownLatch latch) {
        assertNotTimedOut(message, latch, 30, TimeUnit.SECONDS);
    }

    public static void assertNotTimedOut(String message, CountDownLatch latch, int timeout, TimeUnit unit) {
        try {
            boolean completed = latch.await(timeout, unit);
            assertThat("Timed out: " + message, completed, is(true));
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted");
        }
    }

    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted", e);
        }
    }

    public static void stopAndCheck(MuServer server) {
        if (server != null) {
            int count = 0;
            while (count < 50 && !server.stats().activeRequests().isEmpty()) {
                sleep(50);
                count++;
            }
            Set<MuRequest> active = server.stats().activeRequests();
            StatusLogger.logRequests(active);
            assertThat("Expected no requests to still be in flight when stopping server",
                active, is(empty()));
            server.stop();
        }
    }

    public static void assertIOException(Throwable t) {
        if (t instanceof UncheckedIOException) {
            assertThat(t.getCause(), instanceOf(IOException.class));
        } else {
            assertThat(t, instanceOf(IOException.class));
        }
    }

    public static <T> void assertEventually(Func<T> actual, Matcher<? super T> matcher) {
        for (int i = 0; i < 100; i++) {
            try {
                T val = actual.apply();
                if (matcher.matches(val)) {
                    return;
                }
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                throw new RuntimeException("Finishing early", e);
            }
        }
        try {
            T result = actual.apply();
            assertThat("Actual: " + result, result, matcher);
        } catch (Exception e) {
            Assertions.fail("Lambda threw exception: " + e);
        }
    }

    public interface Func<V> {
        V apply() throws Exception;
    }
}
