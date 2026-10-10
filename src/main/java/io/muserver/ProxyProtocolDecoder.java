package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * One-owner incremental PROXY parser. Consumes only the preamble, leaving TLS/HTTP bytes in the
 * supplied buffer. Retains a fixed scratch array and the address block; TLV values are skipped
 * without allocation. The transport owns the absolute preamble deadline and per-turn byte budget.
 */
final class ProxyProtocolDecoder {
    private enum State { FIRST, V1, V2_HEADER, ADDRESSES, TLV_HEADER, TLV_VALUE, LOCAL, DONE }
    private final HAProxyProtocolConfig config;
    private final byte[] scratch = new byte[107];
    private byte[] addresses = new byte[0];
    private State state = State.FIRST;
    private int size;
    private int payloadRemaining;
    private int valueRemaining;
    private ProxyProtocol.@Nullable V2Header header;
    private @Nullable ProxiedConnectionInfo result;
    private @Nullable IOException failure;

    ProxyProtocolDecoder(HAProxyProtocolConfig config) { this.config = config; }

    /** Returns null until the complete preamble (including all TLVs) has been validated. */
    @Nullable ProxiedConnectionInfo decode(ByteBuffer source) throws IOException {
        checkFailure();
        try {
            for (;;) {
                if (state == State.DONE) return result;
                if (state == State.TLV_HEADER && payloadRemaining == 0) {
                    state = State.DONE;
                    return result;
                }
                if (state == State.TLV_HEADER && size == 0 && payloadRemaining < 3) throw invalid();
                if (!source.hasRemaining()) return null;
                switch (state) {
                    case FIRST:
                        scratch[size++] = source.get();
                        if (scratch[0] == 'P' && config.supportedVersions().contains(HAProxyProtocolVersion.V1)) state = State.V1;
                        else if (scratch[0] == '\r' && config.supportedVersions().contains(HAProxyProtocolVersion.V2)) state = State.V2_HEADER;
                        else throw invalid();
                        break;
                    case V1:
                        scratch[size++] = source.get();
                        if (scratch[size - 2] == '\r' && scratch[size - 1] == '\n') {
                            if (size < 15) throw invalid();
                            result = ProxyProtocol.parseV1(new String(scratch, 0, size - 2, StandardCharsets.US_ASCII));
                            state = State.DONE;
                        } else if (size == scratch.length) throw new IOException("PROXY v1 header exceeds 107 bytes");
                        break;
                    case V2_HEADER:
                        size += copy(source, scratch, size, 16 - size);
                        if (size == 16) {
                            header = ProxyProtocol.parseV2Header(scratch, config);
                            payloadRemaining = header.payloadLength;
                            addresses = new byte[header.addressLength];
                            size = 0;
                            if (header.local) {
                                result = ProxyProtocol.parseV2Addresses(header, addresses);
                                state = payloadRemaining == 0 ? State.DONE : State.LOCAL;
                            } else if (addresses.length == 0) {
                                result = ProxyProtocol.parseV2Addresses(header, addresses);
                                state = State.TLV_HEADER;
                            } else state = State.ADDRESSES;
                        }
                        break;
                    case ADDRESSES:
                        int copied = copy(source, addresses, size, addresses.length - size);
                        size += copied;
                        payloadRemaining -= copied;
                        if (size == addresses.length) {
                            result = ProxyProtocol.parseV2Addresses(java.util.Objects.requireNonNull(header), addresses);
                            size = 0;
                            state = State.TLV_HEADER;
                        }
                        break;
                    case TLV_HEADER:
                        int count = copy(source, scratch, size, 3 - size);
                        size += count;
                        payloadRemaining -= count;
                        if (size == 3) {
                            valueRemaining = ((scratch[1] & 255) << 8) | (scratch[2] & 255);
                            if (valueRemaining > payloadRemaining) throw invalid();
                            size = 0;
                            state = valueRemaining == 0 ? State.TLV_HEADER : State.TLV_VALUE;
                        }
                        break;
                    case TLV_VALUE:
                        int skipped = skip(source, valueRemaining);
                        valueRemaining -= skipped;
                        payloadRemaining -= skipped;
                        if (valueRemaining == 0) state = State.TLV_HEADER;
                        break;
                    case LOCAL:
                        payloadRemaining -= skip(source, payloadRemaining);
                        if (payloadRemaining == 0) state = State.DONE;
                        break;
                    default: throw new IllegalStateException("Unexpected PROXY parser state");
                }
            }
        } catch (IOException error) {
            failure = error;
            result = null;
            throw error;
        }
    }

    ProxiedConnectionInfo endOfInput() throws IOException {
        checkFailure();
        if (state == State.DONE) return java.util.Objects.requireNonNull(result);
        failure = new EOFException("Truncated PROXY preamble");
        result = null;
        throw failure;
    }

    private void checkFailure() throws IOException { if (failure != null) throw failure; }

    private static int copy(ByteBuffer source, byte[] target, int offset, int maximum) {
        int count = Math.min(source.remaining(), maximum);
        source.get(target, offset, count);
        return count;
    }

    private static int skip(ByteBuffer source, int maximum) {
        int count = Math.min(source.remaining(), maximum);
        source.position(source.position() + count);
        return count;
    }

    private static IOException invalid() { return new IOException("Invalid or unsupported PROXY preamble"); }
}
