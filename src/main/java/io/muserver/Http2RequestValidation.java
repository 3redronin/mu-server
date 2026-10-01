package io.muserver;

import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.util.AsciiString;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.netty.handler.codec.http2.Http2Error.PROTOCOL_ERROR;
import static io.netty.handler.codec.http2.Http2Exception.streamError;

/** Checks authority semantics that Netty's HTTP/2 decoder does not validate. */
final class Http2RequestValidation {
    // URI validates host syntax; this restricts ports to ASCII digits and excludes IPv6 zones.
    private static final Pattern AUTHORITY = Pattern.compile("(?:\\[[^%]+\\]|[^:\\[\\]]+)(?::([0-9]*))?");

    private Http2RequestValidation() { }

    /** Must run before Host is replaced with :authority in the shared headers. */
    static String authority(int streamId, Http2Headers headers) throws Http2Exception {
        CharSequence authority = headers.authority();
        if (authority == null) {
            authority = headers.get(HeaderNames.HOST);
        }
        if (authority == null) {
            throw streamError(streamId, PROTOCOL_ERROR, "Request has neither :authority nor Host");
        }
        String selected = authority.toString();
        try {
            validate(selected);
            for (CharSequence host : headers.getAll(HeaderNames.HOST)) {
                if (!AsciiString.contentEqualsIgnoreCase(selected, host)) {
                    throw streamError(streamId, PROTOCOL_ERROR, "Conflicting :authority and Host values");
                }
            }
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw streamError(streamId, PROTOCOL_ERROR, e, "Invalid request authority");
        }
        return selected;
    }

    private static void validate(String value) throws URISyntaxException {
        URI uri = new URI("http://" + value);
        Matcher authority = AUTHORITY.matcher(value);
        // Checking the complete raw authority rejects path, query and fragment injection.
        if (!value.equals(uri.getRawAuthority()) || value.indexOf('@') >= 0 || !authority.matches()) {
            throw new IllegalArgumentException("Expected a host and optional port");
        }
        String port = authority.group(1);
        if (port != null && !port.isEmpty() && Integer.parseInt(port) > 65535) {
            throw new IllegalArgumentException("Port out of range");
        }
    }
}
