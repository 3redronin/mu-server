package io.muserver;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Objects;

class Http2Settings implements LogicalHttp2Frame {
    private static final Logger log = LoggerFactory.getLogger(Http2Settings.class);
    static Http2Settings DEFAULT_CLIENT_SETTINGS = new Http2Settings(false,
        4096, 100, 65535, 16384, 32 * 1024
    );

    final boolean isAck;
    /**
     * This setting allows the sender to inform the remote endpoint of the maximum size of the
     * compression table used to decode field blocks, in units of octets. The encoder can select any
     * size equal to or less than this value by using signaling specific to the compression format
     * inside a field block (see [COMPRESSION]). The initial value is 4,096 octets.
     */
    final int headerTableSize;

    /**
     * This setting indicates the maximum number of concurrent streams that the sender will allow.
     * This limit is directional: it applies to the number of streams that the sender permits the
     * receiver to create. Initially, there is no limit to this value. It is recommended that this
     * value be no smaller than 100, so as to not unnecessarily limit parallelism.
     *
     * <p>A value of 0 for SETTINGS_MAX_CONCURRENT_STREAMS SHOULD NOT be treated as special by
     * endpoints. A zero value does prevent the creation of new streams; however, this can also happen
     * for any limit that is exhausted with active streams. Servers SHOULD only set a zero value for
     * short durations; if a server does not wish to accept requests, closing the connection is more
     * appropriate.</p>
     */
    final int maxConcurrentStreams;

    /**
     * This setting indicates the sender's initial window size (in units of octets) for stream-level
     * flow control. The initial value is 2^16-1 (65,535) octets.
     *
     * <p>This setting affects the window size of all streams (see Section 6.9.2).</p>
     *
     * <p>Values above the maximum flow-control window size of 2^31-1 MUST be treated as a connection
     * error (Section 5.4.1) of type FLOW_CONTROL_ERROR.</p>
     */
    final int initialWindowSize;

    /**
     * This setting indicates the size of the largest frame payload that the sender is willing to receive, in units of octets.
     *
     * <p>The initial value is 2^14 (16,384) octets. The value advertised by an endpoint MUST be
     * between this initial value and the maximum allowed frame size (2^24-1 or 16,777,215 octets),
     * inclusive. Values outside this range MUST be treated as a connection error (Section 5.4.1)
     * of type PROTOCOL_ERROR.</p>
     */
    final int maxFrameSize;

    /**
     * This advisory setting informs a peer of the maximum field section size that the sender is
     * prepared to accept, in units of octets. The value is based on the uncompressed size of field
     * lines, including the length of the name and value in units of octets plus an overhead of
     * 32 octets for each field line.
     *
     * <p>For any given request, a lower limit than what is advertised MAY be enforced. The initial
     * value of this setting is unlimited.</p>
     */
    final int maxHeaderListSize;

    Http2Settings(boolean isAck, int headerTableSize, int maxConcurrentStreams, int initialWindowSize, int maxFrameSize, int maxHeaderListSize) {
        this.isAck = isAck;
        this.headerTableSize = headerTableSize;
        this.maxConcurrentStreams = maxConcurrentStreams;
        this.initialWindowSize = initialWindowSize;
        this.maxFrameSize = maxFrameSize;
        this.maxHeaderListSize = maxHeaderListSize;
    }

    /**
     * zero body length type 4 (settings) ack bit set with no stream ID
     */
    private static final byte[] ackBytes = new byte[] { 0, 0, 0, 4, 1, 0, 0, 0, 0 };

    public void writeTo(@Nullable Http2Peer connection, OutputStream out) throws IOException {
        if (isAck) {
            out.write(ackBytes);
        } else {
            // we have 5 settings - so payload of 5 * 6 bytes, type 4, no ack and no stream ID
            out.write(new byte[] {
                // header
                0, 0, 5 * 6, 4, 0, 0, 0, 0, 0,
                // setting list: 2 byte identifier followed by 4 byte unsigned int value
                0, 1, (byte)((headerTableSize >> 16) & 0xFF), (byte)((headerTableSize >> 24) & 0xFF), (byte)((headerTableSize >> 8) & 0xFF), (byte)(headerTableSize & 0xFF),
                0, 3, (byte)((maxConcurrentStreams >> 16) & 0xFF), (byte)((maxConcurrentStreams >> 24) & 0xFF), (byte)((maxConcurrentStreams >> 8) & 0xFF), (byte)(maxConcurrentStreams & 0xFF),
                0, 4, (byte)((initialWindowSize >> 16) & 0xFF), (byte)((initialWindowSize >> 24) & 0xFF), (byte)((initialWindowSize >> 8) & 0xFF), (byte)(initialWindowSize & 0xFF),
                0, 5, (byte)((maxFrameSize >> 16) & 0xFF), (byte)((maxFrameSize >> 24) & 0xFF), (byte)((maxFrameSize >> 8) & 0xFF), (byte)(maxFrameSize & 0xFF),
                0, 6, (byte)((maxHeaderListSize >> 16) & 0xFF), (byte)((maxHeaderListSize >> 24) & 0xFF), (byte)((maxHeaderListSize >> 8) & 0xFF), (byte)(maxHeaderListSize & 0xFF)
            });

        }
    }

