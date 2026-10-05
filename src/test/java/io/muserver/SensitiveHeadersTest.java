package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static io.muserver.RFCTestUtils.getHelloHeaders;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ServerUtils.httpsServerForTest;

@Timeout(20)
class SensitiveHeadersTest {

    @Test
    void defaultsAreSharedAndImmutable() {
        assertEquals(Set.of("authorization", "cookie", "set-cookie"), Headers.DEFAULT_SENSITIVE_HEADERS);
        assertSame(Headers.DEFAULT_SENSITIVE_HEADERS, new MuServerBuilder().sensitiveHeaders());
        assertThrows(UnsupportedOperationException.class, () -> Headers.DEFAULT_SENSITIVE_HEADERS.add("x-secret"));
    }

    @Test
    void configurationReplacesDefaultsAndCopiesNormalizedNames() {
        var names = new ArrayList<>(List.of("X-API-Key", "x-api-key", "AUTHORIZATION"));
        var builder = new MuServerBuilder().withSensitiveHeaders(names);
        names.clear();

        assertEquals(Set.of("x-api-key", "authorization"), builder.sensitiveHeaders());
        assertThrows(UnsupportedOperationException.class, () -> builder.sensitiveHeaders().add("cookie"));
        builder.withSensitiveHeaders(List.of());
        assertTrue(builder.sensitiveHeaders().isEmpty());
    }

    @Test
    void invalidConfigurationDoesNotReplaceTheExistingPolicy() {
        var builder = new MuServerBuilder().withSensitiveHeaders(Set.of("x-secret"));
        assertThrows(NullPointerException.class, () -> builder.withSensitiveHeaders(null));
        assertThrows(NullPointerException.class, () -> builder.withSensitiveHeaders(Arrays.asList("x-valid", null)));
        for (String name : List.of("", "bad name", "x-\u2603", "x-bad\r\n")) {
            assertThrows(IllegalArgumentException.class, () -> builder.withSensitiveHeaders(List.of("x-valid", name)));
        }
        assertEquals(Set.of("x-secret"), builder.sensitiveHeaders());
    }

    @Test
    void aRunningServerRetainsItsPolicyWhenTheBuilderChanges() {
        var builder = MuServerBuilder.httpServer().withHttpPort(0).withSensitiveHeaders(Set.of("X-Secret"));
        try (MuServer server = builder.start()) {
            builder.withSensitiveHeaders(Set.of("x-other"));
            assertEquals(Set.of("x-secret"), server.sensitiveHeaders());
            assertThrows(UnsupportedOperationException.class, () -> server.sensitiveHeaders().clear());

            Headers headers = Headers.create().set("Authorization", "default-secret").set("X-Secret", "custom-secret");
            assertTrue(headers.toString().contains("authorization: (hidden)"));
            assertTrue(headers.toString().contains("x-secret: custom-secret"));
            String configured = headers.toString(server.sensitiveHeaders());
            assertTrue(configured.contains("authorization: default-secret"));
            assertTrue(configured.contains("x-secret: (hidden)"));
            assertFalse(configured.contains("custom-secret"));
        }
    }

    static Stream<Arguments> transportsAndPolicies() {
        return Stream.of(false, true).flatMap(tls -> Stream.of(
            Arguments.of(tls, null),
            Arguments.of(tls, Set.of("X-API-Key")),
            Arguments.of(tls, Set.of())));
    }

    @ParameterizedTest
    @MethodSource("transportsAndPolicies")
    void h2AppliesThePolicyToInformationalAndFinalResponses(boolean tls, Set<String> configured) throws Exception {
        var builder = httpsServerForTest(tls ? "h2" : "http")
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler((request, response) -> {
                Headers fields = Headers.create()
                    .set("Authorization", "")
                    .set("Cookie", "session=secret")
                    .set("Set-Cookie", "session=secret")
                    .set("X-API-Key", "custom-secret")
                    .set("X-Public", "public-value");
                response.sendInformationalResponse(HttpStatus.EARLY_HINTS_103, fields);
                response.headers().setAll(fields);
                response.status(204);
                return true;
            });
        if (configured != null) builder.withSensitiveHeaders(configured);

        try (MuServer server = builder.start(); var client = new H2Client();
             var con = tls ? client.connect(server) : client.connectClearText(server)) {
            Set<String> expected = configured == null ? Headers.DEFAULT_SENSITIVE_HEADERS
                : configured.isEmpty() ? Set.of() : Set.of("x-api-key");
            assertEquals(expected, server.sensitiveHeaders());
            con.handshake().writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders(server.uri().getPort()))).flush();

            for (String status : List.of("103", "204")) {
                Http2HeadersFrame frame = readHeaders(con);
                assertEquals(1, frame.streamId());
                assertEquals(status.equals("204"), frame.endStream());
                FieldBlock fields = frame.headers();
                assertEquals(status, fields.get(":status"));
                assertEquals("", fields.get("authorization"));
                assertEquals("session=secret", fields.get("cookie"));
                assertEquals("session=secret", fields.get("set-cookie"));
                assertEquals("custom-secret", fields.get("x-api-key"));
                assertEquals("public-value", fields.get("x-public"));
                for (FieldLine line : fields.lineIterator()) {
                    assertEquals(expected.contains(line.getKey()), line.neverIndexed(), line.getKey());
                }
            }
        }
    }

    private static Http2HeadersFrame readHeaders(H2ClientConnection con) throws Exception {
        LogicalHttp2Frame frame;
        do {
            frame = con.readLogicalFrame();
        } while (frame instanceof Http2WindowUpdate);
        assertInstanceOf(Http2HeadersFrame.class, frame);
        return (Http2HeadersFrame) frame;
    }
}
