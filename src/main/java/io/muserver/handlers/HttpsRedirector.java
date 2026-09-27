package io.muserver.handlers;

import io.muserver.*;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * <p>Sends any HTTP requests to the same HTTPS address at the supplied port and optionally enables
 * <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Strict-Transport-Security" target="_blank">Strict-Transport-Security (HSTS)</a>
 * </p>
 * @see HttpsRedirectorBuilder
 */
public class HttpsRedirector implements MuHandler {

    private final int httpsPort;
    private final long expireTimeInSeconds;
    private final boolean includeSubDomainsForHSTS;
    private final boolean preload;

    HttpsRedirector(int httpsPort, long expireTimeInSeconds, boolean includeSubDomainsForHSTS, boolean preload) {
        this.httpsPort = httpsPort;
        this.expireTimeInSeconds = expireTimeInSeconds;
        this.includeSubDomainsForHSTS = includeSubDomainsForHSTS;
        this.preload = preload;
    }

    @Override
    public boolean handle(MuRequest request, MuResponse response) throws Exception {
        URI uri = request.uri();
        if (request.isSecure()) {
            // Note: clients should ignore HSTS headers on non-HTTPS requests
            if (expireTimeInSeconds > 0) {
                String val = "max-age=" + expireTimeInSeconds;
                if (includeSubDomainsForHSTS) {
                    val += "; includeSubDomains";
                }
                if (preload) {
                    val += "; preload";
                }
                response.headers().set(HeaderNames.STRICT_TRANSPORT_SECURITY, val);
            }
            return false;
        }

        URI newURI = httpsUri(uri);
        if (request.method() == Method.GET || request.method() == Method.HEAD) {
            response.status(301);
            response.redirect(newURI);
        } else {
            response.status(400);
            response.contentType(ContentTypes.TEXT_PLAIN_UTF8);
            response.write("HTTP is not supported for this endpoint. Please use the HTTPS endpoint at " + newURI.resolve("/"));
        }
        return true;
    }

    private URI httpsUri(URI uri) throws URISyntaxException {
        String rawAuthority = uri.getRawAuthority();
        if (rawAuthority == null) throw new IllegalArgumentException("Request URI has no authority");

        // A port can only trail the authority; IPv6 literals end in ']' and remain intact.
        String host = rawAuthority.replaceFirst(":[0-9]*$", "");
        StringBuilder target = new StringBuilder("https://").append(host);
        if (httpsPort != 443) target.append(':').append(httpsPort);
        if (uri.getRawPath() != null) target.append(uri.getRawPath());
        if (uri.getRawQuery() != null) target.append('?').append(uri.getRawQuery());
        if (uri.getRawFragment() != null) target.append('#').append(uri.getRawFragment());
        return new URI(target.toString());
    }

    @Override
    public String toString() {
        return "HttpsRedirector{" +
            "httpsPort=" + httpsPort +
            ", expireTimeInSeconds=" + expireTimeInSeconds +
            ", includeSubDomainsForHSTS=" + includeSubDomainsForHSTS +
            ", preload=" + preload +
            '}';
    }
}
