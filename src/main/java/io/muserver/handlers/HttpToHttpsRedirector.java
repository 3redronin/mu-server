package io.muserver.handlers;

import io.muserver.AsyncContext;
import io.muserver.AsyncMuHandler;
import io.muserver.Headers;

import java.net.URI;
import java.nio.ByteBuffer;

/**
 * Sends any HTTP requests to the same HTTPS address at the supplied port.
 * <p>
 * Add as an async handler to enable. Sample usage:
 * <pre>
 *     server = MuServerBuilder.muServer()
 *                 .withHttpConnection(8080)
 *                 .withHttpsConnection(80443, SSLContextBuilder.unsignedLocalhostCert())
 *                 .addAsyncHandler(new HttpToHttpsRedirector(80443))
 *                 .addHandler( ... your handler ... )
 *                 .start();
 * </pre>
 */
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
        ctx.complete();
        return true;
    }

    public void onRequestData(AsyncContext ctx, ByteBuffer buffer) throws Exception {
    }

    public void onRequestComplete(AsyncContext ctx) {
    }
}
