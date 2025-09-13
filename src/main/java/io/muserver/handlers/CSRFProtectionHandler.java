package io.muserver.handlers;

import io.muserver.*;
import jakarta.ws.rs.BadRequestException;

import java.net.URI;
import java.util.Set;

/**
 * <p>Protects against Cross-Site Request Forgery (CSRF) by rejecting non-safe cross-origin browser requests.</p>
 * <p>Safe methods (GET, HEAD, OPTIONS) are always allowed. For other methods, requests are allowed if:</p>
 * <ul>
 *   <li>The <code>Sec-Fetch-Site</code> header is <code>same-origin</code> or <code>none</code></li>
 *   <li>The <code>Origin</code> header matches the request host</li>
 *   <li>The <code>Origin</code> header is in the trusted origins list</li>
 *   <li>The request path matches a bypass pattern</li>
 * </ul>
 * <p>If a request is rejected, the configured rejection handler is called. By default, this throws a {@link BadRequestException}.</p>
 * <p>The return value of the <code>handle</code> method is the return value of the rejection handler,
 * allowing custom handlers to override the rejection. In other words, if you supply a custom rejection handler,
 * and you return <code>false</code> from the {@link MuHandler#handle(MuRequest, MuResponse)} method, then
 * the request will not be rejected.</p>
 * <p>This logic was inspired by <a href="https://words.filippo.io/csrf/">Cross-Site Request Forgery</a> by Filippo Valsorda.</p>
 * @see CSRFProtectionHandlerBuilder
 */
public class CSRFProtectionHandler implements MuHandler {

    private final Set<String> trustedOrigins;
    private final Set<String> bypassPaths;
    private final MuHandler rejectionHandler;

    CSRFProtectionHandler(Set<String> trustedOrigins, Set<String> bypassPaths, MuHandler rejectionHandler) {
        this.trustedOrigins = trustedOrigins;
        this.bypassPaths = bypassPaths;
        this.rejectionHandler = rejectionHandler;
    }

    @Override
    public boolean handle(MuRequest request, MuResponse response) throws Exception {
        Method method = request.method();
        if (method == Method.GET || method == Method.HEAD || method == Method.OPTIONS) {
            return false;
        }

        // Bypass patterns
        if (bypassPaths.contains(request.uri().getRawPath())) {
            return false;
        }

        String secFetchSite = request.headers().get("Sec-Fetch-Site");
        if ("same-origin".equals(secFetchSite) || "none".equals(secFetchSite)) {
            return false;
        }
        if (secFetchSite == null || secFetchSite.isEmpty()) {
            // Fallback to Origin header
            String origin = request.headers().get("Origin");
            if (origin == null || origin.isEmpty()) {
                return false;
            }
            URI uri = request.uri();
            String host = uri.getHost();
            int port = uri.getPort();
            String hostHeader = port > 0 ? host + ":" + port : host;
            if (origin.endsWith("://" + hostHeader) || trustedOrigins.contains(origin)) {
                return false;
            }
        } else {
            // Cross-origin request detected
            if (trustedOrigins.contains(request.headers().get("Origin"))) {
                return false;
            }
        }
        // Rejected
        return rejectionHandler.handle(request, response);
    }

    private static MuHandler defaultRejectionHandler() {
        return (request, response) -> { throw new BadRequestException("Cross-origin request rejected by CSRFHandler"); };
    }

    @Override
    public String toString() {
        return "CSRFHandler{" +
            "trustedOrigins=" + trustedOrigins +
            ", bypassPaths=" + bypassPaths +
            ", rejectionHandler=" + rejectionHandler +
            '}';
    }
}