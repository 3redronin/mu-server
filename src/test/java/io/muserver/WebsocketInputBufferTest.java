package io.muserver;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class WebsocketInputBufferTest {
    @Test
    void bytewiseIncrementalReadsUseTheFreeTailBeforeMovingThePayload() throws Exception {
        int capacity = 8192;
        int headerLength = 14;
        // Include a backing-array offset, as well as the already-consumed frame header.
        ByteBuffer buffer = ByteBuffer.wrap(new byte[capacity + 11], 11, capacity).slice();
        buffer.position(headerLength).limit(headerLength);
        InputStream input = new InputStream() {
            int delivered;
            @Override public int read() { throw new AssertionError("Expected bulk read"); }
            @Override public int read(byte[] target, int offset, int length) {
                // A single necessary compaction occurs when the end of the array is reached.
                // Moving the growing prefix sooner leads to repeated copies without useful space gain.
                int expectedPosition = delivered < capacity - headerLength ? headerLength : 0;
                assertEquals(expectedPosition, buffer.position(), "Premature compaction after " + delivered + " bytes");
                assertTrue(length > 0, "Refill must make progress");
                target[offset] = (byte) delivered++;
                return 1;
            }
        };
        for (int requested = 1; requested <= capacity; requested++) {
            WebsocketConnection.readAtLeast(buffer, input, requested);
            assertEquals(requested, buffer.remaining());
            assertEquals((byte) (requested - 1), buffer.get(buffer.position() + requested - 1));
        }
        for (int i = 0; i < capacity; i++) assertEquals((byte) i, buffer.get());
    }

    @Test
    void compactionOfAFullBufferPreservesUnreadBytes() throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[]{0, 1, 2, 3, 4, 5, 6, 7});
        buffer.position(5);
        WebsocketConnection.readAtLeast(buffer, new ByteArrayInputStream(new byte[]{8, 9}), 5);
        byte[] actual = new byte[5];
        buffer.get(actual);
        assertArrayEquals(new byte[]{5, 6, 7, 8, 9}, actual);
    }

    @Test
    void eofWithAPartialPayloadIsReportedWithoutDiscardingIt() {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.put((byte) 42).flip();
        assertThrows(ClientDisconnectedException.class,
            () -> WebsocketConnection.readAtLeast(buffer, new ByteArrayInputStream(new byte[0]), 2));
        assertEquals(42, buffer.get());
    }
}
