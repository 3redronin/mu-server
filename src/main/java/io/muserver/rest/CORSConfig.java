package io.muserver.rest;

import io.muserver.*;

import java.util.*;
import java.util.regex.Pattern;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toSet;

/**
 * CORS configuration for REST resources. Create this using {@link CORSConfigBuilder#corsConfig()}
 */
public class CORSConfig {

    private final boolean allowCredentials;
    private final Collection<String> allowedOrigins;
    private final List<Pattern> allowedOriginRegex;
    private final Collection<String> exposedHeaders;
    private final long maxAge;
    private final Collection<String> allowedHeaders;
    private final String exposedHeadersCSV;
    private final String allowedHeadersCSV;

    CORSConfig(boolean allowCredentials, Collection<String> allowedOrigins, List<Pattern> allowedOriginRegex, Collection<String> allowedHeaders, Collection<String> exposedHeaders, long maxAge) {
        Mutils.notNull("allowedOriginRegex", allowedOriginRegex);
        this.allowCredentials = allowCredentials;
        this.allowedOrigins = allowedOrigins == null ? null : Collections.unmodifiableCollection(allowedOrigins);
        this.allowedOriginRegex = allowedOriginRegex;
        this.maxAge = maxAge;
        this.allowedHeaders = allowedHeaders;
        this.allowedHeadersCSV = String.join(", ", allowedHeaders);
        this.exposedHeaders = Collections.unmodifiableCollection(exposedHeaders);
        this.exposedHeadersCSV = String.join(", ", exposedHeaders);
    }

    /**
     * Adds CORS headers to the response, if needed.
     *
     * @param request        The request
     * @param response       The response to add headers to
     * @param allowedMethods The methods
     * @return Returns true if any Access Control headers were added; otherwise false. (Note: the <code>Vary: origin</code> header is always added.
     */
    public boolean writeHeaders(MuRequest request, MuResponse response, Set<Method> allowedMethods) {
        boolean written = writeHeadersInternal(request, response, null);
        if (written) {
            response.headers().set(HeaderNames.ACCESS_CONTROL_ALLOW_METHODS, getAllowedString(allowedMethods));
        }
        return written;
    }

    boolean writeHeadersInternal(MuRequest request, MuResponse response, Set<RequestMatcher.MatchedMethod> matchedMethodsForPath) {

        Headers respHeaders = response.headers();
        if (!respHeaders.containsValue(HeaderNames.VARY, HeaderNames.ORIGIN, true)) {
            respHeaders.add(HeaderNames.VARY, HeaderNames.ORIGIN);
        }

        String origin = request.headers().get(HeaderNames.ORIGIN);
        if (Mutils.nullOrEmpty(origin)) {
            return false;
        }

        if (allowCors(origin)) {
            respHeaders.set(HeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
            if (matchedMethodsForPath != null) {
                String allowed = getAllowedMethods(matchedMethodsForPath);
                respHeaders.set(HeaderNames.ACCESS_CONTROL_ALLOW_METHODS, allowed);
            }
            if (request.method() == Method.OPTIONS) {
                respHeaders.set(HeaderNames.ACCESS_CONTROL_MAX_AGE, maxAge);
                if (!allowedHeaders.isEmpty()) {
                    respHeaders.set(HeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, allowedHeadersCSV);
                }
            }
            if (!exposedHeaders.isEmpty()) {
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
        Set<Method> methods = matchedMethodsForPath.stream().map(m -> m.resourceMethod.httpMethod).collect(toSet());
        return getAllowedString(methods);
    }


    static String getAllowedString(Set<Method> allowed) {
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
        for (Pattern pattern : allowedOriginRegex) {
            if (pattern.matcher(origin).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the value described by {@link CORSConfigBuilder#withAllowCredentials}
     */
    public boolean allowCredentials() {
        return allowCredentials;
    }

    /**
      @return the value described by {@link CORSConfigBuilder#withAllowedOrigins}
     */
    public Collection<String> allowedOrigins() {
        return allowedOrigins;
    }

    /**
      @return the value described by {@link CORSConfigBuilder#withAllowedOriginRegex}
     */
    public List<Pattern> allowedOriginRegex() {
        return allowedOriginRegex;
    }

    /**
      @return the value described by {@link CORSConfigBuilder#withExposedHeaders}
     */
    public Collection<String> exposedHeaders() {
        return exposedHeaders;
    }

    /**
      @return the value described by {@link CORSConfigBuilder#withMaxAge}
     */
    public long maxAge() {
        return maxAge;
    }

    /**
      @return the value described by {@link CORSConfigBuilder#withAllowedHeaders}
     */
    public Collection<String> allowedHeaders() {
        return allowedHeaders;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CORSConfig that = (CORSConfig) o;
        return allowCredentials == that.allowCredentials && maxAge == that.maxAge && Objects.equals(allowedOrigins, that.allowedOrigins) && Objects.equals(allowedOriginRegex, that.allowedOriginRegex) && Objects.equals(exposedHeaders, that.exposedHeaders) && Objects.equals(allowedHeaders, that.allowedHeaders) && Objects.equals(exposedHeadersCSV, that.exposedHeadersCSV) && Objects.equals(allowedHeadersCSV, that.allowedHeadersCSV);
    }

    @Override
    public int hashCode() {
        return Objects.hash(allowCredentials, allowedOrigins, allowedOriginRegex, exposedHeaders, maxAge, allowedHeaders, exposedHeadersCSV, allowedHeadersCSV);
    }

    @Override
    public String toString() {
        return "CORSConfig{" +
            "allowCredentials=" + allowCredentials +
            ", allowedOrigins=" + allowedOrigins +
            ", allowedOriginRegex=" + allowedOriginRegex +
            ", exposedHeaders=" + exposedHeaders +
            ", maxAge=" + maxAge +
            ", allowedHeaders=" + allowedHeaders +
            ", exposedHeadersCSV='" + exposedHeadersCSV + '\'' +
            ", allowedHeadersCSV='" + allowedHeadersCSV + '\'' +
            '}';
    }
}
