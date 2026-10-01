package io.muserver;

import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.util.NetUtil;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Locale;

import static io.netty.handler.codec.http2.Http2Error.PROTOCOL_ERROR;
import static io.netty.handler.codec.http2.Http2Exception.streamError;

/** Checks authority semantics that Netty's HTTP/2 decoder does not validate. */
final class Http2RequestValidation {
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
            CharSequence scheme = headers.scheme();
            int defaultPort = "https".equalsIgnoreCase(String.valueOf(scheme)) ? 443
                : "http".equalsIgnoreCase(String.valueOf(scheme)) ? 80 : -1;
            Authority expected = new Authority(selected, defaultPort);
            for (CharSequence host : headers.getAll(HeaderNames.HOST)) {
                if (!expected.matches(new Authority(host.toString(), defaultPort))) {
                    throw streamError(streamId, PROTOCOL_ERROR, "Conflicting :authority and Host values");
                }
            }
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw streamError(streamId, PROTOCOL_ERROR, e, "Invalid request authority");
        }
        return selected;
    }

    private static final class Authority {
        private final String host;
        private final int port;

        private Authority(String value, int defaultPort) throws URISyntaxException {
            // URI checks escaping and authority syntax without resolving a hostname. Checking the
            // complete raw authority also rejects path, query, fragment and userinfo injection.
            URI uri = new URI("http://" + value);
            if (!value.equals(uri.getRawAuthority()) || value.indexOf('@') >= 0) {
                throw new IllegalArgumentException("Expected a host and optional port");
            }
            int portSeparator;
            if (value.startsWith("[")) {
                int end = value.indexOf(']');
                String literal = value.substring(1, end);
                // Zone identifiers are not part of an HTTP URI host. Do not silently discard one.
                if (literal.indexOf('%') >= 0 || !NetUtil.isValidIpV6Address(literal)) {
                    throw new IllegalArgumentException("Invalid IPv6 host");
                }
                host = Arrays.toString(NetUtil.createByteArrayFromIpAddressString(literal));
                portSeparator = end + 1;
            } else {
                int colon = value.indexOf(':');
                portSeparator = colon < 0 ? value.length() : colon;
                host = value.substring(0, portSeparator).toLowerCase(Locale.ROOT);
                if (host.isEmpty()) {
                    throw new IllegalArgumentException("Empty host");
                }
            }
            int parsedPort = defaultPort;
            if (portSeparator < value.length()) {
                if (value.charAt(portSeparator) != ':') {
                    throw new IllegalArgumentException("Invalid port delimiter");
                }
                String digits = value.substring(portSeparator + 1);
                if (!digits.isEmpty()) {
                    parsedPort = 0;
                    for (int i = 0; i < digits.length(); i++) {
                        char digit = digits.charAt(i);
                        if (digit < '0' || digit > '9') {
                            throw new IllegalArgumentException("Invalid port");
                        }
                        parsedPort = parsedPort * 10 + digit - '0';
                        if (parsedPort > 65535) {
                            throw new IllegalArgumentException("Port out of range");
                        }
                    }
                }
            }
            port = parsedPort;
        }

        private boolean matches(Authority other) {
            return host.equals(other.host) && port == other.port;
        }
    }
}
