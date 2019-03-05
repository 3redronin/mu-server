package io.muserver;

import java.util.List;
import java.util.Set;

/**
 * A filter allowing the selection of SSL certificates
 */
interface SSLCipherFilter {

    /**
     * A method that returns the ciphers desired in the preferred order of use.
     * @param supportedCiphers All the ciphers supported, including insecure ones.
     * @param defaultCiphers The default ciphers, as determined by the JDK.
     * @return A list of ciphers to use.
     */
    List<String> selectCiphers(Set<String> supportedCiphers, List<String> defaultCiphers);
}
