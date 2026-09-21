package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import scaffolding.Http1Client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.equalTo;
import static scaffolding.MuAssert.assertEventually;

class HAProxyProtocolConfigTest {
    @Test void defaultsAndSnapshots() {
        var builder = new HAProxyProtocolConfigBuilder();
        var config = builder.build();
        assertTrue(config.enabled());
        assertEquals(List.of(HAProxyProtocolVersion.V1, HAProxyProtocolVersion.V2), new ArrayList<>(config.supportedVersions()));
        assertEquals(10000, config.timeoutMillis());
        assertEquals(65535, config.maxV2PayloadSize());
        assertEquals(config, HAProxyProtocolConfigBuilder.config().build());
        assertEquals(config, config.toBuilder().build());
        assertEquals(config.hashCode(), config.toBuilder().build().hashCode());
        assertTrue(config.toString().contains("maxV2PayloadSize=65535"));
        var versions = new ArrayList<>(List.of(HAProxyProtocolVersion.V2, HAProxyProtocolVersion.V2));
        builder.withSupportedVersions(versions);
        var snapshot = builder.supportedVersions();
        versions.clear();
        builder.withSupportedVersions(List.of(HAProxyProtocolVersion.V1));
        assertEquals(List.of(HAProxyProtocolVersion.V2), new ArrayList<>(snapshot));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
        assertThrows(UnsupportedOperationException.class, () -> config.supportedVersions().clear());
        var changed = config.toBuilder().withEnabled(false).withTimeout(2, TimeUnit.SECONDS)
            .withMaxV2PayloadSize(12).withSupportedVersions(List.of(HAProxyProtocolVersion.V1)).build();
        assertNotEquals(config, changed);
        assertEquals(2000, changed.timeoutMillis());
        assertEquals(12, changed.maxV2PayloadSize());
        assertFalse(changed.enabled());
        assertEquals(config, config.toBuilder().build());
    }

    @Test void invalidSettingsAreRejected() {
        var builder = HAProxyProtocolConfigBuilder.config();
        assertThrows(NullPointerException.class, () -> builder.withSupportedVersions(null));
        assertThrows(NullPointerException.class, () -> builder.withSupportedVersions(Arrays.asList(HAProxyProtocolVersion.V1, null)));
        assertThrows(IllegalArgumentException.class, () -> builder.withSupportedVersions(List.of()));
        assertThrows(IllegalArgumentException.class, () -> builder.withEnabled(false).withSupportedVersions(List.of()));
        assertThrows(IllegalArgumentException.class, () -> builder.withMaxV2PayloadSize(-1));
        assertThrows(IllegalArgumentException.class, () -> builder.withMaxV2PayloadSize(65536));
        assertThrows(NullPointerException.class, () -> builder.withTimeout(1, null));
        for (long invalid : new long[]{-1, 0, Long.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> builder.withTimeout(invalid, TimeUnit.SECONDS));
        }
        assertThrows(IllegalArgumentException.class, () -> builder.withTimeout(999, TimeUnit.MICROSECONDS));
        assertEquals(1, builder.withTimeout(1999, TimeUnit.MICROSECONDS).timeoutMillis());
        assertEquals(TimeUnit.NANOSECONDS.toMillis(Long.MAX_VALUE), builder.withTimeout(Long.MAX_VALUE, TimeUnit.NANOSECONDS).timeoutMillis());
        assertEquals(0, builder.withMaxV2PayloadSize(0).maxV2PayloadSize());
    }

