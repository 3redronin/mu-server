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
            String absoluteTargetAuthority = absoluteTarget == null ? null : authorityWithoutUserInfo(absoluteTarget);
            String defaultScheme = absoluteTarget == null ? defaultValue.getScheme() : absoluteTarget.getScheme();
            List<ForwardedHeader> forwarded = getForwardedHeaders(h, absoluteTargetAuthority);
            if (forwarded.isEmpty()) {
                if (absoluteTargetAuthority != null) {
                    if (!Mutils.nullOrEmpty(hostHeader)) {
                        URI.create(defaultScheme + "://" + hostHeader);
                    }
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

    private static @Nullable URI absoluteTarget(@Nullable String requestTarget) throws URISyntaxException {
        if (requestTarget == null) return null;
        URI uri = new URI(requestTarget);
        return uri.isAbsolute() && uri.getRawAuthority() != null ? uri : null;
    }

    private static String authorityWithoutUserInfo(URI uri) throws HttpException {
        String authority = uri.getRawAuthority();
        String userInfo = uri.getRawUserInfo();
        if (authority == null) throw HttpException.badRequest("Invalid request authority");
        if (userInfo != null) authority = authority.substring(userInfo.length() + 1);
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
