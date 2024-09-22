package io.muserver;

import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static io.muserver.ForwardedHeaderTest.fwd;
import static java.util.Arrays.asList;
import static java.util.Collections.*;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.core.IsEqual.equalTo;

public class Http1AndHttp2HeadersTest {

    private final Headers[] impls = { new Mu3Headers() };

    @Test
    public void caseIsInsensitive() {
        for (Headers headers : impls) {
            headers.set("Header", "1");
            headers.add("hEader", "2");
            headers.add((CharSequence)("heAder"), "3");
            assertThat(headers.get("heaDer"), is("1"));
            assertThat(headers.get("headEr", "blah"), is("1"));
            assertThat(headers.getAll("headeR"), contains("1", "2", "3"));
            assertThat(headers.contains("HEader"), is(true));
            assertThat(headers.contains("HEAder", "1", false), is(true));
            assertThat(headers.containsValue("HEAder", "1", false), is(true));
            if (headers instanceof Http1Headers) {
                assertThat(headers.names(), contains("Header", "hEader", "heAder"));
            } else {
                assertThat(headers.names(), contains("header"));
            }
            assertThat(headers.size(), is(3));

            assertThat(headers.isEmpty(), is(false));
            for (Map.Entry<String, String> header : headers) {
                assertThat(header.getKey().toLowerCase(), is("header"));
            }
        }
    }

    @Test
    public void canParsePrimitives() {
        for (Headers headers : impls) {
            headers.set("decimal", "1.234");
            headers.set("int", "1234");
            headers.set("bool", "true");
            headers.set("nobool", "false");

            assertThat(headers.getFloat("decimal", 1.3f), is(1.234f));
            assertThat(headers.getFloat("decimaldewey", 1.3f), is(1.3f));
            assertThat(headers.getDouble("decimal", 1.3), is(1.234));
            assertThat(headers.getDouble("decimaldewey", 1.3), is(1.3));

            assertThat(headers.getInt("int", 123456789), is(1234));
            assertThat(headers.getInt("clint", 123456789), is(123456789));
            assertThat(headers.getLong("int", 123456789L), is(1234L));
            assertThat(headers.getLong("clint", 123456789L), is(123456789L));

            assertThat(headers.getBoolean("bool"), is(true));
            assertThat(headers.getBoolean("nobool"), is(false));
            assertThat(headers.getBoolean("reallynobool"), is(false));
        }
    }

    @Test
    public void acceptHeaderCanBeParsed() {
        for (Headers headers : impls) {
            assertThat(headers.accept(), equalTo(emptyList()));

            headers.set("Accept", "text/html,application/xhtml+xml,application/xml ; q=0.9,image/webp,*/*;q=0.8");
            assertThat(headers.accept(), contains(
                ph("text/html"),
                ph("application/xhtml+xml"),
                ph("application/xml", "q", "0.9"),
                ph("image/webp"),
                ph("*/*", "q", "0.8")
            ));
        }
    }

    @Test
    public void acceptCharsetHeaderCanBeParsed() {
        for (Headers headers : impls) {
            assertThat(headers.acceptCharset(), equalTo(emptyList()));

            headers.set("Accept-Charset", "iso-8859-5, unicode-1-1;q=0.8");
            assertThat(headers.acceptCharset(), contains(
                ph("iso-8859-5"),
                ph("unicode-1-1", "q", "0.8")
            ));
        }
    }

    @Test
    public void acceptEncodingHeaderCanBeParsed() {
        for (Headers headers : impls) {
            assertThat(headers.acceptEncoding(), equalTo(emptyList()));

            headers.set("Accept-Encoding", "compress, gzip");
            assertThat(headers.acceptEncoding(), contains(
                ph("compress"),
                ph("gzip")
            ));

            headers.set("Accept-Encoding", "*");
            assertThat(headers.acceptEncoding(), contains(
                ph("*")
            ));

            headers.set("Accept-Encoding", "compress;q=0.5, gzip;q=1.0");
            assertThat(headers.acceptEncoding(), contains(
                ph("compress", "q", "0.5"),
                ph("gzip", "q", "1.0")
            ));

            headers.set("Accept-Encoding", "gzip;q=1.0, identity; q=0.5, *;q=0");
            assertThat(headers.acceptEncoding(), contains(
                ph("gzip", "q", "1.0"),
                ph("identity", "q", "0.5"),
                ph("*", "q", "0")
            ));
        }
    }

    @Test
    public void acceptLanguageHeaderCanBeParsed() {
        for (Headers headers : impls) {
            assertThat(headers.acceptLanguage(), equalTo(emptyList()));

            headers.set("Accept-Language", "da, en-gb;q=0.8, en;q=0.7");
            assertThat(headers.acceptLanguage(), contains(
                ph("da"),
                ph("en-gb", "q", "0.8"),
                ph("en", "q", "0.7")
            ));
        }
    }

