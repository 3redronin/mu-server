package io.muserver.rest;

import io.muserver.MuResponse;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class LazyAccessOutputStreamTest {

    @Test
    public void streamsInsteadOfBufferingBeyondTheDeferredByteLimit() throws Exception {
        ByteArrayOutputStream actual = new ByteArrayOutputStream();
        AtomicInteger outputStreamAccesses = new AtomicInteger();
        LazyAccessOutputStream stream = new LazyAccessOutputStream(response(actual, outputStreamAccesses), () -> { });

        stream.write(new byte[LazyAccessOutputStream.MAX_DEFERRED_BYTES]);
        assertThat(outputStreamAccesses.get(), is(0));

        stream.write(1);
        assertThat(outputStreamAccesses.get(), is(1));
        assertThat(actual.size(), is(LazyAccessOutputStream.MAX_DEFERRED_BYTES + 1));
    }

    @Test
    public void uncommittedBytesCanBeDiscardedBeforeAWrapperIsClosed() throws Exception {
        ByteArrayOutputStream actual = new ByteArrayOutputStream();
        AtomicInteger outputStreamAccesses = new AtomicInteger();
        LazyAccessOutputStream stream = new LazyAccessOutputStream(response(actual, outputStreamAccesses), () -> { });

        stream.write(new byte[10]);
        stream.releaseWrites();
        stream.discardUncommittedWrites();
        stream.write(new byte[10]);
        stream.close();

        assertThat(outputStreamAccesses.get(), is(0));
        assertThat(actual.size(), is(0));
    }

    @Test
    public void closingDoesNotCommitDeferredBytesBeforeFinish() throws Exception {
        ByteArrayOutputStream actual = new ByteArrayOutputStream();
        AtomicInteger outputStreamAccesses = new AtomicInteger();
        LazyAccessOutputStream stream = new LazyAccessOutputStream(response(actual, outputStreamAccesses), () -> { });

        stream.releaseWrites();
        stream.deferUncommittedWrites();
        stream.write(new byte[10]);
        stream.close();

        assertThat(outputStreamAccesses.get(), is(0));
        stream.finish();
        assertThat(outputStreamAccesses.get(), is(1));
        assertThat(actual.size(), is(10));
    }

    private static MuResponse response(ByteArrayOutputStream output, AtomicInteger outputStreamAccesses) {
        return (MuResponse) Proxy.newProxyInstance(
            MuResponse.class.getClassLoader(),
            new Class[]{MuResponse.class},
            (proxy, method, args) -> {
                if (method.getName().equals("outputStream")) {
                    outputStreamAccesses.incrementAndGet();
                    return output;
                }
                throw new UnsupportedOperationException(method.getName());
            }
        );
    }
}
