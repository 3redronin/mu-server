package io.muserver;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class Http2RequestValidationTest {
    @Test
    public void authoritiesDifferingOnlyInCaseAreAcceptedWithoutChangingTheHeaders() throws Exception {
        for (String[] example : new String[][]{
            {"https", "example.test", "EXAMPLE.TEST", "example.test"},
            {"http", "example.test:80", "EXAMPLE.TEST:80"},
            {"https", "example.test:65536", "EXAMPLE.TEST:65536"},
            {"https", "example.test:bad", "EXAMPLE.TEST:bad"},
            {"https", "example.test:000000000000000000008443", "EXAMPLE.TEST:000000000000000000008443"},
            {"https", "example.test:", "EXAMPLE.TEST:"},
            {"https", "my_service.test", "MY_SERVICE.TEST"},
            {"https", "[2001:db8::1]:443", "[2001:DB8::1]:443"},
            {"https", "[::ffff:192.0.2.1]", "[::FFFF:192.0.2.1]"}
        }) {
            FieldBlock headers = new FieldBlock();
            headers.add(":scheme", example[0]).add(":authority", example[1]);
            for (int i = 2; i < example.length; i++) {
                headers.add("host", example[i]);
            }
            List<String> originalHosts = new ArrayList<>(headers.getAll("host"));
            assertEquals(example[1], Http2RequestValidation.authority(3, headers.get(":authority"), headers));
            assertEquals(originalHosts, headers.getAll("host"));
        }
    }

    @Test
    public void authorityOrHostAloneIsSufficient() throws Exception {
        FieldBlock headers = new FieldBlock();
        headers.add(":scheme", "https").add(":authority", "example.test");
        assertEquals("example.test", Http2RequestValidation.authority(3, headers.get(":authority"), headers));
        headers.remove(":authority");
        headers.add("host", "Example.test:443").add("host", "example.test:443");
        assertEquals("Example.test:443", Http2RequestValidation.authority(3, headers.get(":authority"), headers));
    }

    @Test
    public void conflictsIncludePortsAndEveryHostValue() {
        for (String[] example : new String[][]{
            {"example.test", "different.test"},
            {"example.test", "example.test:8443"},
            {"example.test:8443", "example.test"},
            {"example.test", "example.test:443"},
            {"example.test:443", "example.test"},
            {"example.test:443", "example.test:0443"},
            {"example.test", "example.test:"},
            {"[::1]", "[0:0:0:0:0:0:0:1]"},
            {"[::ffff:192.0.2.1]", "[::ffff:c000:201]"},
            {null, "example.test", "example.test:443"},
            {"example.test", "example.test", "different.test"},
            {null, "example.test", "different.test"},
            {"[::1]", "[::2]"}
        }) {
            FieldBlock headers = new FieldBlock();
            headers.add(":scheme", "https");
            if (example[0] != null) headers.add(":authority", example[0]);
            for (int i = 1; i < example.length; i++) headers.add("host", example[i]);
            assertStreamError(headers);
        }
    }

    @Test
    public void missingAndEmptyAuthoritiesAreStreamErrors() {
        assertStreamError(new FieldBlock().add(":scheme", "https"));
        assertStreamError(new FieldBlock().add(":scheme", "https").add(":authority", ""));
        assertStreamError(new FieldBlock().add(":scheme", "https").add("host", ""));
    }

    private static void assertStreamError(Headers headers) {
        Http2Exception error = assertThrows(Http2Exception.class,
            () -> Http2RequestValidation.authority(3, headers.get(":authority"), headers), headers.toString());
        assertEquals(Http2Level.STREAM, error.errorType());
        assertEquals(3, error.streamId());
        assertEquals(Http2ErrorCode.PROTOCOL_ERROR, error.errorCode());
    }
}