    @Test
    public void cacheControlHeaderCanBeParsed() {
        for (Headers headers : impls) {
            assertThat(headers.cacheControl().parameters(), equalTo(emptyMap()));

            headers.set("Cache-Control", "max-age=60");
            assertThat(headers.cacheControl().parameters(), equalTo(singletonMap("max-age", "60")));
            headers.set("Cache-Control", "private, community=\"UCI\"");
            assertThat(headers.cacheControl().parameters().keySet(), contains("private", "community"));
            assertThat(headers.cacheControl().parameter("community"), equalTo("UCI"));
        }
    }

    @Test
    public void contentTypeCanBeParsed() {
        for (Headers headers : impls) {
            assertThat(headers.contentType(), is(nullValue()));

            headers.set("Content-Type", "text/html; charset=ISO-8859-4");
            assertThat(headers.contentType(), equalTo(new MediaType("text", "html", "ISO-8859-4")));
        }
    }

    @Test
    public void forwardedHeadersCanBeParsed() {
        for (Headers headers : impls) {
            assertThat(headers.forwarded(), equalTo(emptyList()));

            headers.set("Forwarded", "for=192.0.2.43");
            assertThat(headers.forwarded(), contains(
                new ForwardedHeader(null, "192.0.2.43", null, null, null)
            ));

            headers.set("X-Forwarded-For", "1.2.3.4"); // ignored as there is a Forwarded header
            headers.set("Forwarded", "for=192.0.2.43," +
                "      for=198.51.100.17;by=203.0.113.60;proto=http;host=example.com");
            assertThat(headers.forwarded(), contains(
                fwd(null, "192.0.2.43", null, null),
                fwd("203.0.113.60", "198.51.100.17", "example.com", "http")
            ));
        }
    }

    @Test
    public void ifNoForwardedHeaderThenXForwardedIsUsed() {
        for (Headers headers : impls) {
            headers.set("X-Forwarded-For", asList("192.0.2.43", "2001:db8:cafe::17"));
            assertThat(headers.forwarded(), contains(
                fwd(null, "192.0.2.43", null, null),
                fwd(null, "2001:db8:cafe::17", null, null)
            ));
            assertThat(headers.forwarded().get(1).toString(), equalTo("for=\"2001:db8:cafe::17\""));

            headers.clear();
            headers.set("X-Forwarded-Host", asList("example.org", "internal.example.org"));
            assertThat(headers.forwarded(), contains(
                fwd(null, null, "example.org", null),
                fwd(null, null, "internal.example.org", null)
            ));

            headers.clear();
            headers.set("X-Forwarded-Host", asList("example.org", "internal.example.org"));
            headers.set("X-Forwarded-Port", asList("80", "8088"));
            assertThat(headers.forwarded(), contains(
                fwd(null, null, "example.org:80", null),
                fwd(null, null, "internal.example.org:8088", null)
            ));

            headers.clear();
            headers.set("X-Forwarded-Proto", asList("http", "https"));
            assertThat(headers.forwarded(), contains(
                fwd(null, null, null, "http"),
                fwd(null, null, null, "https")
            ));
        }
    }

    @Test
    public void ifMultipleXForwardedHeadersHaveSameLengthsThenAllUsed() {
        for (Headers headers : impls) {
            headers.add("X-Forwarded-For", "192.0.2.43");
            headers.add("X-Forwarded-Host", "example.org");
            headers.add("X-Forwarded-Proto", "https");

            headers.add("X-Forwarded-Proto", "http");
            headers.add("X-Forwarded-For", "10.0.0.0");
            headers.add("X-Forwarded-Host", "internal.example.org");

            assertThat(headers.forwarded(), contains(
                fwd(null, "192.0.2.43", "example.org", "https"),
                fwd(null, "10.0.0.0", "internal.example.org", "http")
            ));
        }
    }

    @Test
    public void ifSomeXForwardedHeadersHaveLessValuesThanOthersThenTheyAreIgnored() {
        for (Headers headers : impls) {
            headers.add("X-Forwarded-For", "192.0.2.43");
            headers.add("X-Forwarded-Host", "example.org");
            headers.add("X-Forwarded-Proto", "https");

            headers.add("X-Forwarded-Proto", "http");
            headers.add("X-Forwarded-Host", "internal.example.org");

            assertThat(headers.forwarded(), contains(
                fwd(null, null, "example.org", "https"),
                fwd(null, null, "internal.example.org", "http")
            ));
        }
    }

    private static ParameterizedHeaderWithValue ph(String value) {
        return new ParameterizedHeaderWithValue(value, emptyMap());
    }

    private static ParameterizedHeaderWithValue ph(String value, String paramName, String paramValue) {
        return new ParameterizedHeaderWithValue(value, Collections.singletonMap(paramName, paramValue));
    }

}
