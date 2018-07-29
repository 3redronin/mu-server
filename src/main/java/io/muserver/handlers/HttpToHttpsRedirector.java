package io.muserver.handlers;

import io.muserver.AsyncContext;
import io.muserver.AsyncMuHandler;
import io.muserver.Headers;

import java.net.URI;
import java.nio.ByteBuffer;

/**
 * @deprecated Use {@link HttpsRedirector#toHttpsPort(int)} instead and add it as standard handler.
 */
@Deprecated
public class HttpToHttpsRedirector implements AsyncMuHandler {

    private final int httpsPort;

    /**
     * Creates a HTTPS redirector
     * @param httpsPort The port that HTTPS is running on
     */
    public HttpToHttpsRedirector(int httpsPort) {
        this.httpsPort = httpsPort;
    }

    public boolean onHeaders(AsyncContext ctx, Headers headers) throws Exception {
        URI uri = ctx.request.uri();
        boolean isHttp = uri.getScheme().equals("http");
        if (!isHttp) {
            return false;
        }
        URI newURI = new URI("https", uri.getUserInfo(), uri.getHost(), httpsPort, uri.getPath(), uri.getQuery(), uri.getFragment());
        ctx.response.redirect(newURI);
        ctx.complete(true);
        return true;
    }

    public void onRequestData(AsyncContext ctx, ByteBuffer buffer) throws Exception {
    }

    public void onRequestComplete(AsyncContext ctx) {
    }
}
