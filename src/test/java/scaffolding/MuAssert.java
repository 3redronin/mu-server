package scaffolding;

import io.muserver.MuServer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

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
            while (count < 40 && !server.stats().activeRequests().isEmpty()) {
                sleep(50);
                count++;
            }
            assertThat(server.stats().activeRequests(), is(empty()));
            server.stop();
        }
    }
}
