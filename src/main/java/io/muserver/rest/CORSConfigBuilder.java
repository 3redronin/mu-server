package io.muserver.rest;

import io.muserver.Method;
import io.muserver.MuServerBuilder;
import io.muserver.Mutils;
import io.muserver.handlers.CORSHandlerBuilder;

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
    private Collection<String> allowedHeaders = Collections.emptySet();
    private long maxAge = -1;

    /**
     * All origins will be allowed
     * @return This builder
     */
    public CORSConfigBuilder withAllOriginsAllowed() {
        return withAllowedOrigins((Collection<String>)null);
    }

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
     * The origin values that CORS requests are allowed for, or null to allow all origins.
     * @param allowedOrigins Allowed origins, such as <code>https://example.org</code> or <code>http://localhost:8080</code>
     * @return This builder
     */
    public CORSConfigBuilder withAllowedOrigins(Collection<String> allowedOrigins) {
        if (allowedOrigins != null) {
            for (String allowedOrigin : allowedOrigins) {
                if (!allowedOrigin.startsWith("http://") && !allowedOrigin.startsWith("https://")) {
                    throw new IllegalArgumentException(allowedOrigin + " is invalid: origins much have an http:// or https:// prefix");
                }
                if (allowedOrigin.lastIndexOf('/') > 8) {
                    throw new IllegalArgumentException(allowedOrigin + " is invalid: origins should not have any paths. Example origin: https://example.org");
                }
            }
        }
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
     * <p>Note: this is a no-op if <code>null</code> is used.</p>
     * @param allowedOriginRegex A regex to match, e.g. <code>Pattern.compile("https://.*\\.example\\.org")</code> to allow
     *                           all subdomains of <code>example.org</code> over HTTPS.
     * @return This builder
     */
    public CORSConfigBuilder withAllowedOriginRegex(Pattern allowedOriginRegex) {
        if (allowedOriginRegex != null) {
            this.allowedOriginRegex.add(allowedOriginRegex);
        }
        return this;
    }

    /**
     * <p>The origin values that CORS requests are allowed for.</p>
     * <p>If called multiple times, then just one of the patterns need to match to allow the origin.</p>
     * <p>Note: this is a no-op if <code>null</code> is used.</p>
     * @param allowedOriginRegex A regex to match, e.g. <code>"https://.*\\.example\\.org"</code> to allow
     *                           all subdomains of <code>example.org</code> over HTTPS.
     * @return This builder
     * @throws PatternSyntaxException If the expression's syntax is invalid
     */
    public CORSConfigBuilder withAllowedOriginRegex(String allowedOriginRegex) {
        if (allowedOriginRegex == null) {
            return this;
        }
        return withAllowedOriginRegex(Pattern.compile(allowedOriginRegex));
    }

    /**
     * Adds all localhost URLs (whether http or https) as allowed origins.
     * @return This builder
     */
    public CORSConfigBuilder withLocalhostAllowed() {
        return withAllowedOriginRegex(Pattern.compile("https?://localhost(:[0-9]+)?"));
    }

    /**
     * <p>Specifies which headers (aside from "simple" headers) are allowed to be accessed by JavaScript in responses.</p>
     * <p>The "simple" headers are: <code>Cache-Control</code>, <code>Content-Language</code>,
     * <code>Content-Type</code>, <code>Expires</code>, <code>Last-Modified</code>, <code>Pragma</code>
     * (so you do not need to specify these values).</p>
     * @param headerNames The names of headers to allow, for example <code>Content-Type</code>
     * @return This builder
     */
    public CORSConfigBuilder withExposedHeaders(String... headerNames) {
        Mutils.notNull("headerNames", headerNames);
        return withExposedHeaders(asList(headerNames));
    }

    /**
     * <p>Specifies which headers (aside from "simple" headers) are allowed to be accessed by JavaScript in responses.</p>
     * <p>The "simple" headers are: <code>Cache-Control</code>, <code>Content-Language</code>,
     * <code>Content-Type</code>, <code>Expires</code>, <code>Last-Modified</code>, <code>Pragma</code>
     * (so you do not need to specify these values).</p>
     * @param headerNames The names of headers to allow, for example <code>Content-Type</code>
     * @return This builder
     */
    public CORSConfigBuilder withExposedHeaders(Collection<String> headerNames) {
        Mutils.notNull("headerNames", headerNames);
        this.exposedHeaders = headerNames;
        return this;
    }

    /**
     * <p>On preflight OPTIONS requests, specifies which headers are allowed to be sent on the request, aside from the "simple" headers.</p>
     * <p>The "simple" headers are <code>Accept</code>, <code>Accept-Language</code>, <code>Content-Language</code>,
     * <code>Content-Type</code> (but only with a MIME type of <code>application/x-www-form-urlencoded</code>, <code>multipart/form-data</code>, or <code>text/plain</code>).
     * You do not need to specify the simple headers.</p>
     * @param headerNames The names of headers to allow, for example <code>Content-Length</code>
     * @return This builder
     */
    public CORSConfigBuilder withAllowedHeaders(String... headerNames) {
        Mutils.notNull("headerNames", headerNames);
        return withAllowedHeaders(asList(headerNames));
    }

    /**
     * <p>On preflight OPTIONS requests, specifies which headers are allowed to be sent on the request, aside from the "simple" headers.</p>
     * <p>The "simple" headers are <code>Accept</code>, <code>Accept-Language</code>, <code>Content-Language</code>,
     * <code>Content-Type</code> (but only with a MIME type of <code>application/x-www-form-urlencoded</code>, <code>multipart/form-data</code>, or <code>text/plain</code>).
     * You do not need to specify the simple headers.</p>
     * @param headerNames The names of headers to allow, for example <code>Content-Length</code>
     * @return This builder
     */
    public CORSConfigBuilder withAllowedHeaders(Collection<String> headerNames) {
        Mutils.notNull("headerNames", headerNames);
        this.allowedHeaders = headerNames;
        return this;
    }

    /**
     * On preflight OPTIONS requests, specifies the time the response is valid for
     * @param seconds The age in seconds, for example 600 for five minutes.
     * @return This builder.
     */
    public CORSConfigBuilder withMaxAge(long seconds) {
        this.maxAge = seconds;
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
     * Creates a CORS handler from this config.
     * <p>This can be used when CORS configuration is needed outside of JAX-RS. Add the created handler to
     * a {@link MuServerBuilder} before any other handlers that require CORS config.</p>
     * <p>Note that if you only need CORS config for JAX-RS then instead of this method you should pass
     * this config to {@link RestHandlerBuilder#withCORS(CORSConfigBuilder)}</p>
     * @param allowedMethods The allowed methods for CORS calls
     * @return A handler that can be added.
     */
    public CORSHandlerBuilder toHandler(Method... allowedMethods) {
        return CORSHandlerBuilder.corsHandler().withCORSConfig(this).withAllowedMethods(allowedMethods);
    }

    /**
     * Builds CORS configuration from a builder
     * @return An immutable configuration object.
     */
    public CORSConfig build() {
        return new CORSConfig(allowCredentials, allowedOrigins, allowedOriginRegex, allowedHeaders, exposedHeaders, maxAge);
    }

}
