package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Incremental client frame decoder. Each call consumes only the next frame's bytes and returns
 * null when more input is needed. Supplied storage is never mutated or retained; each completed
 * frame owns its payload until the application releases it. Failure is terminal and sticky.
 */
final class WebsocketFrameDecoder {
    static final class Frame {
        final int opcode;
        final boolean fin;
        final boolean text;
        final ByteBuffer payload;
        final int closeCode;
        final String closeReason;
        private Frame(int opcode, boolean fin, boolean text, ByteBuffer payload, int closeCode, String closeReason) {
            this.opcode = opcode;
            this.fin = fin;
            this.text = text;
            this.payload = payload;
            this.closeCode = closeCode;
            this.closeReason = closeReason;
        }
    }
    static final class InvalidFrame extends ProtocolException {
        final int closeCode;
        private InvalidFrame(int closeCode, String reason) {
            super(reason);
            this.closeCode = closeCode;
        }
    }
    private enum Phase { BASIC, LENGTH, MASK, PAYLOAD, CLOSED }
    private final int maximumFrame;
    private final long maximumMessage;
    private final byte[] header = new byte[14];
    private final WebsocketUtf8Validator textValidator = new WebsocketUtf8Validator();
    private final WebsocketUtf8Validator closeValidator = new WebsocketUtf8Validator();
    private Phase phase = Phase.BASIC;
    private int headerCount;
    private int lengthBytes;
    private int maskOffset;
    private int opcode;
    private boolean fin;
    private boolean text;
    private int fragmentedOpcode;
    private long messageLength;
    private int payloadLength;
    private @Nullable ByteBuffer payload;
    private @Nullable Exception failure;

    WebsocketFrameDecoder(int maximumFrame, long maximumMessage) {
        if (maximumFrame < 1 || maximumMessage < 1) throw new IllegalArgumentException("Positive frame/message limits required");
        this.maximumFrame = maximumFrame;
        this.maximumMessage = maximumMessage;
    }

