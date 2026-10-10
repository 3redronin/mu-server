package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.IOException;

/** A single writer owns this state; validation precedes IO and publication follows complete drain. */
final class WebsocketWriteState {
    static final class Frame {
        final byte[] header;
        final int nextMessageOpcode;
        final boolean close;
        private Frame(byte[] header, int nextMessageOpcode, boolean close) {
            this.header = header;
            this.nextMessageOpcode = nextMessageOpcode;
            this.close = close;
        }
    }

    private int messageOpcode;
    private boolean closed;
    private @Nullable IOException failure;

    Frame prepare(int opcode, boolean fin, boolean fragment, int payloadLength) throws IOException {
        if (failure != null) throw new IOException("Cannot write websocket messages after a previous write failed", failure);
        if (closed) throw new IllegalStateException("Cannot write websocket messages after close frame sent");
        if (payloadLength < 0) throw new IllegalArgumentException("Negative payload length");
        int next = messageOpcode;
        int wireOpcode = opcode;
        if (opcode == 1 || opcode == 2) {
            if (!fragment && !fin) throw new IllegalArgumentException("Whole messages must be final");
            if (messageOpcode != 0 && (!fragment || messageOpcode != opcode)) {
                throw new IllegalStateException("Another fragmented websocket message is in progress");
            }
            if (fragment && messageOpcode != 0) wireOpcode = 0;
            next = fin ? 0 : opcode;
        } else if (opcode == 8 || opcode == 9 || opcode == 10) {
            if (!fin || fragment) throw new IllegalArgumentException("Control frames cannot be fragmented");
            if (payloadLength > 125) throw new IllegalArgumentException("WebSocket control frame payload cannot exceed 125 bytes");
        } else {
            throw new IllegalArgumentException("Unsupported websocket opcode: " + opcode);
        }
        return new Frame(header((byte) ((fin ? 128 : 0) | wireOpcode), payloadLength), next, opcode == 8);
    }

    void written(Frame frame) {
        messageOpcode = frame.nextMessageOpcode;
        if (frame.close) closed = true;
    }

    void fail(IOException cause) {
        if (failure == null) failure = cause;
    }

    private static byte[] header(byte first, int length) {
        int extended = length <= 125 ? 0 : length <= 65535 ? 2 : 8;
        byte[] result = new byte[2 + extended];
        result[0] = first;
        result[1] = (byte) (extended == 0 ? length : extended == 2 ? 126 : 127);
        long remaining = length;
        for (int i = result.length - 1; i >= 2; i--) {
            result[i] = (byte) remaining;
            remaining >>>= 8;
        }
        return result;
    }
}
