package io.muserver.handlers;

import io.muserver.MuHandler;
import io.muserver.MuHandlerBuilder;
import jakarta.ws.rs.BadRequestException;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * <p>Builder for {@link CSRFProtectionHandler} which protects against Cross-Site Request Forgery (CSRF) by rejecting non-safe
 * cross-origin browser requests.</p>
 * <p>Allows configuration of trusted origins, bypass patterns, and a custom rejection handler.</p>
 */
public class CSRFProtectionHandlerBuilder implements MuHandlerBuilder<CSRFProtectionHandler> {

    private final Set<String> trustedOrigins = new HashSet<>();
    private final Set<String> bypassPaths = new HashSet<>();
    private MuHandler rejectionHandler;

    /**
     * Adds a trusted origin. Requests with an <code>Origin</code> header exactly matching this value are allowed.
     * @param origin The trusted origin (e.g. <code>https://example.com</code>)
     * @return This builder
     */
    public CSRFProtectionHandlerBuilder addTrustedOrigin(String origin) {
        trustedOrigins.add(origin);
        return this;
    }

    /**
     * Adds a bypass path. Requests to this path are always allowed.
     * @param path The path to bypass (e.g. <code>/api/health</code>)
     * @return This builder
     */
    public CSRFProtectionHandlerBuilder addBypassPath(String path) {
        bypassPaths.add(path);
        return this;
    }

    /**
     * Sets a custom handler to execute when a request is rejected due to CSRF protection.
     *
     * <p>The provided handler will be invoked whenever a request fails CSRF validation. The handler can
     * perform custom logic such as logging, returning a specific error response, or allowing the request
     * to proceed by returning <code>false</code> from its <code>handle</code> method.</p>
     *
     * <p>If no custom handler is set, a default handler will throw a {@link BadRequestException}.</p>
     *
     * <p>Example usage:</p>
     * <pre><code>
     * builder.withRejectionHandler((request, response) -&gt; {
     *     System.out.println("CSRF protection triggered for request to " + request.uri() + " with headers: " + request.headers());
     *     response.status(400);
     *     response.write("Forbidden");
     *     return true;
     * });
     * </code></pre>
     * @param handler The {@link MuHandler} to execute when a request is rejected by CSRF protection, or <code>null</code>
     *                for a default handler.
     * @return This builder instance for method chaining.
     */
    public CSRFProtectionHandlerBuilder withRejectionHandler(MuHandler handler) {
        this.rejectionHandler = handler;
        return this;
    }

    @Override
    public CSRFProtectionHandler build() {
        Set<String> trustedOriginsFinal = Collections.unmodifiableSet(new HashSet<>(trustedOrigins));
        Set<String> bypassPathsFinal = Collections.unmodifiableSet(new HashSet<>(bypassPaths));
        MuHandler rejectionHandlerFinal = rejectionHandler == null ? defaultRejectionHandler() : rejectionHandler;
        return new CSRFProtectionHandler(trustedOriginsFinal, bypassPathsFinal, rejectionHandlerFinal);
    }

    private static MuHandler defaultRejectionHandler() {
        return (request, response) -> {
            throw new BadRequestException("Cross-origin request rejected by CSRFHandler");
        };
    }

    /**
     * Creates a new builder.
     * @return A new CSRFHandlerBuilder
     */
    public static CSRFProtectionHandlerBuilder csrfProtection() {
        return new CSRFProtectionHandlerBuilder();
    }
}