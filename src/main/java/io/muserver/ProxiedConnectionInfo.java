package io.muserver;

import org.jspecify.annotations.Nullable;

/** Connection endpoints supplied by a trusted proxy using PROXY protocol v1 or v2. */
public interface ProxiedConnectionInfo {
    /** Gets the advertised source address.
     * @return the client's address, or null when the proxy supplied no address */
    @Nullable String sourceAddress();
    /** Gets the advertised source port.
     * @return the client's port, or zero when unspecified */
    int sourcePort();
    /** Gets the advertised destination address.
     * @return the destination address, or null when unspecified */
    @Nullable String destinationAddress();
    /** Gets the advertised destination port.
     * @return the destination port, or zero when unspecified */
    int destinationPort();
}
