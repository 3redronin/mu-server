package io.muserver;

import jakarta.ws.rs.core.MediaType;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyList;

class Headtils {
    static List<ForwardedHeader> getForwardedHeaders(Headers headers) {
        return getForwardedHeaders(headers, null);
    }

    private static List<ForwardedHeader> getForwardedHeaders(Headers headers, @Nullable String fallbackHost) {
        List<String> all = headers.getAll(HeaderNames.FORWARDED);
        if (all.isEmpty()) {

            List<String> hosts = getXForwardedValue(headers, HeaderNames.X_FORWARDED_HOST);
            List<String> ports = getXForwardedValue(headers, HeaderNames.X_FORWARDED_PORT);
            List<String> protos = getXForwardedValue(headers, HeaderNames.X_FORWARDED_PROTO);
            List<String> fors = getXForwardedValue(headers, HeaderNames.X_FORWARDED_FOR);
            int max = Math.max(Math.max(Math.max(hosts.size(), protos.size()), fors.size()), ports.size());
            if (max == 0) {
                return emptyList();
            }
            List<ForwardedHeader> results = new ArrayList<>();

            boolean includeHost = hosts.size() == max;
            boolean includeProto = protos.size() == max;
            boolean includeFor = fors.size() == max;
            boolean includePort = ports.size() == max;
            String curHost = includePort && !includeHost
                ? Mutils.coalesce(fallbackHost, headers.get(HeaderNames.HOST))
                : null;

            for (int i = 0; i < max; i++) {
                String host = includeHost ? hosts.get(i) : null;
                String port = includePort ? ports.get(i) : null;
                String proto = includeProto ? protos.get(i) : null;
                String forValue = includeFor ? fors.get(i) : null;
                boolean useDefaultPort = port == null || (proto != null &&
                    ((proto.equalsIgnoreCase("http") && "80".equals(port))
                    || (proto.equalsIgnoreCase("https") && "443".equals(port))));
                String hostToUse =
                    includeHost ? host
                    : includePort ? curHost
                    : null;
                if (hostToUse != null && !useDefaultPort) {
                    hostToUse = hostToUse.replaceFirst(":[0-9]+$", "") + ":" + port;
                }
                results.add(new ForwardedHeader(null, forValue, hostToUse, proto, null));
            }

            return results;
        } else {
            List<ForwardedHeader> results = new ArrayList<>();
            for (String s : all) {
                results.addAll(ForwardedHeader.fromString(s));
            }
            return results;
        }
    }

    private static List<String> getXForwardedValue(Headers headers, CharSequence name) {
        List<String> values = headers.getAll(name);
        if (values.isEmpty()) return emptyList();
        return values.stream().map(v -> v.split("\\s*,\\s*")).flatMap(Arrays::stream).collect(Collectors.toList());
    }

    static URI getUri(Logger log, Headers h, String requestUri, URI defaultValue) {
        return getUri(log, h, requestUri, defaultValue, null);
    }

    static URI getUri(Logger log, Headers h, String requestUri, URI defaultValue,
                      @Nullable String originalRequestTarget) {
        try {
            String hostHeader = h.get(HeaderNames.HOST);
            URI absoluteTarget = absoluteTarget(originalRequestTarget);
            String absoluteTargetAuthority = absoluteTarget == null ? null : authorityFromAbsoluteTarget(absoluteTarget);
            String defaultScheme = absoluteTarget == null ? defaultValue.getScheme() : absoluteTarget.getScheme();
            if (originalRequestTarget != null) {
                validateHttp1Host(h);
                if (absoluteTargetAuthority != null) validateAuthority(absoluteTargetAuthority);
            }
            List<ForwardedHeader> forwarded = getForwardedHeaders(h, absoluteTargetAuthority);
            if (forwarded.isEmpty()) {
                if (absoluteTargetAuthority != null) {
                    return URI.create(defaultScheme + "://" + absoluteTargetAuthority).resolve(requestUri);
                }
                if (Mutils.nullOrEmpty(hostHeader) || defaultValue.getHost().equals(hostHeader)
                    || defaultValue.getRawAuthority().equals(hostHeader)) {
                    return defaultValue;
                }
                return URI.create(defaultValue.getScheme() + "://" + hostHeader).resolve(requestUri);
            }
            ForwardedHeader f = forwarded.get(0);
            String originalScheme = Mutils.coalesce(f.proto(), defaultScheme);
            String host = Mutils.coalesce(f.host(), absoluteTargetAuthority, hostHeader, "localhost");
            return URI.create(originalScheme + "://" + host).resolve(requestUri);
        } catch (Exception e) {
            if (e instanceof HttpException) throw (HttpException) e;
            log.warn("Could not create a URI object using header values " + h);
            throw HttpException.badRequest("Invalid request authority");
        }
    }

