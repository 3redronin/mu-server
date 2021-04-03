package io.muserver.handlers;

import io.muserver.AsyncMuHandler;
import io.muserver.MuException;

/**
 * @deprecated Use {@link HttpsRedirectorBuilder#toHttpsPort(int)} instead and add it as standard handler.
 */
@Deprecated
public class HttpToHttpsRedirector implements AsyncMuHandler {

    /**
     * Creates a HTTPS redirector
     * @param httpsPort The port that HTTPS is running on
     * @deprecated Use {@link HttpsRedirectorBuilder#toHttpsPort(int)} instead and add it as standard handler.
     */
    @Deprecated
    public HttpToHttpsRedirector(int httpsPort) {
        throw new MuException("This class has been deprecated. Please use HttpsRedirectorBuilder.toHttpsPort(int) instead.");
    }

}
