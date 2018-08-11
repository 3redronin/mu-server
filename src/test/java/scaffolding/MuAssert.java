package scaffolding;

import io.muserver.MuServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

public class MuAssert {
    private static final Logger log = LoggerFactory.getLogger(MuAssert.class);

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
            long start = System.currentTimeMillis();
            int count = 0;
            while (count < 40 && !server.stats().activeRequests().isEmpty()) {
                sleep(50);
                count++;
                if (count > 2) {
                    log.info("Waiting " + (System.currentTimeMillis() - start) + "ms for requests to end");
                }
            }
            assertThat(server.stats().activeRequests(), is(empty()));
            server.stop();
        }
    }

    public static void waitUntil(Callable<Boolean> check) throws Exception {
        long start = System.currentTimeMillis();

        while (!check.call()) {
            Thread.sleep(100);
            if ((System.currentTimeMillis() - start) > 30000) {
                throw new TimeoutException("Timed out");
            }
        }

    }
}