    @SuppressWarnings("deprecation")
    @Test void serverDefaultsNullOverloadsAndLegacyReplacement() {
        var server = MuServerBuilder.httpServer();
        assertNull(server.haProxyProtocolConfig());
        assertFalse(server.haProxyProtocolEnabled());
        var builder = HAProxyProtocolConfigBuilder.config().withTimeout(20, TimeUnit.SECONDS).withMaxV2PayloadSize(12);
        server.withHAProxyProtocolConfig(builder);
        var custom = server.haProxyProtocolConfig();
        builder.withMaxV2PayloadSize(0);
        assertEquals(12, custom.maxV2PayloadSize());
        server.withHAProxyProtocolEnabled(true);
        assertEquals(HAProxyProtocolConfigBuilder.config().build(), server.haProxyProtocolConfig());
        server.withHAProxyProtocolEnabled(false);
        assertNull(server.haProxyProtocolConfig());
        server.withHAProxyProtocolConfig(custom).withHAProxyProtocolConfig((HAProxyProtocolConfig) null);
        assertNull(server.haProxyProtocolConfig());
        server.withHAProxyProtocolConfig(custom).withHAProxyProtocolConfig((HAProxyProtocolConfigBuilder) null);
        assertNull(server.haProxyProtocolConfig());
        server.withHAProxyProtocolConfig(HAProxyProtocolConfigBuilder.config().withEnabled(false));
        assertFalse(server.haProxyProtocolEnabled());
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 3})
    void versionsAreFilteredBeforeReadingTheRest(int mask) throws Exception {
        var config = HAProxyProtocolConfigBuilder.config().withSupportedVersions(mask == 3
            ? List.of(HAProxyProtocolVersion.V1, HAProxyProtocolVersion.V2)
            : List.of(mask == 1 ? HAProxyProtocolVersion.V1 : HAProxyProtocolVersion.V2)).build();
        for (int version = 1; version <= 2; version++) {
            byte[] header = version == 1 ? "PROXY UNKNOWN\r\n".getBytes(StandardCharsets.US_ASCII) : binary(0x20, 0, new byte[0]);
            var input = new ByteArrayInputStream(header);
            if ((mask & version) != 0) assertNull(ProxyProtocol.parse(input, config).sourceAddress());
            else {
                assertThrows(IOException.class, () -> ProxyProtocol.parse(input, config));
                assertEquals(header.length - 1, input.available());
            }
        }
    }

    @ParameterizedTest @CsvSource({"32,255,0", "33,0,0", "33,17,12", "33,33,36", "33,49,216"})
    void payloadLimitAppliesBeforeAnyPayloadRead(int command, int family, int length) throws Exception {
        byte[] payload = new byte[length == 0 ? 3 : length]; // UNSPEC uses a zero-length TLV; LOCAL ignores bytes.
        byte[] header = binary(command, family, payload);
        var config = HAProxyProtocolConfigBuilder.config().withMaxV2PayloadSize(payload.length).build();
        byte[] bytes = Arrays.copyOf(header, header.length + 1); bytes[header.length] = 42;
        var accepted = new ByteArrayInputStream(bytes);
        ProxyProtocol.parse(accepted, config);
        assertEquals(42, accepted.read());
        var rejected = new ByteArrayInputStream(bytes);
        assertThrows(IOException.class, () -> ProxyProtocol.parse(rejected, config.toBuilder().withMaxV2PayloadSize(payload.length - 1).build()));
        assertEquals(payload.length + 1, rejected.available());
    }

    @Test void zeroAndMaximumPayloadLimits() throws Exception {
        var zero = HAProxyProtocolConfigBuilder.config().withMaxV2PayloadSize(0).build();
        for (int command : new int[]{0x20, 0x21}) {
            assertNull(ProxyProtocol.parse(new ByteArrayInputStream(binary(command, 0, new byte[0])), zero).sourceAddress());
        }
        byte[] maximum = binary(0x20, 255, new byte[65535]);
        ProxyProtocol.parse(new ByteArrayInputStream(maximum), HAProxyProtocolConfigBuilder.config().build());
        var input = new ByteArrayInputStream(maximum);
        assertThrows(IOException.class, () -> ProxyProtocol.parse(input, zero.toBuilder().withMaxV2PayloadSize(65534).build()));
        assertEquals(65535, input.available());
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3})
    void absentDisabledAndClearedConfigsAcceptOrdinaryHttp(int mode) throws Exception {
        var builder = MuServerBuilder.httpServer();
        if (mode == 1) builder.withHAProxyProtocolConfig(HAProxyProtocolConfigBuilder.config().withEnabled(false));
        if (mode == 2) builder.withHAProxyProtocolConfig(HAProxyProtocolConfigBuilder.config()).withHAProxyProtocolConfig((HAProxyProtocolConfig) null);
        if (mode == 3) builder.withHAProxyProtocolConfig(HAProxyProtocolConfigBuilder.config()).withHAProxyProtocolConfig((HAProxyProtocolConfigBuilder) null);
        try (MuServer server = builder.addHandler((req, resp) -> { assertTrue(req.connection().proxyInfo().isEmpty()); resp.write("ok"); return true; }).start();
             Http1Client client = Http1Client.connect(server)) {
            client.writeRequestLine(Method.GET, "/").endHeaders().flush();
            assertEquals("HTTP/1.1 200 OK", client.readLine());
            assertEquals("ok", client.readBody(client.readHeaders()));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void policyRejectionPrecedesTlsAndHandlersAndRecovers(boolean tls) throws Exception {
        AtomicInteger handled = new AtomicInteger();
        var builder = tls ? MuServerBuilder.httpsServer() : MuServerBuilder.httpServer();
        try (MuServer server = builder.withHAProxyProtocolConfig(HAProxyProtocolConfigBuilder.config()
            .withSupportedVersions(List.of(HAProxyProtocolVersion.V2)).withMaxV2PayloadSize(0))
            .addHandler((req, resp) -> { handled.incrementAndGet(); resp.write("ok"); return true; }).start()) {
            for (byte[] rejected : new byte[][]{"P".getBytes(StandardCharsets.US_ASCII), Arrays.copyOf(binary(0x20, 0, new byte[1]), 16)}) {
                try (Socket socket = new Socket("localhost", server.uri().getPort())) {
                    socket.setSoTimeout(1000); socket.getOutputStream().write(rejected);
                    try { assertEquals(-1, socket.getInputStream().read()); } catch (SocketException reset) { /* closed */ }
                }
            }
            assertEventually(() -> server.stats().failedToConnect(), equalTo(2L));
            ConnectionRejectionTest.assertNoPendingSockets(server);
            assertEquals(0, handled.get());
            try (Socket raw = new Socket("localhost", server.uri().getPort())) {
                raw.setSoTimeout(3000); raw.getOutputStream().write(binary(0x20, 0, new byte[0]));
                Socket transport = raw;
                if (tls) {
                    var secure = (javax.net.ssl.SSLSocket) scaffolding.ClientUtils.sslContextForTesting(scaffolding.ClientUtils.veryTrustingTrustManager)
                        .getSocketFactory().createSocket(raw, "localhost", server.uri().getPort(), true);
                    secure.setSoTimeout(3000); secure.startHandshake(); transport = secure;
                }
                try (var client = new Http1Client(transport, transport.getInputStream(), transport.getOutputStream(), server.uri())) {
                    client.writeRequestLine(Method.GET, "/").writeHeader("Connection", "close").endHeaders().flush();
                    assertEquals("HTTP/1.1 200 OK", client.readLine());
                    assertEquals("ok", client.readBody(client.readHeaders()));
                }
            }
            assertEquals(1, handled.get());
            assertEventually(() -> server.activeConnections().size(), equalTo(0));
            ConnectionRejectionTest.assertNoPendingSockets(server);
        }
    }

    private static byte[] binary(int command, int family, byte[] payload) {
        return ByteBuffer.allocate(16 + payload.length).put("\r\n\r\n\0\r\nQUIT\n".getBytes(StandardCharsets.US_ASCII))
            .put((byte) command).put((byte) family).putShort((short) payload.length).put(payload).array();
    }
}
