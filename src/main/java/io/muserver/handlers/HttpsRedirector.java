package io.muserver.handlers;

import io.muserver.*;

import java.net.URI;

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
        boolean isHttp = uri.getScheme().equals("http");
        if (!isHttp) {
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

        int port = httpsPort == 443 ? -1 : httpsPort;
        URI newURI = new URI("https", uri.getUserInfo(), uri.getHost(), port, uri.getPath(), uri.getQuery(), uri.getFragment());
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
