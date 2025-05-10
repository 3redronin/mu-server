package io.muserver;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

class Http2BodyInputStreamTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 11, 1024})
    void readsWhenDataIsAvailable(int copyBufferSize) throws Exception {

        var received = new ByteArrayOutputStream();

        try (var stream = new Http2BodyInputStream(10000)) {
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

    }

    private Http2DataFrame data(String data, boolean eos) {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        return new Http2DataFrame(1, eos, bytes, 0, bytes.length);
    }

}