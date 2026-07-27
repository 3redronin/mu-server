package io.muserver;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Http2BodyInputStreamTest {

    @Test
    void zeroLengthReadReturnsImmediatelyWithoutWaitingForData() throws Exception {
        try (var stream = new Http2BodyInputStream(1, credit -> {}, credit -> {})) {
            assertThat(stream.read(new byte[1], 0, 0), equalTo(0));
        }
    }

    @Test
    void zeroTimeoutWaitsUntilDataArrives() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var stream = new Http2BodyInputStream(0, credit -> {}, credit -> {})) {
            var aboutToRead = new CountDownLatch(1);
            var read = executor.submit(() -> {
                aboutToRead.countDown();
                return stream.read();
            });

            assertThat(aboutToRead.await(5, TimeUnit.SECONDS), equalTo(true));
            assertThrows(TimeoutException.class, () -> read.get(50, TimeUnit.MILLISECONDS));
            stream.onData(data("x", true));

            assertThat(read.get(5, TimeUnit.SECONDS), equalTo((int) 'x'));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void finiteTimeoutThrows408WithoutAnHttp1ConnectionHeader() throws Exception {
        try (var stream = new Http2BodyInputStream(1, credit -> {}, credit -> {})) {
            var timeout = assertThrows(HttpException.class, stream::read);

            assertThat(timeout.status(), equalTo(HttpStatus.REQUEST_TIMEOUT_408));
            assertThat(timeout.responseHeaders().get(HeaderNames.CONNECTION), nullValue());
        }
    }

    @Test
    void returningFlowControlCreditDoesNotRetainTheBodyBufferLock() throws Exception {
        var callbackStarted = new CountDownLatch(1);
        var releaseCallback = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        try (var stream = new Http2BodyInputStream(
            10_000,
            credit -> {
                callbackStarted.countDown();
                try {
                    if (!releaseCallback.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting to release credit callback");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Credit callback was interrupted", e);
                }
            },
            credit -> {}
        )) {
            stream.onData(data("a", false));
            var read = executor.submit(() -> stream.read());

            assertThat(callbackStarted.await(5, TimeUnit.SECONDS), equalTo(true));
            var producer = executor.submit(() -> stream.onData(data("b", true)));

            assertThat(producer.get(1, TimeUnit.SECONDS), nullValue());
            releaseCallback.countDown();
            assertThat(read.get(5, TimeUnit.SECONDS), equalTo((int) 'a'));
        } finally {
            releaseCallback.countDown();
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 11, 1024})
    void readsWhenDataIsAvailable(int copyBufferSize) throws Exception {

        var received = new ByteArrayOutputStream();
        var callbackValue = new AtomicLong();

        try (var stream = new Http2BodyInputStream(10000, callbackValue::addAndGet, credit -> {})) {
            var t = new Thread(() -> {
                try {
                    Mutils.copy(stream, received, copyBufferSize);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            t.start();

            stream.onData(data("Hello ", false));
            stream.onData(data("", false));
            stream.onData(data("world", true));

            t.join(10000);
        }

        assertThat(received.toString(StandardCharsets.UTF_8), equalTo("Hello world"));
        assertThat(callbackValue.get(), equalTo(11L));
    }

    @Test
    void doesNotReturnZeroAfterExactlyConsumingADataFrame() throws Exception {
        var readCredit = new AtomicLong();

        try (var stream = new Http2BodyInputStream(10000, readCredit::addAndGet, credit -> {})) {
            stream.onData(data("Hello", false));
            stream.onData(data("world", true));

            byte[] buffer = new byte[5];
            assertThat(stream.read(buffer, 0, 5), equalTo(5));
            assertThat(stream.read(buffer, 0, 5), equalTo(5));
            assertThat(new String(buffer, StandardCharsets.UTF_8), equalTo("world"));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5})
    void flowControlCreditIncludesPaddingWhenAFrameIsFullyConsumed(int firstReadSize) throws Exception {
        var callbackValue = new AtomicLong();

        try (var stream = new Http2BodyInputStream(10000, callbackValue::addAndGet, credit -> {})) {
            stream.onData(data("Hello", false), 8);

            var buffer = new byte[Math.max(firstReadSize, 8)];
            assertThat(stream.read(buffer, 0, firstReadSize), equalTo(firstReadSize));
            assertThat(callbackValue.get(), equalTo(firstReadSize == 5 ? 8L : (long) firstReadSize));

            if (firstReadSize < 5) {
                assertThat(stream.read(buffer, firstReadSize, 5 - firstReadSize), equalTo(5 - firstReadSize));
                assertThat(callbackValue.get(), equalTo(8L));
            }
        }
    }

    @Test
    void paddingOnlyDataReturnsFlowControlCreditImmediately() throws Exception {
        var callbackValue = new AtomicLong();

        try (var stream = new Http2BodyInputStream(10000, callbackValue::addAndGet, credit -> {})) {
            stream.onData(data("", false), 8);

            assertThat(callbackValue.get(), equalTo(8L));
            assertThat(stream.isRequestBodyComplete(), equalTo(false));
        }
    }

    @Test
    void paddingOnlyEndStreamReturnsCreditAndPublishesEof() throws Exception {
        var callbackValue = new AtomicLong();

        try (var stream = new Http2BodyInputStream(10000, callbackValue::addAndGet, credit -> {})) {
            stream.onData(data("", true), 8);

            assertThat(callbackValue.get(), equalTo(8L));
            assertThat(stream.read(), equalTo(-1));
            assertThat(stream.isRequestBodyComplete(), equalTo(true));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4})
    void unreadQueuedDataIsRefundedOnCancel(int firstReadSize) throws Exception {
        var readCallbackValue = new AtomicLong();
        var discardCallbackValue = new AtomicLong();

        try (var stream = new Http2BodyInputStream(10000, readCallbackValue::addAndGet, discardCallbackValue::addAndGet)) {
            stream.onData(data("Hello", false), 8);

            var buffer = new byte[8];
            assertThat(stream.read(buffer, 0, firstReadSize), equalTo(firstReadSize));

            stream.cancel(new IOException("cancelled"));
        }

        assertThat(readCallbackValue.get(), equalTo((long) firstReadSize));
        assertThat(discardCallbackValue.get(), equalTo(8L - firstReadSize));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4})
    void unreadQueuedCreditAcrossMultipleFramesIsRefundedOnCancel(int firstReadSize) throws Exception {
        var readCallbackValue = new AtomicLong();
        var discardCallbackValue = new AtomicLong();

        try (var stream = new Http2BodyInputStream(10000, readCallbackValue::addAndGet, discardCallbackValue::addAndGet)) {
            stream.onData(data("Hello", false), 8);
            stream.onData(data("world", false), 9);

            var buffer = new byte[8];
            assertThat(stream.read(buffer, 0, firstReadSize), equalTo(firstReadSize));

            stream.cancel(new IOException("cancelled"));
        }

        assertThat(readCallbackValue.get(), equalTo((long) firstReadSize));
        assertThat(discardCallbackValue.get(), equalTo((8L - firstReadSize) + 9L));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4})
    void partialReadResetRefundsUnreadCreditOnlyOnce(int firstReadSize) throws Exception {
        var readCallbackValue = new AtomicLong();
        var discardCallbackValue = new AtomicLong();

        try (var stream = new Http2BodyInputStream(10000, readCallbackValue::addAndGet, discardCallbackValue::addAndGet)) {
            stream.onData(data("Hello", false), 8);

            var buffer = new byte[8];
            assertThat(stream.read(buffer, 0, firstReadSize), equalTo(firstReadSize));

            stream.onStreamReset(new Http2ResetStreamFrame(1, Http2ErrorCode.CANCEL.code()));
            stream.cancel(new IOException("second terminal call"));
        }

        assertThat(readCallbackValue.get(), equalTo((long) firstReadSize));
        assertThat(discardCallbackValue.get(), equalTo(8L - firstReadSize));
    }

    @Test
    void applicationCompletionReturnsUnreadReusableCreditOnlyOnce() throws Exception {
        var readCallbackValue = new AtomicLong();
        var discardCallbackValue = new AtomicLong();

        try (var stream = new Http2BodyInputStream(10000, readCallbackValue::addAndGet, discardCallbackValue::addAndGet)) {
            stream.onData(data("Hello", false), 8);

            var buffer = new byte[8];
            assertThat(stream.read(buffer, 0, 2), equalTo(2));

            stream.discardRemaining();
            stream.discardRemaining();

            assertThat(stream.read(buffer), equalTo(-1));
            assertThat(stream.isRequestBodyComplete(), equalTo(false));
        }

        assertThat(readCallbackValue.get(), equalTo(8L));
        assertThat(discardCallbackValue.get(), equalTo(0L));
    }

    @Test
    void dataArrivingInDiscardModeReturnsReusableCreditUntilPeerEndStream() throws Exception {
        var readCallbackValue = new AtomicLong();
        var discardCallbackValue = new AtomicLong();

        try (var stream = new Http2BodyInputStream(10000, readCallbackValue::addAndGet, discardCallbackValue::addAndGet)) {
            stream.discardRemaining();

            stream.onData(data("Hello", false), 8);
            assertThat(stream.isRequestBodyComplete(), equalTo(false));
            stream.onData(data("bye", true), 5);

            assertThat(stream.read(), equalTo(-1));
            assertThat(stream.isRequestBodyComplete(), equalTo(true));
        }

        assertThat(readCallbackValue.get(), equalTo(13L));
        assertThat(discardCallbackValue.get(), equalTo(0L));
    }

    private Http2DataFrame data(String data, boolean eos) {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        return new Http2DataFrame(1, eos, bytes, 0, bytes.length);
    }

}