    @Nullable Frame decode(ByteBuffer input) throws IOException {
        checkFailure();
        try {
            while (phase != Phase.CLOSED) {
                if (phase == Phase.BASIC) {
                    if (!header(input, 2)) return null;
                    int first = header[0] & 255;
                    fin = (first & 128) != 0;
                    opcode = first & 15;
                    if ((first & 112) != 0) throw invalid(1002, "Unsupported websocket reserved keywords");
                    if (opcode > 2 && opcode != 8 && opcode != 9 && opcode != 10) throw invalid(1002, "Unsupported websocket opcode: " + opcode);
                    if ((header[1] & 128) == 0) throw invalid(1002, "Unmasked client data");
                    int marker = header[1] & 127;
                    if (opcode >= 8 && !fin) throw invalid(1002, "Fragmented control frame");
                    if (opcode >= 8 && marker > 125) throw invalid(1002, "Control frame payload cannot exceed 125 bytes");
                    lengthBytes = marker == 126 ? 2 : marker == 127 ? 8 : 0;
                    phase = Phase.LENGTH;
                }
                if (phase == Phase.LENGTH) {
                    if (!header(input, 2 + lengthBytes)) return null;
                    long length = header[1] & 127;
                    if (lengthBytes > 0) {
                        length = 0;
                        for (int i = 0; i < lengthBytes; i++) length = (length << 8) | (header[2 + i] & 255);
                        if (length < 0) throw invalid(1002, "Invalid payload length");
                        if (length < (lengthBytes == 2 ? 126 : 65536)) throw invalid(1002, "Nonminimal websocket payload length");
                    }
                    if (length > maximumFrame) throw invalid(1009, "Max payload length of " + maximumFrame + " exceeded with frame size " + length);
                    if (opcode == 0 && fragmentedOpcode == 0) throw invalid(1002, "Continuation frame received unexpectedly");
                    if (opcode == 1 && fragmentedOpcode != 0) throw invalid(1002, "New text message sent while expecting continuation frame");
                    if (opcode == 2 && fragmentedOpcode != 0) throw invalid(1002, "New binary message received while expecting continuation frame");
                    if (opcode < 8) {
                        // Control frames are independent of a fragmented message. Subtraction
                        // keeps the aggregate guard safe even when its configured limit is long.
                        if (length > maximumMessage - messageLength) throw invalid(1009, "Max message length of " + maximumMessage + " exceeded");
                        messageLength += length;
                    }
                    text = opcode == 1 || (opcode == 0 && fragmentedOpcode == 1);
                    if (opcode == 1) textValidator.reset();
                    if (opcode == 8) {
                        if (length == 1) throw invalid(1002, "Close frame payload of 1 byte is invalid");
                        closeValidator.reset();
                    }
                    payloadLength = (int) length;
                    maskOffset = 2 + lengthBytes;
                    phase = Phase.MASK;
                }
                if (phase == Phase.MASK) {
                    if (!header(input, maskOffset + 4)) return null;
                    payload = ByteBuffer.allocate(payloadLength);
                    phase = Phase.PAYLOAD;
                }
                if (phase == Phase.PAYLOAD) {
                    ByteBuffer data = java.util.Objects.requireNonNull(payload);
                    while (data.hasRemaining() && input.hasRemaining()) {
                        int offset = data.position();
                        int value = (input.get() ^ header[maskOffset + (offset & 3)]) & 255;
                        data.put((byte) value);
                        // Reject an invalid prefix before waiting for the rest of this frame.
                        if (text && !textValidator.accept(value)) throw invalid(1007, "Non UTF-8 data in text frame");
                        if (opcode == 8) {
                            if (offset == 1) validateCloseCode(data.getShort(0) & 65535);
                            if (offset >= 2 && !closeValidator.accept(value)) throw invalid(1007, "Non UTF-8 data in close reason");
                        }
                    }
                    if (data.hasRemaining()) return null;
                    if (text && fin && !textValidator.isComplete()) throw invalid(1007, "Non UTF-8 data in text frame");
                    if (opcode == 8 && !closeValidator.isComplete()) throw invalid(1007, "Non UTF-8 data in close reason");
                    data.flip();
                    int closeCode = opcode == 8 ? (data.remaining() == 0 ? 1005 : data.getShort(0) & 65535) : -1;
                    String reason = opcode == 8 && data.remaining() > 2
                        ? StandardCharsets.UTF_8.decode(data.duplicate().position(2)).toString() : "";
                    Frame frame = new Frame(opcode, fin, text, data, closeCode, reason);
                    if (opcode < 8) {
                        if (fin) {
                            fragmentedOpcode = 0;
                            messageLength = 0;
                        } else if (opcode != 0) {
                            fragmentedOpcode = opcode;
                        }
                    }
                    payload = null;
                    headerCount = 0;
                    phase = opcode == 8 ? Phase.CLOSED : Phase.BASIC;
                    return frame;
                }
            }
            return null;
        } catch (IOException invalid) {
            failure = invalid;
            payload = null;
            throw invalid;
        }
    }

    void endOfInput() throws IOException {
        checkFailure();
        if (phase == Phase.CLOSED) return;
        ClientDisconnectedException disconnected = new ClientDisconnectedException();
        failure = disconnected;
        payload = null;
        throw disconnected;
    }

    private void checkFailure() throws IOException {
        if (failure instanceof IOException) throw (IOException) failure;
        if (failure instanceof ClientDisconnectedException) throw (ClientDisconnectedException) failure;
    }

    private boolean header(ByteBuffer input, int count) {
        int length = Math.min(count - headerCount, input.remaining());
        input.get(header, headerCount, length);
        headerCount += length;
        return headerCount == count;
    }

    private static void validateCloseCode(int code) throws InvalidFrame {
        // RFC 6455 section 7.4 plus registered 1012-1014 codes. No negotiated extension
        // defines 1016-2999 here; 1004-1006 and 1015 are reserved.
        if (code < 1000 || code >= 5000 || (code >= 1004 && code <= 1006) || (code >= 1015 && code < 3000)) {
            throw invalid(1002, "Invalid websocket close code: " + code);
        }
    }

    private static InvalidFrame invalid(int code, String message) { return new InvalidFrame(code, message); }
}
