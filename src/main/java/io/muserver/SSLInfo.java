package io.muserver;

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

}
