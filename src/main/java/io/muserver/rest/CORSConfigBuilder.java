package io.muserver.rest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static java.util.Arrays.asList;

/**
 * <p>A builder to set configuration for CORS requests.</p>
 */
public class CORSConfigBuilder {

    private boolean allowCredentials = false;
    private Collection<String> allowedOrigins = Collections.emptySet();
    private List<Pattern> allowedOriginRegex = new ArrayList<>();
    private Collection<String> exposedHeaders = Collections.emptySet();
    private long maxAge = -1;

    /**
     * If set to true, then <code>access-control-allow-credentials</code> is returned with <code>true</code> on CORS responses.
     * @param allowCredentials Whether or not to include the credentials header
     * @return This builder
     */
    public CORSConfigBuilder withAllowCredentials(boolean allowCredentials) {
        this.allowCredentials = allowCredentials;
        return this;
    }

    /**
     * The origin values that CORS requests are allowed for.
     * @param allowedOrigins Allowed origins, such as <code>https://example.org</code> or <code>http://localhost:8080</code>
     * @return This builder
     */
    public CORSConfigBuilder withAllowedOrigins(Collection<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
        return this;
    }

    /**
     * The origin values that CORS requests are allowed for.
     * @param allowedOrigins Allowed origins, such as <code>https://example.org</code> or <code>http://localhost:8080</code>
     * @return This builder
     */
    public CORSConfigBuilder withAllowedOrigins(String... allowedOrigins) {
        return withAllowedOrigins(asList(allowedOrigins));
    }

    /**
     * <p>The origin values that CORS requests are allowed for.</p>
     * <p>If called multiple times, then just one of the patterns need to match to allow the origin.</p>
     * @param allowedOriginRegex A regex to match, e.g. <code>Pattern.compile("https://.*\\.example\\.org")</code> to allow
     *                           all subdomains of <code>example.org</code> over HTTPS.
     * @return This builder
     */
    public CORSConfigBuilder withAllowedOriginRegex(Pattern allowedOriginRegex) {
        this.allowedOriginRegex.add(allowedOriginRegex);
        return this;
    }

    /**
     * <p>The origin values that CORS requests are allowed for.</p>
     * <p>If called multiple times, then just one of the patterns need to match to allow the origin.</p>
     * * @param allowedOriginRegex A regex to match, e.g. <code>"https://.*\\.example\\.org"</code> to allow
     *                           all subdomains of <code>example.org</code> over HTTPS.
     * @return This builder
     * @throws PatternSyntaxException If the expression's syntax is invalid
     */
    public CORSConfigBuilder withAllowedOriginRegex(String allowedOriginRegex) {
        return withAllowedOriginRegex(Pattern.compile(allowedOriginRegex));
    }

    /**
     * Specifies which headers are allowed to be accessed by JavaScript.
     * @param headerNames The names of headers to allow, for example <code>Content-Type</code>
     * @return This builder
     */
    public CORSConfigBuilder withExposedHeaders(String... headerNames) {
        return withExposedHeaders(asList(headerNames));
    }


    /**
     * Specifies which headers are allowed to be accessed by JavaScript.
     * @param headerNames The names of headers to allow, for example <code>Content-Type</code>
     * @return This builder
     */
    public CORSConfigBuilder withExposedHeaders(Collection<String> headerNames) {
        this.exposedHeaders = headerNames;
        return this;
    }

    /**
     * On preflight OPTIONS requests, specifies the time the response is valid for
     * @param maxAge The age in seconds, for example 600 for five minutes.
     * @return This builder.
     */
    public CORSConfigBuilder withMaxAge(long maxAge) {
        this.maxAge = maxAge;
        return this;
    }

    /**
     * Creates a builder to set CORS configuration. Normally at least {@link #withAllowedOrigins(String...)} will be called.
     * @return A new builder to create CORS config with
     */
    public static CORSConfigBuilder corsConfig() {
        return new CORSConfigBuilder()
            .withMaxAge(600);
    }

    /**
     * Creates CORS configuration that disables all CORS requests.
     * @return A new builder to create CORS config with
     */
    public static CORSConfigBuilder disabled() {
        return new CORSConfigBuilder();
    }

    /**
     * Builds CORS configuration from a builder
     * @return An immutable configuration object.
     */
    public CORSConfig build() {
        return new CORSConfig(allowCredentials, allowedOrigins, allowedOriginRegex, exposedHeaders, maxAge);
    }

}
