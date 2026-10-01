package io.muserver.rest;

import io.muserver.MuResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LazyAccessOutputStreamTest {
    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "true,true"})
    void closeCannotReopenTheResponseOrRunDeferredPreparation(boolean writeFirst, boolean failClose) throws Exception {
        var prepares = new AtomicInteger();
        var opens = new AtomicInteger();
        var wire = new ByteArrayOutputStream() {
            @Override public void close() throws IOException {
                if (failClose) throw new IOException("close failed");
            }
        };
        var response = (MuResponse) Proxy.newProxyInstance(MuResponse.class.getClassLoader(), new Class<?>[] {MuResponse.class},
            (proxy, method, args) -> {
                assertEquals("outputStream", method.getName());
                opens.incrementAndGet();
                return wire;
            });
        var out = new LazyAccessOutputStream(response, prepares::incrementAndGet);
        if (writeFirst) out.write('x');
        if (failClose) assertThrows(IOException.class, out::close);
        else out.close();
        assertThrows(IOException.class, () -> out.write('y'));
        assertThrows(IOException.class, () -> out.write(new byte[0], 0, 0));
        assertThrows(IOException.class, out::flush);
        out.close();
        assertEquals(writeFirst ? 1 : 0, prepares.get());
        assertEquals(writeFirst ? 1 : 0, opens.get());
        assertEquals(writeFirst ? "x" : "", wire.toString());
    }
}
