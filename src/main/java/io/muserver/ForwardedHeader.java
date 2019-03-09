package io.muserver;

import java.util.*;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;

/**
 * <p>Represents a <code>Forwarded</code> header as described by RFC-7239.</p>
 */
public class ForwardedHeader {

    private final String by;
    private final String forValue;
    private final String host;
    private final String proto;
    private final Map<String,String> extensions;

    /**
     * Creates a new Forwarded Header. All values are optional.
     */
    public ForwardedHeader(String by, String forValue, String host, String proto, Map<String, String> extensions) {
        this.by = by;
        this.forValue = forValue;
        this.host = host;
        this.proto = proto;
        this.extensions = extensions == null ? emptyMap() : extensions;
    }

    /**
     * @return The interface where the request came in to the proxy server (e.g. the IP address of the reverse
     * proxy that forwarded this request), or <code>null</code> if not specified.
     */
    public String by() {
        return by;
    }

    /**
     * @return The interface where the request came in to the proxy server, e.g. the IP address of the client
     * that originated the request), or <code>null</code> if not specified.
     */
    public String forValue() {
        return forValue;
    }

    /**
     * @return The Host request header field as received by the proxy (e.g. the hostname used on the original
     * request), or <code>null</code> if not specified.
     */
    public String host() {
        return host;
    }

    /**
     * @return Indicates which protocol was used to make the request (typically "http" or "https"), or
     * <code>null</code> if not specified.
     */
    public String proto() {
        return proto;
    }

    /**
     * @return Values not covered by by, for, host, or proto.
     */
    public Map<String, String> extensions() {
        return extensions;
    }



    private enum State {PARAM_NAME, PARAM_VALUE;}
    /**
     * <p>Parses the value of a <code>Forwarded</code> header into an object.</p>
     * <p>Where multiple reverse proxies have resulted in multiple Forwarded headers, the first
     * value in the list should contain the information of the original request.</p>
     * <p>Null or blank strings return an empty list.</p>
     * @param input The value to parse
     * @return A list of ForwardedHeader objects
     * @throws IllegalArgumentException The value cannot be parsed
     */
    public static List<ForwardedHeader> fromString(String input) {
        if (input == null || input.trim().isEmpty()) {
            return emptyList();
        }
        StringBuilder buffer = new StringBuilder();

        List<ForwardedHeader> results = new ArrayList<>();

        int i = 0;
        while (i < input.length()) {

            LinkedHashMap<String, String> extensions = null;
            State state = State.PARAM_NAME;
            String paramName = null;
            String by = null;
            String forValue = null;
            String host = null;
            String proto = null;
            boolean isQuotedString = false;

            headerValueLoop:
            for (; i < input.length(); i++) {
                char c = input.charAt(i);

                if (state == State.PARAM_NAME) {
                    if (c == ',' && buffer.length() == 0) {
                        i++; // a semi-colon without an parameter, like "something;"
                        break headerValueLoop;
                    } else if (c == '=') {
                        paramName = buffer.toString();
                        buffer.setLength(0);
                        state = State.PARAM_VALUE;
                    } else if (ParseUtils.isTChar(c)) {
                        buffer.append(c);
                    } else if (ParseUtils.isOWS(c)) {
                        if (buffer.length() > 0) {
                            throw new IllegalArgumentException("Got whitespace in parameter name while in " + state + " - header was " + buffer);
                        }
                    } else {
                        throw new IllegalArgumentException("Got ascii " + ((int)c) + " while in " + state);
                    }
                } else {
                    boolean isFirst = !isQuotedString && buffer.length() == 0;
                    if (isFirst && ParseUtils.isOWS(c)) {
                        // ignore it
                    } else if (isFirst && c == '"') {
                        isQuotedString = true;
                    } else {

                        if (isQuotedString) {
                            char lastChar = input.charAt(i - 1);
                            if (c == '\\') {
                                // don't append
                            } else if (lastChar == '\\') {
                                buffer.append(c);
                            } else if (c == '"') {
                                // this is the end, but we'll update on the next go
                                isQuotedString = false;
                            } else {
                                buffer.append(c);
                            }
                        } else {
                            if (ParseUtils.isTChar(c)) {
                                buffer.append(c);
                            } else if (c == ';') {

                                String val = buffer.toString();
                                switch (paramName.toLowerCase()) {
                                    case "by":
                                        by = val;
                                        break;
                                    case "for":
                                        forValue = val;
                                        break;
                                    case "host":
                                        host = val;
                                        break;
                                    case "proto":
                                        proto = val;
                                        break;
                                    default:
                                        if (extensions == null) {
                                            extensions = new LinkedHashMap<>(); // keeps insertion order
                                        }
                                        extensions.put(paramName, val);
                                    break;
                                }

                                buffer.setLength(0);
                                paramName = null;
                                state = State.PARAM_NAME;
                            } else if (ParseUtils.isOWS(c)) {
                                // ignore it
                            } else if (c == ',') {
                                i++;
                                break headerValueLoop;
                            } else {
                                throw new IllegalArgumentException("Got character code " + ((int) c) + " (" + c + ") while parsing parameter value");
                            }
                        }
                    }
                }
            }
            switch (state) {
                case PARAM_VALUE:
                    String val = buffer.toString();
                    switch (paramName.toLowerCase()) {
                        case "by":
                            by = val;
                            break;
                        case "for":
                            forValue = val;
                            break;
                        case "host":
                            host = val;
                            break;
                        case "proto":
                            proto = val;
                            break;
                        default:
                            if (extensions == null) {
                                extensions = new LinkedHashMap<>(); // keeps insertion order
                            }
                            extensions.put(paramName, val);
                            break;
                    }
                    buffer.setLength(0);
                    break;
                default:
                    if (buffer.length() > 0) {
                        throw new IllegalArgumentException("Unexpected ending point at state " + state + " for " + input);
                    }
            }

            results.add(new ForwardedHeader(by, forValue, host, proto, extensions));
        }

        return results;
    }

    /**
     * Converts the HeaderValue into a string, suitable for printing in an HTTP header.
     *
     * @return A String, such as "some-value" or "content-type:text/html;charset=UTF-8"
     */
    public String toString() {
        StringBuilder sb = new StringBuilder();
        appendString(sb, "by", by);
        appendString(sb, "for", forValue);
        appendString(sb, "host", host);
        appendString(sb, "proto", proto);
        this.extensions().forEach((key, value) -> appendString(sb, key, value));
        return sb.toString();
    }

    /**
     * Converts a list of headers into a single string that can be put into a Forwarded header field.
     * @param headers The headers to serialise
     * @return An RFC-7239 compliant Forwarded header value.
     */
    public static String toString(List<ForwardedHeader> headers) {
        StringBuilder sb = new StringBuilder();
        for (ForwardedHeader header : headers) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(header.toString());
        }
        return sb.toString();
    }

    private static void appendString(StringBuilder sb, String key, String value) {
        if (value == null) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(';');
        }
        sb.append(key).append('=').append(ParseUtils.quoteIfNeeded(value));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ForwardedHeader that = (ForwardedHeader) o;
        return Objects.equals(by, that.by) &&
            Objects.equals(forValue, that.forValue) &&
            Objects.equals(host, that.host) &&
            Objects.equals(proto, that.proto) &&
            extensions.equals(that.extensions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(by, forValue, host, proto, extensions);
    }
}