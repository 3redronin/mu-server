package io.muserver.rest;

import io.muserver.*;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.regex.Pattern;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toSet;

/**
 * CORS configuration for REST resources. Create this using {@link CORSConfigBuilder#corsConfig()}
 */
public class CORSConfig {

    public final boolean allowCredentials;
    public final Collection<String> allowedOrigins;
    public final Pattern allowedOriginRegex;
    public final Collection<String> exposedHeaders;
    public final long maxAge;
    private final String exposedHeadersCSV;

    CORSConfig(boolean allowCredentials, Collection<String> allowedOrigins, Pattern allowedOriginRegex, Collection<String> exposedHeaders, long maxAge) {
        this.allowCredentials = allowCredentials;
        this.allowedOrigins = Collections.unmodifiableCollection(allowedOrigins);
        this.allowedOriginRegex = allowedOriginRegex;
        this.exposedHeaders = Collections.unmodifiableCollection(exposedHeaders);
        this.maxAge = maxAge;
        this.exposedHeadersCSV = exposedHeaders.stream().sorted().collect(joining(", "));
    }

    boolean writeHeaders(MuRequest request, MuResponse response, Set<RequestMatcher.MatchedMethod> matchedMethodsForPath) {

        response.headers().add(HeaderNames.VARY, HeaderNames.ORIGIN);

        String origin  = request.headers().get(HeaderNames.ORIGIN);
        if (Mutils.nullOrEmpty(origin)) {
            return false;
        }

        Headers respHeaders = response.headers();
        if (allowCors(origin)) {
            respHeaders.set(HeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
            respHeaders.set(HeaderNames.ACCESS_CONTROL_ALLOW_METHODS, getAllowedMethods(matchedMethodsForPath));
            if (request.method() == Method.OPTIONS) {
                respHeaders.set(HeaderNames.ACCESS_CONTROL_MAX_AGE, maxAge);
                respHeaders.set(HeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, exposedHeadersCSV);
            } else {
                respHeaders.set(HeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS, exposedHeadersCSV);
            }
            if (allowCredentials) {
                respHeaders.set(HeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
            }
            return true;
        } else {
            respHeaders.set(HeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "null");
            return false;
        }

    }

    static String getAllowedMethods(Set<RequestMatcher.MatchedMethod> matchedMethodsForPath) {
        Set<Method> allowed = matchedMethodsForPath.stream().map(m -> m.resourceMethod.httpMethod).collect(toSet());
        if (allowed.contains(Method.GET)) {
            allowed.add(Method.HEAD);
        }
        allowed.add(Method.OPTIONS);
        return allowed.stream().map(Enum::name).sorted().collect(joining(", "));
    }

    boolean allowCors(String origin) {
        if (allowedOrigins == null || allowedOrigins.contains(origin)) {
            return true;
        }
        return allowedOriginRegex != null && allowedOriginRegex.matcher(origin).matches();
    }

}
