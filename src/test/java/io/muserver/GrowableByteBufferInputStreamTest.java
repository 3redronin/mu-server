package io.muserver;

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class GrowableByteBufferInputStreamTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(50);
    private final Random rng = new Random();

    @Test
    public void itCanHaveStuffAddedAsyncyAndClosedButReadInASyncManner() throws IOException {
        GrowableByteBufferInputStream stream = new GrowableByteBufferInputStream();

        int totalSize = 0;
        List<ByteBuffer> generated = new ArrayList<>();
        for (int i = 0; i < 1; i++) {
            ByteBuffer buffer = randomBuffer();
            totalSize += buffer.remaining();
            generated.add(buffer);
        }

        ByteBuffer expected = ByteBuffer.allocate(totalSize);
        for (ByteBuffer byteBuffer : generated) {
            expected.put(byteBuffer);
            byteBuffer.position(0);
        }
        expected.position(0);
        executor.submit(() -> {
            for (ByteBuffer byteBuffer : generated) {
                stream.handOff(byteBuffer);
            }
            try {
                stream.close();
            } catch (IOException e) {
            }
        });

        ByteBuffer actual = ByteBuffer.allocate(expected.capacity());
        byte[] data = new byte[128];
        int read;
        while ((read = stream.read(data)) > -1) {
            assertThat(read, greaterThan(0));
            actual.put(data, 0, read);
        }

        actual.position(0);
        assertThat(actual.asCharBuffer(), equalTo(expected.asCharBuffer()));
    }


    @Test
    public void itCanBeSwitchedToListenerMode() throws InterruptedException {
        GrowableByteBufferInputStream gb = new GrowableByteBufferInputStream();
        List<Throwable> errors = new ArrayList<>();
        List<ByteBuffer> sent = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            sent.add(randomBuffer());
        }

        CountDownLatch atLeastSomeReceivedBeforeSwitch = new CountDownLatch(1);
        CountDownLatch atLeastSomeReceivedAfterSwitch = new CountDownLatch(1);

        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    int count = 0;
                    for (ByteBuffer byteBuffer : sent) {
                        gb.handOff(byteBuffer);
                        if (count == 10) {
                            atLeastSomeReceivedBeforeSwitch.countDown();
                        }
                        if (count == 80) {
                            atLeastSomeReceivedAfterSwitch.await(30, TimeUnit.SECONDS);
                        }
                        count++;
                    }
                    gb.close();
                } catch (Exception e) {
                    errors.add(e);
                }
            }
        });

        assertThat("Timed out waiting for something to happen", atLeastSomeReceivedBeforeSwitch.await(1, TimeUnit.MINUTES));

        List<ByteBuffer> received = new ArrayList<>();
        CountDownLatch completeLatch = new CountDownLatch(1);
        gb.switchToListener(new RequestBodyListener() {
            @Override
            public void onDataReceived(ByteBuffer buffer) {
                received.add(buffer);
            }

            @Override
            public void onComplete() {
                completeLatch.countDown();
            }

            @Override
            public void onError(Throwable t) {

            }
        });

        atLeastSomeReceivedAfterSwitch.countDown();

        assertThat(errors, is(empty()));
        assertThat("Timed out waiting for end", completeLatch.await(30, TimeUnit.SECONDS), is(true));
        assertThat(received, equalTo(sent));
    }

    private ByteBuffer randomBuffer() {
        int size = 1 + rng.nextInt(16384);
        byte[] bytes = new byte[size];
        rng.nextBytes(bytes);
        return ByteBuffer.wrap(bytes);
    }
}