    static boolean isSecure(Headers headers, boolean transportIsSecure) {
        List<ForwardedHeader> forwarded = getForwardedHeaders(headers);
        if (forwarded.isEmpty() || forwarded.get(0).proto() == null) return transportIsSecure;
        return "https".equalsIgnoreCase(forwarded.get(0).proto());
    }

    private static @Nullable URI absoluteTarget(@Nullable String requestTarget) throws URISyntaxException {
        if (requestTarget == null) return null;
        URI uri = new URI(requestTarget);
        if (uri.isAbsolute() && (uri.getRawUserInfo() != null || uri.getRawFragment() != null)) {
            throw HttpException.badRequest("Invalid request target");
        }
        if (uri.isAbsolute() && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
            && uri.getRawAuthority() == null) {
            throw HttpException.badRequest("Invalid request target");
        }
        return uri.isAbsolute() && uri.getRawAuthority() != null ? uri : null;
    }

    private static void validateHttp1Host(Headers headers) throws URISyntaxException {
        List<String> hosts = headers.getAll(HeaderNames.HOST);
        if (hosts.size() > 1) throw HttpException.badRequest("Multiple Host headers");
        if (hosts.size() == 1) validateAuthority(hosts.get(0));
    }

    private static void validateAuthority(String authority) throws URISyntaxException {
        URI parsed = new URI("http://" + authority);
        if (parsed.getRawAuthority() == null || parsed.getRawUserInfo() != null
            || (parsed.getRawPath() != null && !parsed.getRawPath().isEmpty())
            || parsed.getRawQuery() != null || parsed.getRawFragment() != null) {
            throw HttpException.badRequest("Invalid request authority");
        }

        // URI#getHost only recognizes server-style names; reg-name also permits valid registered names such as service_name.
        String host = authority;
        String port = null;
        if (authority.startsWith("[")) {
            int closeBracket = authority.indexOf(']');
            if (closeBracket < 0) {
                throw HttpException.badRequest("Invalid request authority");
            }
            host = authority.substring(0, closeBracket + 1);
            if (closeBracket + 1 < authority.length()) {
                if (authority.charAt(closeBracket + 1) != ':') {
                    throw HttpException.badRequest("Invalid request authority");
                }
                port = authority.substring(closeBracket + 2);
            }
        } else {
            int colon = authority.lastIndexOf(':');
            if (colon >= 0) {
                if (authority.indexOf(':') != colon) throw HttpException.badRequest("Invalid request authority");
                host = authority.substring(0, colon);
                port = authority.substring(colon + 1);
            }
            if (!isValidRegName(host)) throw HttpException.badRequest("Invalid request authority");
        }
        if (port != null && !port.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw HttpException.badRequest("Invalid request authority");
        }
    }

    private static boolean isValidRegName(String host) {
        if (host.isEmpty()) return false;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (isUnreserved(c) || "!$&'()*+,;=".indexOf(c) >= 0) continue;
            if (c == '%' && i + 2 < host.length() && isHex(host.charAt(i + 1)) && isHex(host.charAt(i + 2))) {
                i += 2;
                continue;
            }
            return false;
        }
        return true;
    }

    private static boolean isUnreserved(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
            || c == '-' || c == '.' || c == '_' || c == '~';
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static String authorityFromAbsoluteTarget(URI uri) throws HttpException {
        String authority = uri.getRawAuthority();
        if (authority == null) throw HttpException.badRequest("Invalid request authority");
        if (authority.isEmpty()) throw HttpException.badRequest("Invalid request authority");
        return authority;
    }

    private static final Logger log = LoggerFactory.getLogger(Headtils.class);
    static Charset bodyCharset(Headers headers, boolean isRequest) {
        MediaType mediaType = headers.contentType();
        Charset bodyCharset = UTF_8;
        if (mediaType != null) {
            String charset = mediaType.getParameters().get("charset");
            if (!Mutils.nullOrEmpty(charset)) {
                try {
                    bodyCharset = Charset.forName(charset);
                } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
                    if (isRequest) {
                        throw HttpException.badRequest("Invalid request body charset");
                    } else {
                        log.error("Invalid response body charset: " + mediaType, e);
                        throw HttpException.internalServerError("Invalid response body charset");
                    }
                }
            }
        }
        return bodyCharset;
    }

}
