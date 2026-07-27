package io.muserver;

/**
 * Specifies how an HTTPS listener authenticates client certificates.
 */
public enum ClientCertificateAuthentication {
    /**
     * The server does not request a client certificate.
     */
    NONE,

    /**
     * The server requests a client certificate, but permits clients that do not provide one.
     * A certificate that is provided must be accepted by the configured trust manager.
     */
    OPTIONAL,

    /**
     * The server requires a client certificate. Clients that do not provide a certificate, or that
     * provide a certificate rejected by the configured trust manager, cannot complete the TLS handshake.
     */
    MANDATORY
}
