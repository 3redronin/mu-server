package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class WebsocketWriteStateTest {
    @ParameterizedTest @ValueSource(ints = {0, 1, 125, 126, 65535, 65536, Integer.MAX_VALUE})
    void frameHeadersUseTheMinimalUnsignedNetworkLength(int length) throws Exception {
        var frame = new WebsocketWriteState().prepare(2, true, false, length);
        var input = new DataInputStream(new ByteArrayInputStream(frame.header));
        assertEquals(0x82, input.readUnsignedByte());
        int marker = input.readUnsignedByte();
        assertEquals(0, marker & 0x80);
        assertEquals(length < 126 ? length : length <= 65535 ? 126 : 127, marker);
        long decoded = marker == 127 ? input.readLong() : marker == 126 ? input.readUnsignedShort() : marker;
        assertEquals(length, decoded);
        assertEquals(-1, input.read());
    }

    @ParameterizedTest @ValueSource(ints = {1, 2})
    void fragmentationRejectsCompetingMessagesAndSurvivesInterleavedControls(int opcode) throws Exception {
        var state = new WebsocketWriteState();
        var first = state.prepare(opcode, false, true, 3);
        assertEquals(opcode, first.header[0]);
        state.written(first);
        assertThrows(IllegalStateException.class, () -> state.prepare(opcode, true, false, 1));
        assertThrows(IllegalStateException.class, () -> state.prepare(opcode == 1 ? 2 : 1, false, true, 1));
        assertThrows(IllegalStateException.class, () -> state.prepare(opcode == 1 ? 2 : 1, true, true, 1));
        for (int control : new int[]{9, 10}) {
            var frame = state.prepare(control, true, false, 125);
            assertEquals(0x80 | control, frame.header[0] & 255);
            state.written(frame);
        }
        var middle = state.prepare(opcode, false, true, 2);
        assertEquals(0, middle.header[0]);
        state.written(middle);
        var last = state.prepare(opcode, true, true, 0);
        assertEquals(0x80, last.header[0] & 255);
        state.written(last);
        assertEquals(0x81, state.prepare(1, true, false, 1).header[0] & 255);
        assertEquals(0x82, state.prepare(2, true, false, 1).header[0] & 255);
    }

    @Test
    void preparingOutputDoesNotPublishItsStateBeforeDrain() throws Exception {
        var state = new WebsocketWriteState();
        var first = state.prepare(1, false, true, 1);
        assertEquals(0x82, state.prepare(2, true, false, 0).header[0] & 255);
        state.written(first);
        var last = state.prepare(1, true, true, 0);
        assertThrows(IllegalStateException.class, () -> state.prepare(2, true, false, 0));
        state.written(last);
        var close = state.prepare(8, true, false, 2);
        assertEquals(0x81, state.prepare(1, true, false, 0).header[0] & 255);
        state.written(close);
        assertThrows(IllegalStateException.class, () -> state.prepare(9, true, false, 0));
    }

    @Test
    void failedOutputIsStickyAndCannotPublishPreparedFragmentsOrClose() throws Exception {
        var state = new WebsocketWriteState();
        state.prepare(1, false, true, 10);
        IOException failure = new IOException("partial transport write");
        state.fail(failure);
        state.fail(new IOException("later failure"));
        for (int opcode : new int[]{1, 2, 8, 9, 10}) {
            IOException repeated = assertThrows(IOException.class, () -> state.prepare(opcode, true, false, 0));
            assertSame(failure, repeated.getCause());
        }
    }

    @Test
    void invalidControlFramesDoNotChangeFragmentState() throws Exception {
        var state = new WebsocketWriteState();
        state.written(state.prepare(1, false, true, 1));
        for (int opcode : new int[]{8, 9, 10}) {
            assertThrows(IllegalArgumentException.class, () -> state.prepare(opcode, true, false, 126));
            assertThrows(IllegalArgumentException.class, () -> state.prepare(opcode, false, true, 0));
        }
        assertEquals(0x80, state.prepare(1, true, true, 0).header[0] & 255);
    }
}
