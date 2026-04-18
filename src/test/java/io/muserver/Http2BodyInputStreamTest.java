package io.muserver;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

class Http2BodyInputStreamTest {

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

    private Http2DataFrame data(String data, boolean eos) {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        return new Http2DataFrame(1, eos, bytes, 0, bytes.length);
    }

}