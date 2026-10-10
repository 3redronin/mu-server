package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ProxyProtocolDecoderTest {
    private static final HAProxyProtocolConfig CONFIG = HAProxyProtocolConfigBuilder.config().build();
    private static final byte[] SUFFIX = {22, 3, 3, 0, 1, 42};

    @ParameterizedTest
    @ValueSource(strings = {"heap", "direct", "readonly", "slice"})
    void everySplitPreservesFollowingTlsBytesAndMatchesTheBlockingParser(String storage) throws Exception {
        for (byte[] preamble : examples()) {
            ProxiedConnectionInfo expected = ProxyProtocol.parse(new ByteArrayInputStream(preamble));
            for (int split = 0; split <= preamble.length; split++) {
                var decoder = new ProxyProtocolDecoder(CONFIG);
                ByteBuffer input = wire(preamble, storage);
                int fullLimit = input.limit();
                input.limit(split);
                ProxiedConnectionInfo first = decoder.decode(input);
                if (split == preamble.length) assertEquals(expected, first);
                else assertNull(first, "A partial preamble exposed addresses before full validation");
                assertFalse(input.hasRemaining());
                input.limit(fullLimit);
                assertEquals(expected, decoder.decode(input));
                assertEquals(expected, decoder.endOfInput());
                assertEquals(expected, decoder.decode(input));
                assertEquals(SUFFIX.length, input.remaining());
                byte[] remaining = new byte[input.remaining()];
                input.get(remaining);
                assertArrayEquals(SUFFIX, remaining);
            }
        }
    }

    @Test
    void singleByteFeedsCanReuseTheirStorageAndEmptyFeedsDoNotMeanEof() throws Exception {
        for (byte[] preamble : examples()) {
            var decoder = new ProxyProtocolDecoder(CONFIG);
            ByteBuffer one = ByteBuffer.allocateDirect(1);
            for (int i = 0; i < preamble.length; i++) {
                one.clear().put(preamble[i]).flip();
                ProxiedConnectionInfo decoded = decoder.decode(one);
                if (i < preamble.length - 1) {
                    assertNull(decoded);
                    assertNull(decoder.decode(one));
                } else assertNotNull(decoded);
            }
            assertEquals(ProxyProtocol.parse(new ByteArrayInputStream(preamble)), decoder.endOfInput());
        }
    }

    @Test
    void everyTruncatedPrefixFailsTerminally() throws Exception {
        for (byte[] preamble : examples()) {
            for (int length = 0; length < preamble.length; length++) {
                var decoder = new ProxyProtocolDecoder(CONFIG);
                assertNull(decoder.decode(ByteBuffer.wrap(preamble, 0, length)));
                EOFException failure = assertThrows(EOFException.class, decoder::endOfInput);
                assertSame(failure, assertThrows(IOException.class, () -> decoder.decode(ByteBuffer.wrap(preamble))));
                assertSame(failure, assertThrows(IOException.class, decoder::endOfInput));
            }
        }
    }

    @Test
    void maximumValuesAndManyEmptyTlvsAreSkippedWithoutConsumingTheNextProtocol() throws Exception {
        byte[] value = new byte[65535];
        byte[] addresses = ipv4();
        System.arraycopy(addresses, 0, value, 0, addresses.length);
        value[12] = (byte) 0xee;
        value[13] = (byte) ((65535 - 15) >>> 8);
        value[14] = (byte) (65535 - 15);
        Arrays.fill(value, 15, value.length, (byte) 0xff);
        byte[] emptyTlvs = new byte[65535];
        for (int i = 0; i < emptyTlvs.length; i += 3) emptyTlvs[i] = (byte) 0xee;
        for (byte[] preamble : List.of(binary(0x21, 0x11, value), binary(0x21, 0x00, emptyTlvs), binary(0x20, 0xff, value))) {
            var decoder = new ProxyProtocolDecoder(CONFIG);
            ByteBuffer input = wire(preamble, "direct");
            int fullLimit = input.limit();
            ProxiedConnectionInfo decoded = null;
            while (decoded == null) {
                input.limit(Math.min(fullLimit, input.position() + 17));
                decoded = decoder.decode(input);
            }
            input.limit(fullLimit);
            assertEquals(ProxyProtocol.parse(new ByteArrayInputStream(preamble)), decoded);
            assertEquals(SUFFIX.length, input.remaining());
        }
    }

    @Test
    void invalidFixedHeadersAndPayloadLimitsFailBeforeWaitingForPayload() {
        for (byte[] preamble : List.of(binary(0x21, 0x41, new byte[20]), binary(0x21, 0x10, new byte[20]),
            binary(0x21, 0x01, new byte[20]), binary(0x21, 0x21, new byte[35]), binary(0x22, 0x11, new byte[20]))) {
            var decoder = new ProxyProtocolDecoder(CONFIG);
            ByteBuffer input = ByteBuffer.wrap(preamble);
            IOException failure = assertThrows(IOException.class, () -> decoder.decode(input));
            assertEquals(16, input.position());
            assertSame(failure, assertThrows(IOException.class, () -> decoder.decode(input)));
        }
        var decoder = new ProxyProtocolDecoder(HAProxyProtocolConfigBuilder.config().withMaxV2PayloadSize(8).build());
        ByteBuffer input = ByteBuffer.wrap(binary(0x20, 0xff, new byte[9]));
        assertThrows(IOException.class, () -> decoder.decode(input));
        assertEquals(16, input.position());
    }

    @Test
    void malformedTlvsAndDisabledVersionsNeverPublishAddresses() {
        for (byte[] payload : List.of(new byte[]{1}, new byte[]{1, 0}, new byte[]{1, 0, 1}, new byte[]{1, (byte) 255, (byte) 255, 0})) {
            var decoder = new ProxyProtocolDecoder(CONFIG);
            ByteBuffer input = ByteBuffer.wrap(binary(0x21, 0, payload));
            assertThrows(IOException.class, () -> decoder.decode(input));
        }
        for (HAProxyProtocolVersion version : HAProxyProtocolVersion.values()) {
            var decoder = new ProxyProtocolDecoder(HAProxyProtocolConfigBuilder.config().withSupportedVersions(List.of(version)).build());
            byte[] other = version == HAProxyProtocolVersion.V1 ? binary(0x20, 0, new byte[0]) : "PROXY UNKNOWN\r\n".getBytes(US_ASCII);
            ByteBuffer input = ByteBuffer.wrap(other);
            assertThrows(IOException.class, () -> decoder.decode(input));
            assertEquals(1, input.position());
        }
    }

    @Test
    void v1LengthLimitAndMalformedAddressesMatchTheBlockingParser() throws Exception {
        for (int length : new int[]{106, 107, 108}) {
            byte[] preamble = ("PROXY UNKNOWN " + "x".repeat(length - 16) + "\r\n").getBytes(US_ASCII);
            var decoder = new ProxyProtocolDecoder(CONFIG);
            if (length < 108) assertEquals(ProxyProtocol.parse(new ByteArrayInputStream(preamble)), decoder.decode(wire(preamble, "heap")));
            else assertThrows(IOException.class, () -> decoder.decode(ByteBuffer.wrap(preamble)));
        }
        for (String line : List.of("PROXY\r\n", "PROXY TCP4 01.2.3.4 1.2.3.4 1 2\r\n",
            "PROXY TCP6 example.com ::1 80 80\r\n", "PROXY TCP4 1.2.3.4 1.2.3.4 65536 80\r\n")) {
            var decoder = new ProxyProtocolDecoder(CONFIG);
            assertThrows(IOException.class, () -> decoder.decode(ByteBuffer.wrap(line.getBytes(US_ASCII))));
        }
    }

    private static List<byte[]> examples() {
        byte[] unix = new byte[216];
        System.arraycopy("/source".getBytes(US_ASCII), 0, unix, 0, 7);
        System.arraycopy("/destination".getBytes(US_ASCII), 0, unix, 108, 12);
        byte[] ipv6 = new byte[36];
        ipv6[10] = -1; ipv6[11] = -1; ipv6[12] = (byte) 192; ipv6[14] = 2; ipv6[15] = 1;
        byte[] withTlvs = ByteBuffer.allocate(22).put(ipv4()).put(new byte[]{1, 0, 0, 2, 0, 1, 42, 3, 0, 0}).array();
        return List.of("PROXY TCP4 192.0.2.1 198.51.100.2 12345 443\r\n".getBytes(US_ASCII),
            "PROXY TCP6 ::1 2001:db8::1 0 65535\r\n".getBytes(US_ASCII), "PROXY UNKNOWN ignored\r\n".getBytes(US_ASCII),
            binary(0x21, 0x11, ipv4()), binary(0x21, 0x11, withTlvs), binary(0x21, 0x21, ipv6), binary(0x21, 0x32, unix),
            binary(0x21, 0, new byte[]{1, 0, 1, 42}), binary(0x20, 0xff, new byte[]{0, 1, 2, 3}), binary(0x20, 0, new byte[0]));
    }

    private static byte[] ipv4() {
        return ByteBuffer.allocate(12).put(new byte[]{(byte) 192, 0, 2, 1, (byte) 198, 51, 100, 2})
            .putShort((short) 12345).putShort((short) 443).array();
    }

    private static byte[] binary(int command, int family, byte[] payload) {
        return ByteBuffer.allocate(16 + payload.length).put("\r\n\r\n\0\r\nQUIT\n".getBytes(US_ASCII))
            .put((byte) command).put((byte) family).putShort((short) payload.length).put(payload).array();
    }

    private static ByteBuffer wire(byte[] preamble, String storage) {
        byte[] bytes = ByteBuffer.allocate(preamble.length + SUFFIX.length).put(preamble).put(SUFFIX).array();
        switch (storage) {
            case "direct": return ByteBuffer.allocateDirect(bytes.length).put(bytes).flip();
            case "readonly": return ByteBuffer.wrap(bytes).asReadOnlyBuffer();
            case "slice":
                ByteBuffer parent = ByteBuffer.allocate(bytes.length + 11);
                parent.position(7).put(bytes).flip().position(7);
                return parent.slice();
            default: return ByteBuffer.wrap(bytes);
        }
    }
}
