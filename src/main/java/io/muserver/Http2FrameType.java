package io.muserver;

enum Http2FrameType {
    DATA((byte) 0x00, false, null, -1),
    HEADERS((byte) 0x01, true, true, -1),
    PRIORITY((byte) 0x02, false, null, 0x05),
    RST_STREAM((byte) 0x03, false, null, 0x04),
    SETTINGS((byte) 0x04, false, false, -1),
    PUSH_PROMISE((byte) 0x05, true, null, -1),
    PING((byte) 0x06, false, null, 0x08),
    GOAWAY((byte) 0x07, false, false, -1),
    WINDOW_UPDATE((byte) 0x08, false, null, 0x04),
    CONTINUATION((byte) 0x09, true, null, -1),
    UNKNOWN(Byte.MAX_VALUE, false, null, -1);

    private final byte b;
    private final boolean hasFieldBlock;
    private final Boolean hasStream;
    private final int fixedSize;

    Http2FrameType(byte b, boolean hasFieldBlock, Boolean hasStream, int fixedSize) {
        this.b = b;
        this.hasFieldBlock = hasFieldBlock;
        this.hasStream = hasStream;
        this.fixedSize = fixedSize;
    }

    public int fixedSize() {
        return fixedSize;
    }

    public byte byteCode() {
        return b;
    }

    public boolean hasFieldBlock() {
        return hasFieldBlock;
    }

    public Boolean hasStream() {
        return hasStream;
    }

    static Http2FrameType fromByte(byte b) {
        switch (b) {
            case 0x00:
                return DATA;
            case 0x01:
                return HEADERS;
            case 0x02:
                return PRIORITY;
            case 0x03:
                return RST_STREAM;
            case 0x04:
                return SETTINGS;
            case 0x05:
                return PUSH_PROMISE;
            case 0x06:
                return PING;
            case 0x07:
                return GOAWAY;
            case 0x08:
                return WINDOW_UPDATE;
            case 0x09:
                return CONTINUATION;
        }
        return UNKNOWN;
    }
}
