package io.muserver.handlers;

import io.muserver.MuHandlerBuilder;

import java.util.concurrent.TimeUnit;

/**
 * <p>A builder for a handler that sends any HTTP requests to the same HTTPS address at the supplied port and optionally enables
 * <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Strict-Transport-Security" target="_blank">Strict-Transport-Security (HSTS)</a>
 * </p>
 * <p>Sample usage:</p>
 * <pre>
 *     server = MuServerBuilder.muServer()
 *                 .withHttpPort(80)
 *                 .withHttpsPort(443)
 *                 .addHandler(HttpsRedirectorBuilder.toHttpsPort(443).withHSTSExpireTime(365, TimeUnit.DAYS))
 *                 .addHandler( ... your handler ... )
 *                 .start();
 * </pre>
 */
public class HttpsRedirectorBuilder implements MuHandlerBuilder<HttpsRedirector> {

    private int port = -1;
    private long expireTimeInSeconds = -1;
    private boolean includeSubDomainsForHSTS = false;
    private boolean preload = false;

    @Override
    public HttpsRedirector build() {
        if (port < 1) {
            throw new IllegalArgumentException("The HTTPS port to redirect to should be a positive number");
        }
        return new HttpsRedirector(port, expireTimeInSeconds, includeSubDomainsForHSTS, preload);
    }

    /**
     * Sets the port to redirect HTTP requests to, for example <code>443</code>
     * @param port The port that the HTTPS version of this website is available at.
     * @return Returns this builder.
     */
    public HttpsRedirectorBuilder withPort(int port) {
        this.port = port;
        return this;
    }

    /**
     * <p>Specifies that this website can be added to the HSTS preload lists.
     * See <a href="https://hstspreload.org/">https://hstspreload.org/</a> for more info.</p>
     * @param preload <code>true</code> to include the preload directive; <code>false</code> to not.
     * @return Returns this builder.
     */
    public HttpsRedirectorBuilder withHSTSPreload(boolean preload) {
        this.preload = preload;
        return this;
    }

    /**
     * <p>If set to a positive number, this will add a <code>Strict-Transport-Security</code> header to all HTTPS
     * responses to indicate that clients should always use HTTPS to access this server.</p>
     * <p>This is not enabled by default.</p>
     * @param expireTime The time that the browser should remember that a site is only to be accessed using HTTPS.
     * @param unit The unit of the expiry time.
     * @return Returns this builder.
     */
    public HttpsRedirectorBuilder withHSTSExpireTime(int expireTime, TimeUnit unit) {
        this.expireTimeInSeconds = unit.toSeconds(expireTime);
        return this;
    }


    /**
     * <p>Specifies that any subdomains should have HSTS enforced too.</p>
     * @param includeSubDomainsForHSTS <code>true</code> to include the <code>includeSubDomains</code> directive in the <code>Strict-Transport-Security</code> header value.
     * @return Returns this builder.
     */
    public HttpsRedirectorBuilder includeSubDomains(boolean includeSubDomainsForHSTS) {
        this.includeSubDomainsForHSTS = includeSubDomainsForHSTS;
        return this;
    }

    public static HttpsRedirectorBuilder toHttpsPort(int port) {
        return new HttpsRedirectorBuilder()
            .withPort(port);
    }
}
