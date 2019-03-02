package io.muserver.acme;

/**
 * A certificate authorisation or order at an ACME server failed.
 */
public class CertificateOrderException extends RuntimeException {

    public CertificateOrderException(String message) {
        super(message);
    }

    public CertificateOrderException(String message, Throwable cause) {
        super(message, cause);
    }
}
