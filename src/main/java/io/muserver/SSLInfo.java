package io.muserver;

import java.security.cert.X509Certificate;
import java.util.List;

/**
 * Information about the SSL settings being used by the server
 */
public interface SSLInfo {

    /**
     * @return An unmodifiable list of ciphers supported, in preference order
     */
    List<String> ciphers();

    /**
     * @return An unmodifiable list of protocols supported, such as <code>TLSv1.2</code>
     */
    List<String> protocols();

    /**
     * @return Gets the SSL provider, e.g. <code>JDK</code> or <code>OpenSSL</code>
     */
    String providerName();

    /**
     * <p>Gets the server certificates that are in use.</p>
     * <p>Note: The certificate information is found by making an HTTPS connection to
     * <code>https://localhost:{port}/</code> and if any exceptions are thrown while
     * doing the lookup then an empty array is returned.</p>
     * <p>Using this information, you can find information such as the expiry date of your
     * certificates by calling {@link X509Certificate#getNotAfter()}.</p>
     * @return An ordered list of server certificates, with the server's own certificate first followed by any certificate authorities.
     */
    List<X509Certificate> certificates();

}
