package io.muserver;

import io.netty.handler.codec.haproxy.HAProxyMessage;

import java.util.Objects;

/**
 * Information about the connection provided by an intermediate proxy using the
 * <a href="https://www.haproxy.org/download/1.8/doc/proxy-protocol.txt">HA Proxy Protocol</a>.
 */
public interface ProxiedConnectionInfo {

    /**
     * The address of the client that connected to the proxy
     * @return A human-readable address, or null if none was set
     */
    String sourceAddress();

    /**
     * The port of the client that connected to the proxy
     * @return The port of the client that connected to the proxy
     */
    int sourcePort();

    /**
     * The destination address that the proxy set
     * @return The destination address that the proxy set
     */
    String destinationAddress();

    /**
     * The destination port
     * @return a port number
     */
    int destinationPort();
}

class ProxiedConnectionInfoImpl implements ProxiedConnectionInfo {

    private final String sourceAddress;
    private final int sourcePort;
    private final String destinationAddress;
    private final int destinationPort;

    private ProxiedConnectionInfoImpl(String sourceAddress, int sourcePort, String destinationAddress, int destinationPort) {
        this.sourceAddress = sourceAddress;
        this.sourcePort = sourcePort;
        this.destinationAddress = destinationAddress;
        this.destinationPort = destinationPort;
    }

    static ProxiedConnectionInfoImpl fromNetty(HAProxyMessage msg) {
        return new ProxiedConnectionInfoImpl(
            msg.sourceAddress(), msg.sourcePort(), msg.destinationAddress(), msg.destinationPort()
        );
    }

    @Override
    public String sourceAddress() {
        return sourceAddress;
    }

    @Override
    public int sourcePort() {
        return sourcePort;
    }

    @Override
    public String destinationAddress() {
        return destinationAddress;
    }

    @Override
    public int destinationPort() {
        return destinationPort;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProxiedConnectionInfoImpl that = (ProxiedConnectionInfoImpl) o;
        return sourcePort == that.sourcePort && destinationPort == that.destinationPort && Objects.equals(sourceAddress, that.sourceAddress) && Objects.equals(destinationAddress, that.destinationAddress);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceAddress, sourcePort, destinationAddress, destinationPort);
    }

    @Override
    public String toString() {
        return "ProxiedConnectionInfo{" +
            "sourceAddress='" + sourceAddress + '\'' +
            ", sourcePort=" + sourcePort +
            ", destinationAddress='" + destinationAddress + '\'' +
            ", destinationPort=" + destinationPort +
            '}';
    }
}