    static Http2Settings readFrom(Http2FrameHeader header, ByteBuffer buffer) throws Http2Exception {
        if (header.length() % 6 != 0) {
            throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR, "Settings size invalid");
        }
        boolean isAck = (header.flags() & 0b00000001) > 0;
        if (isAck) {
            if (header.length() > 0) {
            /*
            When set, the ACK flag indicates that this frame acknowledges receipt and application of the peer's
            SETTINGS frame. When this bit is set, the frame payload of the SETTINGS frame MUST be empty. Receipt
            of a SETTINGS frame with the ACK flag set and a length field value other than 0 MUST be treated as a
            connection error (Section 5.4.1) of type FRAME_SIZE_ERROR. For more information, see Section 6.5.3
            ("Settings Synchronization").
             */
                throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR, "Settings ACK with settings");
            }
            return Http2Settings.ACK;
        }
        var toGo = header.length() / 6;
        int tableHeaderSize = -1;
        int maxConcurrentStreams  = -1;
        int initialWindowSize  = -1;
        int maxFrameSize = -1;
        int maxHeaderListSize = -1;
        while (toGo > 0) {
            int identifier = buffer.getShort() & 0xFFFF;
            long value = buffer.getInt() & 0xFFFFFFFFL;
            if (identifier == 0x01) {
                tableHeaderSize = value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
            } else if (identifier == 0x03) {
                maxConcurrentStreams = value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
            } else if (identifier == 0x04) {
                if (value > Integer.MAX_VALUE) {
                    throw new Http2Exception(Http2ErrorCode.FLOW_CONTROL_ERROR, "initialWindowSize too large");
                }
                initialWindowSize = (int) value;
            } else if (identifier == 0x05) {
                if (value < 16384L || value > 16777215L) {
                    throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "Invalid maxFrameSize");
                }
                maxFrameSize = (int) value;
            } else if (identifier == 0x06) {
                maxHeaderListSize = value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
            } else {
                log.info("Ignoring unknown setting " + identifier + "=" + value);
            }
            toGo--;
        }
        return new Http2Settings(false, tableHeaderSize, maxConcurrentStreams, initialWindowSize, maxFrameSize, maxHeaderListSize);
    }

    static final Http2Settings ACK = new Http2Settings(true, -1, -1, -1, -1, -1);

    private static int updatedValue(int oldValue, int newValue) {
        return newValue == -1 ? oldValue : newValue;
    }

    Http2Settings copyIfChanged(Http2Settings existingSettings) {
        assert !existingSettings.isAck;
        int newHeaderTableSize = updatedValue(existingSettings.headerTableSize, headerTableSize);
        int newInitialWindowSize = updatedValue(existingSettings.initialWindowSize, initialWindowSize);
        int newMaxFrameSize = updatedValue(existingSettings.maxFrameSize, maxFrameSize);
        if (newHeaderTableSize != existingSettings.headerTableSize
        || newInitialWindowSize != existingSettings.initialWindowSize
        || newMaxFrameSize != existingSettings.maxFrameSize) {
            return new Http2Settings(false, newHeaderTableSize, existingSettings.maxConcurrentStreams,
                newInitialWindowSize, newMaxFrameSize, existingSettings.maxHeaderListSize);
        }
        return existingSettings;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Http2Settings that = (Http2Settings) o;
        return isAck == that.isAck && headerTableSize == that.headerTableSize && maxConcurrentStreams == that.maxConcurrentStreams && initialWindowSize == that.initialWindowSize && maxFrameSize == that.maxFrameSize && maxHeaderListSize == that.maxHeaderListSize;
    }

    @Override
    public int hashCode() {
        return Objects.hash(isAck, headerTableSize, maxConcurrentStreams, initialWindowSize, maxFrameSize, maxHeaderListSize);
    }

    @Override
    public String toString() {
        return "Http2SettingsFrame{" +
            "isAck=" + isAck +
            ", headerTableSize=" + headerTableSize +
            ", maxConcurrentStreams=" + maxConcurrentStreams +
            ", initialWindowSize=" + initialWindowSize +
            ", maxFrameSize=" + maxFrameSize +
            ", maxHeaderListSize=" + maxHeaderListSize +
            '}';
    }
}
