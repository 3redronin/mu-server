package io.muserver;

import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class Http2RequestValidationTest {
    @Test
    public void authoritiesDifferingOnlyInCaseAreAcceptedWithoutChangingTheHeaders() throws Exception {
        for (String[] example : new String[][]{
            {"https", "example.test", "EXAMPLE.TEST", "example.test"},
            {"http", "example.test:80", "EXAMPLE.TEST:80"},
            {"https", "example.test:000000000000000000008443", "EXAMPLE.TEST:000000000000000000008443"},
            {"https", "example.test:", "EXAMPLE.TEST:"},
            {"https", "my_service.test", "MY_SERVICE.TEST"},
            {"https", "[2001:db8::1]:443", "[2001:DB8::1]:443"},
            {"https", "[::ffff:192.0.2.1]", "[::FFFF:192.0.2.1]"}
        }) {
            DefaultHttp2Headers headers = new DefaultHttp2Headers();
            headers.scheme(example[0]).authority(example[1]);
            for (int i = 2; i < example.length; i++) {
                headers.add("host", example[i]);
            }
            List<CharSequence> originalHosts = new ArrayList<>(headers.getAll("host"));
            assertEquals(example[1], Http2RequestValidation.authority(3, headers));
            assertEquals(originalHosts, headers.getAll("host"));
        }
    }

    @Test
    public void authorityOrHostAloneIsSufficient() throws Exception {
        DefaultHttp2Headers headers = new DefaultHttp2Headers();
        headers.scheme("https").authority("example.test");
        assertEquals("example.test", Http2RequestValidation.authority(3, headers));
        headers.remove(":authority");
        headers.add("host", "Example.test:443").add("host", "example.test:443");
        assertEquals("Example.test:443", Http2RequestValidation.authority(3, headers));
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
            DefaultHttp2Headers headers = new DefaultHttp2Headers();
            headers.scheme("https");
            if (example[0] != null) headers.authority(example[0]);
            for (int i = 1; i < example.length; i++) headers.add("host", example[i]);
            assertStreamError(headers);
        }
    }

    @Test
    public void missingAndMalformedAuthoritiesAreStreamErrors() {
        assertStreamError(new DefaultHttp2Headers().scheme("https"));
        for (String value : new String[]{"", ":443", "user@example.test", "example.test/path", "example.test/",
            "example.test?query", "example.test#fragment", "example.test:bad", "example.test:-1",
            "example.test:65536", "example.test:999999999999999999999", "example.test:443:80",
            "example.test:+443", "example.test:\u0664\u0664\u0663",
            "example.test\\evil", "example.test%", "[not-ipv6]", "[::1", "[::1]extra", "[fe80::1%25eth0]"}) {
            // Disable Netty's field checks to exercise this helper independently of decoding.
            DefaultHttp2Headers headers = new DefaultHttp2Headers(false);
            headers.scheme("https").authority(value);
            assertStreamError(headers);
            headers.remove(":authority");
            headers.add("host", value);
            assertStreamError(headers);
        }
    }

    private static void assertStreamError(io.netty.handler.codec.http2.Http2Headers headers) {
        Http2Exception error = assertThrows(headers.toString(), Http2Exception.class,
            () -> Http2RequestValidation.authority(3, headers));
        assertTrue(error instanceof Http2Exception.StreamException);
        assertEquals(3, Http2Exception.streamId(error));
        assertEquals(Http2Error.PROTOCOL_ERROR, error.error());
    }
}
