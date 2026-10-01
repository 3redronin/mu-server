package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class Http2ResponseFrameTest {
    private static Http2ResponseFrame response() {
        var headers = new FieldBlock();
        headers.set(":status", "200");
        return new Http2ResponseFrame(new Http2HeadersFrame(1, false, headers), true,
            "hello".getBytes(StandardCharsets.UTF_8), 0, 5);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, 100})
    void creditControlsDataButNeverHoldsHeaders(int credit) throws Exception {
        var coordinator = new Http2WriteCoordinator(credit);
        coordinator.openStream(1, credit);
        var task = new WriteTask(response(), true);
        coordinator.submit(task);
        coordinator.processAvailableCommands();
        var first = coordinator.pollWritable();
        assertNotNull(first);
        assertEquals(Math.min(5, credit), first.frame().flowControlSize());
        assertEquals(credit >= 5, first.frame().endStream());
        assertTrue(first.beginWrite());
        if (credit == 0) assertInstanceOf(Http2HeadersFrame.class, first.frame());
        else assertInstanceOf(Http2ResponseFrame.class, first.frame());
        first.complete();
        assertNull(coordinator.pollWritable());
        if (credit < 5) {
            coordinator.applyConnectionWindowUpdate(5, 1);
            coordinator.applyStreamWindowUpdate(1, 5);
            coordinator.processAvailableCommands();
            var rest = coordinator.pollWritable();
            assertNotNull(rest);
            assertEquals(5 - credit, rest.frame().flowControlSize());
            assertTrue(rest.frame().endStream());
            var remainder = new ByteArrayOutputStream();
            rest.frame().writeTo(null, remainder);
            assertEquals(0, remainder.toByteArray()[3], "Headers must not be repeated");
            assertTrue(rest.beginWrite());
            rest.complete();
        }
        task.await(1, TimeUnit.SECONDS);
        assertNull(coordinator.pollWritable());
    }

    @Test
    void peerResetAfterHeadersFailsTheRemainingBody() throws Exception {
        var coordinator = new Http2WriteCoordinator(0);
        coordinator.openStream(1, 0);
        var task = new WriteTask(response(), true);
        coordinator.submit(task);
        coordinator.processAvailableCommands();
        var first = coordinator.pollWritable();
        assertNotNull(first);
        assertTrue(first.beginWrite());
        first.complete();
        coordinator.resetStream(new Http2ResetStreamFrame(1, Http2ErrorCode.CANCEL.code()),
            new IOException("peer cancelled"), null);
        coordinator.processAvailableCommands();
        assertThrows(IOException.class, () -> task.await(1, TimeUnit.SECONDS));
        assertNull(coordinator.pollWritable());
    }

    @Test
    void emitsHeadersAndTerminalDataInOneBulkWriteWithoutFlushing() throws Exception {
        var bytes = new ByteArrayOutputStream();
        int[] calls = {0};
        var output = new OutputStream() {
            @Override public void write(int value) { fail("Expected a bulk write"); }
            @Override public void write(byte[] data, int offset, int length) {
                calls[0]++;
                bytes.write(data, offset, length);
            }
            @Override public void flush() { fail("Coordinator owns flush"); }
        };
        var coordinator = new Http2WriteCoordinator(100);
        response().writeTo(new Http2Peer() {
            @Override public int maxFrameSize() { return 16384; }
            @Override public FieldBlockEncoder fieldBlockEncoder() { return coordinator.fieldBlockEncoder(); }
        }, output);
        assertEquals(1, calls[0]);
        var wire = ByteBuffer.wrap(bytes.toByteArray());
        var headers = Http2FrameHeader.readFrom(wire);
        assertEquals(Http2FrameType.HEADERS, headers.frameType());
        assertEquals(4, headers.flags());
        wire.position(wire.position() + headers.length());
        var data = Http2FrameHeader.readFrom(wire);
        assertEquals(Http2FrameType.DATA, data.frameType());
        assertEquals(0, data.flags());
        assertEquals(5, data.length());
        assertEquals(1, data.streamId());
        byte[] payload = new byte[data.length()];
        wire.get(payload);
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), payload);
        var end = Http2FrameHeader.readFrom(wire);
        assertEquals(Http2FrameType.DATA, end.frameType());
        assertEquals(1, end.flags());
        assertEquals(0, end.length());
        assertFalse(wire.hasRemaining());
    }
}
